# 版本与发布规范 — V2.3

本文件是版本号、正式构建、签名、Tag、Release 和重试的权威来源。用户权限和启用条件见 [仓库管理规范](REPOSITORY_WORKFLOW.md)。工作流由 develop 提交，获准合入 master 后生效；签名和正式发布端到端验收须另外核实。

## 按版本位置分流（目标规则）

2026-11-05 起，版本按 `一.二.三[.四]` 分流。三段版本用于 develop 开发构建或 master 正式发行，第四段仅用于远端临时保存；这是一套项目自定义规则。版本变化决定流程，推送 Tag 本身不触发构建或发布。

| 变化 | 示例 | 推送与后续动作 |
| --- | --- | --- |
| 仅第三位提高，第一、二位不变 | `0.1.2 → 0.1.3` | 只推送 origin/develop；运行开发 CI，检查并生成 Debug 产物；成功后在本次完整 SHA 创建 `v0.1.3` 开发 Tag；不创建 Release，不进入 master 合并流程 |
| 第一或第二位提高 | `0.1.2 → 1.0.0` / `0.2.0` | 先推送 origin/develop，跳过该 push 的开发构建；准备同仓库 develop → master PR，经只读预检和用户对具体提交及版本的确认后合并；合并事件固定 SHA 执行完整 CI/CD，成功后创建正式 Tag 和 Release |
| 仅第四位提高，前三位不变 | `1.0.1 → 1.0.1.12` | 从明确源 SHA 创建并推送对应 brody 分支，执行基础验证；不打包、不创建 Tag/Release、不自动合并 |
| 版本不变 | `0.1.3 → 0.1.3` | 获准后可推送 develop 保存；普通 push 不启动开发构建、不创建 Tag，也不自动进入正式发行 |

### 基础验证与打包分离（P1-13）

基础验证不以升版为前提：develop/brody push、目标 develop/master 的 PR 执行 `Basic quality`，包含文本编码、仓库文档检查、Node 测试和事件/版本门禁，不安装 Android 工具链、不构建 APK。所有 PR 均不运行重复 Debug/Release 打包；目标 master 的 PR 另须通过只读 `Release readiness`。这里的“不构建/不发行”不再表示“不运行基础验证”。

只有 develop push 的第三位升版运行 `Development Debug`，APK 和必要检查成功后，独立最小写权限任务创建开发 Tag；不创建 Release、不传入正式签名 Secrets。手动入口默认只做基础验证；`repair_debug=true` 仅允许 develop，并必须填写原始升版前的 `base_sha`，固定事件 SHA。已有 Tag 指向不同 SHA 时停止，须准备新版本。

brody 分支名必须与四段版本完全一致。push 按事件 before/after SHA 比较；首次建分支 before 为零或缺少可信基准时门禁失败，不能猜测，可以用明确源 SHA 的手动基础验证继续；该路径即使手动也不能打包/发行。Tag push 不触发这些工作流。正式发行仍只接受本仓库 develop → master 的已合并 PR 事件。

### 比较基准与合法性

- `origin/tag` 不是单一版本引用。分流使用推送前、后提交中唯一版本配置：develop 路径固定推送前 origin/develop 的完整 SHA 与本次提交 SHA；临时分支路径固定选定源 SHA，已有同名远端分支时先核对目标，不能覆盖保存。远端竞争须重新取基准、核对并确认，不能强推。首次建分支或无法取得基准时停止并明确基准，不猜测。
- 数字段按整数逐位比较，三段在比较时补第四位 `0`，例如 `1.0.1` 对应 `1.0.1.0`；源码和分支名仍使用实际三段或四段文本。数字不得有前导零，不按字符串或 Tag 创建时间排序。
- 从左侧首个不同位置判断升版；第一位提高时后续位清零，第二位提高时第三位清零；第三位提高时使用三段版本。四段版本只能在前三位不变时提高第四位，第四位须为正整数，不能在同一次变更中与前三位一起升高。第四位首次从省略的 `0` 开始，可跳号，例如 `.12`。
- 2026-11-07 用户明确指定单次版本对齐：`0.1.2 (102) → 1.8.15 (10815)`，保留所指定的后两位并走正式发行准备路径。脚本例外仅匹配来源 `0.1.2/102` 与目标名 `1.8.15`，安装编号仍须严格递增；其他来源/目标仍执行上述归零规则。`10815` 以 `1 | 08 | 15` 对应本次版本，后续编号继续单独核对，不建立自动换算规则。
- 版本回退、只改 versionCode、格式不合法或混合四段升版均不能当作新版本触发。仅第三位变化不因将来可能发行而提前合并 master；以后要正式发行，另行确认第一或第二位升版。
- 开发 CI 是否启动依据版本变化，不能依赖最新正式 Tag：开发版本可能连续递增且尚无正式 Release。开发 Tag 在成功后才创建，所以失败时也不能把最新 Tag 当成上次推送版本。
- 正式发行候选还须与本仓库已公开正式 Release 的最高数字版本比较，第一或第二位必须提高；所有现存 Tag 用于检查名称占用，不能把开发 Tag 当正式发行历史。历史三段补丁正式版保留，不追溯改变其性质。
- 第四位分支不作为后续 develop 的隐式版本基准；如需集成其中代码，单独确认集成范围并按目标 develop 新版本重新检查。不得直接将 brody 分支合入 master 发行；临时分支不自动删除。

### 开发 Tag、失败与重复事件

- 开发 Tag 与正式 Tag 均用 `v` 加三段 versionName；开发 Tag 没有 Release。正式版本的身份还须核对已公开 Release、对应 Tag/SHA 及 develop → master 合并来源，不以 Tag 名字单独推断。
- 开发流水线只在所有必要检查和 Debug 产物校验成功后创建指向本次源码完整 SHA 的 Tag；需要最小化的 Tag 写权限，不能因此授予开发构建正式签名凭据或发布权限。
- 同名 Tag 指向相同 SHA 时核验后幂等结束；指向其他 SHA 时失败，不移动、不删除、不覆盖。普通同版本后续 push 不重复构建或创建 Tag。
- 失败重跑固定原 SHA、版本和比较基准；源码修复产生新 SHA 后，未用版本可通过明确的修复验证入口重新检查，不能因“版本未变”静默跳过所需验证。若开发 Tag 已存在，修复代码必须准备新的第三位版本。正式发布失败恢复仍按下文执行。
- 第一、二位升版跳过 develop push 的构建，也不运行重复的 master PR Debug/Release 构建；合并前仍保留来源、固定候选 SHA、版本、Tag 占用及签名配置的只读预检。正式构建、测试、lint、签名及包校验集中在合并后执行。合并后失败则停止发行并报告，不把合并成功当作发布成功。

### 尚待实现的适配

本地已实现基础验证、版本分流、开发 Tag 幂等/冲突门禁、brody 隔离及固定基准手动修复，移除合并前重复 Release 构建；三/四段解析在 `scripts/version-policy.mjs`，CI 事件/开发 Tag 在 `scripts/ci.mjs`，正式发行门禁沿用 `scripts/release.mjs`。本地实现不等于远端部署或端到端验收。

2026-11-07 只读预检：远端 master 仍要求 `build`、`Release readiness`、`Release build`，strict/enforce_admins 为 true；最高公开版本为 v0.1.2。待获准推送并验证新检查后，拟仅将 required_status_checks 调整为 GitHub Actions 的 `Basic quality` 和 `Release readiness`，保留严格同步、管理员约束及其他保护，不绕过当前门禁。正式工作流仍需获准合入 master 后生效。

工作流 YAML/依赖图和本地门禁测试可验证代码边界，但各版本路径的 GitHub 实际运行、开发 Tag 及正式发行仍须分别留证。此前 actionlint 下载受审批额度阻碍；本次工具已取得，1.7.12 原始输出只报两处不识别 `queue: max`。该字段见 [GitHub 官方并发文档](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency)，保留排队行为并单独记录工具兼容性差异；仅排除这两项后核验其他规则，不将本地静态结果视为 GitHub 实机验收。

## 发布边界

仅允许已获用户对具体提交和版本确认的本仓库 develop → master PR 合并事件触发正式发行，并须满足第一或第二位升版条件。以该次合并结果的完整 SHA 为发布来源；流水线固定检出该 SHA，不能读取运行期间变化的最新 HEAD。验证和正式打包成功后，由 CI 自动创建 Tag 与同名 Release，不再要求用户逐次指定 Tag。第三位开发 Tag 不属于正式发行；其他操作的授权边界仍按仓库管理规范执行。

每次获准的 master 合并应是一批完整发行变更，含版本准备；文档改动可在 develop 累积，不为每个小改动单独合入 master。未准备新版本的 master 合并不得静默发布或由 CI 猜测版本，必须报告版本冲突。

## 版本的唯一来源

- EhReader 当前版本来源为 app/build.gradle.kts 中的 versionName 与 versionCode。后续若抽成单独配置，应只保留一个来源并同步脚本。
- 开发构建和正式版 versionName 使用三段数字，Tag 固定为 v 加 versionName，例如 v0.1.3。四段版本仅用于 brody 临时保存，不属于正式版或自动预发布 Release。
- versionCode 为正整数，独立于版本段数，不从第四位拼接或猜测。新发行必须严格大于本项目历史已公开发行的最大值；新开发安装包也须高于已分发的同 applicationId 开发包，避免无法升级。只保存的四段版本不要求生成新安装包编号；同一版本、同一 SHA 的失败重试不增加 versionCode。比较不能混入上游旧应用的版本序列。
- 是否需要版本问答按[第 2 阶段默认策略](DEVELOPMENT_FLOW.md#版本准备第-2-阶段)处理：普通改动及必要编译不自动升版，交付测试包但未说明版本时先确认；发行或升版目标不能猜测。需要更新时先确认具体版本并修改配置，第 3 阶段检查最终状态，第 4 阶段用[最终审核单](DEVELOPMENT_FLOW.md#最终审核单)同时处理测试安排和提交推送权限，复用已有明确答复，不设置升版后的第二轮常规测试确认；第 5 阶段才提交。正式发行仍单独确认具体合并及版本。CI 从提交配置派生 Tag，不修改源码、不自动增加数字、不因重跑生成新版本。
- 目标 master 的 PR 必须通过独立 `Release readiness` 只读检查：仅接受同仓库 develop，固定 PR head SHA，检查第一或第二位升版、未使用的 Tag、严格递增的版本和四项签名 Secrets 是否齐备。不创建 Tag/Release，不执行 Debug/Release 构建，也不代替合并后的正式门禁。
- 完整格式/测试/lint/构建和实际 ABI 源码审计在合并后对固定 SHA 执行；此目标取代原合并前必需 `Release build`，实际迁移边界见上文。
- 更换 Tag 名称不会改变提交中的 APK 版本。不能把同一版本提交反复标为不同正式发行；版本缺失或冲突须在合并前解决。
- Tag 格式借鉴上游 v* 命名；上游工作流及 Aviewer 没有提供可直接沿用的“合并后自动递增版本”算法。

## CI/CD 必须执行的阶段

1. 核对仓库、pull_request closed + merged=true 事件、head=develop、base=master 和来源仓库，固定实际合并 SHA；验证三段版本格式、第一或第二位升版、递增关系及 Tag 是否已被使用。第三位开发版本和四段保存版本不得正式发行。
2. 对该提交执行格式检查、Release lint、适用测试和正式构建。必须显式运行 lintRelease；assembleRelease 成功不等于 lint 成功。
3. 使用受保护的正式签名凭据；与 v0.1.0 相同的 EhReader 独立签名证书保持更新兼容。凭据缺失、证书不符时失败，不回退到 debug 签名，不把 unsigned 当正式包。私钥及密码不进入仓库、日志、缓存或附件。
4. 验证包名 com.haorengg12.ehreader、版本、ABI 清单、签名、对齐及来源 SHA。产物必须来自本次固定 SHA 的 CI 构建，不混入本地文件或其他运行附件。
5. 构建和验证通过后创建指向该 SHA 的 Tag 与同名 Release 草稿，上传必需附件并校验大小、SHA-256 和清单；齐全后才公开。不得在附件不完整时宣布发布成功。
6. 发布记录包括版本、Tag、SHA、构建运行链接、检查结果、警告及未做的验证。遵守 GPLv3，保留原版权、许可证、第三方声明并提供对应源码和构建说明。

必需附件：所支持 ABI 的正式 APK（当前 arm64-v8a、armeabi-v7a、x86_64 及 universal）、对应源码、LICENSE/NOTICE、校验清单、验证说明。映射和原生调试符号应作为可追溯构建资料妥善保存；公开证书可随包提供，私钥禁止提供。

## 自动化与并发

- 使用一条 master 流水线完成构建、创建 Tag、上传、发布，不依赖“该流水线新建 Tag 再触发另一条流水线”才能继续发布。
- 同一版本使用互斥或等效并发控制；已公开版本不得覆盖。工作流设置 queue: max 和 cancel-in-progress: false 保留等待运行；不依赖排队顺序保证正确，运行时重新检查已发布最大版本，较旧版本拒绝发布。
- 重跑时先核对现有 Tag 目标和 Release 状态。相同 SHA 的未完成草稿可恢复；已完整公开则验证后结束；同名 Tag 指向其他 SHA 时直接失败，不强行移动。
- 创建正式 Tag 后上传失败可暂时只有草稿或待完成状态；一个正式 Tag 最终对应一个 Release，不为满足数量要求公开失败产物。第三位开发 Tag 不创建 Release。

## 失败恢复

- 网络、下载、上传等临时故障：按同一来源 SHA、版本和流水线重试，恢复已有草稿，不新建版本。
- 源码、工作流或版本配置需要修复：从 develop 产生新提交，重新验证并由用户确认合入 master。若原 Tag 已存在则不得把它移动到修复提交，应准备新的合法版本；若尚未创建 Tag 且版本未公开，可在审查后沿用未发布版本。
- 既有公开 Tag/Release 不因新规范而删除、覆盖或补发。废弃草稿、删除标签或回滚公开版本须单独明确授权。

## 参考与差异

参考 [Aviewer 发布工作流](https://github.com/haorengg12/Aviewer/blob/84c1715cbe6a87c78768f9b8da4c6808e1f006d2/.github/workflows/release.yml) 的分阶段构建、附件命名与汇总发布。EhReader 不采用其调试签名回退和允许必需附件失败后继续的方式。Flutter 命令及版本配置不能直接移植到本项目。

## 现有实现与签名配置

以下描述当前代码，版本分流目标与待改事项见上文；不得把现有触发行为当作新规范已实现。

- `.github/workflows/ci.yml`：基础验证与开发构建分成独立任务；仅第三位升版或明确的 develop 修复运行 Debug，成功后独立最小写权限任务创建开发 Tag，其余任务只读。
- `.github/workflows/release-readiness.yml`：仅目标 master 的 PR 运行 `Release readiness`，在合并前报告版本复用和签名配置缺失。独立工作流避免 develop push 上的 skipped 同名检查干扰必需检查结果。
- `.github/workflows/releases.yml`：仅已合并 PR 事件，核对同仓库 develop 来源、master 目标；固定 merge_commit_sha。直接 push master、手动触发、push Tag 不发布。GitHub 的已合并 PR 事件用于区分真正合并与普通推送。
- `scripts/release.mjs`：合并前只读 readiness、合并后版本/事件门禁、包校验、源码附件、Release 草稿和远端附件散列核验。`scripts/release.test.mjs` 验证误触发和版本冲突边界。`scripts/native-sources.mjs` 从每个 ABI 的 CMakeCache 找到隔离的 Nettle 源码，比较实际源码散列后打包，禁止回退到共享旧目录。
- 正式 CI 所需 repository Secrets：EHREADER_KEYSTORE_BASE64、EHREADER_STORE_PASSWORD、EHREADER_KEY_ALIAS、EHREADER_KEY_PASSWORD。私钥使用 v0.1.0 的同一 EhReader 密钥，不能重新生成另一把；证书 SHA-256 固定在发布脚本。仅在获准时将密钥配置到 GitHub，禁止写入 Git 或输出密码。
- 工作流临时解码 keystore 到 runner.temp，权限 600，构建结束删除；不缓存正式构建，凭据只传给签名构建步骤。
- 失败后重跑原合并事件；不提供绕过合并条件的手动发布入口。已公开版本不覆盖；同提交草稿可补全，标签冲突失败。
- 既有 0.1.0 (100)、0.1.1 (101) 不得复用；版本历史始终从远端公开发行读取，不能靠修改 Tag 名字规避版本冲突。

## Release 页面

参考 Aviewer v0.2.7 的版本标题、下载徽章和架构下载表。EhReader 仅列 Android arm64-v8a、armeabi-v7a、x86_64 和 universal，不展示本项目没有的 iOS/Windows 包。

页面包含更新内容/PR、版本与 versionCode、Tag、完整来源 SHA、CI 链接、SHA256SUMS、验证说明、GPL 和对应源码下载。设备测试未执行要明确标记；NO-SOURCE 不写作“全部测试通过”。必需附件全部上传并核对远端大小和 SHA-256 后才公开。

页面模板位于 EhReader 仓库的 [`.github/RELEASE_TEMPLATE.md`](../../.github/RELEASE_TEMPLATE.md)，由 `scripts/release.mjs` 从固定合并提交读取并渲染。更新内容自动采用合并时的 PR 标题和正文；正文为空时提供 PR 与提交历史入口。完整变更链接从上一已公开版本指向本次 Tag，首次发行则链接固定 SHA 的提交历史。恢复草稿时重新应用同提交模板和标题，不修改已公开 Release。

模板可用占位符：`repository`、`tag`、`version`、`version_code`、`download_base`、`sha`、`run_url`、`changes`、`history`，均使用 `{{名称}}`。预检会验证占位符；未知名称失败停止。PR 正文中的同名文本不会被二次替换。

参考：[GitHub 合并事件](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows)、[并发队列](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency)、[Aviewer Release 页面](https://github.com/haorengg12/Aviewer/releases/tag/v0.2.7)。
