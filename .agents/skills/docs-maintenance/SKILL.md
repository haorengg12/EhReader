---
name: docs-maintenance
description: 维护 EhReader 的规范、流程、Markdown 导航或文档目录，检查本地链接、锚点与编码。用于文档修改、迁移和流程同步；不用于 Word/PDF 排版。
---

# EhReader 文档维护

在 EhReader 仓库执行。先读仓库 AGENTS.md 和[开发流程](../../docs/DEVELOPMENT_FLOW.md)，按当前阶段读取[规范说明](../../docs/STANDARDS_README.md)及实际修改的主题。每个主题沿用现有权威规范，不将规则复制到技能中。

- 确定文档调用方和入口。移动或重命名时搜索旧路径，更新仓库入口、工作区入口与[文档导航](../../../../文档导航.md)中受影响的链接，保留历史记录所述事实。只修改本地文档不强制升版。
- 修改阶段、判断、授权或回退路径时，同步[开发流程图](../../docs/DEVELOPMENT_FLOWCHART.md)，人工核对图文一致；自动链接检查不证明流程语义一致。
- 从仓库根目录运行 `node scripts/check-docs.mjs`，默认递归覆盖 `.agents` 中的规范及技能，只查仓库内目标，仓库外链接跳过并计数，适用于独立克隆。在完整工作区运行 `node scripts/check-docs.mjs --workspace`，额外检查工作区入口、导航、评估及仓库外目标。可传具体文件替代默认范围，此时检查其全部本地目标。
- 检查器覆盖常规行内/图片/引用链接、文件及目录存在性、Markdown 标题及显式 HTML 锚点、UTF-8 无 BOM/LF/末尾换行。外部 URL 不联网；MDX、模板、HTML href、标题扩展语法须按实际需要补查。不能把本地检查通过写成外部网址可用。
- 非 Markdown 配置/脚本编码仍使用[本地验证入口](../../docs/LOCAL_DEVELOPMENT.md#按变更选择验证)，Python 不可用时可用 `node scripts/check-docs.mjs --encoding-only <文件...>` 对实际目标做等效文本检查，适用于未渲染的发行模板。新增检查器的行为验证使用 `node --test scripts/check-docs.test.mjs`；临时测试目录按工作区约定用 `EHREADER_TEST_TEMP` 指向 `temp` 下本次任务目录。
- 执行 `git diff --check`，并单独审阅新增未跟踪文件及仓库外文件。纯文档不构建 APK。检查失败先修正文档或报告真实缺项，不删除有效内容凑通过。

按[任务记录要求](../../docs/DEVELOPMENT_FLOW.md#最后一步追加到-acceptancemd)追加实际结果，说明检查范围和未验证事项。用户给出完成清单时，完成并验证一条后用删除线保留原条目。技能不提供提交、推送或发布授权；已有授权依开发流程复用。
