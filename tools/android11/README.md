# Android 11 独立验证工具

`ProbeActivity` 是独立定位客户端（包名 `com.locationspoofer.android11probe`），测试缓存查询、连续监听、单次定位、GNSS、NMEA、PendingIntent 和注销后是否继续回调。不要把它加入 LSPosed App 进程作用域，否则不能证明 system_server 的 Hook 已生效。

`FrameworkApiProbe` 在独立进程加载指定的新 APK，核对 Android 11 的实际类、签名与部分新代码。它不会安装/启用新模块，不等同于端到端测试。

PowerShell 示例（替换 SDK/JDK 路径；多个设备时每条 adb 命令指定 `-s`）：

```powershell
python tools/android11/build_probe.py --sdk 'C:\Users\xvsu\AppData\Local\Android\Sdk' --java-home 'D:\Users\xvsu\AppData\Local\Programs\Android Studio 3\jbr'
$adb = 'C:\Users\xvsu\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb install --no-incremental -r build/android11-validation/probe/android11-probe.apk
& $adb shell pm grant com.locationspoofer.android11probe android.permission.ACCESS_FINE_LOCATION
& $adb shell pm grant com.locationspoofer.android11probe android.permission.ACCESS_COARSE_LOCATION
& $adb shell am start -n com.locationspoofer.android11probe/.ProbeActivity
# 客户端 12 秒后注销，随后再观察 3 秒
& $adb shell logcat -d -s Android11Probe:I
```

不安装新模块的合约检查：

```powershell
& $adb push build/android11-validation/probe/android11-probe.apk /data/local/tmp/locationspoofer-api-probe.apk
& $adb push app/build/outputs/apk/global/debug/app-global-arm64-v8a-debug.apk /data/local/tmp/locationspoofer-api-module.apk
& $adb shell 'CLASSPATH=/data/local/tmp/locationspoofer-api-probe.apk app_process /system/bin com.locationspoofer.android11probe.FrameworkApiProbe /data/local/tmp/locationspoofer-api-module.apk'
```

预期最后一行为 `FRAMEWORK_API_RESULT=PASS`。ABI 不同的设备应选择对应 APK。检查完可删除上述两个明确命名的临时文件，保留本地编译结果。

ADB 可直接截屏，不依赖 scrcpy：

```powershell
& $adb shell screencap -p /sdcard/locationspoofer-check.png
& $adb pull /sdcard/locationspoofer-check.png build/android11-validation/check.png
```

API 30 调试版发布完整配置副本 `locationspoofer-current.json`。定位进程在偏好通知丢失时通过远程文件读取追上更新；Hook 报告的 `config.path` 会显示 `libxposed:remote-file-reconciled`。报告的 `config.modified` 应随应用发布更新，不能只看方法挂载数量。

同签名更新后可使用项目已有的 instrumentation 尝试热重载（需安装 `assembleGlobalDebugAndroidTest` 的 APK）：

```powershell
& $adb shell am instrument -w -e check module-hot-reload-active com.vincenthzr.locationspoofer.test/com.vincenthzr.locationspoofer.SystemFilesInstrumentation
```

此模式保留当前模拟配置并要求三个系统目标 PID 不变。2 GB 实机本轮出现 provider 发布超时，未完成热重载，实际验证通过重启加载；测试入口失败不能计为热重载成功。
