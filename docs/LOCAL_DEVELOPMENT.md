# 本地开发规范 — V2.1

本文件定义开发机验证和 Debug 打包。正式发布及密钥使用见 [发布规范](RELEASE_POLICY.md)。本地常规检查和 Debug 构建纳入已授权开发任务，不再要求每次额外说明“先本地编译”；纯文档整理不执行编译。

## 开始任务

1. 检查工作区状态和分支，先读 AGENTS.md 和流程导航，再读取当前阶段规范与现有实现；不得覆盖其他任务改动。
2. 说明影响范围、接口或数据兼容风险及验证项目。小改动采用简短说明。
3. 检查已有工具链；项目当前使用 JDK 21、SDK 36、Build Tools 36.0.0、NDK 29.0.14206865、Gradle Wrapper。实际版本以项目配置为准。
4. Windows 建议使用英文源码和 SDK 路径。路径检查开关不能修复所有 CMake/Ninja 兼容问题；不得以跳过原生编译冒充完整构建。
5. 环境安装、移动工作区或改变系统配置有实质影响时先说明并获取必要许可；不因环境问题静默修改项目工具链。

## 按变更选择验证

| 变更 | 本地必要检查 |
| --- | --- |
| 纯文档或规范 | 编码、链接、内容一致性、git diff --check；不编译 Android |
| Kotlin/Java 或业务行为 | Spotless、相关单元测试、Debug 编译；按影响运行 lint |
| UI、资源、Manifest、依赖或构建配置 | Spotless、Debug 构建、lint、适用测试 |
| 数据库、下载或文件操作 | 上述检查加测试规范规定的回归验证 |
| 仅工作流 | YAML/脚本静态检查、触发条件和权限审查；实际运行由获准推送后的 CI 验证 |

Windows 常用入口（按需要选择，不要求每次全部重跑）：

```powershell
python scripts/check_standards_encoding.py
node --test scripts/*.test.mjs
git diff --check
.\gradlew.bat spotlessCheck
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug lintDebug
python scripts/check_debug_apks.py
node scripts/native-sources.mjs
```

Linux CI 使用 ./gradlew。Python 编码脚本只检查文本，不是编译命令；Python 不可用时可做等效逐字节检查并如实记录。

## 本地打包与失败处理

- 本地默认只生成 arm64-v8a 的 Debug 包，位于 app/build/outputs/apk/debug/，用于开发验证，不作为正式发行附件。获准的本地 Release 也默认仅 ARM64，不生成通用包；不得为了绕过限制在本地伪造 GITHUB_ACTIONS=true。
- GitHub Actions 按完整架构构建：arm64-v8a、armeabi-v7a、x86_64 及 universal；Debug 和 Release 均适用。构建配置以 GitHub Actions 环境区分本地与远程，修改架构集合需同步发布附件校验。
- Nettle 下载源码位于各 ABI 的 `.cxx/<配置>/<构建标识>/<ABI>/_deps/nettle`，不得改回共享源码树目录。`native-sources.mjs` 从实际 CMakeCache 核对目录隔离、ABI 覆盖与源码一致性，正式源码附件也复用该检查。AGP 正式变体通常使用 `RelWithDebInfo`；Release 审计兼容该目录及 `Release`、`MinSizeRel`，不能假设目录一定叫 Release。
- 本地 Release 问题复现或正式密钥操作需单独说明用途并获准；正式发行包必须由 master 对应提交的 CI/CD 生成。
- 记录实际命令、退出状态、产物和未验证事项。一次失败不得直接跳过或关闭检查；任务代码导致的失败先修复。
- 若仅受工具链或网络阻碍，报告原因与已完成检查；获得用户针对本次例外的许可后，才可跳过本地阻塞项并推送 develop 交由 CI 验证。不得称本地检查已通过。
- 验证通过且无新变化时不反复全量构建。提交只包含本次任务文件；产物、缓存、私钥和密码不得进入 Git。
