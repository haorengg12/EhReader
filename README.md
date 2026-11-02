# EhReader

EhReader 是 haorengg12 维护的 EhViewer 衍生项目，初始化版本为 **0.1.0 (100)**。
开发仓库为 [haorengg12/EhReader](https://github.com/haorengg12/EhReader)，开发分支为 `dev`。

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
使用 Gradle Wrapper（Windows 使用 `gradlew.bat`）：

```sh
./gradlew spotlessCheck assembleDebug lintDebug testDebugUnitTest
./gradlew assembleRelease
```

SDK 路径通过本机 `local.properties` 或 `ANDROID_HOME` 配置。
未配置签名时 Release 为 **unsigned** 产物，不能直接安装或作为正式发行包。
Debug 使用 Android 标准调试签名，仅用于开发。

正式签名须在受信任的本地环境中同时提供以下环境变量：

| 环境变量 | 含义 |
| --- | --- |
| `EHREADER_KEYSTORE_PATH` | 仓库外的私有 keystore 绝对路径 |
| `EHREADER_STORE_PASSWORD` | keystore 密码 |
| `EHREADER_KEY_ALIAS` | 签名密钥别名 |
| `EHREADER_KEY_PASSWORD` | 密钥密码 |

部分配置或无效 keystore 路径会使构建失败。不要将私钥、密码或含密码的配置提交到 Git。
已移除当前源码树中的上游公开 keystore 与硬编码密码；为保留历史，旧提交仍含该公开密钥。
它不得用于 EhReader 正式签名，必须新建并妥善备份独立私钥。

CI 对 `dev`、`feature/**` 和面向 `dev` 的 PR 运行检查，上传明确标记的 debug 产物。
原 Release 工作流仅允许手动在 `dev` 构建 unsigned APK，无标签触发、无 GitHub Release 发布步骤，
也不读取签名 secrets。正式 Release 在 dev 上完成检查、使用独立私钥签名并验证 APK 后手动发布，
仅使用 EhReader 自己的版本标签（首版为 `v0.1.0`），不得复用上游签名或标签。

Windows 中文路径可在本机命令中加 `"-Pandroid.overridePathCheck=true"`；不要因此跳过编译检查。
仓库通过 `.gitattributes` 统一文本为 LF，避免 Windows 与 CI 的格式检查产生分歧。

## 许可证与版权

本项目按 [GNU GPLv3](LICENSE) 分发。所有上游版权、文件许可证和第三方声明保持有效，
详见 [NOTICE](NOTICE) 及源码文件头；haorengg12 不主张上游代码的版权。
Copyright (C) 2026 haorengg12 仅适用于新增原创修改。
分发二进制时应同时提供相应版本的完整对应源码、构建说明及许可证，并履行 GPLv3 义务。
