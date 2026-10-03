# Android 11 / LineageOS 18.1 适配

验证日期：2026-10-03。设备：Redmi 4X / santoni，LineageOS `18.1-20260524-UNOFFICIAL-Mi8937_4_19`，API 30，arm64-v8a，Magisk Root，Vector 2.2（3080）/ libxposed API 102。

## 接口差异与修复

| 链路 | Android 11 的实际入口 | 本次修改 |
|---|---|---|
| provider 管理 | `LocationManagerService$LocationProviderManager` | 加入 AOSP 类名候选，缓存查询沿用该版本实际存在的方法 |
| 连续定位 | `requestLocationUpdates(LocationRequest, ILocationListener, PendingIntent, ...)` | 原系统注册成功后才记录监听器；为已注册 PendingIntent 补充主动位置派发 |
| GNSS 状态 | `onSvStatusChanged(int, int[], float[], float[], float[], float[], float[])` | 支持 7 参数，卫星 SVID 左移 12 位、星座左移 8 位，保留 baseband C/N0；沿用 6 参数和对象签名兼容 |
| NMEA | 与 GNSS 共用 `IGnssStatusListener.onNmeaReceived` | 注册/注销共享监听器，主动发送有效模拟报文，替换物理未定位报文；按 Binder 实例区分目标 |
| 原始测量 / 导航消息 | `addGnssMeasurementsListener` / `addGnssNavigationMessageListener` | 增加旧方法别名，以接口类型识别监听器，避免把 `GnssRequest` 当监听器 |
| Wi-Fi 单次扫描 | Messenger `CMD_GET_SINGLE_SCAN_RESULTS` → `replySucceeded(Message)` | 在原系统完成权限检查后的回包处改写结果，使用 `Message.sendingUid` 判定调用者；不改物理扫描缓存 |
| Wi-Fi 数据结构 | `WifiSsid.createFromAsciiEncoded`、扫描时间戳以微秒计 | 增加旧构造入口；普通 Wi-Fi 查询与 scanner 共用一份结果生成器和配置 |
| 网络能力 | API 30 的 `WifiInfo` 尚未实现 `TransportInfo` | 按字段类型检查，避免不合法赋值 |
| ROM 识别 | Xiaomi/Redmi 硬件也可能运行 LineageOS | 优先识别 `ro.lineage.version`，自动选 AOSP，并显示正确系统名称 |
| 配置同步 | Vector 的 `getRemotePreferences().getString()` 读取进程内缓存 | API 30 发布完整配置的远程文件副本，后台每秒通过新 FD 补偿读取；快照去重并防止旧缓存覆盖新停止状态；框架 Error 不再让定时接收永久退出 |

此机没有 `network` provider 后端。API 30 且模拟开启、调用者命中模拟范围、实际缺少网络 provider 时，向该调用者提供网络 provider 别名，并将其 `LocationRequest` 的副本接到已有 GPS provider。原系统仍执行注册检查；原请求不被修改，监听者接收的模拟位置仍标为其请求的 provider。未命中范围或停止模拟时不提供该别名。

`dumpsys location` 中 `last location=null` 表示物理 provider 尚无定位缓存。本项目在客户端查询/回调边界替换位置，它本身不能证明目标应用没有收到模拟位置。此次没有通过全局写入假缓存来把诊断值改成非空。

## 已完成验证与边界

| 验证项 | 结果与范围 |
|---|---|
| 原版 APK 连续 GPS/fused/passive 与 GPS 单次定位 | 独立客户端在真机收到模拟位置；移除监听器后 3 秒内新增回调为 0 |
| 原版网络定位 | 注册抛出 `provider doesn't exist: network` |
| 原版 NMEA | 收到 `$GPGGA,,,,,,0,,,,,,,,*66`，即未定位报文 |
| 原版 PendingIntent | 12 秒测试内未观察到位置派发 |
| 新构建 Android 11 接口合约 | 真机独立 `app_process` 加载新 APK，共 12 项 PASS：实际 GNSS 编码往返、WifiSsid、时间单位、旧扫描回包包装、开关、provider 类与 request 副本等 |
| 单元测试 | xposed 40 项、core-data 39 项全部通过；前一轮 core-geo 36 项通过 |
| APK 构建 | global debug 的 arm64-v8a 与 armeabi-v7a 两份均成功 |
| 新模块初次部署后的失败复现 | 三个推荐系统作用域已开启、类和方法均挂载；定位进程配置发布时间停在启动时，电话/蓝牙配置继续更新。独立客户端最近位置为 null、连续坐标回调为 0、NMEA 为未定位报文 |
| 本轮修复部署 | 用户已自行安装上一轮 debug 版；确认签名一致后，经用户授权保留数据更新。热重载测试因应用发布 provider 超时未执行成功；随后重启加载新代码 |
| 本轮配置同步 | 定位进程报告来源变为 `libxposed:remote-file-reconciled`，发布时间随应用更新，Hook 报告 errors 为空 |
| 本轮独立定位客户端 | GPS/network/fused/passive 最近位置及连续位置、GPS 单次定位全部收到模拟坐标；12 秒共 56 次连续回调；注销后 3 秒新增回调 0 |
| 本轮 GNSS / NMEA / PendingIntent | 配置的 10 颗卫星全部 usedInFix；GPGGA 有坐标且 fix=1；PendingIntent 每秒收到模拟位置 |
| 高德网页 | 用户于本轮更新、重启验证后确认“现在高德可以定位”；浏览器 fused 监听注册与首次模拟位置投递也有系统日志记录 |
| 支付宝定位 | 用户确认“现在在支付宝当中也可以模拟”；悦跑营小程序仍有无提示暂停，持续运动业务不能登记为通过 |
| 高德原生 App | 未独立复验；不得把网页结果当作原生 App 的通过证据 |

接口检查是直接运行新 APK 的部分代码，并与真机 framework 交互，不等同于新模块的端到端验证。独立测试客户端未加入 App 进程 Hook 作用域，基线回调来自系统服务。

## 可重复验证

独立客户端与不安装模块的接口检查工具见 `tools/android11/README.md`。本地输出位于忽略目录 `build/android11-validation/`；其中保留原版 APK、原版日志和报告，不提交设备数据。

新 APK：`app/build/outputs/apk/global/debug/app-global-arm64-v8a-debug.apk`，以及对应的 `armeabi-v7a` APK。使用本地 debug 签名，不能覆盖上游原版签名；本轮手机已由用户换成相同 debug 签名，因此更新保留数据成功。不要为了安装直接卸载用户原版。

独立客户端未加入模块作用域，实测作用域只有 `system`、`com.android.phone`、`com.android.bluetooth`。本轮支付宝后台进程确有 lowmemorykiller 回收记录；另有 `libxriver-core.so` 的 fdsan 崩溃，因此不能把支付宝所有崩溃都归因于内存。系统 GNSS 辅助定位仍可能因这台 ROM 缺少物理 network 后端记录异常；它不代表目标客户端的模拟回调失败。

后续悦跑营前台亮屏无提示暂停的观察：模拟配置仍 active、路线自动运行、stop_at_destination=false；64 秒内系统投递了 65 个持续变化的位置采样，最大间隔约 1.003 秒，估算移动速度 4.03–6.13 m/s，观察窗口没有停止配置、监听者死亡或相关进程退出记录。两次页面截图均显示“继续”，中间用户曾恢复，距离由 2.62 增至 2.95 km。支付宝存在真实加速度计/计步器订阅，但这不能证明悦跑营的暂停规则；触发原因尚未确认，不应归因于 GPS 信号或登记为已修复。

## 对照源码

- [AOSP Android 11 LocationManagerService](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-11.0.0_r1/services/core/java/com/android/server/location/LocationManagerService.java)
- [AOSP Android 11 IGnssStatusListener](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-11.0.0_r1/location/java/android/location/IGnssStatusListener.aidl)
- [AOSP Android 11 GnssStatus 编码](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-11.0.0_r1/location/java/android/location/GnssStatus.java)
- [LineageOS 18.1 WifiScanningServiceImpl](https://raw.githubusercontent.com/LineageOS/android_frameworks_opt_net_wifi/lineage-18.1/service/java/com/android/server/wifi/scanner/WifiScanningServiceImpl.java)
- [VectorRemotePreferences 缓存与通知](https://raw.githubusercontent.com/JingMatrix/Vector/master/xposed/src/main/kotlin/org/matrix/vector/impl/VectorRemotePreferences.kt)
- [VectorContext 按组复用远程偏好实例](https://raw.githubusercontent.com/JingMatrix/Vector/master/xposed/src/main/kotlin/org/matrix/vector/impl/VectorContext.kt)
