# EhReader 开发流程图

更新日期：2026-11-14。阶段见[开发流程](DEVELOPMENT_FLOW.md)，版本算法见[发布规范](RELEASE_POLICY.md)，权限见[仓库管理](REPOSITORY_WORKFLOW.md)。模型选择参考[节点推荐组合](DEVELOPMENT_FLOW.md#节点推荐组合)，不改变授权条件。

```mermaid
flowchart TD
 A[1. 核对任务、分支与工作区] --> B[2. 本地开发与版本准备]
 B --> V{版本目标是否明确}
 V -->|缺测试包或上传版本| W[只等待缺少决定，继续无关本地工作]
 W --> B
 V -->|默认不升版或已确认版本| C[3. 必要检查与最终材料]
 C --> OK{必要检查通过}
 OK -->|否| REC[按失败分类留证，修复或暂停]
 REC --> B
 OK -->|是| D[4. 最终审核单与提交前记录]
 D --> LC[5. 代码任务按持续授权在 develop 本地提交]
 LC --> UP{明确要求上传且版本与测试条件满足}
 UP -->|否| LOCAL[仅本地交付]
 LOCAL --> Z[9. 核对记录与真实结果]
 UP -->|是| PLAN[固定 origin/develop 基准与源码 SHA，核对上传计划]
 PLAN --> P{最高变化位}
 P -->|第四位或源码仍四段| BLOCK[仅本地；上传须先确认递增三段版本]
 BLOCK --> Z
 P -->|第三位| SNAP[推送新 develop-六位UUID 分支]
 SNAP --> VERIFY[核对远端 SHA；不运行 CI、不建 Tag/PR]
 VERIFY --> Z
 P -->|不升版三段| SAVE[获准推 develop，仅基础检查]
 SAVE --> Z
 P -->|第一或第二位| PUSH[推 develop，跟踪基础检查与 Debug 构建]
 PUSH --> CI{同 SHA 构建成功}
 CI -->|否| REC
 CI -->|是| LEVEL{最高变化位}
 LEVEL -->|第二位| REPORT[只汇报 CI 和附件]
 REPORT --> Z
 LEVEL -->|第一位| PR[7. 助手创建 develop 到 master PR]
 PR --> GATE{固定输入、版本、只读预检和实际保护均满足}
 GATE -->|否| STOP[停止合并并报告，复核受影响内容]
 STOP --> Z
 GATE -->|是| MERGE[无需二次确认，以 expected head SHA 执行 Merge commit]
 MERGE --> RELEASE[8. 固定 merge SHA 正式构建、原证书签名和包校验]
 RELEASE --> PASS{附件和发布校验通过}
 PASS -->|否| FAIL[停止发行，按发布规范恢复]
 FAIL --> Z
 PASS -->|是| PUB[正式 Tag、完整上传并公开 Release]
 PUB --> Z
```

- 多位变化只取最左侧变化位；右侧允许改变，第四位仍仅本地，上传前三段须合法提升，安装编号独立递增。
- 第一位的版本确认与本次明确上传覆盖助手连续创建 PR、Merge commit 和发布验收，不再要求二次合并批准；CI 成功不补足尚未取得的上传许可。
- 第二位只构建并汇报，第三位只保存新临时分支且没有任何远端 CI，第四位只在本地按需 ARM64 Debug；上述路径都不新建开发 Tag。
- 本地必要检查不因远端不运行 CI 而免除；失败、取消、NO-SOURCE、未运行分别记录。复用与恢复见开发流程，不绕过保护或强推。
- 当前任务仅本地适配，部署状态见[适配清单](RELEASE_POLICY.md#尚待实现的适配)。提交前写已知记录，提交后 SHA/CI 留 temp 或 CI summary，不循环提交。
