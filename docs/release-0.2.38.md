# DouAi 0.2.38 正式版：原生消息流式接入

版本 **0.2.38 / 139**，包名 `io.github.yudigaga.douai`，沿用长期签名和 R8 Release 配置。GitHub 标签为 [`v0.2.38`](https://github.com/yudigaga/DouAi/releases/tag/v0.2.38)，LSPosed 标签为 [`139-0.2.38`](https://github.com/Xposed-Modules-Repo/io.github.yudigaga.douai/releases/tag/139-0.2.38)，两端发布同一份原签名 APK。

## 本次更新

- 恢复消息页 AI 联系人的原生会话入口：使用抖音 `//chat` 页面路由和模块联系人 ID，不再走 0.2.37 的 Flow 实验路径。模块联系人模型 ID、名称和头像继续保留。
- OpenAI Chat / Responses、Claude Messages 与 Gemini 的完整 SSE 文本增量接入现有原生聊天气泡。按所选模型的流式设置实时显示增量，非流式则等完整回复后展示；最终消息进入原生会话。
- 消息对话与本地分析共享协议层，但保持独立的会话历史及取消状态。
- 不声明 40.8.0 Doubao 整页 SSE 能力，本次未建立该场景的调用证据。

继续保留 0.2.35 至 0.2.37 的消息联系人显示、回复浮窗移除后的恢复、评论回复历史分页扫描及 Flow 页面入口适配。

## 安装包

| 项目 | 结果 |
| --- | --- |
| versionName / versionCode | `0.2.38 / 139` |
| 大小 | 5,413,226 字节 |
| SHA-256 | `4ABEC87B6268C82A73C34DCB2AF9B891734629DE11D625753C8C511A7A58004D` |
| 签名 | 长期证书，APK v3 验证通过 |
| 证书 SHA-256 | `FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47` |
| 对齐 | 16 KiB ZIP 对齐检查通过 |
| 下载 | [GitHub APK](https://github.com/yudigaga/DouAi/releases/download/v0.2.38/DouAi-0.2.38-release.apk) · [LSPosed 正式版](https://github.com/Xposed-Modules-Repo/io.github.yudigaga.douai/releases/tag/139-0.2.38) |

## 验证范围

按用户要求，本次只构建签名 Release，未运行测试；未独立运行完整 `lintRelease`，构建自带的 Release vital Lint 通过。没有 0.2.38 对应的专项 JUnit 结果。0.2.37 的测试和其它历史版本结果不累计为本版验证。

已核对包名及版本元数据、APK 与 Gradle 输出 SHA-256 一致、R8 mapping 文件、原证书 v3 签名和 16 KiB 对齐。没有构建前源码哈希冻结清单，构建时源码工作区包含未提交改动；本地签名与构建产物检查不能替代完整源码封存。未进行模拟器、手机安装、真实 LSPosed Hook、真实服务请求或设备流式行为验证。

本地构建记录保留 `published=false`、`tests.run=false`、`fullLint=not-run` 快照；远端标签、CI 及资产核验另存于本地 `artifacts/DouAi-0.2.38-publication.json`，不覆盖原构建证据。

## 免责协议

本项目仅用于学习与技术研究。用户的应用、配置、二次开发及使用行为和结果由用户自行决定并承担责任，与本项目及作者无关。完整边界见 [免责协议](../DISCLAIMER.md)，APK 关于页与 LSPosed 仓库同步保留。
