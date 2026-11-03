# 版本与发布规范 — V2.1

本文件是版本号、正式构建、签名、Tag、Release 和重试的权威来源。用户权限和启用条件见 [仓库管理规范](REPOSITORY_WORKFLOW.md)。工作流由 develop 提交，获准合入 master 后生效；签名和正式发布端到端验收须另外核实。

## 发布边界

仅在用户确认本仓库 develop → master 的 PR 已合并后，以该次合并结果的完整 SHA 为发布来源；流水线固定检出该 SHA，不能读取运行期间变化的最新 HEAD。验证和正式打包成功后，由 CI 自动创建 Tag 与同名 Release，不再要求用户逐次指定 Tag。旧版“本地禁止常规编译”和“每次打 Tag 单独确认”的流程由 V2.0 目标流程替代，其他操作的授权边界仍按仓库管理规范执行。

每次获准的 master 合并应是一批完整发行变更，含版本准备；文档改动可在 develop 累积，不为每个小改动单独合入 master。未准备新版本的 master 合并不得静默发布或由 CI 猜测版本，必须报告版本冲突。

## 版本的唯一来源

- EhReader 当前版本来源为 app/build.gradle.kts 中的 versionName 与 versionCode。后续若抽成单独配置，应只保留一个来源并同步脚本。
- 正式版 versionName 使用三段数字，例如 0.1.1；Tag 固定为 v 加 versionName，例如 v0.1.1。本规范不定义预发布版自动发布，若需要须先确定预发布命名和排序规则。
- versionCode 为正整数，新发行必须严格大于本项目历史已公开发行的最大值；同一个版本的失败重试不增加 versionCode。比较不能混入上游旧应用的版本序列。
- 版本更新先作为 develop 提交审查，用户合并确认包含该版本。CI 从提交配置派生 Tag，不修改源码、不自动增加数字、不因重跑生成新版本。
- 更换 Tag 名称不会改变提交中的 APK 版本。不能把同一版本提交反复标为不同正式发行；版本缺失或冲突须在合并前解决。
- Tag 格式借鉴上游 v* 命名；上游工作流及 Aviewer 没有提供可直接沿用的“合并后自动递增版本”算法。

## CI/CD 必须执行的阶段

1. 核对仓库、pull_request closed + merged=true 事件、head=develop、base=master 和来源仓库，固定实际合并 SHA；验证版本格式、递增关系及 Tag 是否已被使用。
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
- 创建 Tag 后上传失败可暂时只有草稿或待完成状态；一个 Tag 最终对应一个 Release，不为满足数量要求公开失败产物。

## 失败恢复

- 网络、下载、上传等临时故障：按同一来源 SHA、版本和流水线重试，恢复已有草稿，不新建版本。
- 源码、工作流或版本配置需要修复：从 develop 产生新提交，重新验证并由用户确认合入 master。若原 Tag 已存在则不得把它移动到修复提交，应准备新的合法版本；若尚未创建 Tag 且版本未公开，可在审查后沿用未发布版本。
- 既有公开 Tag/Release 不因新规范而删除、覆盖或补发。废弃草稿、删除标签或回滚公开版本须单独明确授权。

## 参考与差异

参考 [Aviewer 发布工作流](https://github.com/haorengg12/Aviewer/blob/84c1715cbe6a87c78768f9b8da4c6808e1f006d2/.github/workflows/release.yml) 的分阶段构建、附件命名与汇总发布。EhReader 不采用其调试签名回退和允许必需附件失败后继续的方式。Flutter 命令及版本配置不能直接移植到本项目。

## 实现与签名配置

- `.github/workflows/ci.yml`：develop push、目标 develop/master 的 PR 验证，上传 Debug 与检查报告，只读权限。
- `.github/workflows/releases.yml`：仅已合并 PR 事件，核对同仓库 develop 来源、master 目标；固定 merge_commit_sha。直接 push master、手动触发、push Tag 不发布。GitHub 的已合并 PR 事件用于区分真正合并与普通推送。
- `scripts/release.mjs`：版本/事件门禁、包校验、源码附件、Release 草稿和远端附件散列核验。`scripts/release.test.mjs` 验证误触发和版本冲突边界。
- 正式 CI 所需 repository Secrets：EHREADER_KEYSTORE_BASE64、EHREADER_STORE_PASSWORD、EHREADER_KEY_ALIAS、EHREADER_KEY_PASSWORD。私钥使用 v0.1.0 的同一 EhReader 密钥，不能重新生成另一把；证书 SHA-256 固定在发布脚本。仅在获准时将密钥配置到 GitHub，禁止写入 Git 或输出密码。
- 工作流临时解码 keystore 到 runner.temp，权限 600，构建结束删除；不缓存正式构建，凭据只传给签名构建步骤。
- 失败后重跑原合并事件；不提供绕过合并条件的手动发布入口。已公开版本不覆盖；同提交草稿可补全，标签冲突失败。
- 首次迁移当前 0.1.0 (100) 已存在，合并前必须在 develop 准备新版本，不能靠修改 Tag 名字规避版本冲突。

## Release 页面

参考 Aviewer v0.2.7 的版本标题、下载徽章和架构下载表。EhReader 仅列 Android arm64-v8a、armeabi-v7a、x86_64 和 universal，不展示本项目没有的 iOS/Windows 包。

页面包含更新内容/PR、版本与 versionCode、Tag、完整来源 SHA、CI 链接、SHA256SUMS、验证说明、GPL 和对应源码下载。设备测试未执行要明确标记；NO-SOURCE 不写作“全部测试通过”。必需附件全部上传并核对远端大小和 SHA-256 后才公开。

参考：[GitHub 合并事件](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows)、[并发队列](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency)、[Aviewer Release 页面](https://github.com/haorengg12/Aviewer/releases/tag/v0.2.7)。
