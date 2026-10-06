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

新 APK：`app/build/outputs/apk/global/debug/app-global-arm64-v8a-debug.apk`，以及对应的 `armeabi-v7a` APK。2026-10-03 使用本地 debug 签名更新用户已安装的调试版；2026-10-06 手机已改用用户自己的发布证书，后续调试 APK 通过本地 `keystore.properties` 使用相同发布证书，核验证书一致后保留数据更新。不要为了安装直接卸载用户原版；不要提交本地签名凭据。

独立客户端未加入模块作用域，实测作用域只有 `system`、`com.android.phone`、`com.android.bluetooth`。本轮支付宝后台进程确有 lowmemorykiller 回收记录；另有 `libxriver-core.so` 的 fdsan 崩溃，因此不能把支付宝所有崩溃都归因于内存。系统 GNSS 辅助定位仍可能因这台 ROM 缺少物理 network 后端记录异常；它不代表目标客户端的模拟回调失败。

后续悦跑营前台亮屏无提示暂停的观察：模拟配置仍 active、路线自动运行、stop_at_destination=false；64 秒内系统投递了 65 个持续变化的位置采样，最大间隔约 1.003 秒，估算移动速度 4.03–6.13 m/s，观察窗口没有停止配置、监听者死亡或相关进程退出记录。两次页面截图均显示“继续”，中间用户曾恢复，距离由 2.62 增至 2.95 km。支付宝存在真实加速度计/计步器订阅，但这不能证明悦跑营的暂停规则；触发原因尚未确认，不应归因于 GPS 信号或登记为已修复。

## 2026-10-06 步频传感器复测

全局定位方案仍需要将接收步频的应用加入 Vector / LSPosed 作用域，因为传感器 Hook 在目标应用进程运行。独立读取工具为 `tools/sensor-probe/`，包名 `com.locationspoofer.sensorprobe`，它只使用普通 SensorManager 接口，不生成模拟事件。该测试 App 与支付宝均已勾选作用域。

修复前，4.8 m/s 自动步频路线采样 164 秒，计步器约 186.85 步/分钟，步伐检测器约 60.11 步/分钟。原逻辑每秒只主动派发一个 detector 事件；线性加速度与未校准加速度仍输出物理传感器数据。同时远程当前快照在一个完整 JSON 对象后残留 4367 字符，确认文件缩短时未截断，导致更新解析失败。

修复内容：独立的步频定时线程按累计步数跨越整数时产生 detector 事件，计步器与 detector 共用累计步数；原生计步事件不再与主动事件重复发送。注销指定传感器不再误删该监听器的其他传感器订阅，最后一个计步订阅注销后停止定时线程；线性加速度与未校准加速度也从同一加速度步态生成，线性加速度扣除重力。远程文件写入前截断并归零文件位置，保留读取失败时继续使用最后有效配置的行为。

相同发布证书更新手机后，冷启动测试 App 以加载新代码。用户此时切换为 1.4 m/s 的自动步频步行模式，前台连续采样 90.048 秒：

| 项目 | 实测 |
|---|---|
| 计步器 | 182 个事件，约 121.02 步/分钟 |
| 步伐检测器 | 181 个事件，约 120.45 步/分钟，最大事件间隔约 542 ms |
| 普通 / 线性 / 未校准加速度 | 均约 50.35 Hz，幅值标准差分别约 3.20 / 2.90 / 3.20 m/s² |
| 时间戳 | 六类传感器均无倒退 |
| 配置文件 | 完整快照后残留字符数为 0 |
| 注销订阅 | 测试页面退到后台后统计停止增长，步频定时线程退出 |
| 构建与回归检查 | global debug 双 ABI APK 构建成功；core-data 与 xposed 合计 83 项单元测试通过 |

陀螺仪仍为物理数据，没有新增陀螺仪模拟。标准差仅描述该采样窗口的幅值变化，不能证明跑步识别有效。不同自动速度的修复前后结果不能直接比较绝对步频；本轮验证的是 counter / detector 一致性、加速度通道与注销行为。支付宝小程序的跑步自动暂停尚未在此修复版完成业务复验，不能保证仅此修复就不会暂停。系统进程未在本轮更新后重启或成功热重载，本轮传感器结果来自重新启动的测试 App 进程。

本地采样与退出检查记录位于忽略目录 `build/sensor-validation/`；不提交设备配置和签名信息。

## 2026-10-06 陀螺仪与个人模板补充

用户安装的 global release 在独立客户端仍可收到约 185 步/分钟的计步事件；不能把悦跑营的空白步频直接归因于 release 构建失效。之前的陀螺仪输出确实始终来自物理传感器。本次新增如下行为：

- `TYPE_GYROSCOPE`（4）和 `TYPE_GYROSCOPE_UNCALIBRATED`（16）均使用与步态相同的跨步相位，单位 rad/s；未校准通道的模拟漂移为零。
- 个人步态录制同时订阅加速度和陀螺仪。按加速度的时间起点与跨步边界对齐两组数据，允许不同采样率；陀螺仪缺失、覆盖不足或中断时保留加速度模板并使用默认角速度曲线。
- 模板 v2 保存六轴，兼容读取只有加速度的 v1；未重新录制的旧模板自动使用默认摆动，不会凭空补出个人陀螺仪数据。界面显示是否包含陀螺仪曲线。角速度随回放步频与录制步频的比例缩放。
- 线性加速度根据个人模板的平均重力方向扣除一倍重力，不再假定录制时 Z 轴始终朝上。这是按周期均值估计的重力方向，并非完整姿态融合。
- 修正编辑期间多出的一次 `onSensorChanged` 调用；保留每个订阅首次成功/首次异常的诊断日志。

core-geo 与 xposed 合计 84 项单元测试通过，涵盖新旧模板编码、采样对齐、陀螺仪缺失/中断回退、角速度相位与步频缩放、静止输出和侧放时的线性加速度；global release 两个 ABI 构建通过。使用用户发布证书保留数据更新了 Android 11 手机，并冷启动验证 App。安装后采样时路线已停止，模拟开启后的实测待用户重新启动路线；个人六轴模板实际步行录制和悦跑营业务结果仍需单独验证。

验证 App 现有七类传感器，新增未校准陀螺仪，并显示量纲。其“标准差”为三轴向量模长在采样窗口内的标准差，不能直接比较加速度（m/s²）与角速度（rad/s），也不是小程序的通过标准。默认角速度是近似周期摆动模型，不保证第三方运动判定通过。

## 对照源码

- [AOSP Android 11 LocationManagerService](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-11.0.0_r1/services/core/java/com/android/server/location/LocationManagerService.java)
- [AOSP Android 11 IGnssStatusListener](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-11.0.0_r1/location/java/android/location/IGnssStatusListener.aidl)
- [AOSP Android 11 GnssStatus 编码](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-11.0.0_r1/location/java/android/location/GnssStatus.java)
- [LineageOS 18.1 WifiScanningServiceImpl](https://raw.githubusercontent.com/LineageOS/android_frameworks_opt_net_wifi/lineage-18.1/service/java/com/android/server/wifi/scanner/WifiScanningServiceImpl.java)
- [VectorRemotePreferences 缓存与通知](https://raw.githubusercontent.com/JingMatrix/Vector/master/xposed/src/main/kotlin/org/matrix/vector/impl/VectorRemotePreferences.kt)
- [VectorContext 按组复用远程偏好实例](https://raw.githubusercontent.com/JingMatrix/Vector/master/xposed/src/main/kotlin/org/matrix/vector/impl/VectorContext.kt)
