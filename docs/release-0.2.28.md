# DouAi 0.2.28 正式版：Fish 与 Breeze 音色标签及搜索

版本 **0.2.28 / 129**，包名 `io.github.yudigaga.douai`，沿用长期签名及 R8 Release 配置。GitHub 标签为 [`v0.2.28`](https://github.com/yudigaga/DouAi/releases/tag/v0.2.28)，LSPosed 标签为 [`129-0.2.28`](https://github.com/Xposed-Modules-Repo/io.github.yudigaga.douai/releases/tag/129-0.2.28)，两端发布同一份 APK。

## 本次更新

- Fish Audio 和 Breeze Blue 音色目录新增供应商原生标签筛选。两家筛选条件、参数和多值语义各自独立；Fish 不套用 Breeze 的性别、年龄或语气枚举。
- 已加载列表可按中文译名、原名、音色 ID 和标签本地搜索；需要扩大全库范围时，可携带当前完整筛选条件发起“搜索全部音色”。翻页与快捷音色选择都校验供应商和查询条件缓存，避免展示旧筛选结果。
- 按需使用当前启用的文字 AI 翻译音色名称。成功显示中文译名并保留原名和 ID；只发送名称、不自动调用或重试、不读写聊天历史。目录、筛选、账户或文字模型切换后，拒绝过期回调。
- 原有 Fish 公开目录窗口、Breeze 大目录逐页浏览、失败保留、取消与配置隔离继续保留。

详细设置和供应商参数见 [Fish Audio 说明](fish-speech.md)、[Breeze Blue 说明](breeze-speech.md) 与[帮助文档](help.md)。

## 安装包

| 项目 | 结果 |
| --- | --- |
| versionName / versionCode | `0.2.28 / 129` |
| 大小 | 5,379,561 字节 |
| SHA-256 | `FEE0709E6FF10FD4B111B6C93E72337A8D617626B4FD3242EE8321C624AABDE8` |
| 签名 | 长期证书，APK v3 验证通过 |
| 证书 SHA-256 | `FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47` |
| 对齐 | 16 KiB ZIP 对齐检查通过 |
| Release Lint | 0 错误、54 条既有警告、0 新增警告 |
| 下载 | [GitHub APK](https://github.com/yudigaga/DouAi/releases/download/v0.2.28/DouAi-0.2.28-release.apk) · [LSPosed 正式版](https://github.com/Xposed-Modules-Repo/io.github.yudigaga.douai/releases/tag/129-0.2.28) |

## 验证范围

维护者已人工测试并确认没有问题。自动化专项捕获未完成最终校验：捕获记录退出码为 1，未生成 JUnit XML，也没有最终专项封存清单，因此本版不报告自动化测试通过数。Release 构建、包名与版本元数据、原证书 v3 签名、16 KiB 对齐及 Lint 预算有独立记录；这些结果不替代自动化专项或设备验收。

本版未重跑完整全量测试或 40.6.0 补充合同。真实 TTS 服务、实际 LSPosed 回调和手机覆盖范围以维护者手动测试所覆盖的场景为限；本文不将本地合同或 Lint 结果表述为更广泛的兼容性保证。

## 免责协议

本项目仅用于学习与技术研究。用户的应用、配置、二次开发及使用行为和结果由用户自行决定并承担责任，与本项目及作者无关。完整责任边界见 [免责协议](../DISCLAIMER.md)，APK 关于页与 LSPosed 仓库同步保留。
