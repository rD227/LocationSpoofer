# Android 11 / LineageOS 步频模拟资料核对

检索日期：2026-10-07。目标是解释独立验证 App 的计步约 190 步/分钟，而支付宝悦跑营最终步频为空的差异。下文区分平台事实、公开项目实现和本项目待验证推断；资料核对后的设备验证记录见末节。

## 平台接口与累计语义

LineageOS 18.1 的 `SystemSensorManager.SensorEventQueue` 仍使用 `dispatchSensorEvent(int, float[], int, long)`。传感器由实例的 `mHandleToSensor` 按 handle 查找，handle 不等于传感器 type。每个监听器共用一个队列和 Looper，框架复制数组、填充时间戳及准确度后通知监听器。旧示例中硬编码 handle=5、查静态 `sHandleToSensor` 等写法不能直接套用。[LineageOS 18.1 SystemSensorManager](https://raw.githubusercontent.com/LineageOS/android_frameworks_base/lineage-18.1/core/java/android/hardware/SystemSensorManager.java)

STEP_DETECTOR 每个事件的值是 1；STEP_COUNTER 是本次系统启动以来、激活期间的累计值，不是步/分钟。Detector 时间戳对应落脚时刻，counter 时间戳对应该次累计更新的最后一步。请求采样间隔不决定 detector 的步频。[AOSP 传感器类型规范](https://source.android.com/docs/core/interaction/sensors/sensor-types#step_detector)

Android 11 的事件时间戳必须使用与 `SystemClock.elapsedRealtimeNanos()` 相同的时基且单调递增，不能传入 Unix 毫秒时间。加速度、重力和线性加速度需要满足相同坐标下的分解关系；单个通道幅值正常不足以验证它们物理上一致。[LineageOS 18.1 SensorEvent](https://raw.githubusercontent.com/LineageOS/android_frameworks_base/lineage-18.1/core/java/android/hardware/SensorEvent.java)

Android 10 起计步传感器需要 ACTIVITY_RECOGNITION 权限。我们已观察到支付宝注册和持续收到计步事件，因此不能仅凭最终步频为空，把本轮原因归于缺权限。[Android 运动传感器说明](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion#sensors-motion-stepcounter)

## Java Hook 的覆盖边界

LineageOS 18.1 的 NDK `ASensorEventQueue_getEvents()` 直接执行原生队列 read / sendAck / filterEvents，不调用 Java 的 `SensorEventQueue.dispatchSensorEvent()`。所以只有 Java Hook 时，独立 NDK 客户端仍可能读取原始运动数据；但尚未证明悦跑营使用该入口，不能把这个覆盖缺口直接登记为本次失败原因。[LineageOS 18.1 原生 sensor.cpp](https://raw.githubusercontent.com/LineageOS/android_frameworks_base/lineage-18.1/native/android/sensor.cpp)，[NDK 传感器 API](https://developer.android.com/ndk/reference/group/sensor)

系统测试另有 `initDataInjection` / `injectSensorData`，但要求 SensorService 和 HAL 支持并进入 DATA_INJECTION 模式，传感器自身也要声明可注入。进入此模式会影响真实传感器输入，不能假定有 root 就能在这台手机使用，更不能未经核实就在当前记录中切换。[LineageOS 18.1 SensorManager 注入接口](https://raw.githubusercontent.com/LineageOS/android_frameworks_base/lineage-18.1/core/java/android/hardware/SensorManager.java)

## 公开项目的参考价值

- [SportEditor 发布记录](https://modules.lsposed.org/module/name.caiyao.sporteditor/)：2.4.1 的 Android 11 修复明确涉及新存储机制导致配置修改失效。这是该项目的兼容记录，不是 Android 11 改变计步单位的证据。
- [SportEditor MainHook 源码](https://raw.githubusercontent.com/YiuChoi/SportEditor/master/app/src/main/java/name/caiyao/tencentsport/MainHook.java)：公开 master 的支付宝分支同时处理加速度与 STEP_COUNTER / STEP_DETECTOR；可用于识别不同输入路径。代码含很大的数值倍率、硬编码 handle 和 Unix 毫秒时间戳，并且公开源码不代表已核实的最新发布实现，不能照抄作为实时步频模型。
- [android-step-detector 示例](https://github.com/huuphuoc1396/android-step-detector)：分别提供硬件 detector 和加速度软件检测路径，适合参考如何在普通测试客户端中比较两种计算结果。它没有证明悦跑营具体采用哪个算法。
- [VirtualSensor](https://github.com/frazew/VirtualSensor)：提供陀螺仪、重力、线性加速度和旋转向量的融合思路，但作者标注支持 SDK 16–23，也没有计步模拟，不能作为 Android 11 可用性证据。
- [FakeLocation 公开仓库](https://github.com/Mai-xiyu/FakeLocation)：标注仅在 HyperOS 3 测试，仓库主要用于反馈，没有公开模块实现；不能用它回答 Android 11 源码兼容问题。

## 对当前问题的结论与验证顺序

没有找到足以证明“Android 11 需要特殊步频单位或倍率”的资料，也没有找到可直接套用且已验证悦跑营的 Android 11 模拟实现。当前 Java 回调、累计语义和 SDK 异常过滤已有实机证据；这仍不等于小程序最终业务入口收到正确数据。

下一轮应先验证模拟加速度本身能否被独立的软件计步算法识别为与 detector 接近的节奏，重点核对录制模板的时变重力、波形周期和每步事件相位。然后定位小程序的实际计步入口，区分 Java、NDK 及宿主业务聚合字段。只有确认 NDK 被使用，再设计该入口的诊断或兼容改动。最终/已上传计步字段为 0 仍是现有日志线索，未证明它们属于悦跑营必经路径。

不通过修改小程序最终结果、上传步数或清除历史数据来代替传感器兼容性验证。

## Java / NDK 同进程实测（2026-10-07）

普通验证 App 增加独立 NDK 队列，使用 `ASensorManager_getInstanceForPackage` / `ASensorEventQueue_getEvents`，同时保留 Java SensorManager。先在 64 位验证进程、再在与本机支付宝一致的 32 位进程中对照，路线和步频模拟持续开启。验证 App 不生成或替换传感器数据。

32 位静置窗口约 66 秒，两条连续运动流均约 50.35 Hz、没有时间戳倒退：

| 输入 | Java | NDK |
| --- | ---: | ---: |
| TYPE_STEP_COUNTER 步频 | 190.13 步/分 | 0（只收到初始真实累计 18） |
| TYPE_STEP_DETECTOR 步频 | 189.84 步/分 | 0（没有单步事件） |
| 加速度模长标准差 | 1.8081 m/s² | 0.01192 m/s² |
| 陀螺仪模长标准差 | 3.7724 rad/s | 0.001196 rad/s |
| 重力模长标准差 | 3.13×10⁻⁷ m/s² | 4.73×10⁻⁷ m/s² |

独立采集两条加速度 CSV，采用 [Google SimpleStepDetector](https://github.com/google/simple-pedometer/blob/master/src/com/google/android/apps/simplepedometer/SimpleStepDetector.java) 的原始窗口、阈值与最小步间隔，计算过程不读取模块的 cadence、counter 或 detector。约 67.7 秒采样，去除前 5 秒预热后，Java 加速度识别到 198 步、189.51 步/分，NDK 加速度识别到 0 步。这里只验证一种公开算法，不能推广为悦跑营的算法或全部物理一致性检查通过。

这证明本项目当前的 Java 传感器模拟没有覆盖独立 NDK 客户端，同时证明当前 Java 加速度可以被一种独立软件算法识别为约 190 步/分。尚未证明悦跑营使用 NDK，也没有用此对照登记其最终步频已修复。记录保存在忽略目录 `build/sensor-validation/oct07-32bit-acceleration-window.json`、`oct07-32bit-java-acceleration.csv`、`oct07-32bit-ndk-acceleration.csv`、`oct07-software-step-analysis.json`。

type 9 为重力：模长约 9.80665 m/s²，方向变化反映在 XYZ；融合实现的重力模长标准差接近 0 本身符合定义。验证界面已补充 NDK 的名称、模长范围、XYZ 与各轴标准差，避免将模长标准差 0.0000 误解为所有通道静止。

## 可选原生入口诊断

新增 `NativeSensorTrace`，采用 [LSPosed Native Hook ABI](https://github.com/LSPosed/LSPosed/wiki/Native-Hook) 以及 [Vector 原生加载接口](https://github.com/JingMatrix/Vector/blob/master/native/src/core/native_api.cpp)，观察 `libandroid.so` 的 `ASensorEventQueue_getEvents`。保留原函数返回值、事件缓冲区及 errno，只记录调用库名、白名单传感器类型、数值统计与时间戳。每队列每 15 秒汇总，队列数有上限；不读取账户、桥接加密内容或业务结果。

仅用 `-PstepPipelineDiagnostics=true` 编译且宿主为支付宝/验证 App 时加载，不对 system_server 开启。原生 trampoline 不能跟随 Java 类加载器热重载，已加载该诊断的宿主需冷启动更新。新增库包含 armeabi-v7a / arm64-v8a，跨 ABI 验证时使用 `-PnativeDiagnosticsUniversal=true` 生成通用 Release；单独 arm64 APK 不含支付宝 32 位进程所需的库。

通用诊断 Release 构建、原发布证书校验及保留数据覆盖安装通过。版本仍为 3.0.1 / 30001，SHA-256 为 `ae67f70c9f59e4738a95b92624f0a3e266d86c131554b748fdc6c559d5a52b9e`。Vector 包更新存在加载延迟：第一次立即启动仍未加载原生诊断，稍后冷启动确认两种 ABI 均 `active=true` 并观察到验证库真实读取。未重启设备；该包也包含前述自定义速度持久化改动。此版本增加诊断，不对 NDK 注入模拟事件。

随后冷启动支付宝 10.3.76.8000（32 位）并打开悦跑营，用户正常登录至跑步准备页。主/子进程的原生库加载由 Vector 日志确认，多个子进程报告诊断 `active=true`；此观察窗口没有支付宝的 NDK `getEvents` 轮询日志。只检查 APK 自带的 197 个原生库，未找到常见 ASensorManager/getEvents API 名称，不能排除下载插件、动态解析或其他原生入口。SensorService 的准备页连接为 Java 地图传感器、APS 步数处理器及定位 SDK，直接通道连接为 0，加速度/陀螺仪的能力标记也没有直接通道支持。本段限于准备页，不能排除开跑后另行启用的路径，也没有证明极速模式改变了计步入口。

离线保留原始波形、按固定间隔抽取样本后，原参数软件算法在约 25.17 Hz / 10.07 Hz / 5.03 Hz 输入得到约 137.82 / 0 / 0.96 步/分。因此 50 Hz 对照成功不保证所有客户端请求的频率及内部节流都成功；这只是采样敏感性的独立算法实验，尚未确认悦跑营的实际算法与频率。没有据此强制改动宿主采样率。

用户正常通过验证开跑后，08:06:31–08:07:33 窗口新增 Java `com.alipay.android.phone.wallet.sportbiz.a.d` 的 STEP_DETECTOR 注册，回调两次汇总约 190 / 187 步/分，最大事件间隔约 333 / 347 ms；health counter 约 188–191 步/分，异常拒绝数仍为 0。SensorService 只见对应 Java collector 与地图连接，直接通道仍为 0；原生 getEvents 诊断未捕获支付宝轮询。告知用户短采样已够，可自行结束，不要求最终里程/步频，也未更新模块或强制结束当前记录。原始日志为 `oct07-live-native-route.log`，系统注册对照为 `oct07-sensorservice-running.txt` / `oct07-sensorservice-running-final.txt`。

APK 中该 StepCollector 的公开字节码经 DexAOP 代理进入私有 stub：类型为 18 时创建 `STEP_MOVE` 事件并通知下游 detector 列表。其普通实现不使用传感器数值、重力模长或 SensorEvent 时间戳来筛选这一步；运行时热补丁可能改变实现。当前尚未观察 STEP_MOVE 的实际下游消费数量或小程序桥接取数，不能把“回调正常返回”当成这些阶段已收到正确计步。下一定位点应为这条 Java 消费链，而不是仅凭支付宝含原生库就断定 NDK 负责步频。

## 最终步频计算的可检验假设

继续追普通 APK 的 StepStateDetector：`f.a(event)` 仅将事件的墙钟时间保存为最后运动时间，供定时判断自动暂停/恢复使用。它没有直接累加最终步数。因此 detector 回调约 190 步/分不能直接证明最终步频的数据源正确。

待验证的假设包括：小程序读取的是延迟更新的每日累计值，或对批次/通知计数而不是累计步数差进行计算。每 5 秒一次通知且每次记 1，会得到 12 步/分；3 秒/2.5 秒对应 20/24。这些数值与历史反馈接近，但数值相似不构成实现证据。正确累计差的换算应为 `(结束累计 - 开始累计) × 60000 / 有效时长毫秒`，仍需确认该小程序使用的累计字段与有效时间范围。

新增只读诊断：在现有 APK 确认的 Ariver `BridgeDispatcher.dispatch` 和旧 H5 `H5BridgeImpl.sendToNative` 上，仅观察 step/sport/rundata/运动传感器相关 API 名称，嵌套分发使用线程深度去重。没有读取请求参数、调用标识、账户内容或加密返回。另在实际 StepStateDetector 中记录 STEP_MOVE 消费次数与最后运动时间年龄，以区别入口返回与下游更新。所有诊断仍由编译开关控制，不修改 SDK 结果和小程序判定。

### 实际取数接口与同步来源

桥接名称诊断 Release 构建及同证书覆盖安装通过，冷启动支付宝后主/子进程均安装 4 个 Ariver dispatch、2 个旧 H5 sendToNative 和 STEP_MOVE 消费观察点。用户正常登录并进入准备页；08:22:27–08:22:36 捕获 `querySportAuthState`、`queryDeviceSportAuthorize`、`getSportsBgStatus`、`querySportDetectData`、`syncUserSportData`。这一窗口没有 getRunData 调用，不能推广为它在所有阶段都不使用。日志为 `oct07-live-bridge-names.log`。

对应本机 APK 的 `PedometerKitBridgeExtension.syncUserSportData` 有两条取源分支：新多源读取开关启用时调用 `PedometerSDK.readMultiDailyStep`，否则由 `RpcClient.a()` 收集来源列表；随后进入 `syncStepListData`。其普通网络同步成功分支将服务端 `StepCounterSyncResultPB.userDailyCount` 写入 `stepInfo.step`，同时 `sourceList` 携带本地来源的 stepCount / accuracy。离线快捷分支可以保留初始化的 step=0 并返回来源列表。尚未记录本次调用的同步响应，不能认定准备页实际选中哪条返回分支，也不能认定最终步频使用 step 而非 sourceList。

这把优先假设收窄到：小程序最终累计差可能来自同步/聚合后的值，或选择 sourceList 内另一来源；本地传感器增量与最终累计差不一定一致。另有具体异常：08:21–08:26 的本地 readDailyStep 从约 196419 增至 197319，历史 dailyCountOffset=187026，finalDailyCount=187138。当前增量仍约 190 步/分，但今日累计绝对值偏大；没有服务端响应证据，不能据此断定被拒绝，也没有清除或改写缓存。

按本机公开字节码补充只读 `SportSync` 观察点：进入 `syncStepListData` 时只统计 StepDataPB 的 stepCount / accuracy，在该线程的同步范围内仅观察返回 StepCounterSyncResultPB 的 RpcClient.a 重载，并记录 success / statusCode / userDailyCount。列表输出限制 16 项、每 30 秒汇总；不读取请求标识、账户字段、错误说明或加密返回，也不发起额外同步。宿主原方法仍以原参数执行、原结果返回、原异常传播。该改动只定位数据来源，不构成最终步频修复。

同步数值诊断的通用 Release 构建、lintVital、原证书验证、保留数据覆盖安装均通过；版本仍为 3.0.1 / 30001，没有 debuggable 标志。导出为 `app/global/release/app-global-universal-sport-sync-diagnostics-release.apk`，SHA-256 为 `d2027541186fa36336badd3611d394d5cdde2ca95f98b6042d32e956b5fca6b2`。08:29 冷启动后多个支付宝进程确认安装 1 个 syncStepListData、12 个 RpcClient.a 重载观察点；仅匹配同步范围内指定返回类型，尚待实际页面同步记录。未重启手机，也未开始或提交额外跑步记录。

用户随后告知已经开跑。08:36:13 捕获 startSportDetect；08:36:14–08:45:30 的 STEP_MOVE 消费累计由 1 增至 1699，汇总采样点的事件年龄和最后运动时间年龄均 0–1 ms，活跃窗口约 186–191 步/分。08:39:10 / 08:39:13 另捕获 pauseSportDetect / resumeSportDetect。这确认运动状态检测的实际下游消费，仍不是最终步频累计来源的证明。当前观察尚未捕获 SportSync 数值，不把“没有日志”解释为同步返回 0。运行期间没有更新、停止支付宝或提交跑步记录。日志为 `oct07-sport-sync-running-initial.log`、`oct07-live-sport-sync-running.log`，续采使用限时 10 分钟的 `oct07-sport-sync-running-bounded.log`。

### 小程序自行处理运动数据的另一入口

[Alipay+ 官方 onAccelerometerChange 文档](https://miniprogram.alipay.com/docs/miniprogram/mpdev/api_device_accelerometer_onaccelerometerchange) 展示 H5 通过 watchShake / monitorAccelerometer 监听 accelerometerChange。这只能证明该 SDK 具备此能力，不能把其公布的 500 ms 间隔直接套用于本机大陆支付宝旧版本或悦跑营。

本机 APK 也独立确认 SensorBridgeExtension.watchShake 按 monitorAccelerometer / monitorGyroscope 注册对应服务；其 $3 / $5 回调分别通过 EngineUtils.sendToRender 发送 accelerometerChange / gyroscopeChange。因此小程序既可以使用支付宝的运动状态服务，也可能自行处理传感器数据。现有名称过滤漏掉 watchShake，旧日志没有加速度 API 名不能据此排除这一路径。

诊断源码已补 shake / motion 名称，并在本 APK 确认的 EngineUtils.sendToRender / H5BridgeImpl.sendToWeb 上按运动事件名统计正常返回的分发次数和频率。嵌套同线程分发去重，不读取运动请求参数或返回载荷；这些是渲染层发送记录，不保证 JS 回调最终执行。通用 Release 编译、lintVital、签名校验通过，导出 `app/global/release/app-global-universal-motion-bridge-diagnostics-release.apk`，SHA-256 为 `c3e4bd6093120874c4e6dff018d891318b6ae3d3d8119bd44dc81c736f622ef6`。当时仍在跑步，未安装此补充包；手机仍为前述同步数值诊断 Release。

### 本轮结束：空来源列表与空同步返回

用户结束本轮后确认最终显示空白“步/分”。08:49:31 的运动状态检测消费累计 2457，最近窗口约 189 步/分；08:49:33.263 小程序调用 syncUserSportData，08:49:33.274 实际 syncStepListData 输入为 sources=0 / counts=[]，08:49:33.281 指定返回类型的 RpcClient.a 方法正常返回 null，08:49:33.921 调用 stopSportDetect。结束日志保存在 `build/sensor-validation/oct07-sport-sync-running-bounded.log`；本机限时采集已停止，未结束其他日志进程。

这是具体的同步异常证据，尚不能认定小程序最终使用该字段。null 是本地方法返回值，不等于服务器返回 0 或拒绝计步：本 APK 的普通实现，在 CommonUtil.getUserId 为空或找不到 RpcService 时即可提前返回 null。普通 syncStepListData 对 null 分支保留初始化 step=0，附 bizError=10007；本轮未直接捕获 BridgeCallback 回包，因此该回包解释仍属源码推断。

RpcClient.a() 的普通取源分支枚举 PedometerAgent.getPedometers，随后按各来源的 PermissionChecker.checkPermission / getPedometerStatus 筛选。来源列表为空可能是此进程没有注册来源，也可能是 SDK 内部授权筛选；不能用主进程 readDailyStep 正常代替该进程的取源证据。Android 系统 ACTIVITY_RECOGNITION 当前已授权。

诊断继续补充这些实际调用的返回观察，以及 syncStepListData 使用的 BridgeCallback.sendJSONResponse 数字白名单。身份相关方法仅输出 session_present 布尔值，不输出用户标识；回包仅输出 success / bizError / error / step / sources，忽略账户、地址与说明文字。原参数、原返回值及原异常不变，不主动调用 SDK 初始化、读取或同步，也不改写业务步数。这些改动用于区分具体分支，并不表示最终步频已经修复。

补充包包含运动桥接诊断，通用 Release 编译、lintVital、原证书校验通过；导出 `app/global/release/app-global-universal-sport-source-diagnostics-release.apk`，SHA-256 为 `d59de233a8fc697d8f29462f09d7adabff4431f9faebbda9e6e960b44f3f7d26`。08:57:20 保留数据覆盖安装成功，包仍为 3.0.1 / 30001，安装标志不含 DEBUGGABLE。用户本轮结束后才冷启动支付宝，08:58:15 起多个进程确认来源注册/授权/身份存在与运动桥接观察点加载。续采限定 5 分钟，日志为 `oct07-source-reply-live.log`；本次仅请求正常打开准备页，不要求再次开跑。

### 准备页观察与客户端版本变量

用户确认已进入准备页，截图和前台活动确认该页面。08:59:41–08:59:46 捕获 querySportAuthState / queryDeviceSportAuthorize / getSportsBgStatus，当前未捕获 syncUserSportData；09:06 查看刚结束的既有记录详情，仅新增 querySportAuthState，也未触发同步。因此本次没有 SportSources / SportSync reply 实际调用记录，不把未调用当成返回空列表、未登录或未授权。上一轮结束的 sources=0 / 本地同步方法 null 仍是有效证据，具体返回分支未确定。

本次 push 进程仍注册 APStepProcessor 的 STEP_COUNTER，SensorPedometer / PushSensorPedometer 的本地累计继续增长；它不能直接代表小程序子进程的来源注册或同步回包。准备页诊断采集已按 5 分钟上限结束，查看既有记录的续采同样限时；没有启动、结束或提交新的跑步记录。

Android 11 上实际安装的支付宝为 10.3.76.8000 / code 761 / target 29 / 32 位。用户报告 Android 16 上成功的支付宝版本为 12.12.16.7200，尚未从那台设备读取或校验 APK。这两套环境同时改变了系统与客户端版本，不能从成功/失败直接推出 Android 11 平台特有的问题。下一步优先离线比较成功设备的支付宝及 LocationSpoofer 安装包，再选择兼容修复位置；目前 ADB 仅连接 santoni，已请求本地 APK 路径或成功设备接入，不要求再次跑步测试。

### 成功设备安装包与两份 SensorService 转储的核对

随后接入 OnePlus 7 Pro（`d237ad98`），只读确认 SDK 36、支付宝 12.12.16.7200 / code 212162 / target 35 / arm64-v8a，LocationSpoofer 3.0.0 / code 30000 / Release。成功设备的模块证书与 Android 11 上当前的用户签名不同。本次提取两个 base APK 做离线字节码对照，没有更新、停止成功设备上的应用，也没有启动新的跑步记录。支付宝由 base 与多个 split 组成，以下代码结论仅针对提取到的 base，仍可能受运行时热补丁影响。

两版 `syncUserSportData` / `syncStepListData` 的普通实现很相似：枚举 PedometerAgent 来源并做 SDK 授权筛选，再调用同步接口。旧 RpcClient 为 `a():List` 和 `a(String,List):StepCounterSyncResultPB`；新版为 `h():ArrayList` 和 `k(String,List):StepCounterSyncResultPB`。不能仅由这些改名推出新版会解决空来源或最终步频。`queryLocalStepRecord`、CommonUtil 的 `isSandboxApp` / `lazyInit` 在旧版也存在，不能把它们当成新版独有能力。

成功设备 3.0.0 的传感器实现使用进程内步数基准、每秒配置工作线程直接分发 counter / detector，并额外分发一次 accelerometer；加速度回放使用当时的 gait template，未找到当前新增的陀螺仪、姿态模拟及共享 StepCounterClock。当前版本使用独立计步事件泵、共享累计时钟和框架队列分发，连续传感器按原注册事件改写。上述属于实际代码差异，尚不能据此认定哪一项造成最终步频空白，也不能用旧版 detector 的分发次数直接代表用户成功记录的步频来源。

用户保存的完整转储为 `xposed/sensorservice-a11-redmi4x-30c5908a7d34.txt`（12:10:31）与 `xposed/sensorservice-a16-onplus7pro-d237ad98.txt`（12:00:56）。核对结果：

| 项目 | Android 11 / Redmi 4X | Android 16 / OnePlus 7 Pro |
| --- | --- | --- |
| 主加速度传感器 | handle 0x1，type 1，continuous，1–200 Hz | handle 0xb，type 1，continuous，1–415.97 Hz |
| 主陀螺仪 | handle 0x5，type 4，continuous，1–250 Hz | handle 0x29，type 4，continuous，1–415.97 Hz |
| Step Counter | handle 0x15，type 19，on-change | handle 0xbf，type 19，on-change |
| Step Detector | handle 0x14，type 18，special-trigger | handle 0xb5，type 18，special-trigger |

`0.02 Hz` 对应 A11 的光线/距离等 on-change 条目；`0.00 Hz` 出现在 counter 和其他事件类条目。这些数值不是加速度或陀螺仪的当前采样频率，不能解释成无法发送计步事件。[Android 官方 reporting modes](https://source.android.com/docs/core/interaction/sensors/report-modes) 区分连续、变化上报与触发模式；[Sensor API](https://developer.android.com/reference/android/hardware/Sensor#getMaxDelay()) 也要求忽略非正的 maxDelay，不应把其倒数作为有效连续频率。

A11 文件含 41 个 HAL 传感器条目；该次快照的 Active sensors 有 accelerometer、magnetometer、rotation vector、counter，共 4 个 handle / 5 个连接。accelerometer 的 active-count=3，请求周期为 66.7 / 200 / 200 ms，HAL 选择 66.67 ms（约 15 Hz）。它只反映当时请求的采样周期，不是硬件最高频率。支付宝地图 listener 请求 200000 us（5 Hz），系统屏幕方向 listener 请求更快；不能由 HAL 的合并频率推定小程序自身按 15 Hz 取数。

在 A11 这份转储保留的注册记录范围内，没有支付宝注册陀螺仪的证据；0x5 / 0x6 的记录来自探针 App。A16 的 0x29 由 `NFCSensorInfoCollector`（10000 us）与 `SensorFeatureTask`（20000 us）注册，二者是同一个支付宝包内的 listener 类，并不是两个应用，也尚未证明悦跑营使用这些数值计算最终步频。A11 的运动 collector 曾注册 0x14 / STEP_DETECTOR，不能因准备页已经注销就判断其从不订阅计步传感器。

SensorService 的 Recent Sensor events 位于 HAL / 服务端，而当前模拟在宿主的 SystemSensorManager 队列中改写/投递，因此转储中的真实 counter 小值或静置波形不能直接当成 Hook 失败证据。此前 Java/NDK 探针对照收到约 50 Hz 的真实原生连续传感器事件，没有硬件损坏证据；完整硬件运动响应仍应在关闭模拟或未被 Hook 的探针上实际转动测试，不能只看已被改写的 Java 波形。

用户补充同一支付宝登录会挤占另一设备，故后续版本对照必须顺序登录，并验证当前设备会话有效。它是结束同步返回 null 的一种可检验条件，但没有本轮 `session_present` 返回观察，不能把此前所有失败归因于账号挤占。

只读同步诊断已改为按已验证的返回类型选择 RpcClient 方法，同时识别 List 与 ArrayList，兼容上面的旧 a / 新 h、k 改名。保持原参数、返回值、异常与业务计步不变；这修补的是观察缺口，不宣称修复最终步频。优先用与成功设备相同的支付宝版本、保留当前模块实现做一次版本变量对照，再决定传感器兼容改动。豌豆荚页面在内置抓取工具返回访问错误，未把用户贴出的版本列表当作已验证安装包元数据。

该补充源码的通用 Release 构建（含 lintVital）及签名校验通过，版本仍为 3.0.1 / 30001，证书与 Android 11 当前用户签名一致。导出 `app/global/release/app-global-universal-alipay-compatibility-diagnostics-release.apk`，SHA-256 为 `3b43db5cea62df0214cd2f75e6835a46aa8526c007ce6ffcf37404d56a97dbcd`。本次未安装该包，两台的已安装模块仍为此前版本。

进一步只读确认 A16 支付宝的 installer 为 `com.android.vending`，A11 的 installer 为 null；A11 系统支持 arm64-v8a / armeabi-v7a / armeabi。已从成功设备提取全部 17 个 APK（base 加 16 个 split，共 471,936,395 字节），保存到 `build/sensor-validation/android16/alipay-12.12.16.7200-apk-set/`。这套安装文件提供相同客户端的对照候选，尚未安装到 A11，也未证明升级可以修复最终步频；分包应成套使用，不能只安装 base。没有提取账户或应用数据。

17 个 APK 的签名均验证通过且一致；主签名证书 SHA-256 为 `389b49f7832f53e9017923220aa85e14dfaa4886ecd7428818bf339543cf498a`，与此前提取的 A11 旧版支付宝 APK 一致。Google Play 的 Source Stamp 是单独的来源签名，不应与应用的主签名证书混淆。

### Android 11 更新客户端后的 407 步/分记录

用户自行更新支付宝至 10.7.88.8000 / code 3190 / 32 位，UID 由旧版的 10167 变为 10174。本次未安装从成功设备提取的 Play 分包；用户随后要求暂缓安装。当前 10.7.88 的 base APK 已只读备份到 `build/sensor-validation/alipay-10.7.88.8000-before-play-update/`，其应用证书与上述候选一致。

早期观察中没有宿主 Hook 日志，只能列出作用域加载作为待查条件；后续只读核对 Vector 数据库确认模块启用、支付宝 user 0 作用域存在，14:42–14:44 的实际回调也确认加载。因此不能继续把当前失败归因于未勾选作用域。模块仍是此前的 3.0.1 / 30001 来源诊断 Release，设备上的更新日期为 08:57:20；本节后续诊断源码尚未安装。

新客户端的 `StepDataManager` 注册 TYPE_STEP_COUNTER，`StepCollector` 注册 TYPE_ACCELEROMETER，实际请求 10000 us。这是具体版本入口变化，不需要由是否订阅陀螺仪来推断计步是否成功。14:43:25 / 14:43:55 / 14:44:25，StepDataManager 实际收到的累计差分别约 180 / 209 / 189 步/分；health counter 也约 190–197 步/分，异常计步拒绝数仍为 0。

14:44:43 结束同步实际输入为一个来源，StepDataPB.stepCount=1715 / accuracy=85，当前会话 `session_present=true`。本地 RPC 方法返回 statusCode=202 / success=false / userDailyCount=69720，桥接回包 success=true / bizError=202 / step=0 / sources=1。外层 success 不能代替业务成功；202 的业务含义尚未确认，也不是已验证的 HTTP 状态码。未改写服务端回复或业务步数。

用户报告最终为 **407 步/分**，超过要求的 80–240；截图确认记录为 0.42 公里 / **00:01:14**。这纠正了此前对同步 step=0 的解释：它不能直接说明本轮最终步频为何显示 407，不能认定最终步频必定使用该字段。SensorService 保留的跑步 collector 注册持续约 14:41:54–14:44:30（约 156 秒），也不等于页面的 74 秒。注册时长不是有效运动时长，但两者差异足以把“计步累计区间与时长区间不一致”列为重要待查条件。不能仅因 407 接近 190–200 的两倍就认定波形双峰或计步合并。

离线检查 10.7.88 普通字节码：StepHelper 使用 50 样本重力估计、10 样本投影和、阈值 10，以及严格大于 250 ms 的判步间隔；有效步骤分支提前返回，不更新 lastSum。正常时间基准下，这一路不能持续产生 407 步/分。StepDataManager.fetchStep 返回 latestCount − initStep − outStepCount；APSActivity 分别保存 APSRecord.step 与 accelStep。JSONParseUtils 分别序列化两个字段，运动 RPC 也将 step 与 accelStep 分别发送，没有在检查的这些方法中找到二者相加。运行时热补丁及小程序/服务端的最终取数公式尚未验证。

### 波形在宿主判步算法下的独立对照

旧 50 Hz Java CSV 曾在 Google 示例的原参数算法下得到约 189.51 步/分。改按本机 10.7.88 普通 StepHelper 算法、保留 float32 运算与上述提前返回行为回放，同一 CSV 仅得到约 **2.87 步/分**。因此不能沿用“Google 示例能计步”来证明宿主算法也能识别当前波形。

本次只更新探针，增加 Java type 1 的可选 `acceleration_period_us` 请求参数；默认 20000 us 保留。设置 10000 us 后实机采集约 78 秒，实际约 100.69 Hz，计步器/检测器约 195 步/分，时间戳无倒退。按宿主普通算法回放新 Java CSV 得到 **0 步/分**；Google 原参数对这批 100 Hz 数据约为 159.51 步/分。记录为 `oct07-current-100hz-java-acceleration.csv` / `oct07-current-100hz-sensor-state.json`，结束探针后已返回此前页面，没有启动新的跑步记录或更新支付宝。

当前个人模板为 v2，录制步频 95、15 个跨步、含加速度及陀螺仪。直接调用本次编译的 core-geo 实现，以固定 195 步/分、5.4 m/s、关闭随机和速度浮动生成两组波形，再使用相同宿主算法回放：

| 加速度输入 | 50 Hz | 100 Hz |
| --- | --- | --- |
| 当前个人模板 | 0 步/分 | 0 步/分 |
| 内置跑步波形 | 195.07 步/分 | 195.03 步/分 |

这些是离线输入对照，没有把目标步频输入判步算法，也没有修改宿主阈值或判步结果。它证明当前模板在所检查算法下不能稳定判步，并提供内置波形作为下一对照；尚未证明 407 的确切原因，也未证明换波形后小程序的最终成绩一定在要求范围。建议使用现有“使用录制的步态”开关暂时切换内置波形，保留录制数据。

诊断源码新增 StepDataManager.fetchStep / onSportStateChange、StepCollector.getAccelStep、RecordDataManager.saveData、JSONParseUtils.parseRecordJsonObject 的数字白名单观察，记录两路累计、基准、暂停扣除量及实际运动时长；另汇总 StepHelper.isValidStepSignal 的实际返回 true 次数，以涵盖运行时热补丁。每 30 秒汇总，运动状态变化保留即时快照；不读取账户、记录标识、坐标或任意 JSON，不改变原参数、返回值、异常或业务判断。新通用 Release 构建及 lintVital 已通过，导出 `app/global/release/app-global-universal-record-duration-diagnostics-release.apk`，本次尚未安装。

该最终导出包 SHA-256 为 `beafa7c9ec34963ecbaf328e558b305e32556da861620ed6075b9669ad1a3462`，签名仍为 Android 11 当前用户发布证书 `130c375d3184373958e3dd9440962d458e6a58a41c7a41d5e0785d692bbeff04`；版本仍为 3.0.1 / 30001。

用户随后明确要求安装。15:10:54 在 Android 11 设备 `30c5908a7d34` 上保留数据覆盖安装该诊断 Release 成功，安装标志不含 DEBUGGABLE，版本仍为 3.0.1 / 30001。随后强制停止支付宝并通过已有悦跑营入口重新打开；主进程 24137 于 15:11:50 确认加载全部五个 SportRecord 方法及 SportSignal.isValidStepSignal 观察点，多个子进程也确认加载。无需系统重启；没有安装 Play 支付宝、删除个人模板、改变步频设置或开始新的跑步记录。限时安装验证日志为 `oct07-record-duration-installed.log`。

### 内置波形的实际判步与默认频率调整

用户启用内置波形后，`oct07-default-gait-live.log` 记录宿主实际判步：约 60 秒接受 192 步、120 秒接受 383 步，硬件计步模拟也约 190 步/分。结束时 APSRecord 为 step=811、accelStep=611、sportDuration=256221 ms、sportDistance=1255.9 m；页面与用户反馈为 0.99 公里、201 秒、241 步/分。SDK 字段与页面时长、里程不一致，最终计算公式仍未确认；卡顿、暂停或统计区间差异是待查因素，不能仅由日志开始时间推出原因。页面该记录的失败文案为人脸核身未通过，不能归因于步频。同步回包 bizError=311 / step=0 的含义也未确认。

15:18:11 第一次暂停时，SensorService 记录跑步加速度 collector 注销；之后几次恢复没有该 collector 重新注册记录，accelStep 保持 611，而 step 继续增长。此现象与当前 APK 普通 StepCollector.stop 不清除 hasRegister、start 在该标志为 true 时直接返回的代码相符，但运行时字段尚未直接验证；本次没有修改宿主生命周期或业务判断。

按用户要求优先降低频率，路线步频默认从自动模式改为固定 165 步/分，并持久化步频和模式；自动模式仍可选择。波形相位与累计计步继续使用同一个 StepCounterClock，内置波形与录制波形均跟随所选步频回放。没有修改内置波形的幅度、形状或宿主算法阈值，也没有增加诊断观察点。现有正在运行的配置不会凭空改变，新的选择在下一次开始模拟时发布。

调用实际编译的 core-geo 内置波形，以 165 步/分生成 50 Hz / 100 Hz 加速度输入，关闭随机强度和速度浮动，用同一宿主普通 StepHelper 算法独立回放，分别得到 165.06 / 165.03 步/分（各评估约 60 秒）。此结果验证降低频率后仍可判步，不保证小程序最终显示恰好 165。

该调整的通用 Release 构建、lintVital、原证书校验通过，导出 `app/global/release/app-global-universal-default-cadence-165-release.apk`，SHA-256 为 `e2edb0f7bf929f972e27e8bdcc4520ba14c73cf3de18916f42062bf0000a5c0c`。15:31:41 保留数据覆盖安装成功，版本仍为 3.0.1 / 30001，安装标志不含 DEBUGGABLE；支付宝已强制停止后通过已有入口重新打开，不需系统重启。

实机新路线配置确认 is_auto_cadence=false、step_cadence_spm=165、gait_template 为空、85 个既有路线点，运动随机强度为 2。探针约 58 秒采样：counter 162.26 / detector 162.07 步/分，Java 加速度约 100.67 Hz，所测传感器时间戳均无倒退。结束探针保存原始加速度后，独立宿主算法回放约 55.26 秒评估区间得到 161.77 步/分。随机浮动下两路约 162 步/分相符；没有启动、结束或提交新的小程序跑步记录，也没有验证新的最终页面步频。

### 用户报告 334 步/分后，将默认步频降为 90

用户报告约 160 步/分时页面最终为 334，截图确认 0.35 公里 / 67 秒 / 334 步/分，记录开始时间 15:36:30。读取时仍在运行的配置是固定 165，已保存设置为 90；修改开始窗口的设置不会实时改变旧会话。环形日志仅保留 15:38:23–15:41:47，缺少该轮结束的 APSRecord / SportSignal；结束后 TriggerPoint 实际接收约 161–163 步/分。因此本轮没有直接证明最终计算公式或确定支付宝本体的责任。结合此前两路约 162 的实测，统计端差异仍是优先怀疑，不能保证单纯降频即能修复它。health daily counter 日志还出现 checkDirtyStepEvent 拒绝，不能用当前 dailyCount 不增长来否定所有计步入口。

按用户要求，将手动步频默认值及兼容配置回退统一改为 90，保留模式持久化和已有自定义设置，不修改宿主计步值、算法阈值、波形幅度或形状。README 同步说明已保存值不受新默认覆盖。内置波形固定 90、随机关闭的离线回放在 50 Hz / 100 Hz 下分别得到 90.03 / 90.02 步/分。

通用 Release 构建和 lintVital 通过，原发布证书校验通过，导出 `app/global/release/app-global-universal-default-cadence-90-release.apk`，SHA-256 为 `5d12a92bd419e83f1ed3fd11b391376f45a917993b860f53709af000e88e1747`，版本仍为 3.0.1 / 30001。更新前看到系统“支付宝没有响应”弹窗；它证明卡顿/ANR 存在，不证明其导致最终步频翻倍。

该 90 步/分 Release 于 15:45:26 保留数据覆盖安装成功，安装标志不含 DEBUGGABLE；随后读取实际发布配置已是手动 90、内置波形、原 85 点路线。支付宝已强制停止；此次没有开始或提交小程序跑步。

### 90 显示 97、跑步过程仍提示步频不达标的代码核对

用户随后报告最终 97 步/分，过程中仍有步频不达标提示，准备自行改为 110 测试。本次只读配置实际是手动 113、速度 5 m/s、随机强度 2、速度浮动 10%、内置波形；不修改或打断这轮。其共享时钟也明确保存 cadenceSpm=113，不能把它记作精确 110。

代码确认：`MotionRealism.boundedCadence` 与 `boundedSteps` 共用同一个浮动缩放比例、同一解析积分；参数在 80–240 以内。中等随机强度的独立步频浮动为 ±3%，10% 速度浮动由步频承担 40%，合计理论幅度 ±7%。正常运动时，90 的瞬时范围为 83.7–96.3，110 为 102.3–117.7，113 为 105.09–120.91。因此正常浮动本身不会把 90 降到 80 以下。该范围不保证宿主按短时间窗、启动阶段或中断回调计算出的值也在范围内。

调用实际编译 core-geo，采用当前会话种子、随机强度 2、10% 速度浮动、5 m/s，生成每组 180 秒内置加速度；共享 boundedSteps 驱动波形相位，分别在 50 Hz / 100 Hz 输入同一宿主普通 StepHelper 算法，排除最初 5 秒：

| 设定步频 | 50 Hz 判步均值 | 100 Hz 判步均值 |
| --- | --- | --- |
| 90 | 90.18 | 90.18 |
| 110 | 110.07 | 110.06 |
| 113 | 113.16 | 113.15 |
| 165 | 165.62 | 165.27 |

这组输入没有指数增长或翻倍现象，不代表小程序的最终取数公式已验证。90 的滚动 5 秒 / 10 秒软件判步区间为 84–96；110 的 5 秒为 96–120、10 秒为 102–114。结果保存在 `oct07-random-cadence-algorithm-review.json`。本轮日志没有 APSRecord / SportSignal，不能认定本次警告对应软件漏步、具体阈值或最终 97 的计算原因。

同时复查宿主普通 StepCollector.start / stop：hasRegister 为 true 时 start 直接返回，stop 注销 listener 却没有清除该标记，恢复后不重新注册是具体代码隐患，与此前暂停后 accelStep 保持 611 的观察一致；运行时热补丁及本轮是否走过该路径仍未确认。本次未改写该字段或安装兼容补丁。模块 StepCounterClock 在配置暂停/恢复时保存旧累计锚点，没有从零重新计算步数；StepEventSchedule 在进程长时间挂起后最多补发 16 个 detector 事件，counter 仍是共享累计，两路在异常中断时可能不同，但未证明本轮发生该情况。

此次没有修改生产源码、默认步频或安装包，只补充独立算法回放与研究记录；手机保持用户当前的 113 步/分测试。

### 默认 130 的日常 Release 与日志清理

用户随后报告 113 最终为 127；截图确认该轮 1.55 公里 / 317 秒 / 127 步/分、打卡成功。用户继续尝试 130 和 150，报告两者仍有实时步频提示但可以使用，接受暂不继续追查提示，明确要求默认改为 130 并编译。

默认手动步频、旧配置回退值和 README 统一改为 130，保留已保存的自定义值。日常构建使用 `-PstepPipelineDiagnostics=false`：不安装计步 SDK 观察 Hook 或 NDK 跟踪，不执行传感器注册/改写/接收的诊断统计，不输出路线移动时持续变化的配置日志；保留回调失败等故障日志。删除了传感器代码中重复描述变量或方法名称的注释，保留累计计步、队列分发、热重载兼容等必要说明。

日志清理后的既有 StepEventSchedule 三项测试通过；默认 130 的通用 Release 构建和 lintVital 通过。生成的 BuildConfig.DEBUG 与 STEP_PIPELINE_DIAGNOSTICS 均为 false。进一步检查实际 APK 字节码：运行 Hook 中没有 SDK 诊断、NDK install 或接收统计调用；持续配置日志方法只校验参数后返回。NativeSensorTrace 的 loaded 状态查询用于保留状态，未安装原生跟踪，不能将状态查询误认为跟踪启动。

导出 `app/global/release/app-global-universal-clean-130-release.apk`，SHA-256 为 `85206063cf98cc9410974698ee29cfb9a3f95b0a8141094d6a827227e740f205`，原发布证书校验通过，版本仍为 3.0.1 / 30001。本次按用户要求只修改与编译，没有安装、重启应用、改动手机音量或覆盖设备上已有步频设置；实时告警的具体原因仍未确认。

用户随后要求安装。16:41:37 在 Android 11 设备 `30c5908a7d34` 上通过保留数据的覆盖安装完成更新；设备 base.apk 的 SHA-256 与上述干净 130 包完全一致，安装标志不含 DEBUGGABLE，版本仍为 3.0.1 / 30001。支付宝已强制停止并通过已有悦跑营入口重新打开，未重启手机、清除应用数据、覆盖用户保存的步频或开始新的跑步记录。
