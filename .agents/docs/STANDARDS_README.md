# EhReader 开发规范 V2.3

更新日期：2026-11-07。当前规范采用 master 默认分支、develop 开发分支，以及按阶段读取规范的执行方式。

## 如何使用

开始只读[工作区入口](../../../AGENTS.md)、[EhReader AI 入口](../../AGENTS.md)和[开发任务流程](DEVELOPMENT_FLOW.md)。进入阶段时再读取流程表中的规范，不要求开工时读完所有文件；已读且未变化的内容复用。

本规范目录是工作区 `D:\Eh项目改造\EhReader\.agents\docs`，位于 EhReader Git 仓库内；README 和 CHANGELOG 仍位于 `EhReader/docs`。工作区根目录保持现有布局：release 存放本地 arm64-v8a 产物及交付材料，temp 存放临时文件，trash 回收废弃文件或目录，详细用途以[目录约定](LOCAL_DEVELOPMENT.md#工作区目录约定)为准。

每个主题只有一个权威来源：编码风格在 CODING_STANDARDS，本地验证在 LOCAL_DEVELOPMENT，行为测试在 TESTING_STANDARDS，架构在 ARCHITECTURE，授权和分支在 REPOSITORY_WORKFLOW，版本与发行在 RELEASE_POLICY。

## 当前流程

按版本默认策略本地开发 → 检查最终状态并准备材料及提交前记录 → 最终审核单一次补齐缺少决定 → 本地交付，或获准提交推送 → 按版本路径跟踪 → 核对记录与外部交付证据。操作时点以开发流程为准。

普通改动和必要编译默认不升版；交付测试包但未明确版本时先确认，再生成最终包。版本与审核的操作细则统一见[开发任务流程](DEVELOPMENT_FLOW.md#版本准备第-2-阶段)及[最终审核单](DEVELOPMENT_FLOW.md#最终审核单)。未要求上传且无保存授权时交付本地结果；已有测试安排和保存权限直接复用，不固定拆成两问。需要人工测试时仍等明确结果，实质改动后重验受影响部分。第一位确认版本且明确上传后，由助手按仓库管理规则跟踪 CI 并自动合并，无需二次确认。

| 主题 | 唯一权威来源 |
| --- | --- |
| 阶段顺序、版本默认意图、最终审核单、失败恢复和记录时点 | [开发任务流程](DEVELOPMENT_FLOW.md) |
| 用户授权、分支写入与具体 master 合并权限 | [仓库管理](REPOSITORY_WORKFLOW.md) |
| 数字版本算法、基础验证/打包发行分流、Tag/Release 和部署实测状态 | [发布规范](RELEASE_POLICY.md) |
| 本地命令、输入/产物目录和构建验收 | [本地开发](LOCAL_DEVELOPMENT.md) |
| 行为覆盖、结果含义、检查复用和失效 | [测试规范](TESTING_STANDARDS.md) |

入口、技能和流程图仅保留必要边界与指向，不另复制完整规则。修改上述主题时更新权威来源及受影响的摘要/图，其他历史记录按当时事实保留。

## 部署与验收边界

- 本地适配、远端门禁和 CI 实测状态见[发布规范](RELEASE_POLICY.md#尚待实现的适配)，不能将本地实现写作已部署，不绕过实际保护。
- 工作流和脚本随 develop 按版本路径提交验证，正式发布工作流须获准合入 master 后才能在 master 生效。
- 首次 master 合并前必须准备未用版本；现有 0.1.0 (100) 已发布，不能再次作为新提交发行。
- 正式签名 Secrets 和分支/标签保护需核实；未配置签名即失败，不能称发布已经验收通过。
- 自动化门禁单元测试与 develop CI 不等于正式发布端到端验收；合并前不通过制造 Tag/Release 来测试。
- 参考流程来自“模拟 EhReader 开发流程”，其中假设成功的结果不属于真实测试证据。

## 文档维护

仓库 `.agents/docs/` 保存规范与参考资料；同级 `.agents/skills/` 保存可复用任务技能。文档维护使用 [docs-maintenance](../skills/docs-maintenance/SKILL.md)，按需引用本目录的权威规范。规范通过 AGENTS.md 引导阅读，迁移本身不会将文档变成技能。

明确要求本地 Docker 打包或产物核验时使用 [docker-build-validation](../skills/docker-build-validation/SKILL.md)，技能中的能力表区分现有入口与尚需补齐的证据；创建技能不代表实际打包验收通过。

需要串联已有本地检查、构建和材料时使用[本地任务调度入口](LOCAL_DEVELOPMENT.md#本地任务调度入口)，结果是否可复用统一见[测试规范](TESTING_STANDARDS.md#检查结果的复用与失效)。入口仅在本地运行并停在提交推送前，远端 CI/发行不由它调度。

只维护当前 Markdown，每次流程变更同步更新本目录 [DEVELOPMENT_FLOWCHART.md](DEVELOPMENT_FLOWCHART.md) 审阅流程图；不生成规范压缩包、不在 EhReader/docs 另存规范副本。工作区与仓库根目录的 AGENTS.md 保留为入口并同步引用路径。规范位于 EhReader 仓库内，提交后由 Git 记录历史并随对应版本分发；工作区根目录入口和导航仍在仓库外维护。ACCEPTANCE.md 中历史记录所列文件名按当时位置保留，当前位置以目录约定为准。

文本使用 UTF-8 无 BOM、LF、文件末尾换行；变更后按[本地命令](LOCAL_DEVELOPMENT.md#按变更选择验证)执行 `node scripts/check-docs.mjs`，完整工作区加 `--workspace`，再审阅阶段路由和规则一致性。编码脚本仍负责其默认清单中的配置及脚本，现有 CI 编码入口沿用该脚本；远端执行结果须在获准推送后另行核实。
