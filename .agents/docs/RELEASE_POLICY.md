# 版本与发布规范 — V3.1

本文件是版本号、构建分流、签名、Tag 和 Release 的权威来源。2026-11-14 按用户确认更新；授权及原生 Auto-merge 边界见[仓库管理](REPOSITORY_WORKFLOW.md)。本地实现与远端部署分别记录。

## 按版本位置分流（目标规则）

本次发行例外（2026-11-22 用户批准）：从 1.8.15（10815）升级至 1.9.0（10823）走正式发行流程。develop 构建、master PR 门禁、正式签名与附件校验均按第一位路径执行，其他版本继续按下表分流。

逻辑版本为“一.二.三.四”，省略第四位等同 0，显示及远端源码只使用三段。第四位为正数时仅用于本地 Debug。比较数字，优先级一 > 二 > 三 > 四；取从左侧首个不同位，该位必须提高，右侧可保留、提高或降低，只执行最高优先级路径。例如 1.8.15.2 → 2.9.16.3 只执行第一位逻辑，但上传前仍须准备合法三段版本。禁止前导零、负数、版本回退或只改安装编号。

| 最高变化位 | 上传目标 | 后续动作 |
| --- | --- | --- |
| 第一位 | develop | 确认版本且明确要求上传后，助手核对授权和固定 SHA，创建同仓库 develop → master PR 并开启原生 Auto-merge（Merge commit）；GitHub 等待同 SHA develop 构建及全部必需检查通过后合并，随后固定 merge SHA 正式签名、Tag 和 Release；无需二次合并确认 |
| 第二位 | develop | 基础验证及完整架构 Debug 构建；只汇报流水线结果和附件，不创建 PR、Tag 或 Release，不合并 master |
| 第三位 | develop-六位UUID | 只保存源码到新的临时分支，例如 develop-a1b2c3；不运行任何远端 CI，不创建 PR、Tag/Release，不自动合并或删除分支 |
| 第四位 | 不上传 | 在本地 develop 保存，按要求生成 ARM64 Debug；不触发远端 CI，不创建 Tag/Release |
| 不升版 | 获准后 develop | 三段版本可仅保存并运行基础验证；四段版本始终仅本地；不创建 Tag/Release，不自动合并 |

### 基础验证与打包分离（P1-13）

仅 develop push、目标 develop/master 的 PR 和明确的 develop 手动入口运行 Basic quality，包含编码、文档、Node 测试和事件/版本门禁。第一、二位 develop push 运行 Development Debug：Spotless、适用测试、lintDebug、完整架构打包、APK 和原生源码验证。PR 不重复构建；master PR 的 Release readiness 同时执行版本/签名只读预检并等待同 SHA develop 构建成功。正式包始终在合并后重建，不复用 Debug 包。

临时 develop-六位UUID 分支不匹配 push 触发器；手动 CI 也只允许 develop。旧 brody 分支仅保留 Git 历史，不再配置 CI 兼容入口，不迁移或删除。Tag push 不触发构建。手动 repair_debug=true 只修复第一、二位构建，必须提供原始升版前 base_sha；手动入口不自动创建 PR 或发行。

### 比较基准与合法性

- 上传分流固定本次上传前最新 origin/develop 完整 SHA 和已审阅的本地提交 SHA；develop push 复核事件 before/after 及祖先关系。临时分支也使用该 origin/develop 基准，并将计划保存到工作区 temp。最新 Tag、最新 Release 和本地上一提交均不能替代上传基准。
- 本地连续测试另与上一个本地版本比较；安装编号不得回退。远端已有旧四段版本可作为历史比较基准，但新的远端源码必须三段。
- 第四位在上传前归零并省略，不能推送后才修改本地文件。须明确提升前三位中的适用部分，例如 1.8.15.2 → 1.8.16；仅删成 1.8.15 是降版，拒绝上传。若本地同时改变多位，可本地保留第四位，但上传前仍须准备合法且递增的三段版本。
- 使用 node scripts/upload-plan.mjs --base 完整SHA 生成只读计划，包含基准、源码 SHA、版本、安装编号和目标分支；入口不授予或执行推送。先 fetch 再复核计划，推送前再次核对远端未变化。第三位分支后缀为 UUID 去连字符后的前六个小写十六进制字符；撞名则重新生成，不能覆盖旧分支。临时分支未合入 develop，下一次仍与最新远端 develop 比较，不改变规则基准。
- 第一位采用 GitHub 原生 Auto-merge：助手用现有用户身份创建 PR 并开启 Merge commit 自动合并；不往仓库存放新增 App/PAT，不使用工作流 GITHUB_TOKEN 创建或合并 PR。固定已审阅源 SHA、版本和上传范围，开启时核对 head；原生合并等待门禁，可在助手离线后继续。追加提交后重新审阅授权及版本，不能把开启时的 SHA 匹配当作后续永久锁定。
- 正式版本还须第一位高于最高公开正式 Release，versionName 与 versionCode 均严格递增。PR 预检相对固定 master 基准也必须是第一位升版；合并后复核实际 merge SHA 的第一父提交。历史补丁正式版和开发 Tag 保持原状，不追溯改名或删除。

### 开发 Tag、失败与重复事件

2026-11-14 起停止新建开发 Tag。第二、三、四位及不升版均不能创建 Tag；现存 Tag 不移动、不覆盖。CI 仅为第一位正式发行创建 v 加三段版本号。

同一 SHA 的临时失败可按现有恢复规则重跑；源码修复产生新 SHA 后须重新验证，并明确原始升版基准，不能因同版本而静默跳过构建。修复后的自动合并必须覆盖重新核验的最终内容和权限。正式 Tag/Release 恢复规则见下文。

### 部署与验收状态

2026-11-14 现行本地版本为 1.8.16 / 10818，第三位快照已推送 develop-25f826，源 SHA dde7a14ff8b3e241e5c23a28260404b3f65f6b40；远端 develop 未变化，对该 SHA 查询到 0 个 Actions 运行，未创建 PR/Tag/Release。证据见工作区 [快照验证](../../../temp/2026-11-14-upload-1.8.16/remote-verification.json)。此前 1.8.15.2 / 10817 是历史本地测试版，不再是当前配置。

远端只读核查：Allow auto-merge、Merge commit 已开启；master 必需检查已为 GitHub Actions 的 Basic quality 和 Release readiness，strict/enforce_admins 均为 true，必需 Review 人数为 0。旧 build / Release build 迁移待办已完成，不再写成尚待迁移。保护现状仍须在每次部署/开启自动合并前核实。

本次为 Release readiness 增加同 SHA 构建证明门禁，本地实现不代表远端已部署。本次仅本地提交，不推送、不创建发行 PR；此新门禁尚待获准上传后的实机验证。正式发布流程须按第一位版本路径合入 master 生效；Auto-merge 开关已开启不等于脚本已部署或正式发布验收通过。

### 原生 Auto-merge 与构建证明

- 助手确认本次第一位上传授权、最终版本和全部待上传内容后创建 PR，可在 develop 构建等待期间开启 Auto-merge。使用当前用户或已有本地账号认证，例如 gh pr merge PR编号 --repo haorengg12/EhReader --auto --merge --match-head-commit 完整SHA；不使用 --admin，不新增仓库凭据。开启前核对 PR 状态、同仓库 develop → master、完整 head SHA、Merge commit 许可及实际保护。
- 原生 Auto-merge 只等待服务端必需检查，不会自动创建 PR、自动计算版本数字，也不替助手审阅变更。只开启仓库开关不会让每个 PR 自动合并；每个符合第一位路径的 PR 都要由助手或用户开启。
- 已有必需检查 Release readiness 保持原名，仅增加 actions:read 与 pull-requests:read 查询权限；版本、签名、Tag 预检成功后，scripts/development-build-gate.mjs 最多等待 30 分钟。只查询本仓库 ci.yml、develop、精确 PR head SHA 的最新 push 或 workflow_dispatch 运行，并查询该运行最新 attempt 的 jobs；整体运行、Basic quality 和 Development Debug 必须均真实成功，不能用 PR 构建、其他 SHA 或 skipped 结果代替。
- 没有运行或排队/构建未完成时保持检查进行中；最新运行失败、取消或跳过打包时失败，不回退旧成功。超时仍阻止合并，原构建成功后可重跑原 PR 的 readiness；同 SHA 手动修复必须满足原始 base_sha 门禁且真实运行 Debug。只重跑失败 jobs 若导致最新 attempt 缺少必需任务，须重跑全部 jobs，不能拿旧 attempt 拼装通过。读取证据后复查 run attempt、最新运行及 PR head/base，变化则重新等待或失败。
- PR 检查按 PR 编号互斥并取消旧运行；PR 追加提交后使用新 SHA 重新检查。master 更新且严格同步要求阻止合并时，先同步分支、重验范围和新 SHA；若普通同版本 push 跳过 Debug，使用明确原始基准的手动修复入口重建该新 SHA，不复用旧 SHA 构建。
- 原生 Auto-merge 会跟随 PR 新提交；--match-head-commit 只约束开启操作时的 head，不是永久冻结。Auto-merge 开启后不继续追加未经核验内容；需修改范围/版本或撤回授权时先关闭该 PR 的 Auto-merge，再修改并重新核验后开启。来源不符、失败、冲突或竞争时不绕过门禁。
- GitHub 接管等待、合并及合并后的发行；助手结束任务时如实报告等待状态和 PR/流水线链接，不承诺本地后台监控。官方规则见 [Auto-merge](https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/automatically-merging-a-pull-request)、[gh pr merge](https://cli.github.com/manual/gh_pr_merge) 与 [工作流触发限制](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow)。

### 发布边界

只有同仓库 develop → master 的已合并 PR 事件可正式发行。第一位已确认并明确要求上传时，依据用户授权由助手创建 PR 并开启原生 Auto-merge，由 GitHub 在全部门禁满足后以 Merge commit 合并，不再单独索要具体合并批准；第二、三、四位不会自动进入该路径。正式发布仍固定实际 merge SHA，不接受直接 push master、Tag push 或手动发布。

## 版本的唯一来源

- app/build.gradle.kts 的 versionName 和 versionCode 是唯一来源。逻辑四段、远端三段，本地第四位为正数，0 省略；例如 1.8.15.1、1.8.15.2 为本地测试版，1.8.16、2.1.0 为三段远端候选。
- versionCode 是独立正整数，不拼接版本数字、不因去掉第四位重置，范围 1～2100000000。每次新安装版本须高于此前已分发及本地测试包编号；同一版本同一 SHA 失败重跑不递增。当前 10818 保留，下一新版本通常至少 10819，并核对实际历史；CI 不自动猜测或写回编号。
- 普通维护不自动升版。需要测试包或上传但版本未明确时只问缺少的信息。最终版本确认、测试安排和上传授权沿用[开发流程](DEVELOPMENT_FLOW.md)；不能从沉默、构建成功或普通本地提交推断发布许可。
- Release readiness 仅接受同仓库 develop → master 的精确 PR head，核对三段版本、第一位提升、未用 Tag、严格递增安装编号及四项签名配置；等待同 SHA 的 develop 构建成功，但不重复构建、不创建 Tag 或 Release。合并后完整正式构建、签名和资产校验仍须通过。

## CI/CD 必须执行的阶段

1. 核对仓库、pull_request closed + merged=true 事件、head=develop、base=master 和来源仓库，固定实际合并 SHA；验证三段版本格式、第一位升版、递增关系及 Tag 是否已被使用。第二、三位开发版本和四段测试版本不得正式发行。
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
- 创建正式 Tag 后上传失败可暂时只有草稿或待完成状态；一个正式 Tag 最终对应一个 Release，不为满足数量要求公开失败产物。不再创建新的开发 Tag。

## 失败恢复

- 网络、下载、上传等临时故障：按同一来源 SHA、版本和流水线重试，恢复已有草稿，不新建版本。
- 源码、工作流或版本配置需要修复：从 develop 产生新提交，重新验证并按第一位上传授权边界核对后合入 master。若原 Tag 已存在则不得把它移动到修复提交，应准备新的合法版本；若尚未创建 Tag 且版本未公开，可在审查后沿用未发布版本。
- 既有公开 Tag/Release 不因新规范而删除、覆盖或补发。废弃草稿、删除标签或回滚公开版本须单独明确授权。

## 参考与差异

参考 [Aviewer 发布工作流](https://github.com/haorengg12/Aviewer/blob/84c1715cbe6a87c78768f9b8da4c6808e1f006d2/.github/workflows/release.yml) 的分阶段构建、附件命名与汇总发布。EhReader 不采用其调试签名回退和允许必需附件失败后继续的方式。Flutter 命令及版本配置不能直接移植到本项目。

## 现有实现与签名配置

以下描述本地实现；远端部署及验收仍按上文分别核实。

- `.github/workflows/ci.yml`：只读基础验证；第一、二位升版运行 Debug 构建，没有开发 Tag 写入任务。
- `.github/workflows/release-readiness.yml`：仅目标 master 的 PR 运行 `Release readiness`，在合并前核对版本、签名配置及同 SHA develop 构建证明。独立工作流避免 develop push 上的 skipped 同名检查干扰必需检查结果。
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
