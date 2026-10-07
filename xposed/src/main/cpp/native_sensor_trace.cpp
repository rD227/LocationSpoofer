#include <android/log.h>
#include <android/sensor.h>
#include <jni.h>
#include <dlfcn.h>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <map>
#include <mutex>
#include <string>
#include <time.h>

// Stable LSPosed/Vector native module ABI. Only use the provided inline hook service.
using HookFunction = int (*)(void*, void*, void**);
using UnhookFunction = int (*)(void*);
using ModuleLoaded = void (*)(const char*, void*);
struct NativeAPIEntries { uint32_t version; HookFunction hook; UnhookFunction unhook; };
static HookFunction hook_function = nullptr;
static ssize_t (*original_get_events)(ASensorEventQueue*, ASensorEvent*, size_t) = nullptr;
static int (*original_destroy_queue)(ASensorManager*, ASensorEventQueue*) = nullptr;
static std::mutex stats_mutex, install_mutex;
static bool installed = false;

static int64_t boot_nanos() {
    timespec now{};
    clock_gettime(CLOCK_BOOTTIME, &now);
    return int64_t(now.tv_sec) * 1000000000 + now.tv_nsec;
}

struct Stats {
    uint64_t events = 0, regressions = 0;
    int64_t first_timestamp = 0, last_timestamp = 0, last_report = 0;
    double first_value = 0, last_value = 0, mean = 0, square = 0;
};
struct QueueStats {
    std::string caller;
    uint64_t calls = 0;
    int64_t last_poll_report = 0;
    std::map<int, Stats> types;
};
static std::map<ASensorEventQueue*, QueueStats> queues;

static bool tracked(int type) {
    switch (type) {
        case 1: case 3: case 4: case 9: case 10: case 11:
        case 15: case 16: case 18: case 19: case 20: case 35: return true;
        default: return false;
    }
}

static ssize_t traced_get_events(ASensorEventQueue* queue, ASensorEvent* events, size_t count) {
    const ssize_t result = original_get_events(queue, events, count);
    const int saved_errno = errno;
    // Inspect only returned sensor records. Leave return values, buffers, and errno unchanged.
    {
        const int64_t now = boot_nanos();
        std::lock_guard<std::mutex> lock(stats_mutex);
        auto found = queues.find(queue);
        if (found == queues.end() && queues.size() < 128) {
            Dl_info info{};
            const char* caller = "unknown";
            if (dladdr(__builtin_return_address(0), &info) != 0 && info.dli_fname != nullptr) {
                const char* slash = std::strrchr(info.dli_fname, '/');
                caller = slash ? slash + 1 : info.dli_fname;
            }
            found = queues.emplace(queue, QueueStats{caller, 0, 0, {}}).first;
        }
        if (found != queues.end()) {
            ++found->second.calls;
            if (found->second.last_poll_report == 0 || now - found->second.last_poll_report >= 15000000000LL) {
                __android_log_print(ANDROID_LOG_INFO, "LocationSpoofer",
                    "[NativeSensorTrace] NDK poll caller=%s calls=%llu returned=%lld read-only=true",
                    found->second.caller.c_str(), (unsigned long long)found->second.calls, (long long)result);
                found->second.last_poll_report = now;
            }
        }
        if (found != queues.end() && result > 0 && events != nullptr && size_t(result) <= count)
        for (ssize_t i = 0; i < result; ++i) {
            const ASensorEvent& event = events[i];
            if (!tracked(event.type)) continue;
            Stats& stats = found->second.types[event.type];
            const double value = event.type == ASENSOR_TYPE_STEP_COUNTER ?
                double(event.u64.step_counter) : event.data[0];
            if (stats.events == 0) {
                stats.first_timestamp = event.timestamp;
                stats.first_value = value;
            } else if (event.timestamp <= stats.last_timestamp) ++stats.regressions;
            ++stats.events;
            stats.last_timestamp = event.timestamp;
            stats.last_value = value;
            double magnitude = value;
            if (event.type != 18 && event.type != 19) {
                magnitude = std::sqrt(double(event.data[0]) * event.data[0] +
                    double(event.data[1]) * event.data[1] + double(event.data[2]) * event.data[2]);
            }
            const double delta = magnitude - stats.mean;
            stats.mean += delta / stats.events;
            stats.square += delta * (magnitude - stats.mean);
            if (stats.last_report == 0 || now - stats.last_report >= 15000000000LL) {
                const double seconds = (stats.last_timestamp - stats.first_timestamp) / 1e9;
                const double cadence = seconds > 0 ? 60 * (event.type == 19 ?
                    stats.last_value - stats.first_value : double(stats.events - 1)) / seconds : 0;
                __android_log_print(ANDROID_LOG_INFO, "LocationSpoofer",
                    "[NativeSensorTrace] caller=%s type=%d events=%llu last=%.3f eventHz=%.3f cadenceSpm=%.3f magnitudeStd=%.6f timestampRegressions=%llu read-only=true",
                    found->second.caller.c_str(), event.type, (unsigned long long)stats.events,
                    stats.last_value, seconds > 0 ? (stats.events - 1) / seconds : 0,
                    event.type == 18 || event.type == 19 ? cadence : 0,
                    stats.events > 1 ? std::sqrt(stats.square / (stats.events - 1)) : 0,
                    (unsigned long long)stats.regressions);
                stats.last_report = now;
            }
        }
    }
    errno = saved_errno;
    return result;
}

static int traced_destroy_queue(ASensorManager* manager, ASensorEventQueue* queue) {
    { std::lock_guard<std::mutex> lock(stats_mutex); queues.erase(queue); }
    return original_destroy_queue(manager, queue);
}

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
ModuleLoaded native_init(const NativeAPIEntries* entries) {
    if (entries != nullptr && entries->version >= 1) hook_function = entries->hook;
    return nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_vincenthzr_locationspoofer_xposed_diagnostics_NativeSensorTrace_startObservation(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(install_mutex);
    if (installed) return JNI_TRUE;
    if (hook_function == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, "LocationSpoofer", "[NativeSensorTrace] framework native API unavailable; Java hooks retained");
        return JNI_FALSE;
    }
    // Keep this handle alive: installed trampolines reference its executable pages.
    void* library = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
    if (library == nullptr) return JNI_FALSE;
    void* target = dlsym(library, "ASensorEventQueue_getEvents");
    if (target == nullptr || hook_function(target, reinterpret_cast<void*>(traced_get_events),
        reinterpret_cast<void**>(&original_get_events)) != 0) return JNI_FALSE;
    installed = true;
    void* destroy = dlsym(library, "ASensorManager_destroyEventQueue");
    if (destroy != nullptr) hook_function(destroy, reinterpret_cast<void*>(traced_destroy_queue),
        reinterpret_cast<void**>(&original_destroy_queue));
    __android_log_print(ANDROID_LOG_INFO, "LocationSpoofer", "[NativeSensorTrace] NDK getEvents observed; events are unchanged");
    return JNI_TRUE;
}
