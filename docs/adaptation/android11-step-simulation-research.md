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
