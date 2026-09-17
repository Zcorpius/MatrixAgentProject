# MatrixAgent 阶段 A 完整审计与可执行缺陷清单

> 初始审计：2026-09-15  
> 最后复核：2026-09-16  
> 对象：`/Users/zhangyongbin/Desktop/Learn/AI/MatrixAgentProject`

## 1. 当前结论

阶段 A **所有可在当前源码、构建环境与本地测试中修复的问题均已关闭**。项目不再是只有
协议定义的 Binder 骨架：Host 根 Binder、四域服务端 Stub、调用方验证、持久任务、统一
持久化 Gate、SDK 协商/重连、三页 Launcher，以及 Model / Download / Voice 的 Host 接入
均已有实现和回归测试。

剩余项不是“待写代码”，而是必须以 OEM/userdebug 镜像或连接的真实设备确认的验收项；它们
列在第 4 节，不能被静态检查、单测或本地 APK 打包替代。

### 本轮系统集成进度（进行中，尚未验收）

- 已确认 2026-09-16 刷入的镜像中，`MatrixAgentService` 的系统基础包位于
  `/system/priv-app/MatrixAgentService` 且为 platform 签名；但设备当前优先运行的是此前
  手动安装的 debug 更新包（`/data/app`）。当前 `MatrixLauncher` 也仍只有 `/data/app`
  安装记录，不能据此宣称双 APK 已完成系统预装。
- 本地 `vendor/matrix` 已改为把 `MatrixLauncher` 纳入 `PRODUCT_PACKAGES`，并更新 Service/
  Launcher 的 release 预装输入；Launcher 去除不需要的 `privileged` 属性，保留 platform
  签名以访问 Host 的 signature 权限。该改动必须经下一次系统构建和刷机才会出现在设备上。
- 已改为 PanoCinema 同类的直接注册模型：`com.matrix.agent` 声明
  `android.uid.system`，其 `MatrixAgentManagerService` 以
  `ServiceManager.addService("matrix_agent_service", binder)` 注册根 Binder，并由
  `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` receiver 启动、以 `START_STICKY` 恢复。此前临时
  `SystemServer` bridge 已移除，业务图不再进入 `system_server`。
- Gradle 使用同一 `grus` Lineage 构建产物的 framework compile stub 编译该 system API；APK
  已以目标 platform 证书签名。SELinux 以包名精确映射的 `matrix_agent_app` 域授予唯一的
  `matrix_agent_service` 注册权，避免向所有 `system_app`/system UID 应用开放该权限；service
  context 位于通配符前。仍需完整 Soong 构建与刷机验证，故属于第 4 节第 1 项的进行中状态。

## 2. 已关闭的审计项

| 项 | 状态 | 关闭依据 |
|---|---|---|
| A-001 | 代码关闭 | `MatrixAgentManagerService`、根 `IMatrixAgentManager.Stub`、Agent/Model/Download/Voice 四域 Stub、Manifest 显式绑定路径和受控 `ServiceRegistry` 已落地。 |
| A-002 | 代码关闭 | `matrix-agent-launcher` 已有任务、模型、下载三个 SDK-only 页面；`matrix-agent-test` 已有 trusted/untrusted instrumentation 源码。 |
| A-003 | 代码关闭 | Provisioning 仅允许服务端 provider allowlist；原始 endpoint/key 只在 Host 受控入口处理，向客户端返回不可逆 credential reference。Service 遗留 `presentation` 已删除。 |
| A-004 | 代码关闭 | v6 数据库包含任务、操作去重、事件、恢复请求；提交、冲突、取消、resume 与 executor 拒绝后的操作账本补偿均在事务语义内处理。 |
| A-005 | 代码关闭（OEM 扩展待验收） | 每次 Binder 调用构造 `CallerContext`，以调用 UID、包名、签名和 Android user 作为服务端身份；业务入口不信任客户端传入的 actor。occupant-zone 映射留给 OEM 平台接入。 |
| A-006 | 代码关闭 | `CallerVerifier`、最小绑定权限、`CallbackRegistry`、显式退订、Binder death 与 `RemoteException` 三条回收路径均已实现。 |
| A-008 | 代码关闭 | FGS 首次启动被拒或 Service 被系统销毁时都会取消本次下载，保留 DAO/.tmp 的可恢复状态，不允许裸 Host executor 隐形续跑。 |
| A-104 | 代码关闭 | SDK 以 `connectionGeneration` 和 `DeathLinkCoordinator` 串行化连接；release/失败路径成对 unlink，陈旧回调不能提升连接。 |
| A-105 | 代码关闭 | Gradle 生成并校验 contract hash；客户端协商 fail-closed，hash 或兼容范围不匹配时不会开放业务调用。 |
| A-108 | 代码关闭 | submit、下载、模型、voice status/session 均提供 Java listener/`AutoCloseable` 路径；raw AIDL overload 明确只作低层 bridge。 |
| A-109 | 代码关闭 | 四域 Stub 在入口校验 schema、UUID、枚举、语言 tag、UTF-8 上限和调用方边界，非法输入得到稳定错误。 |
| A-110 | 代码关闭 | 公开 error/event/feature 常量已冻结，`PublicAbiGoldenTest` 防止重用或改义旧值。Voice feature 只在运行时确有 VoiceRuntime 时发布。 |
| A-111 | 代码关闭 | `PersistenceGate` 是四域权威写操作唯一 fail-closed 闸门；不可用时返回 `PERSISTENCE_UNAVAILABLE`，不制造局部“假成功”。 |
| A-113 | 代码关闭 | Host 生产路径统一使用 `MatrixExecutorRegistry` 的有界预算；Audit 采用 fixed delay；下载与任务投递把拒绝转换为可恢复 `OVERLOADED`。 |
| A-114 | 代码关闭（设备交互通过） | Service 已移除 `presentation/`、MainActivity、旧 Fragment/ViewModel、布局与 UI 依赖；Launcher 三页重构为 MVVM：Fragment 仅渲染 LiveData/转发意图，页面 ViewModel 持有状态与订阅，`LauncherHostGateway` 是唯一 SDK 接入点。Mi 9 SE 已实际验证三页切换、Host 连接与任务终态回显。 |
| A-115 | 代码关闭 | Host 按 `PersistenceGraph`、`TaskGraph`、`ModelGraph`、`DownloadGraph`、`VoiceGraph` 和 `MatrixServiceGraph` 切分；Manager Service 只依赖根 graph。 |
| A-116 | 代码关闭（设备部分验收通过） | service-lib 有连接、竞态、协商、release、listener 语义单测；Mi 9 SE 上 trusted/untrusted Test APK 已实际执行：trusted 完成协商、四域 Binder 与 Voice 状态订阅/退订，untrusted 被签名权限拒绝。Launcher 端到端提交亦已验收：任务接收、运行、完成与终态快照均通过。 |
| A-201 | 代码关闭 | SDK 默认显式 bind，`ServiceDiscovery` 为可注入端口；普通 APK 不反射 hidden `ServiceManager`。 |
| A-202 | 代码关闭 | `AuditEventRecorder` 使用 `scheduleWithFixedDelay`、单 flush 锁和 shutdown drain，不补跑堆积 flush。 |
| A-203 | 代码/静态产物关闭 | SQLCipher 升至 4.6.1；重新生成的 debug APK 用 `zipalign -c -P 16 -v 4` 验证，`libsqlcipher.so`、MNN、Vosk 与全部 native entry 均为 OK。 |
| A-204 | 代码关闭 | 旧 Service UI 已删除；Launcher 不获得 Host worker，客户端执行器由 Launcher 自身管理。 |
| A-205 | 代码关闭 | 协议性大小写转换统一使用 `Locale.ROOT`，并有土耳其语 locale 回归覆盖。 |
| A-206 | 代码关闭 | Launcher 和下载 FGS 的用户可见文本均使用 string resources 与 format args；不再遗留 Service presentation 文案。 |
| A-207 | 代码关闭 | 模型和 Vosk 下载会用 `StorageManager.getAllocatableBytes/allocateBytes` 预留峰值空间，失败映射为 `INSUFFICIENT_STORAGE`。 |
| A-208 | 代码关闭 | 对必须原子化的 credential/memory epoch 写入保留 `commit()`，并标注非 UI 线程要求与失败处理。 |
| A-209 | 处置完成 | 这不是 Binder 正确性缺陷：版本 catalog 固定为经当前 compileSdk 36 / AGP 9.0.1 / Gradle 9.3.1 验证的兼容基线；SQLCipher 4.19 需要 compileSdk 37，不能为消 lint 的“有新版本”提示盲升。后续作为独立工具链升级批次重新评估。 |
| A-210 | 代码关闭 | `PublicTaskStateMapper` 是内部任务状态到冻结公开状态的唯一出口，含 null fail-closed 和全状态覆盖测试。 |

### 域能力的准确边界

- **Model**：Provider allowlist、credential reference、下载状态、模型调用和 Binder DTO 验证已经接入 Host；真实 provider 凭据和网络服务成功与否属于集成验收。
- **Download**：DAO 进度、前台服务、替换旧下载、饱和拒绝、终止/恢复语义已经接入；真实系统停止 FGS 的行为需要设备验证。
- **Voice**：Debug Host 会按需创建运行时；仅在用户发起 PTT 后切入前台、装配并打开麦克风，partial/final/state/error 都经受限 Binder 回调传递，PCM 不跨 Binder。Release/OEM 未装配 VoiceRuntime 时不会虚报 voice feature；OEM 提供运行时、签名与车机音频策略后才会发布该 feature。

## 3. 本轮验证记录

- `:matrix-agent-service:assembleDebug`、`:matrix-agent-service:assembleRelease` 成功。
- `:matrix-agent-service:testDebugUnitTest` 成功。
- `:matrix-agent-service-lib:testDebugUnitTest` 成功。
- `:matrix-agent-launcher:assembleDebug`、`:matrix-agent-launcher:lintDebug` 成功。
- `:matrix-agent-service:lintDebug` 成功；0 error。保留的 warning 是 toolchain/依赖可用新版本和 arm64-only ChromeOS 提示，不代表当前功能缺陷。
- Mi 9 SE（Android 15/API 35、4 KB page-size）已执行跨 APK instrumentation：trusted Host 冷启动、契约协商、四域 Binder 获取、任务提交入口，以及 Voice 状态读取/订阅/退订均通过（2 tests）；untrusted APK 无法协商到受签名权限保护的 Host（1 test）。测试源集已改为 AGP 识别的 `androidTestTrusted` / `androidTestUntrusted`，确保测试不再被静默排除。
- Host 的 `RECORD_AUDIO` 已在设备 Manifest/runtime-permission 状态中确认：声明存在且 Debug 安装包授予成功。Launcher 冷启动成功（约 1.0 s）且未发现崩溃；设备处于锁屏状态，未绕过系统锁屏执行页面控件交互。
- 解锁后的 Mi 9 SE 已完成 Launcher 三页交互：任务、模型、下载页均从 Host 获取受控状态；离线 `hello` 任务经过接收、运行、完成，最终快照为 sequence 3 / error 0。任务恢复改为异步 fail-closed，冷启动不再因 2 秒超时永久拒绝任务；Launcher 改为单一可关闭订阅，不再重复显示同一事件。RoomMemoryStore epoch 加载已移至 Host DB executor，最新 Host 进程未再出现主线程 Room 访问告警。
- 设备是 root-enabled `userdebug`，但当前 Debug APK 仍不会出现在 `ServiceManager`；这是正常的系统分区/平台签名/SELinux 边界，而非应用级 root 可修复项。
- `zipalign -c -P 16 -v 4 matrix-agent-service-debug.apk` 成功，APK 内 SQLCipher/MNN/Vosk native 库均已通过 16 KB 对齐静态检查。
- service-lib 的 SDK 单测覆盖连接竞态、death link 去重、release、协商 hash/版本 fail-closed；Service 测试覆盖任务持久化、输入校验、回调与下载/语音状态机。

## 4. 剩余验收项（只能在真机或 OEM/userdebug 环境完成）

1. **userdebug/OEM 系统集成（进行中）**：平台签名、`android.uid.system`、包名专属
   `matrix_agent_app` SELinux 域、`ServiceManager.addService` 直接发布、开机/更新后启动及
   预装定义均已落地。仍需完整 Soong 构建、刷机后验证 `service list`、进程标签、系统服务
   重启后的重新发布、拒绝未授权调用方与 MatrixLauncher 显式绑定；设备已确认普通 Debug APK
   不会获得该能力，不能依赖运行时 root。
2. **跨 APK instrumentation（部分已通过）**：Mi 9 SE 已通过 trusted 成功、Voice 状态回调，以及 untrusted 拒绝；仍需覆盖 Binder client 进程死亡清理、自动重连与 callback 回收。
3. **多 Android user 与 occupant-zone**：至少两个 Android user/zone 上验证任务、memory、audit 相互不可见；zone 的权威映射由目标 OEM 车辆平台提供。
4. **FGS/WorkManager 系统策略**：Android 15+ 实际拒绝/停止 dataSync FGS 时确认通知、DAO 可恢复状态和无后台续跑；当前代码已按合规路径处理。
5. **16 KB 设备运行**：在实际 16 KB page-size 设备启动 SQLCipher、MNN、Vosk 与首次模型加载。APK 对齐只是必要的静态门禁，不等价于运行验证。
6. **Voice 真实外设链路**：Mi 9 SE 已确认 `RECORD_AUDIO` runtime permission 与受限 Binder 状态回调；仍需验证实际麦克风采集、Vosk 资源、AudioRecord/TTS、焦点/后台切换及 OEM 车机音频策略。Release 还需要 OEM 提供 VoiceRuntime 装配与签名策略。
7. **真实凭据与网络**：用受控测试 provider 完成 provisioning、catalog 签名、断网/低空间/断点续传和模型实际调用的端到端验收；不得用生产 token 写入测试记录。

## 5. 关闭标准

完成第 4 节的设备/OEM 验收并留存日志、测试报告和目标镜像版本后，阶段 A 才可以从“代码关闭”升级为“发布验收关闭”。在此之前，不应把静态构建结果误写为通过了平台签名、SELinux、真实 Binder 进程死亡或硬件音频验证。
