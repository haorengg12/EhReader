# EhReader

EhReader 是 haorengg12 维护的 EhViewer 衍生项目，初始化版本为 **0.1.0 (100)**。
开发仓库为 [haorengg12/EhReader](https://github.com/haorengg12/EhReader)，默认分支为 `master`，开发分支为 `develop`。

基于 [EhViewer-NekoInverter/EhViewer](https://github.com/EhViewer-NekoInverter/EhViewer)
提交 `9e1021640472ee45f35c0bb1950723db08549b9e`，保留完整 Git 历史。
[原始 README](docs/README.upstream.md) 按基线保存，其中下载、徽章和链接属于上游项目。

## 应用标识与版本

- applicationId：`com.haorengg12.ehreader`；debug：`com.haorengg12.ehreader.debug`。
- namespace 保留 `com.hippo.ehviewer`，与安装包名独立。
- versionName：`0.1.0`；versionCode：`100`。
- 新包名从 100 独立计数，每次发布（包括预发布）必须使用更大的 versionCode，不复用旧值。

## 构建与签名

需要 JDK 21、Android SDK 36、Build Tools 36.0.0、NDK 29.0.14206865 和 CMake。
本地检查和 Debug 打包按 [本地开发规范](docs/LOCAL_DEVELOPMENT.md) 选择；正式包由 CI/CD 构建。Windows 使用 `gradlew.bat`，CI 使用 `./gradlew`：

```sh
./gradlew spotlessCheck assembleDebug lintDebug testDebugUnitTest

```

SDK 路径通过本机 `local.properties` 或 `ANDROID_HOME` 配置。
未配置签名时 Release 为 **unsigned** 产物，不能直接安装或作为正式发行包。
Debug 使用 Android 标准调试签名，仅用于开发。
本地默认仅构建 arm64-v8a；GitHub Actions 构建 arm64-v8a、armeabi-v7a、x86_64 和 universal，Debug/Release 均遵守这一架构划分。

正式签名由 CI/CD 的受保护凭据提供以下环境变量，私钥和密码不得进入 Git 或公开日志：

| 环境变量 | 含义 |
| --- | --- |
| `EHREADER_KEYSTORE_PATH` | 仓库外的私有 keystore 绝对路径 |
| `EHREADER_STORE_PASSWORD` | keystore 密码 |
| `EHREADER_KEY_ALIAS` | 签名密钥别名 |
| `EHREADER_KEY_PASSWORD` | 密钥密码 |

部分配置或无效 keystore 路径会使构建失败。不要将私钥、密码或含密码的配置提交到 Git。
已移除当前源码树中的上游公开 keystore 与硬编码密码；为保留历史，旧提交仍含该公开密钥。
它不得用于 EhReader 正式签名，必须新建并妥善备份独立私钥。

开发时先读 [AGENTS.md](AGENTS.md) 和[流程导航](docs/DEVELOPMENT_FLOW.md)，按阶段读取规范。develop 推送自动生成 Debug 附件，不创建 Tag；用户确认 develop → master 的 PR 合并后，正式 CI 按版本生成 Tag 和 Release。工作流须经批准合入 master 才生效，正式签名配置和端到端验收以实际结果为准。既有 v0.1.0 发布历史保留。

## 许可证与版权

本项目按 [GNU GPLv3](LICENSE) 分发。所有上游版权、文件许可证和第三方声明保持有效，
详见 [NOTICE](NOTICE) 及源码文件头；haorengg12 不主张上游代码的版权。
Copyright (C) 2026 haorengg12 仅适用于新增原创修改。
分发二进制时应同时提供相应版本的完整对应源码、构建说明及许可证，并履行 GPLv3 义务。
