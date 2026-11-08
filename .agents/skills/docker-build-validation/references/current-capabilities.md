# 现有 Docker 入口的能力边界

2026-11-06 按本地脚本静态核对；本表不是本次构建或设备验收结果。入口目前保留在仓库外的 `build-tools/ehreader-docker`，本任务不迁入仓库、不改变分发方式；另一台机器仅克隆 EhReader 不会获得这些工具。后续如决定版本化或分发，须明确脚本范围、缓存/大文件排除及迁移路径。

本表按需帮助定位实现，验收要求统一引用[本地开发规范](../../../docs/LOCAL_DEVELOPMENT.md#docker-检查与验收)，不在这里另定标准。脚本变化后重新核对。

| 能力 | 当前实现及边界 | 使用时需补查 |
| --- | --- | --- |
| 构建模式与参数 | [build.ps1](../../../../../build-tools/ehreader-docker/build.ps1) 提供 Debug、ReleaseAll、Source、OutputRoot、SigningDirectory 和 OfflineDependencies；默认 Debug | 不存在 ARM64 Release 模式；ReleaseAll 会挂载正式签名目录，不能当作默认构建或替代模式 |
| 宿主隔离与卷 | build.ps1 只读挂载源码和工具；[build.sh](../../../../../build-tools/ehreader-docker/build.sh) 用 rsync 同步到 Linux 卷，排除 .gradle/.cxx/build/local.properties；工作卷和缓存卷名固定，Debug home 持久化 | 启动前核对没有同卷并发；宿主前后状态/内容未自动比较 |
| 工具链 | [Dockerfile](../../../../../build-tools/ehreader-docker/Dockerfile) 固定基础镜像摘要、JDK 21、Node 22、SDK/Build Tools 36、NDK 29、CMake 3.22.1；运行输出 Java/Node/Python 版本 | 运行镜像 ID/摘要和完整实际工具版本清单未自动导出；不能仅凭 Dockerfile 证明实际镜像相同 |
| 依赖离线 | Debug 将 OfflineDependencies 转为 Gradle --offline；[maven-cache.py](../../../../../build-tools/ehreader-docker/maven-cache.py) 整理缓存并可能下载 Linux AAPT2；CMake 仍可能下载原生源码 | 缓存不足须如实报告；此参数不是禁止容器一切联网 |
| 本地初始化与文件覆盖 | 入口复制 build-use-file 中的 .gradle 脚本，Debug 显式加载 local-cache.init.gradle；full-abis 仅供获准专项模式使用 | 不会按仓库相对路径覆盖 app/build.gradle.kts 等任意文件；缺基线/替换散列和路径越界自动校验 |
| 输入记录 | build.sh 输出 source-sha.txt 与容器副本的 source-status.txt，包含未提交源码输入 | 缺 dirty 文件内容清单/散列、宿主前后完整性比较及替换清单；容器状态不能替代宿主状态 |
| Debug 检查 | build.sh 执行 Python 编码检查、Node 脚本测试、Spotless、testDebugUnitTest、显式 lintDebug、assembleDebug、仓库 Debug APK 检查和 Nettle 原生源码检查 | 逐项核对真实输出与警告；NO-SOURCE 无业务测试覆盖；Node 文档检查器的默认文档扫描需另行执行 |
| 包与签名 | [verify.py](../../../../../build-tools/ehreader-docker/verify.py) 检查 APK 数量、包名、版本、实际 ABI、签名有效性和 zipalign；Debug 要求仅 arm64-v8a，输出 APK 散列 | Debug 未比较预期证书指纹；正式模式另有证书比较，不因此获得正式密钥使用授权 |
| 日志和附件 | build.ps1 以 OutputRoot 创建时间戳目录并写 build.log；verify.py 导出 APK、SHA256SUMS、VALIDATION、reports/test-results | 默认 OutputRoot=release，会混入日志；可传 temp 下本次目录，再将已验证的交付文件整理到 release；不自动迁移历史目录 |
| 最终验收 | VALIDATION 写明设备未运行和本地包非正式发行 | 不自动生成完整镜像/输入/替换/前后比较报告；设备验收、最终证据报告及 ACCEPTANCE 记录需补齐 |

## 缺项处理

- 可以用实际只读检查补齐镜像、散列、签名指纹及宿主前后比较证据；在报告中区分自动提供和人工补查。
- 所需能力尚未实现时，先说明受影响项。当前任务范围包含入口适配的，完成适配与验证；否则报告阻塞及已完成部分，不声称完整验收通过，也不擅自迁移脚本或提升构建模式。
- 失败后保留本次 temp 诊断，不把部分 APK 或历史 PASS 当作本次有效交付；不因失败删除固定卷、还原宿主源码或重签名。
