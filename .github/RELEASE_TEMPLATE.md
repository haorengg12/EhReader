<div align="center">

# EhReader {{tag}}

[![Release Downloads](https://img.shields.io/github/downloads/{{repository}}/{{tag}}/total?style=flat-square&logo=github)](https://github.com/{{repository}}/releases/tag/{{tag}})

</div>

## Android 下载

| 架构 | 下载 | 适用设备 |
| --- | --- | --- |
| arm64-v8a | [![APK ARMv8](https://img.shields.io/badge/APK-ARMv8-168039.svg?logo=android)]({{download_base}}/EhReader-{{version}}-android-arm64-v8a.apk) | 主流 64 位手机，推荐 |
| armeabi-v7a | [![APK ARMv7](https://img.shields.io/badge/APK-ARMv7-45bf55.svg?logo=android)]({{download_base}}/EhReader-{{version}}-android-armeabi-v7a.apk) | 旧款 32 位 ARM 设备 |
| x86_64 | [![APK x64](https://img.shields.io/badge/APK-x64-96ed89.svg?logo=android)]({{download_base}}/EhReader-{{version}}-android-x86_64.apk) | x86_64 设备或模拟器 |
| universal | [![APK Universal](https://img.shields.io/badge/APK-Universal-0078D6.svg?logo=android)]({{download_base}}/EhReader-{{version}}-android-universal.apk) | 不确定架构时选择，体积较大 |

## 更新内容

{{changes}}

{{history}}

## 构建与校验

- 版本：{{version}}；versionCode：{{version_code}}；Tag：{{tag}}
- 来源提交：[{{sha}}](https://github.com/{{repository}}/commit/{{sha}})
- [构建记录]({{run_url}})
- [SHA-256 校验清单]({{download_base}}/SHA256SUMS.txt) / [验证说明]({{download_base}}/VALIDATION.txt)
- 构建与静态检查通过；设备运行验证未执行，测试覆盖以验证说明为准。

## 源码与许可

[对应源码（含原生依赖）]({{download_base}}/EhReader-{{version}}-source.zip) · [GPLv3]({{download_base}}/LICENSE) · [版权与致谢]({{download_base}}/NOTICE)
