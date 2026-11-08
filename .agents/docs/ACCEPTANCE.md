# F13：关于页面验收

入口：设置 → 关于。本次只调整关于页面展示与跳转，不修改应用版本、数据或版权归属。

## 展示与链接

- 软件名称和免责声明中的名称均为 `EhReader`，适用于现有全部语言资源。
- 作者保留 Hippo、NekoInverter 的既有署名和删除线；`小白白` 也显示删除线，其下一行显示不带删除线的 `haorengg12`。
- 点击作者条目打开当前维护者主页；原有反馈入口改为“问题反馈”，与目标页面用途一致。

| 条目 | 目标 |
| --- | --- |
| 作者 | <https://github.com/haorengg12> |
| 问题反馈 | <https://github.com/haorengg12/EhReader/issues> |
| 最新版本 | <https://github.com/haorengg12/EhReader/releases> |
| 源代码 | <https://github.com/haorengg12/EhReader> |

## 设备验收

1. 安装目标提交的 Debug APK，从设置进入关于页面，核对名称、免责声明和作者的换行、删除线。
2. 分别点击作者、问题反馈、最新版本及源代码，确认浏览器打开上表中的地址；返回应用后仍能正常操作关于页。
3. 长按反馈、版本及源码条目，确认仍能复制对应链接。
4. 检查简体中文、繁体中文、日文和默认英文资源，并在深色模式与较大系统字体下确认作者完整可见、文案不重叠。

设备验收需实际执行并单独记录结果，不能以静态核验、编译通过或 CI 成功替代。

## 2026-11-04：开发流程改为追加任务记录

- **内容**：将开发流程最后的简短汇报改为在本文件末尾追加记录，保留已有内容。
- **文件**：`AGENTS.md`、`docs/DEVELOPMENT_FLOW.md`、`docs/ACCEPTANCE.md`。
- **结果**：文档修改完成，编码、链接和差异检查通过；本次仅修改本地文档，未提交、推送或运行构建。

## 2026-11-04：Docker 本地构建与验收规范

- **内容**：本地打包改为 Docker ARM64，临时替换统一存放在仓库外 `build-tools/build-use-file`，仅覆盖容器源码副本；经用户确认，纳入工具链/缓存隔离、替换文件有效性与仓库完整性、APK 校验与设备验收。
- **文件**：`README.md`、`docs/LOCAL_DEVELOPMENT.md`、`docs/DEVELOPMENT_FLOW.md`、`docs/TESTING_STANDARDS.md`、`docs/ACCEPTANCE.md`。
- **结果**：规范更新完成，编码、链接和差异检查通过；本次未构建、提交或推送。现有脚本支持初始化脚本加载，通用文件覆盖及新增追溯检查的自动化仍待适配，未宣称已经实现。

## 2026-11-05：增加人工测试安排确认阶段

- **内容**：在自动检查与提交之间增加“人工测试安排确认”；需要等待则等用户明确测试通过，无需等待才继续，未回复不提交，已有明确指示不重复询问。
- **文件**：`AGENTS.md`、`docs/DEVELOPMENT_FLOW.md`、`docs/REPOSITORY_WORKFLOW.md`、`docs/TESTING_STANDARDS.md`、`docs/STANDARDS_README.md`、`docs/ACCEPTANCE.md`。
- **结果**：文档更新完成，编码、链接和差异检查通过；本次仅修改本地文档，未构建、提交或推送。

## 2026-11-05：统一临时文件目录

- **内容**：各阶段的临时日志、排查记录及辅助文件统一存放在工作区根目录 `D:\Eh项目改造\temp`，按任务和阶段分组；同步调整构建留证规则。
- **文件**：`AGENTS.md`、`docs/DEVELOPMENT_FLOW.md`、`docs/LOCAL_DEVELOPMENT.md`、`docs/ACCEPTANCE.md`。
- **结果**：文档更新完成，编码、链接和差异检查通过；未迁移既有文件或修改脚本，未构建、提交或推送。

## 2026-11-05：提交前推送与版本确认及审阅流程图

- **内容**：第 4 阶段依次确认测试安排、推送授权与版本更新，第 5 阶段才执行 commit/push；后续按条件连续推进，保留具体 master 合并确认；新增根目录流程图并要求每次流程变更同步更新。
- **文件**：`AGENTS.md`、`DEVELOPMENT_FLOW.md`、`docs/DEVELOPMENT_FLOW.md`、`docs/REPOSITORY_WORKFLOW.md`、`docs/RELEASE_POLICY.md`、`docs/STANDARDS_README.md`、`docs/TESTING_STANDARDS.md`、`docs/ACCEPTANCE.md`。
- **结果**：本地文档及流程图已更新；未更改应用版本，未构建、提交或推送，待用户审阅。

## 2026-11-05：开发流程节点推荐模型组合

- **内容**：为九个开发流程节点定义模型与推理等级建议，默认沿用 Sol + High，复杂问题提示升级并由用户决定是否切换。
- **文件**：`AGENTS.md`、`docs/DEVELOPMENT_FLOW.md`、`DEVELOPMENT_FLOW.md`、`docs/ACCEPTANCE.md`。
- **结果**：本地规范及审阅流程图说明已更新；编码、相对链接和差异检查通过。本次仅修改文档，未构建、提交或推送。

## 2026-11-05：按版本位置分流与开发 Tag

- **内容**：第三位升版仅在 develop 运行开发 CI，成功后创建开发 Tag、不发 Release、不合并 master；第一、二位先推送 develop 并跳过开发构建，经只读预检和具体合并确认后在 master 合并事件构建发布；第四位仅保存 brody-版本号 分支、不运行 CI/CD。补齐固定比较基准、数字排序、版本不变、Tag 不可覆盖和失败修复规则，同步流程图。
- **文件**：`AGENTS.md`、`README.md`、`DEVELOPMENT_FLOW.md`、`docs/DEVELOPMENT_FLOW.md`、`docs/LOCAL_DEVELOPMENT.md`、`docs/REPOSITORY_WORKFLOW.md`、`docs/RELEASE_POLICY.md`、`docs/STANDARDS_README.md`、`docs/TESTING_STANDARDS.md`、`docs/ACCEPTANCE.md`。
- **结果**：本地规范更新完成，10 份文档的 UTF-8 无 BOM、LF、末尾换行及 49 处相对链接/锚点检查通过，git diff --check 通过，并核对分流表与流程图一致。未更改应用版本，未构建、提交、推送或修改工作流及远端保护；版本分流、开发 Tag、brody 入口隔离和 master 必需检查调整仍待实现，现有自动化行为不变。

## 2026-11-05：取消升版后的第二轮人工测试确认

- **内容**：将版本意向确认和所需修改前移至第 2 阶段，第 3 阶段检查最终版本，第 4 阶段统一确认一次人工测试安排，再确认推送授权；删除流程图中升版后的第二个人工测试节点，保留变更后重新验收及具体 master 合并授权。
- **文件**：`AGENTS.md`、`DEVELOPMENT_FLOW.md`、`docs/DEVELOPMENT_FLOW.md`、`docs/LOCAL_DEVELOPMENT.md`、`docs/REPOSITORY_WORKFLOW.md`、`docs/RELEASE_POLICY.md`、`docs/TESTING_STANDARDS.md`、`docs/STANDARDS_README.md`、`docs/ACCEPTANCE.md`。
- **结果**：本地规范与流程图同步完成；9 份文档的编码/换行、44 处相对链接及锚点、阶段引用和差异检查通过。未更改应用版本、工作流或远端设置，未构建、提交或推送。

## 2026-11-06：文档迁移后更新链接与工作区目录约定

- **内容**：按用户已确认的目录结构修正两个根目录 AGENTS.md、仓库 README、规范及流程图的链接；开发规范集中在工作区 docs，README/CHANGELOG 保留在 EhReader/docs；明确 release 存放本地 arm64-v8a 打包产物及配套材料、temp 存放临时文件、trash 回收废弃文件或目录。
- **文件**：工作区 `AGENTS.md`、`EhReader/AGENTS.md`、`EhReader/README.md`；`docs` 下 DEVELOPMENT_FLOW、DEVELOPMENT_FLOWCHART、LOCAL_DEVELOPMENT、RELEASE_POLICY、STANDARDS_README 和 ACCEPTANCE。完整差异见[changes.diff](../../../temp/2026-11-05-docs-links/20261105-155853048/changes.diff)。此前任务记录中的路径保留当时位置。
- **结果**：本地链接及锚点、UTF-8 无 BOM/LF/末尾换行和差异检查通过，未再次移动文件，未构建、提交或推送。编码检查示例已显式指定工作区文档；脚本默认清单及 CI 对仓库外规范的适配仍待处理，本次未修改脚本或工作流。

## 2026-11-06：新增根目录文档导航与文件夹说明

- **内容**：新增根目录导航，提供快速审阅顺序、docs 全部 10 份文档的可点击链接及根目录 7 个文件夹的简短用途表。
- **文件**：[文档导航.md](../../../文档导航.md)、`docs/ACCEPTANCE.md`。
- **结果**：导航已生成，文档与文件夹覆盖、本地链接及锚点、UTF-8 无 BOM/LF/末尾换行检查通过；仅修改本地文档，未构建、提交或推送。

## 2026-11-06：规范迁入 .agents/docs

- **内容**：将工作区 docs 的 10 份文档迁入 `.agents/docs/`，创建空的 `.agents/skills/` 供后续技能使用，同步入口、导航、目录约定及本地检查命令；历史记录的文字路径保留原位置，仅修正可点击链接。
- **文件**：迁移 `.agents/docs/*.md`；更新工作区 `AGENTS.md`、`文档导航.md`、`EhReader/AGENTS.md`、`EhReader/README.md` 和其中 6 份规范文档。完整差异见[changes.diff](../../../temp/2026-11-06-agents-docs-144456/changes.diff)，迁移清单及原文件 SHA-256 见[manifest.json](../../../temp/2026-11-06-agents-docs-144456/manifest.json)。
- **结果**：10 份文档完整性、导航覆盖、本地链接与锚点、UTF-8 无 BOM/LF/末尾换行及差异检查通过；原有其他任务改动保留。未构建、提交或推送；编码脚本默认清单及 CI 对仓库外规范的既有适配待办仍保留。

## 2026-11-06：将 .agents 移入 EhReader 仓库

- **内容**：将工作区 `.agents/` 整体移入 `EhReader/.agents/`，同步两个入口、根目录导航、README、目录约定和文档链接；编码检查默认清单改为仓库内 `.agents/docs/`，覆盖全部 10 份规范。
- **文件**：迁移 `EhReader/.agents/`；更新工作区 `AGENTS.md`、`文档导航.md`、仓库 `AGENTS.md`、`README.md`、`scripts/check_standards_encoding.py` 及 6 份规范。完整差异见[changes.diff](../../../temp/2026-11-06-agents-into-repo-144845/changes.diff)，迁移清单见[manifest.json](../../../temp/2026-11-06-agents-into-repo-144845/manifest.json)。
- **结果**：10 份规范完整性、导航覆盖、本地链接和锚点、编码与换行及 git diff --check 通过。Python 当前不可用，以 PowerShell 对脚本全部 22 个默认目标完成等效逐字节检查；Python 脚本本身未运行。保留其他任务改动，未构建、提交或推送，未运行远端 CI。

## 2026-11-06：项目结构与 AI 协作评估

- **内容**：梳理工作区与仓库文件用途，对照 FlClash 的 AI 协作组织，评估技能适用范围并列出更新优先级；本次仅提出建议，未安装或启用技能。
- **文件**：[项目结构与AI协作评估.md](../../../项目结构与AI协作评估.md)、[文档导航.md](../../../文档导航.md)、`.agents/docs/ACCEPTANCE.md`。
- **结果**：评估文档已生成并加入根目录导航；本地链接、锚点、UTF-8 无 BOM/LF/末尾换行和差异检查通过。只读核对本地目录、配置及部分模块入口，未做完整代码审计、远端配置核验、Android 构建或设备测试，未提交或推送。

## 2026-11-06：为更新建议增加编号与模型组合

- **内容**：为评估文档“更新优先级”的 10 条建议增加固定序号，并补充建议模型、推理等级和引用方式，方便按 P1-03 等编号安排任务。
- **文件**：[项目结构与AI协作评估.md](../../../项目结构与AI协作评估.md)、`.agents/docs/ACCEPTANCE.md`。
- **结果**：仅修改评估文档的目标章节，章节外内容保持一致；条目数量、编号、表格列数、链接与锚点、编码换行及差异检查通过。模型组合沿用项目流程建议，未切换模型、构建、提交或推送。

## 2026-11-06：P1-03 文档维护技能及复用检查入口

- **内容**：新增文档维护项目技能与 Node 文档检查器，支持本地链接、锚点、编码及明确的仓库/工作区检查范围；验证后在评估表中划掉 P1-03 并保留原文。
- **文件**：`.agents/skills/docs-maintenance/SKILL.md`、`scripts/check-docs.mjs`、`scripts/check-docs.test.mjs`、`AGENTS.md`、`.agents/docs/LOCAL_DEVELOPMENT.md`、`.agents/docs/STANDARDS_README.md`、`.agents/docs/ACCEPTANCE.md`；工作区[导航](../../../文档导航.md)与[评估表](../../../项目结构与AI协作评估.md)。
- **结果**：6 项行为测试通过，覆盖失效文件/锚点/引用、编码错误、代码块排除、中文路径及独立克隆边界；工作区文档检查与 git diff --check 通过。Python 当前不可用，技能头部按实际双字段格式完成等效校验，未运行 quick_validate.py；未升版、构建、提交或推送。

## 2026-11-06：P2-05 Docker 构建验收技能及能力边界

- **内容**：在 P1-03 完成后新增 Docker 构建验收技能，按现有入口逐项区分已实现能力和缺项；示例利用 OutputRoot 将日志先放 temp，再整理已验证交付材料。P2-05 已划掉且保留原文，其他 8 条建议保持不变。
- **文件**：`.agents/skills/docker-build-validation/SKILL.md` 及 `references/current-capabilities.md`、`AGENTS.md`、`.agents/docs/LOCAL_DEVELOPMENT.md`、`.agents/docs/STANDARDS_README.md`、`.agents/docs/ACCEPTANCE.md`；为未渲染模板补充 `scripts/check-docs.mjs` 的 encoding-only 模式、对应测试及 docs-maintenance 说明；工作区[导航](../../../文档导航.md)与[评估表](../../../项目结构与AI协作评估.md)。
- **结果**：19 项 Node 测试通过（含 7 项新文档检查测试）；工作区链接、锚点、编码和差异检查通过。两个技能完成头部等效校验；Docker 示例完成参数/语法核对及中文空格路径模拟调用，未实际调用 Docker。Python 不可用，其 22 个默认编码目标加 2 个新脚本以 Node 等效检查通过。证据见[验证报告](../../../temp/2026-11-06-skills-03-05/verification.md)。未运行 Python 验证器、Android 构建、设备测试或远端 CI；未升版、提交或推送，未改变仓库外 Docker 脚本及分发方式。

## 2026-11-06：开发流程冗余与自动化专项评估

- **内容**：对照流程、授权规范、流程图、CI 和 Docker 入口，补充重复确认、版本与 CI 耦合、证据整理、检查复用、提交后记录及失败恢复的评估；提出关键人工审核与连续自动执行方案，并新增固定序号 11～17 的建议，保留原条目及完成标记。
- **文件**：[项目结构与AI协作评估.md](../../../项目结构与AI协作评估.md)、`.agents/docs/ACCEPTANCE.md`。
- **结果**：评估文档更新完成，原 10 条建议逐项比对保持不变，17 条建议编号与表格列数检查通过；工作区文档链接、锚点、编码换行及 git diff --check 通过。此次仅更新评估和记录，未修改权威流程、流程图、脚本或授权规则，未构建、运行 CI、提交或推送。

## 2026-11-07：P1-11 合并重复问答与关键人工审核

- **内容**：将版本默认策略、同一份最终审核单、已有及条件授权复用写入开发流程，保留测试与提交推送的独立条件和具体 master 合并确认；用户选择“只要求测试包但版本未定时先确认版本”。同步流程图及相关规范，P1-11 划掉保留原文，其他 16 条建议未变。
- **文件**：`AGENTS.md`；`.agents/docs/DEVELOPMENT_FLOW.md`、`DEVELOPMENT_FLOWCHART.md`、`REPOSITORY_WORKFLOW.md`、`RELEASE_POLICY.md`、`LOCAL_DEVELOPMENT.md`、`TESTING_STANDARDS.md`、`STANDARDS_README.md`、`ACCEPTANCE.md`；工作区[导航](../../../文档导航.md)及[评估](../../../项目结构与AI协作评估.md)。本次独立于前序未提交改动的[完整差异](../../../temp/2026-11-07-p1-11/changes.diff)及[变更清单](../../../temp/2026-11-07-p1-11/manifest.json)保存在 temp。
- **结果**：本地文档链接、锚点、UTF-8 无 BOM/LF/末尾换行与差异检查通过，16 个场景完成图文人工一致性审阅；[验证记录](../../../temp/2026-11-07-p1-11/verification.md)。仅交付本地文档，未要求上传；未修改应用版本、代码、工作流或远端保护，未构建、运行设备测试或 CI、提交或推送。Mermaid 未渲染，未以场景审阅冒充真实执行测试。

## 2026-11-07：iQOO 10 Android 16 模拟器硬件配置

- **内容**：在工作区根目录生成 Device Manager 可导入的 iQOO 10 硬件配置，采用 6.78 英寸、1080×2400、8 GiB RAM，软件限定 Android 16 / API 36；模拟器内部存储设为 8 GiB，支持 x86_64 与 arm64-v8a 镜像。
- **文件**：[iQOO10_Android16.xml](../../../iQOO10_Android16.xml)、`.agents/docs/ACCEPTANCE.md`。
- **结果**：使用本地 Android SDK dvlib 内置 devices-3.xsd 校验通过；未在 Android Studio 中实际导入或启动。XML 为近似硬件配置，系统镜像需另选 Android 16，不包含 OriginOS 或真机性能仿真。仅本地交付，未升版、构建、提交或推送。

## 2026-11-07：P1-12 本地检查、构建与交付调度

- **内容**：按用户选择实现最小本地命令行调度器，按明确任务文件选择文本/文档/Node 检查及现有 ARM64 Debug 构建，保存状态、退出码和证据，核验后整理交付材料；在提交推送前停下。P1-12 完成并划掉保留。
- **文件**：`scripts/local-task.mjs`、`scripts/local-task.test.mjs`；`.agents/docs/LOCAL_DEVELOPMENT.md`、`DEVELOPMENT_FLOW.md`、`DEVELOPMENT_FLOWCHART.md`、`ACCEPTANCE.md`；工作区[评估表](../../../项目结构与AI协作评估.md)。
- **结果**：真实本地调度运行的文本、文档、Node 语法、25 项 Node 测试和差异检查通过；6 项新测试覆盖失败停止/退出码、恢复、范围/并发锁、缺项、模拟 Debug 交付及源码变化阻止交付，Docker 为隔离模拟。材料见[本地审核单](../../../temp/p1-12-validation/review.md)。未实际打包、设备测试、CI、提交推送；未修改版本、Docker 入口或 CI 实现。结果复用将在随后 P2-14 单独实现。

## 2026-11-07：P2-14 检查结果复用与失效

- **内容**：为本地调度器加入按检查分组的输入/配置/工具环境指纹、日志散列及复用判定；只复用有效成功结果，变化后重跑受影响项，status 重新核对有效性；正式 merge SHA 构建独立验收。P2-14 划掉保留，其他 15 条建议及前序改动保留。
- **文件**：`scripts/check-results.mjs`、`scripts/local-task.mjs`、`scripts/local-task.test.mjs`；`.agents/docs/TESTING_STANDARDS.md`、`LOCAL_DEVELOPMENT.md`、`DEVELOPMENT_FLOW.md`、`DEVELOPMENT_FLOWCHART.md`、`STANDARDS_README.md`、`ACCEPTANCE.md`；工作区[导航](../../../文档导航.md)及[评估](../../../项目结构与AI协作评估.md)。本次[差异](../../../temp/2026-11-07-p1-12-p2-14/changes.diff)、[清单](../../../temp/2026-11-07-p1-12-p2-14/manifest.json)及[验证记录](../../../temp/2026-11-07-p1-12-p2-14/verification.md)保存在 temp。
- **结果**：32 项 Node 测试通过，其中调度及复用 13 项；真实未变任务续跑五项结果全部 reused 且 status=valid，文档变化只重跑受影响检查。编码、链接/锚点和差异检查通过。按用户选择仅本地调度；Docker 为隔离模拟，实际签名卷及完整工具证据未固定时不自动复用构建。未实际打包、设备测试、CI、升版、提交或推送，未改写 Docker 入口、远端工作流或其他待办。

## 2026-11-07：P1-01 / P1-13 本地适配，待远端验收

- **内容**：将基础质量校验与 APK 构建/发行分离，实现三/四段版本分流、固定事件 SHA、开发 Tag 幂等/冲突门禁和 develop 手动修复；移除 master PR 的重复 Release 构建，准备两个必需检查的调整方案。
- **文件**：`.github/workflows/ci.yml`、`release-readiness.yml`、`releases.yml`；`scripts/version-policy.mjs`、`ci.mjs`、`ci.test.mjs`、`release.mjs`、`release.test.mjs`、`check_standards_encoding.py`；相关流程、测试、仓库与发布规范。部署方案见[deployment-plan.md](../../../temp/2026-11-07-p1-01-p1-13/deployment-plan.md)。
- **结果**：本地 YAML/任务依赖图通过，分流/发布专项 18 项测试通过（含真实固定 Git SHA 的 CLI 测试）；先前完整回归 41 项通过，最终全量将在后续记录/恢复改动后统一执行。实际 GitHub CI、开发 Tag/正式发行及远端必需检查尚未验收，不将 P1-01/P1-13 标为全部完成。未升版、提交推送或修改远端保护。actionlint 下载因自动审批额度检查失败而未执行；仅用现有 SnakeYAML 完成语法/依赖检查，未冒充 actionlint 或远端成功。

<!-- local-task-record:p2-15-17-validation -->
## 2026-11-07：p2-15-17-validation

- **内容**：完成本地记录草稿、规范权威来源去重、有限重试与暂停恢复；状态：awaiting-review。
- **文件**：`.agents/docs/ACCEPTANCE.md`、`.agents/docs/DEVELOPMENT_FLOW.md`、`.agents/docs/DEVELOPMENT_FLOWCHART.md`、`.agents/docs/LOCAL_DEVELOPMENT.md`、`.agents/docs/RELEASE_POLICY.md`、`.agents/docs/REPOSITORY_WORKFLOW.md`、`.agents/docs/STANDARDS_README.md`、`.agents/docs/TESTING_STANDARDS.md`、`AGENTS.md`、`README.md`、`scripts/check-results.mjs`、`scripts/ci.mjs`、`scripts/ci.test.mjs`、`scripts/local-task.mjs`、`scripts/local-task.test.mjs`、`scripts/release.mjs`、`scripts/release.test.mjs`、`scripts/task-records.mjs`、`scripts/task-recovery.mjs`、`scripts/version-policy.mjs`、`../文档导航.md`、`../项目结构与AI协作评估.md`。
- **结果**：所调度自动检查完成，未据此取得人工测试或推送许可；text=reused；docs=reused；node-syntax=reused；node-tests=reused；diff=reused。版本 0.1.2 / 102。证据位于工作区 `temp/p2-15-17-validation/`。未由本入口执行提交、推送、CI 或正式发行。

## 2026-11-08：v1.8.15 版本准备与本地完整验证

- **内容**：按用户指定将版本对齐为 1.8.15，安装编号用 10815 对应 1 | 08 | 15；版本门禁只为 0.1.2/102 → 1.8.15 保留此次明确指定的对齐例外。同步 README 当前版本与唯一配置来源，P1-04 划掉保留；继续验证前序文档迁移、本地调度及 CI 分流改动。
- **文件**：`app/build.gradle.kts`、`scripts/version-policy.mjs`、`scripts/ci.test.mjs`、`README.md`、`.agents/docs/RELEASE_POLICY.md`、`.agents/docs/ACCEPTANCE.md`；拟上传累计 41 个仓库路径，见[范围清单](../../../temp/2026-11-07-release-1.8.15/pending-upload.json)。工作区[评估表](../../../项目结构与AI协作评估.md)保留完成标记。
- **结果**：Docker ARM64 Debug 构建成功；编码、48 项 Node 测试、Spotless、lint、APK 身份/架构/签名/对齐和 Nettle 检查通过，宿主 679 个源码路径构建前后散列一致。lint 221 条警告与旧 Debug 报告无变化；Android 单元测试 NO-SOURCE，设备测试未执行。actionlint 原始两处 queue 字段兼容性错误单独留证，排除后其他规则检查通过。详见[测试包与验收报告](../../../release/20261108-v1.8.15-debug/验收报告.md)。本次推送已获授权，用户明确无需等待人工测试（不记为测试通过）；提交、同 SHA 远端 CI、保护切换与具体 master 合并/正式发行待执行，P1-01/P1-13 尚未全部划掉。提交后证据将留在 temp 或 CI 摘要，不为回填结果循环提交。
