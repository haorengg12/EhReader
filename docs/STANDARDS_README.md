# EhReader 开发规范 V2.1

更新日期：2026-11-03。当前规范采用 master 默认分支、develop 开发分支，以及按阶段读取规范的执行方式。

## 如何使用

开始只读[AI 入口](../AGENTS.md)和[开发任务流程](DEVELOPMENT_FLOW.md)。进入阶段时再读取流程表中的规范，不要求开工时读完所有文件；已读且未变化的内容复用。

每个主题只有一个权威来源：编码风格在 CODING_STANDARDS，本地验证在 LOCAL_DEVELOPMENT，行为测试在 TESTING_STANDARDS，架构在 ARCHITECTURE，授权和分支在 REPOSITORY_WORKFLOW，版本与发行在 RELEASE_POLICY。

## 当前流程

本地开发 → 按改动检查/Debug 打包 → 提交并推送 develop → 监控自动 CI 与开发附件 → 需要发行时准备新版本 → 用户确认具体 develop → master PR 合并 → 固定合并 SHA 正式构建 → 自动 Tag/Release。

develop 不生成 Tag。版本来自 Gradle 配置，CI 不猜测版本或改写源码。每个正式 Tag 对应一个 Release；失败草稿须恢复完整后才公开。规则中的日常推送授权不包含 master 合并、任意设置修改或历史改写。

## 部署与验收边界

- 工作流和脚本随 develop 提交验证，正式发布工作流须获准合入 master 后才能在 master 生效。
- 首次 master 合并前必须准备未用版本；现有 0.1.0 (100) 已发布，不能再次作为新提交发行。
- 正式签名 Secrets 和分支/标签保护需核实；未配置签名即失败，不能称发布已经验收通过。
- 自动化门禁单元测试与 develop CI 不等于正式发布端到端验收；合并前不通过制造 Tag/Release 来测试。
- 参考流程来自“模拟 EhReader 开发流程”，其中假设成功的结果不属于真实测试证据。

## 文档维护

只维护当前 Markdown，不生成规范压缩包、不另存旧版副本；历史由 Git 记录。工作区 AGENTS.md 只引用仓库入口。UTF-8 无 BOM、LF、文件末尾换行；编码脚本覆盖规范，变更后检查链接、阶段路由及规则一致性。
