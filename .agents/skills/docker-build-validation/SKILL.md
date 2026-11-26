---
name: docker-build-validation
description: 在 EhReader 明确要求本地 Docker 打包、生成测试 APK 或核验本地产物时使用，核对 ARM64 Debug 构建输入、现有入口能力和交付证据。不用于纯文档检查或远端正式发行。
---

# EhReader Docker 构建与验收

先读仓库 AGENTS.md 和[开发流程](../../docs/DEVELOPMENT_FLOW.md)，本地构建规则以[本地开发](../../docs/LOCAL_DEVELOPMENT.md#docker-检查与验收)为唯一来源，行为回归按[测试规范](../../docs/TESTING_STANDARDS.md)。不要把历史成功、源码审阅或技能验证写成本次构建成功。

## 确定本次输入

核对分支、SHA、工作区未提交状态及版本意向；按变更选择检查。准备最终版本后再打包。默认本地仅 ARM64 Debug；Release、全架构和正式密钥按[发布边界](../../docs/RELEASE_POLICY.md#发布边界)另行判断授权，不能因入口提供 ReleaseAll 就使用它代替 ARM64 Release。

执行前读[当前能力与缺项](references/current-capabilities.md)，检查工作区 `build-tools/ehreader-docker` 是否存在并审阅本次实际使用的脚本；独立克隆没有这些文件时报告缺失，不根据路径示例自行下载或重建工具链。确认 Docker daemon、现有镜像和固定工作卷无并发占用，保存实际镜像 ID 与工具版本。不要为查版本触发镜像拉取或升级。

在工作区 `temp/<本次任务>` 留存宿主源码状态及必要文件散列，构建后比较，保留其他任务改动。存在初始化脚本或替换文件时，记录目标、用途、当前原文件及替换文件 SHA-256；原文件更新后重新审阅。现有入口不支持任意相对路径覆盖，任务确需该能力时先适配并验证入口，不能先改原仓库再还原。

## 使用已实现的入口

从 EhReader 仓库根目录执行以下示例，将本次日志及中间检查报告先放入 temp；路径从当前仓库推导：

```powershell
$repoRoot = (Get-Location).Path
$workspaceRoot = Split-Path $repoRoot -Parent
$runRoot = Join-Path $workspaceRoot ('temp/' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-docker-validation')
New-Item -ItemType Directory -Path $runRoot | Out-Null
$builder = Join-Path $workspaceRoot 'build-tools/ehreader-docker/build.ps1'
& $builder -Mode Debug -Source $repoRoot -OutputRoot $runRoot -OfflineDependencies
```

`-OfflineDependencies` 只限制 Gradle 依赖解析，缓存缺失、AAPT2 或原生源码下载仍可能联网；按实际网络条件选用并报告。现有脚本抛出构建失败时停止产物成功交付，不跳过检查。不得自动启用 ReleaseAll、改签名、伪造 GITHUB_ACTIONS 或清空缓存绕过失败。

入口已运行的检查见能力表；额外需要的回归按变更补做。核对实际任务输出、退出状态、警告和 NO-SOURCE，不能只看最后的 VALIDATION.txt。Debug 签名验证有效不等于已与预期调试证书指纹比较，首次基线和后续更新兼容性须实际核对。

## 交付与记录

只有本次构建及必要包检查成功后，才将本次 APK、SHA256SUMS、来源清单及最终验收报告整理到工作区 `release/<本次构建>`。直接使用入口的默认 OutputRoot 会把日志也放进 release；以上示例利用已有参数避免这一点，无需迁移历史材料。日志、lint/test 报告等留在 temp 并由最终报告引用。

来源清单补齐实际命令/退出状态、镜像与工具版本、Git SHA/dirty 状态、输入和替换散列、宿主构建前后比较、实际包身份/版本/ABI/证书及对齐结果。仅有 SHA 和 source-status.txt 不能完整标识未提交内容。无法补齐的证据明确列为未验证；包生成成功与完整验收分别给出结论。

设备安装、启动与功能回归单独记录，设备不可用时写未执行。按[记录要求](../../docs/DEVELOPMENT_FLOW.md#最后一步追加到-acceptancemd)追加结论；人工测试安排及提交推送继续按开发流程和已有授权处理。该技能不创建 Tag/Release、不取得 master 合并权限。
