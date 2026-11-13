# 本地开发规范 — V2.2

本文件定义开发机验证和 Docker 本地 Debug 打包。正式发布及密钥使用见 [发布规范](RELEASE_POLICY.md)。本地常规检查和 Debug 构建纳入已授权开发任务；纯文档整理不执行编译。本地打包统一进入 Docker Linux 容器，源码编辑和 Git 操作仍在 Windows 仓库中进行。

## 工作区目录约定

用户于 2026-11-06 确认将 `.agents/` 移入 EhReader 仓库，规范位于 `.agents/docs/`，与 `.agents/skills/` 并列；其余工作区目录保持现有布局。工作区根目录是 `D:\Eh项目改造`，Git 仓库根目录是其下的 `EhReader`，二者分别管理本地资料和仓库源码。

| 工作区相对路径 | 用途 |
| --- | --- |
| `AGENTS.md` | 工作区入口，引用 EhReader 入口和开发流程，保留在根目录 |
| `EhReader/` | Git 仓库、源码、构建配置和仓库入口 AGENTS.md |
| `EhReader/.agents/` | AI 协作资料目录，包含规范 docs 和技能 skills |
| `EhReader/.agents/skills/` | 项目级可复用任务技能；按技能描述匹配任务，规范正文仍在 docs |
| `EhReader/.agents/docs/` | 本地开发规范、流程说明、流程图、验收记录、需求资料和模型指南；流程图为 DEVELOPMENT_FLOWCHART.md |
| `EhReader/docs/` | 保留 README、CHANGELOG 目录及 README.upstream.md，不存放已迁出的开发规范 |
| `release/` | 本地 arm64-v8a 打包产物及随包校验清单、构建来源清单和最终验收报告；按构建分组保存 |
| `temp/` | 临时文件，包括日志、排查记录、临时报告、迁移清单和一次性辅助文件；按任务及阶段分组 |
| `trash/` | 废弃文件或目录的回收位置；保留可辨认的原名称与来源，重名时按日期或任务分组，不覆盖既有回收项，不自动清空 |
| `build-tools/` | 仓库外 Docker 构建入口及本地临时替换文件，沿用现有子目录 |

源码、规范、产物、临时文件和废弃文件按上表归位；其他既有目录保持现状，不因规范更新批量移动或删除。确认废弃的文件或目录移入 `trash`，需要恢复时核对来源；创建回收目录不等于授权永久删除。

本地 `release` 文件夹用于保存打包交付材料，GitHub 正式发行仍按发布规范执行。开发规范位于 Git 仓库内的 `.agents/docs/`，提交后随对应版本的克隆和 CI checkout 携带，不在其他位置维护副本。文中 `app/`、`scripts/`、`.github/` 等源码路径均相对 EhReader 仓库根目录；`.agents/docs/` 和 `.agents/skills/` 相对仓库根目录；`release/`、`temp/`、`trash/` 相对工作区根目录。

## 版本与远端路径

版本算法与远端路径只在[发布规范](RELEASE_POLICY.md#按版本位置分流目标规则)维护；本地必要检查不因远端分流免除；第一/二位 develop 构建，第三位快照没有远端 CI，第四位仅本地。部署状态见[适配清单](RELEASE_POLICY.md#尚待实现的适配)，本地规范/脚本不代表远端已启用。

## 开始任务

1. 检查工作区状态和分支，先读[工作区入口](../../../AGENTS.md)、[仓库入口](../../AGENTS.md)和[流程导航](DEVELOPMENT_FLOW.md)，再读取当前阶段规范与现有实现；不得覆盖其他任务改动。
2. 说明影响范围、接口或数据兼容风险及验证项目。小改动采用简短说明。
3. 检查已有工具链；项目当前使用 JDK 21、SDK 36、Build Tools 36.0.0、NDK 29.0.14206865、Gradle Wrapper。实际版本以项目配置为准。
4. 使用现有 Docker 构建入口，源码只读挂载后复制到容器的 `/workspace/ehreader` 编译；SDK 使用镜像内的 Linux 路径，不再为本地打包创建 R 盘映射或直接在 Windows 源码目录编译。
5. 环境安装、移动工作区或改变系统配置有实质影响时先说明并获取必要许可；不因环境问题静默修改项目工具链。

本地开发阶段先按[版本准备](DEVELOPMENT_FLOW.md#版本准备第-2-阶段)应用默认策略：普通改动及必要编译默认不升版，不为此等待答复；用户要求交付测试包但未明确版本时，先确认版本。完成所需修改后再检查最终状态、准备材料，用[最终审核单](DEVELOPMENT_FLOW.md#最终审核单)同时说明测试安排与提交推送权限，只补齐缺项。仅交付本地结果时不增加上传问答；第一位按已确认版本与上传条件由助手跟踪 CI 后自动合并，不再二次询问。

## 按变更选择验证

| 变更 | 本地必要检查 |
| --- | --- |
| 纯文档或规范 | 编码、链接、内容一致性、git diff --check；不编译 Android |
| Kotlin/Java 或业务行为 | Spotless、相关单元测试、Debug 编译；按影响运行 lint |
| UI、资源、Manifest、依赖或构建配置 | Spotless、Debug 构建、lint、适用测试 |
| 数据库、下载或文件操作 | 上述检查加测试规范规定的回归验证 |
| 仅工作流 | YAML/脚本静态检查、触发条件和权限审查；实际运行由获准推送后的 CI 验证 |

Windows 上从 `D:\Eh项目改造\EhReader` 执行的文本检查和 Docker 打包入口（按变更选择，不要求每次全部重跑）：

```powershell
$standardsFiles = @(Get-ChildItem -LiteralPath '.agents\docs' -File -Filter '*.md' | ForEach-Object { $_.FullName })
python scripts/check_standards_encoding.py ../AGENTS.md AGENTS.md README.md @standardsFiles
node scripts/check-docs.mjs --workspace
node --test scripts/*.test.mjs
git diff --check
& 'D:\Eh项目改造\build-tools\ehreader-docker\build.ps1' -OfflineDependencies
```

上述 Docker 入口默认执行 ARM64 Debug 的 Spotless、适用测试、lint 和打包，以及 APK/原生源码检查。`-OfflineDependencies` 仅限制 Gradle 依赖解析；原生源码首次下载等步骤仍可能联网，缓存缺失时不能宣称可以完全离线构建。Linux CI 使用 ./gradlew。Python 编码脚本只检查文本，不是编译命令；Python 不可用时可做等效逐字节检查并如实记录。

编码检查脚本的默认清单已改为仓库内 `.agents/docs/`，覆盖全部 10 份规范文档，现有 CI 调用入口保持不变。检查工作区入口等仓库外文件时仍须显式传入路径；本地检查通过不等于远端 CI 已运行。

文档检查入口 `node scripts/check-docs.mjs` 不依赖 Python，默认检查仓库 AGENTS.md、README.md 和 `.agents/**/*.md` 的编码、本地链接及锚点；只查仓库内目标，仓库外链接跳过并计数，适用于独立克隆。完整工作区加 `--workspace`，再检查仓库外入口、导航、评估及仓库外目标。显式文件参数替代默认清单，并检查其全部本地目标，非 Markdown 文件仅查编码。它不联网校验外部 URL，不解析 MDX、模板、HTML href 或标题扩展语法；流程语义仍需人工审阅。新检查器测试会创建临时夹具，本地运行前将 `EHREADER_TEST_TEMP` 指向工作区 `temp/<本次任务>` 已存在目录；未设置时使用系统临时目录，供独立克隆及 CI 运行。

需要等效逐字节文本检查时可执行 `node scripts/check-docs.mjs --encoding-only <文件...>`，包括未渲染的发行模板；该模式只检查实际传入目标的编码换行，不声称模板链接或 Python 脚本已验证。

## 本地任务调度入口

`scripts/local-task.mjs` 只调度本地检查、现有 ARM64 Debug 构建及材料整理；在提交推送前停下，不代替人工测试，不修改版本，不操作 Git 提交、远端、CI 或发行。首次必须显式列出本次文件，不能将工作区所有未提交改动自动视为本任务；默认不升版，明确交付新测试包时仍先按开发流程确认版本。

```powershell
node scripts/local-task.mjs run --task my-task --files scripts/example.mjs .agents/docs/LOCAL_DEVELOPMENT.md
node scripts/local-task.mjs status --task my-task
node scripts/local-task.mjs run --task my-task
node scripts/local-task.mjs record --task my-task
node scripts/local-task.mjs record --task my-task --append
```

示例中的文件须替换为实际任务路径；路径相对仓库根目录，带空格时加引号。首次文件范围保存后，同一任务可继续运行；需要扩大范围时使用新任务名。状态和每次尝试的输入、命令、退出码及日志保存在工作区 `temp/<任务名>/`，审核材料为 `review.md`。任务失败保留证据并停止后续步骤，不自动重试、还原源码或清理固定卷。若异常中断遗留 `.lock`，先确认原任务进程确已结束，再人工移除该锁文件；入口不自行抢占或删除任务证据。

| 本次文件 | 自动调度与能力边界 |
| --- | --- |
| 常规文本 | 对实际存在的本次文本执行 UTF-8 无 BOM、LF、末尾换行；检查本次 diff 和新增脚本/文档行尾空白；已跟踪删除单独识别 |
| Markdown | 调用已有文档检查器；完整工作区检查入口、导航、评估及仓库外链接，独立克隆只核验仓库内目标 |
| Node 脚本 | `node --check` 检查本次 JS/MJS，运行仓库 scripts 中的 Node 测试；无测试源或命令失败不记通过 |
| app、Gradle、Wrapper 等 Android 输入 | 调用仓库外现有 build.ps1 的 Debug 模式；先固定现有镜像 ID、检查固定工作卷占用，不拉取镜像、不提升为 ReleaseAll |
| YAML、Python、PowerShell、Shell 或未识别文件 | 不猜测业务影响；可执行的检查照常运行，但未自动完成的语法/行为检查明确列入缺项，不记全部通过 |

Android 构建日志和中间报告先输出至本次 temp；命令成功、宿主源码前后内容未变且 APK 与散列清单一致后，才将 APK、校验及来源材料整理至 `release/<任务名>-<本次尝试>`。现有 Docker 验证器负责实际包名、版本、ABI、签名有效性及对齐检查；调试证书预期指纹、完整实际工具版本、替换基线和设备测试等缺项仍按能力表补查，不能把包生成成功写成完整验收通过。

`awaiting-review` 表示已调度检查完成、等待按开发流程审阅；`needs-manual-validation` 表示仍有明确缺项；`failed/blocked` 表示失败或无法继续，均不能直接作为提交依据。即使本地检查完成，脚本也不取得人工测试或推送权限。Node 调度器测试使用临时隔离仓库与模拟 Docker 入口，不把模拟通过当作真实 Android 构建。

同一任务再次 `run` 会按[复用与失效规则](TESTING_STANDARDS.md#检查结果的复用与失效)逐项核对输入、工具/配置、范围及原日志，只有有效成功结果标记 `reused`，其余重跑；`status` 重新核对并标明 `valid/stale/unverified`。修改文档一般不使 Node 语法/测试组失效，修改脚本依赖或发行模板则重跑相应测试组。Docker 构建因未固定签名卷及完整工具证据不自动复用；正式合并构建仍独立验收。

每次状态保存会生成 `acceptance-draft.md`，可用 `--summary "本次更改内容"` 提供一句任务说明；文件与结果从真实状态生成。`record` 重新核对当前有效性并刷新草稿；显式 `--append` 才追加 ACCEPTANCE，同一生成任务只追加一次，之后仅刷新 temp 证据，避免为记录制造循环提交。记录时点见[开发流程](DEVELOPMENT_FLOW.md#最后一步追加到-acceptancemd)。

可用 `run --task my-task --pause-before node-tests` 在所选步骤前暂停，继续时省略该参数。状态为 paused 不代表通过或取得新权限；取消、失败分类、同输入最多一次的只读重试及重新审核见[恢复规则](DEVELOPMENT_FLOW.md#失败恢复与重新审核)。Docker 构建和任何签名/Tag/权限写入不在自动重试白名单中。

## 容器内临时替换文件

- 本地构建专用文件统一放在仓库外 `D:\Eh项目改造\build-tools\build-use-file`，不得先修改原仓库再还原。
- 需要完整替换文件时，按仓库相对路径存放，例如 `build-use-file/app/build.gradle.kts` 对应容器构建副本的 `/workspace/ehreader/app/build.gradle.kts`；其他文件遵守同一规则。
- 每次先将本次源码同步到容器构建副本，再将本次需要的替换文件复制到对应位置，随后执行检查和编译。替换只作用于该次容器工作副本，不写回 Windows 仓库，也不把它固化到可复用工具链镜像。
- 外部 Gradle 初始化脚本也保存在该目录，仅在需要时加载，不作为仓库相对路径覆盖文件。现有 `local-cache.init.gradle` 用于缓存解析；`full-abis.init.gradle` 不属于日常 ARM64 构建，不能默认加载。
- 当前实现已支持外部初始化脚本，尚未实现任意文件按相对路径覆盖。实际任务需要替换 `app/build.gradle.kts` 等文件时，须先适配并验证仓库外的 Docker 执行脚本，不得声称现有脚本会自动复制所有文件。本次规范更新不代表该实现已经完成。

## Docker 检查与验收

下列项目已纳入每次适用的本地构建验收；脚本未自动提供的证据须实际补查，不得只引用历史成功记录。

1. **镜像与工具链**：记录镜像 ID/摘要和实际工具版本，与当前项目及 CI 配置核对。复用已有镜像；需要升级时明确原因。容器与 CI 的工具链对齐不代表二者系统镜像完全相同。
2. **源码与缓存隔离**：Windows 源码只读挂载，复制到 Linux 数据卷内构建；不导入 Windows 的项目 `.gradle`、`.cxx`、`build`、`local.properties` 或平台编译缓存。可复用经过校验的依赖下载；Linux 缓存保留在专用卷，同一工作卷不得并发构建。新依赖或缓存缺失需如实报告，不以跳过检查解决。
3. **替换文件有效性**：记录本次源码 SHA、工作区未提交状态、每个替换文件的相对目标路径、用途、基于的原文件 SHA-256 和替换文件 SHA-256；初始化脚本也列入清单。原文件已变化时先审阅和更新替换文件，不能直接用过期副本覆盖。目标必须位于容器源码副本内，拒绝绝对路径、路径穿越或符号链接导致的越界写入。每次重新从当前源码同步，避免沿用上次覆盖内容。
4. **原仓库完整性**：构建前后对比原仓库状态及相关文件内容，确认 Docker 没有修改原仓库或写回临时配置。已有未提交改动也属于输入，必须保留；发现差异先调查，不能自动还原用户或其他任务的新修改。替换影响构建逻辑时，记录明确说明该产物来自“源码快照 + 本地替换”，不能声称它等同于未经替换的 CI 构建。
5. **检查与包验证**：按变更矩阵完成 Spotless、适用测试、显式 lint 和构建；核对 APK 包名、版本、实际原生库仅 arm64-v8a、签名身份和对齐。本地 Debug 使用持久的调试签名；正式密钥仍须另获授权，不能写入镜像、替换文件、缓存、日志或附件。不得通过改版本、关闭检查或更换签名来掩盖失败。
6. **导出与留证**：APK、SHA-256 清单、镜像/源码/替换清单及最终验收报告输出到仓库外 `release/<时间戳>-docker-<构建类型>`；构建日志、失败诊断、临时检查报告和排查辅助文件统一放在工作区根目录 `D:\Eh项目改造\temp`，按[临时文件存放规则](DEVELOPMENT_FLOW.md#临时文件存放)分组。记录实际命令与退出状态，并在最终报告中引用对应日志。失败不标记 PASS；`NO-SOURCE`、警告和未运行项目分别记录。包含未提交改动时，仅有 Git SHA 不足以标识实际输入。
7. **设备验收**：按本次功能在设备上安装、启动和验证；设备验收与容器构建分别记录。设备不可用时写明未执行，不以打包成功代替运行验收。最终在仓库[ACCEPTANCE.md](ACCEPTANCE.md)追加简短结论并链接本次证据目录。

实施边界：现有 Docker 入口已有部分构建、包验证与导出能力；通用文件覆盖、替换文件基线/散列和构建前后完整性自动核验尚需后续适配。这里规定应完成的验收，不代表所有检查已经自动实现。

具体执行可用 [Docker 构建验收技能](../skills/docker-build-validation/SKILL.md)，其[当前能力表](../skills/docker-build-validation/references/current-capabilities.md)按实际脚本核对缺项。现有入口默认将 build.log 和中间报告写入 release；新任务可使用已有 `-OutputRoot` 参数先输出到本次 temp 目录，再将验证成功的 APK、清单和最终报告整理至 release，日志保留在 temp。本次仅明确使用方式，不迁移历史材料、不迁移或改写仓库外入口。

## 本地打包与失败处理

- 本地日常打包仅生成 arm64-v8a 的 Debug APK，不生成其他 ABI 或通用包；构建完成后导出到 `D:\Eh项目改造\release\<时间戳>-docker-Debug`。容器内中间产物位于构建副本的 `app/build/outputs/apk/debug/`，不写回原仓库。
- 评估对话中的 `ReleaseAll` 是当次明确批准的专项验证，不构成后续全架构构建或正式密钥使用的常规授权。获准的本地 Release 默认也仅 ARM64；当前入口仅提供 Debug 和 ReleaseAll，后续若需 ARM64 Release，应先适配入口，不用 ReleaseAll 代替。不得在本地伪造 GITHUB_ACTIONS=true。
- GitHub Actions 按完整架构构建：arm64-v8a、armeabi-v7a、x86_64 及 universal；Debug 和 Release 均适用。构建配置以 GitHub Actions 环境区分本地与远程，修改架构集合需同步发布附件校验。
- Nettle 下载源码位于各 ABI 的 `.cxx/<配置>/<构建标识>/<ABI>/_deps/nettle`，不得改回共享源码树目录。`native-sources.mjs` 从实际 CMakeCache 核对目录隔离、ABI 覆盖与源码一致性，正式源码附件也复用该检查。AGP 正式变体通常使用 `RelWithDebInfo`；Release 审计兼容该目录及 `Release`、`MinSizeRel`，不能假设目录一定叫 Release。
- 本地 Release 问题复现或正式密钥操作需单独说明用途并获准；正式发行包必须由 master 对应提交的 CI/CD 生成。
- 记录实际命令、退出状态、产物和未验证事项。一次失败不得直接跳过或关闭检查；任务代码导致的失败先修复。
- 若仅受工具链或网络阻碍，报告原因与已完成检查；获得用户针对本次例外的许可后，才可跳过本地阻塞项并推送 develop 交由 CI 验证。不得称本地检查已通过。
- 验证通过且无新变化时不反复全量构建。提交只包含本次任务文件；产物、缓存、私钥和密码不得进入 Git。
