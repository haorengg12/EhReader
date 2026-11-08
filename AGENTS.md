# EhReader AI 协作入口

适用仓库：haorengg12/EhReader。规范更新日期：2026-11-07。

请按本入口及其引用规范执行。开始只读本文件和[开发任务流程](.agents/docs/DEVELOPMENT_FLOW.md)，确认任务所在阶段、范围和验证计划；进入下一阶段前再读对应规范，不在开工时批量读取全部文档。同一任务内已读且未变化的内容直接复用。

## 最小执行约束

- 模型与推理等级参考[开发流程节点推荐组合](.agents/docs/DEVELOPMENT_FLOW.md#节点推荐组合)；默认沿用 GPT-6.1 Sol + High，需要升级时给出理由，由用户决定是否切换。
- 先检查分支和工作区，保留其他任务改动。默认分支 master，日常开发分支 develop。
- 每次任务按流程推进；跳过不适用阶段须说明原因，不把模拟、未执行、NO-SOURCE 或失败称为通过。
- 工作区目录按[本地开发规范](.agents/docs/LOCAL_DEVELOPMENT.md#工作区目录约定)管理：规范在仓库内 `.agents/docs`，本地 arm64-v8a 产物放工作区 `release`，临时文件放 `temp`，废弃文件或目录回收到 `trash`；两个根目录 AGENTS.md 保留为入口。临时日志、排查记录等按[临时文件规则](.agents/docs/DEVELOPMENT_FLOW.md#临时文件存放)管理。
- 版本默认策略、审核单和失败恢复按[开发流程](.agents/docs/DEVELOPMENT_FLOW.md)；提交推送及已有授权复用以[仓库管理](.agents/docs/REPOSITORY_WORKFLOW.md#状态与授权范围)为准。未要求上传则本地交付，不自行 commit/push；实质变更重新核验受影响结果，不沿用旧包验收、不强推。
- 版本算法、基础验证/打包分流及部署状态只在[发布规范](.agents/docs/RELEASE_POLICY.md)维护。master 合并始终另需确认具体提交及版本；仅本仓库 develop → master 的已合并 PR 事件可正式发行。
- 版本、签名或发布条件不满足时失败停止；不得用调试签名冒充正式包，不改写既有 Tag。
- 保留 GPL、上游版权和 Git 历史。旧仓库 haorengg12/EhViewer 不在写入范围。
- 记录的生成、追加与提交后证据按[流程记录要求](.agents/docs/DEVELOPMENT_FLOW.md#最后一步追加到-acceptancemd)，保留历史及实际结果，不为追写 SHA/CI 制造循环提交。

授权细节在提交阶段读取[仓库管理](.agents/docs/REPOSITORY_WORKFLOW.md)。用户当次明确指示优先；有实质冲突只暂停受影响操作。文档是执行要求，不代表远端技术门禁或签名配置已经就绪。

文档维护、迁移及导航更新可使用项目技能 [docs-maintenance](.agents/skills/docs-maintenance/SKILL.md)，引用现有规范并运行仓库内检查入口。
明确要求本地打包或核验产物时可使用 [docker-build-validation](.agents/skills/docker-build-validation/SKILL.md)，先核对现有入口能力，默认 ARM64 Debug。
