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

core-geo 与 xposed 合计 84 项单元测试通过，涵盖新旧模板编码、采样对齐、陀螺仪缺失/中断回退、角速度相位与步频缩放、静止输出和侧放时的线性加速度；global release 两个 ABI 构建通过。使用用户发布证书保留数据更新了 Android 11 手机，并冷启动验证 App。用户随后重新录制，当前快照为 v2，包含六轴曲线，录制步频 92 步/分钟。录制步频是模板原始节奏，模拟时使用配置的自动/手动步频，不直接固定为 92。

用户再次启用约 4.9 m/s 的自动步频路线后，独立 App 连续采样 95.213 秒：counter 187.216、detector 187.219 步/分钟，均处于用户要求的 80–240 区间。七类传感器均无时间戳倒退，加速度/线性加速度/未校准加速度模长标准差约 3.852/3.411/3.849 m/s²，陀螺仪/未校准陀螺仪约 2.310/2.312 rad/s，事件频率约 50.34 Hz。此处陀螺仪使用用户新模板，已不再仅返回物理静止数据。

支付宝主进程和 push 进程的日志确认计步 counter 回调已投递；其中 `SensorPedometer$1` 定期短暂注册以读取最新累计步数，不能单凭这些注册间隔推断丢步。用户上一条悦跑营记录仍仅显示 22 步/分钟，本轮模块原始输出正常不等同于该业务已通过；小程序新一轮统计仍待验证。采样结果位于忽略目录 `build/sensor-validation/alipay22-final.json`。

验证 App 现有七类传感器，新增未校准陀螺仪，并显示量纲。其“标准差”为三轴向量模长在采样窗口内的标准差，不能直接比较加速度（m/s²）与角速度（rad/s），也不是小程序的通过标准。默认角速度是近似周期摆动模型，不保证第三方运动判定通过。

## 悦跑营低步频的后续诊断

用户这次完成的记录为 1.60 km / 8 分 58 秒，页面显示 16 步/分钟；上一条为 22 步/分钟。连续传感器验证 App 的约 187 步/分钟不能作为小程序问题已修复的依据。跑步页不显示实时步频，需要用户完成至少 1.5 km 并结束后查看记录。

新增 `SensorStep` 诊断：记录计步订阅的接收线程，每 30 秒统计已完成的回调数、累计步数增量或 detector 事件频率、最大投递间隔。统计基于回调完成时的单调时钟，能反映 Handler 延迟，而非只记录定时线程计划发送的数量。诊断没有改动步频算法。

诊断版 global release 已构建、使用用户发布证书保留数据安装；沿用用户本地修改的版本号 3.0.1 / 30001。Vector 日志显示安装后自动热重载了多个存活目标。另行运行的 global debug instrumentation 与已裁剪的 release 目标不兼容，因 `kotlin.jvm.internal.Intrinsics.areEqual` 缺失退出；不能据此认定模块热重载失败，也不能把该入口记为验证通过。随后在确认跑步 detector 已注销后重新打开支付宝，主进程重新加载模块。小程序内的回调速率和最终步频仍待本轮采集确认。

### 共享累计计数修复与实机复核

诊断版跑步期间，支付宝 sportbiz 的 TYPE_STEP_DETECTOR、主进程 SensorTriggerPoint 的 TYPE_STEP_COUNTER、push 进程 APStepProcessor 的 TYPE_STEP_COUNTER 都收到约 156–162 步/分钟，最大回调间隔约 0.4 秒。然而用户完成后的业务记录仍为 16 步/分钟。主/push 原始累计值相差约 206 步，说明连续测试客户端正常仍不能排除业务统计链路的问题。

本次修复移除按进程订阅时长累加步数的做法。新增 `StepCounterClock`：配置发布者保存并发布相同的计数起点、时间锚点、节奏与运行状态，模块在任意进程按相同配置和时间计算累计步数。更换节奏、暂停和恢复时先接续上一段累计值；停止监听不再冻结累计计数，重启目标进程也不从 2350 重新开始。摇杆过期和路线到终点停止也会冻结计数。自动节奏使用配置的基础速度，再加入原有的确定性节奏变化，避免用当前瞬时速度重算过去的步数。手动设置及模拟节奏限制为 80–240；靠近上下界时减小节奏变化幅度，使积分与瞬时节奏保持一致。陀螺仪使用相同的节奏和步态相位。

core-geo、core-data、xposed 共 134 项单元检查通过，包括跨进程状态重建、间歇读取、暂停/恢复、节奏改变、过期/到终点停止及节奏上下界。global release 双 ABI 构建完成，使用用户原发布证书保留数据更新手机，版本 3.0.1 / 30001，安装标志无 DEBUGGABLE。

普通 SensorManager 验证接收器在同一个测试包的两个独立进程中做短暂后台读取，无需切换小程序前台。实机两个 PID 的 9 组接近同时的读数全部一致，从 9330 到 9338，时间差约 0.4–42.9 ms；再结束订阅并重启测试进程，42.316 秒的间隔累计增加 116 步，约 164.477 步/分钟，没有重置或漏掉间隔。结果保存于忽略目录 `build/sensor-validation/shared-main-parallel.json`、`shared-remote-parallel.json`、`shared-restarted.json`。测试进程已停止并重新打开支付宝；修复后的新一轮小程序最终统计仍待用户完成后确认，不应登记为悦跑营已通过。

### 2026-10-06 姿态通道缺口与统计链路观察

共享计数版的悦跑营业务仍失败：用户两轮完成记录分别为 15:55:46 的 1.52 km / 5 分 12 秒、重启后 16:23:36 的 1.52 km / 4 分 43 秒，均只有单位“步/分”且显示“步频数据异常”。原始计步回调正常不能登记为业务修复成功。用户报告模拟配合物理刷步器时可满足步频，这是下一轮对照线索，并非某个传感器导致失败的证明。

系统 sensorservice 记录确认支付宝订阅了加速度、旋转向量和方向；之前只覆盖计步、加速度和陀螺仪，TYPE_ROTATION_VECTOR 与 TYPE_ORIENTATION 确实遗漏。本轮按用户要求补上重力（9）、旋转向量（11）、游戏旋转向量（15）、地磁旋转向量（20）和旧方向（3）。新增 `GaitAttitude`：个人陀螺仪周期去均值后积分出近似摆动姿态，无陀螺仪模板时使用默认周期摆动；重力和方向由同一单位四元数导出，游戏通道使用自身固定航向参考。此模型是周期姿态近似，不是完整惯性导航融合或磁场校准，也不保证第三方业务判定通过。

框架 `SystemSensorManager$SensorEventQueue.dispatchSensorEvent` 在复制事件给 Java 监听器之前改写运动数组；具体监听器 Hook 保留为补充，线程标记避免同一次同步分发二次改写。相位按传感器的单调时间戳换算，避免把排队延迟误当作采样时刻。不拦截 NDK 直接读取或厂商私有传感器接口。个人加速度模板的周期均值先移除，再加单位重力，避免旧录制均值偏大造成持续的额外加速度；普通/未校准加速度与线性加速度、重力满足相同坐标下的分解关系。

可选只读诊断 `-PstepPipelineDiagnostics=true` 记录传感器注册类型、SDK `StepSensorEvent.convert`、异常过滤结果、`readDailyStep` 返回值及白名单数字步数元数据。默认构建关闭，不修改支付宝方法参数、返回值或判定结果，不打印任意 SDK 对象。随实际监听器类加载器补装观察点，以兼容迟加载 SDK。诊断全局 release 已使用原证书保留数据安装，沿用 3.0.1 / 30001；APK 无 DEBUGGABLE，导出文件同步至 `app/global/release/`。

core-geo 与 xposed 本轮 96 项测试通过，新增覆盖四元数归一化、单位重力、侧放/倒置坐标、个人陀螺仪积分周期连续性、方向角范围、3/4/5 分量布局、未知航向精度及加速度分解。Android 11 实机静置采样 88.645 秒，12 类传感器均注册且持续回调：counter 192.667、detector 192.116 步/分钟；运动通道约 50.35 Hz，地磁旋转向量约 49.65 Hz；三个旋转向量的四元数长度约 0.99999994–1.00000006，均无时间戳倒退。采样保存在忽略目录 `build/sensor-validation/attitude-final.json`。诊断 App 增加三轴分别的标准差，因为重力模长固定为一倍重力本身并不代表方向静止。

支付宝 push 的 SDK 日志观察到 `checkDirtyStepEvent` 返回 false、`readDailyStep` 连续增长，与原始 counter 的步数增量相符。但 SDK 内还有较大的历史 `dailyCountOffset` 和不同的 `finalDailyCount`，暂未证明小程序使用哪个字段，不修改历史步数数据。实机传感器与这段 SDK 读数验证通过，补齐后的悦跑营完成记录仍待用户对照。

### 补齐后的失败反馈与刷步器对照准备

用户反馈姿态通道补齐后最终步频仍异常，转为模拟配合物理刷步器对照。设备记录核实：模块 16:41:12 更新，支付宝主进程 16:43:50.569 冷启动为 PID 17659，push 16:44:05.290 冷启动为 PID 18432；不是只更新 APK 而沿用更新前进程。当前跑步页面位于 XRiverLite4，读取期间未停止、重开或更新支付宝。

16:58–16:59 的主/push TYPE_STEP_COUNTER 和 sportbiz TYPE_STEP_DETECTOR 回调仍约 189–190 步/分钟，最大投递间隔约 0.35 秒；主/push SDK 计数在增长。sensorservice 中的原生加速度明显受物理晃动影响，但所采集转储隐藏了原生累计步数，无法直接比较系统原始 counter 与模拟 counter。现有回调日志也未统计支付宝运动通道改写后的实际值，不能把独立测试 App 的姿态验证视为已验证每个支付宝进程。

从本机安装 APK 的 `PedometerServiceImpl.getTodayStepCount` 字节码确认另一条数据路径：它返回 `StepInfoRecord.uploadedDailyCount`，不是 `readDailyStep` 当前累计值。暂未确认悦跑营使用该入口，因此只记为待验证线索。新增可选只读观察该方法及 `HealthPedometerBridgeExtension.getRunData` 的调用次数、调用者与白名单数字字段，增加框架运动数组改写前后差异统计，以区分真正执行改写与仅注册传感器。诊断失败不会替换 SDK 方法结果，不读取桥接的加密数据、签名或账户内容。

该补充诊断的 release 构建通过，候选 APK 仅保存于忽略目录 `build/sensor-validation/app-global-arm64-v8a-bridge-diagnostics-release.apk`，没有在当前跑步中安装，也未替换 `app/global/release/` 的已验证通道修复包。用户断开 USB 准备无线调试后暂停设备读取，等待连接恢复与本轮最终结果。对照摘要位于忽略目录 `build/sensor-validation/brush-comparison.json`。

### 2026-10-06 框架投递与异步转发缺陷

对照 AOSP Android 11 / 16 的 `SystemSensorManager`，两者的队列分发仍为 `dispatchSensorEvent(int, float[], int, long)`，计步器累计值与 detector 单次值的单位没有版本差异。仓库适配前提交 `31ea16d` 保留框架原始分发，也没有按线程标记拦截具体监听器的计步回调；这只是仓库历史对照，尚未核对用户 Android 16 实际安装包对应的提交。

当前代码在框架入口抑制真实计步流，由定时线程直接调用监听器，并在具体监听器类的 Hook 中根据 ThreadLocal 放行模拟回调。模拟事件被同一类的另一实例异步转发时，线程标记不能传播，因此合法事件被误当成原始事件丢弃。在 Android 11 真机用普通 SensorManager 后台接收器复现：4 秒内入口收到 14 个事件（累计 69609→69622），工作线程的同类接收器收到 0 个。接收器只转发系统回调，不生成或改写计步数据；转发在入口回调返回前完成，避免复用 SensorEvent 导致的读值歧义。支付宝仍在前台，未中断用户记录。结果位于忽略目录 `build/sensor-validation/relay-before.json`。

修复仅在框架队列入口抑制原始计步事件，具体监听器的计步回调不再依赖线程标记。已注册的真实传感器通过实际 SensorEventQueue 投递模拟值，使用框架原本的事件数组、时间戳及准确度通知流程；没有原生队列的虚拟计步器保留直接回调，并补充准确度通知。相同监听器的多个传感器沿用首次捕获的 Handler；热重载保存队列引用，兼容旧保存格式。

core-geo 53 项、xposed 43 项回归检查通过，global release 双 ABI 构建成功。候选 `build/sensor-validation/app-global-arm64-v8a-framework-delivery-release.apk` 使用相同发布证书，SHA-256 为 `ea0cde2b7223cc5e08e3ab581b473587b752e4fb849237b37a3b2b616efa032b`。当前跑步期间只更新验证 App，没有安装模块候选或替换用户导出 APK。

本轮旧版支付宝 SDK 与计步回调仍约 191 步/分钟，异常过滤返回 false；`getRunData` / `getTodayStepCount` 的观察点暂未捕获实际调用，不能把历史 uploadedDailyCount 认定为原因。异步误拦截已独立复现，但尚未证明悦跑营必经此转发路径；候选修复仍需对照投递与最终业务结果，不能登记为小程序问题解决。

用户本轮最终反馈 24 步/分钟。记录结束后，按用户要求安装上述候选 Release，保留数据并强制结束支付宝；安装时间 21:17:32、3.0.1 / 30001、无 DEBUGGABLE。新启动的验证进程仍输出旧版首次投递格式，没有新增的 `path=framework/virtual` 标记，4 秒转发测试仍为入口 14、出口 0，因此这一结果不能登记为新版代码验证。已安装候选同步到 `app/global/release/app-global-arm64-v8a-release.apk`，等待重启清除框架旧加载状态，再做短暂后台对照；尚未登记业务通过。

稍后再次冷启动验证进程做相同后台转发测试，入口与工作线程各收到 14 个事件，累计值和时间戳逐个一致（`build/sensor-validation/relay-after-delayed.json`）。因此异步误拦截修复的实机对照通过；初次安装后即刻启动仍沿用旧行为，随后生效，不能简单认定必须重启。用户询问重启时曾建议重启，确认延后结果时 ADB 已离线，随后已告知更新生效有延迟、重启并非必需；恢复连接后还需核对框架投递标记，悦跑营最终步频仍未复验。

重启并解锁后确认新版在支付宝与验证进程均输出 `path=framework`。计步器后台转发入口/出口为 15/15，值与时间戳逐个一致；detector 为 13/13，全部值为 1，时间戳严格递增。分别保存于 `relay-after-reboot.json`、`detector-relay-after.json`。支付宝主/push 的 30 秒完成回调统计约 196/197 步/分钟，最大投递间隔约 444/323 ms。此处证明框架分发及异步转发缺陷已修复，不代表悦跑营本轮最终步频通过。验证过程保持支付宝前台，未替用户结束或提交记录。

### 框架投递修复后仍为空白：发现 SDK 实际拒绝计步事件

用户反馈新版再次完成仍只显示单位“步/分”。21:36–21:37 的实际日志与上一轮不同：支付宝主/push 的 counter 已通过 `path=framework` 投递，完成回调约 190–192 步/分钟；但 push 的 `SensorPedometer.checkDirtyStepEvent` 为 calls=2241 / rejected=2241，主进程为 calls=180 / rejected=180，返回均为 true。此前的“过滤未拒绝”只适用于上一轮观察，不能沿用到此次记录。

SDK 输入对照为上次 count=0、当前 count≈74583，设备重启后才运行约 15 分钟；主/push 仍保存约 20 万的 dailyCountOffset。字节码确认异常过滤会对较大步数增量计算频率，拒绝不合理跳变。代码中的共享计数此前跨设备重启继续累计，且旧初始值为 2350，违反 TYPE_STEP_COUNTER“自上次重启、传感器激活期间”的累计值语义。此次有 SDK 明确拒绝的证据，跨启动累计是很强的原因线索；尚未证明悦跑营唯一使用这一条 SDK 链路。原始日志保存于忽略目录 `build/sensor-validation/blank-after-framework-log.txt`，不清空或改写支付宝历史、上传记录和判定结果。

新增时钟 v2 记录 `Settings.Global.BOOT_COUNT`。同一次开机的跨进程、重新订阅、暂停和恢复继续共享累计值；新开机或迁移无法确认所属启动的旧时钟时，从 0 和共同发布时间锚点开始。配置发布者在恢复保存快照、重连以及用户变更时检查启动编号；目标进程遇到旧启动的快照时先保持 0，等待发布者给出本次启动的统一锚点，避免目标进程各自重建计数。兼容解码旧 v1 数据；读取启动编号失败时保留兼容行为。

core-geo / core-data / xposed 分别 55 / 46 / 43 项检查通过，共 144 项，覆盖 7 万旧累计跨重启清零、同次启动重连连续性及暂停/节奏改变保留启动标识。global Release 双 ABI 构建通过，沿用 3.0.1 / 30001 和用户发布证书。候选为 `build/sensor-validation/app-global-arm64-v8a-boot-clock-release.apk`，SHA-256 `64adca73daa9ee22b6e42bdef8870f1eb2145c7e77b3823b2bd79c06bbd17b51`。Android 11 已断开 ADB，用户优先用可用的 Android 16 完成 22:00 前的当天记录；本轮没有安装新候选，`app/global/release/` 保留上次实机框架投递包。后续应先验证 SDK 异常过滤是否接受新累计增量，再核对小程序最终步频。

### 2026-10-07 启动计数修复后的运行观察

用户自行更新模块（设备安装时间 07:16:16）并重新开跑。07:24–07:26 的日志中，主/push TYPE_STEP_COUNTER 完成回调约 186–189 步/分钟，sportbiz 的 TYPE_STEP_DETECTOR 约 186–188 步/分钟，最大投递间隔约 332–339 ms。两进程的 `checkDirtyStepEvent` 返回 false；截至该观察窗口主进程 calls=50 / rejected=0、push calls=29 / rejected=0，`readDailyStep` 持续增长。累计 counter 已在约 1000–1500 范围，而非刚开机时沿用约 7 万。这说明此前发现的 SDK 异常过滤在本轮观察中消失，但不能据此登记悦跑营最终步频通过。

实际小程序宿主的加速度改写也有记录：30.013 秒窗口内 768 个事件、768 个发生改写。读取过程没有停止、重开或更新支付宝和模块。原始日志保存在忽略目录 `build/sensor-validation/oct07-running-log.txt` 与 `oct07-running-log-latest.txt`。

按用户要求，将开始模拟窗口的自定义速度值和选择的速度档位保存到本地设置，界面重新创建或进程重启后恢复；非法或非有限速度回退到默认值。此改动不修改正在运行的配置，安装验证留到当前记录结束后。

速度持久化版 global Release 双 ABI 构建及签名校验通过，保留用户发布证书和 3.0.1 / 30001。候选已导出到 `app/global/release/`，arm64 APK SHA-256 为 `0c3669366a5a26956656d3cdc0d82e674c33b1eb32eae9056fb1ecc904e5a744`；另在忽略目录保存 `app-global-arm64-v8a-speed-persistence-release.apk`。当前记录期间没有安装该版本，速度持久化的应用重启验证尚未进行。07:27–07:28 的补充日志仍为 SDK 拒绝数 0，counter / detector 约 189–190 步/分钟。

### 2026-10-07 最终步频仍为空的数值核对

用户反馈悦跑营仍显示空白“步/分”。直接截图时前台为传感器验证 App，没有停止模拟、清除数据或重开支付宝。71 秒统计中 counter / detector 为 187.10 / 186.99 步/分钟，全部运动通道无时间戳倒退，主要通道约 50.35 Hz。截图、统计和日志分别保存在忽略目录 `oct07-blank-current.png`、`oct07-probe-observed.json` 和 `oct07-blank-followup-log.txt`。

当前个人模板为 v2、95 步/分钟、15 个跨步，含六轴曲线。模板 gyro X 为 -6.215～6.155 rad/s、标准差 4.254，而 Y/Z 标准差仅 0.0064 / 0.0328；积分后的 X 姿态摆幅约 69.62°。回放到约 187 步/分钟后，gyro 模长峰值为 13.56 rad/s，X 标准差为 8.37，Y/Z 为 0.0128 / 0.0646。因此当前“陀螺仪近乎静止”已不符合测量；较强且几乎单轴的运动主要来自录制模板及约两倍的回放节奏，尚不能认定为小程序失败原因。模板摘要保存为 `oct07-gait-summary.json`。

重力模长约 9.80665 m/s²，尽管模长标准差接近 0，其 Y/Z 分量标准差为 0.811 / 3.910，方向在变化。三个旋转向量的完整四元数长度约 0.99999994～1.00000006；前三项长度小于 1 本身符合 Android 数组定义，不能按加速度或陀螺仪幅值解释。加速度模长 9.88～16.08 m/s²，线性加速度 0.175～8.392 m/s²。现有模板动态加速度仅去除周期均值，录制期间转动导致的时变重力可能仍残留，之后又叠加姿态重力；这属于需要核对的跨传感器物理一致性线索，不能仅凭统计确认泄漏大小。

同一日志中 `checkDirtyStepEvent` calls=57 / rejected=0，SDK 原始当前累计增量被接受；但 StepInfoRecord 仍保存 dailyCountOffset=187026，readDailyStep=189897，finalDailyCount=0、uploadedDailyCount=0。这些分别属于历史偏移、当前读数和其他业务字段，不应视为同一条步频链路。未捕获悦跑营实际读取哪个字段的证据，空白步频原因仍未确认，不修改业务字段或上传结果。Release 覆盖安装不要求清除用户数据；本轮观测不足以支持用清空模块/支付宝数据解决问题。

### 2026-10-07 Java / NDK 与独立软件计步对照

增加验证 App 的独立 NDK 读取及 opt-in 原生入口诊断，详情见 [步频资料与实测记录](android11-step-simulation-research.md)。同一台 Android 11 手机的 32 位验证进程、静置且开启模拟时，Java counter / detector 约 190 步/分；加速度经独立 Google SimpleStepDetector 算法得到 189.51 步/分，而 NDK 原始加速度算出 0。确认 Java 模拟有独立 NDK 覆盖缺口，但不能直接把它认定为悦跑营失败原因。

通用诊断 Release 已按原证书保留数据更新，包含自定义速度持久化，版本 3.0.1 / 30001；无需重启，Vector 加载更新延迟后冷启动生效。NDK 诊断仅观察，不改写事件。候选导出为 `app/global/release/app-global-universal-ndk-diagnostics-release.apk`，两个原 ARM 分包导出名没有被这次通用诊断包覆盖。

悦跑营登录、开跑后的短观察捕获 Java sportbiz STEP_DETECTOR 注册及 187–190 步/分完成回调，SDK 异常拒绝数为 0；未捕获支付宝 NDK getEvents 读取，SensorService 直接通道为 0。普通 StepCollector 字节码生成 STEP_MOVE 后通知下游，尚未核对其运行时消费和桥接返回。Android 16 成功原因及极速模式影响尚未确认，当前没有登记最终业务步频已修复。

随后准备页的桥接名称观察确认调用 `syncUserSportData`。本机 APK 的普通网络同步成功分支把服务端 userDailyCount 写入 stepInfo.step，并另附本地 sourceList；最终步频实际选用字段及本次响应仍待验证。已补只读本地 stepCount / accuracy 与同步 success / statusCode / userDailyCount 对照，编译并保留数据安装通用诊断 Release `app/global/release/app-global-universal-sport-sync-diagnostics-release.apk`，仍为 3.0.1 / 30001，原证书且无 debuggable 标志。08:29 冷启动确认同步观察点加载。本地今日累计接近 20 万步、历史偏移 187026 是待核对异常，不等于已证明服务端拒绝；没有删除历史或改写最终结果。完整证据与包哈希见上述实测记录。

08:49 本轮结束后用户仍报告空白“步/分”。结束时的同步调用实际输入 sources=0，指定同步返回类型的本地方法返回 null；运动状态 STEP_MOVE 仍约 189 步/分。不能据此认定服务器返回 0：普通客户端实现也可在身份/RPC 服务未就绪时提前返回 null。系统 ACTIVITY_RECOGNITION 已授权。08:57 保留数据更新 `app-global-universal-sport-source-diagnostics-release.apk`，仍为原签名 Release 3.0.1 / 30001；本轮结束后才冷启动支付宝，08:58 确认来源注册、SDK 授权、身份存在布尔值与运动桥接诊断加载。实际同步回包和空来源分支仍待准备页观察，不登记为最终步频已修复。

## 对照源码

- [AOSP Android 11 LocationManagerService](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-11.0.0_r1/services/core/java/com/android/server/location/LocationManagerService.java)
- [Android SensorEvent 数组布局与量纲](https://developer.android.com/reference/android/hardware/SensorEvent)
- [Android BOOT_COUNT 系统启动编号](https://developer.android.com/reference/android/provider/Settings.Global#BOOT_COUNT)
- [AOSP Android 11 SystemSensorManager 事件队列](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-11.0.0_r1/core/java/android/hardware/SystemSensorManager.java)
- [AOSP Android 16 SystemSensorManager 事件队列](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-16.0.0_r1/core/java/android/hardware/SystemSensorManager.java)
- [AOSP Android 11 SensorManager 四元数与坐标转换](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-11.0.0_r1/core/java/android/hardware/SensorManager.java)
- [AOSP Android 11 IGnssStatusListener](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-11.0.0_r1/location/java/android/location/IGnssStatusListener.aidl)
- [AOSP Android 11 GnssStatus 编码](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-11.0.0_r1/location/java/android/location/GnssStatus.java)
- [LineageOS 18.1 WifiScanningServiceImpl](https://raw.githubusercontent.com/LineageOS/android_frameworks_opt_net_wifi/lineage-18.1/service/java/com/android/server/wifi/scanner/WifiScanningServiceImpl.java)
- [VectorRemotePreferences 缓存与通知](https://raw.githubusercontent.com/JingMatrix/Vector/master/xposed/src/main/kotlin/org/matrix/vector/impl/VectorRemotePreferences.kt)
- [VectorContext 按组复用远程偏好实例](https://raw.githubusercontent.com/JingMatrix/Vector/master/xposed/src/main/kotlin/org/matrix/vector/impl/VectorContext.kt)
