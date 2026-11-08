# DouAi 0.2.48 正式版：AI 视频封面恢复

版本 **0.2.48 / 149**，包名 `io.github.yudigaga.douai`，沿用长期证书与 R8 Release。GitHub [`v0.2.48`](https://github.com/yudigaga/DouAi/releases/tag/v0.2.48)、LSPosed [`149-0.2.48`](https://github.com/Xposed-Modules-Repo/io.github.yudigaga.douai/releases/tag/149-0.2.48) 仅上传同一份 APK。

## 本次修复

相对 0.2.41，本次累计完善评论 AI 搜索与原生解析、作品媒体上下文、追问和相关视频分页；以下为 0.2.48 的封面修复。

用户截图显示部分视频封面灰色占位，但标题和作者正常。新日志确认搜索/正文完成，旧封面读取器没有失败阶段记录，不能逐张确认具体原因。本轮修复已确认的代码缺口：

- 保留最多八个安全封面候选，同组 CDN 备用、缩略/原始静态封面和图文首图按核实来源回退；无效首地址不再连带丢弃后面的有效地址。
- 封面统一两路有界排队，队列满不丢需求，完成后继续补发；失败最多自动再试一轮，再失败可点封面位置重试，滚动重绑后仍保留重试入口。
- 源图最多 16 MiB，直接解码到 128px 缩略图，保留方向与白底，避免原先 2 MiB 和 AI 附件中转造成的额外失败。
- 停止当前回复不再取消旧消息封面；清空/关闭仍完整取消，迟到图片不能贴到新视图。
- 新增安全封面阶段、HTTP 状态和候选次数日志，保留原始地址与问答隐私。

手动聊天和 AI 联系人共用修复。视频分页、模型资料限制、评论解析、自动化及语音策略未改变。

安装后彻底退出并重新启动抖音，再重新搜索一次复验。过期地址、服务拒绝或作品确实没有可用图片时仍可能无法加载；此时可点封面位置重试。

## 安装包

[GitHub 下载 APK](https://github.com/yudigaga/DouAi/releases/download/v0.2.48/DouAi-0.2.48-release.apk) · [LSPosed 正式版](https://github.com/Xposed-Modules-Repo/io.github.yudigaga.douai/releases/tag/149-0.2.48)

| 项目 | 值 |
|---|---|
| APK | `DouAi-0.2.48-release.apk` |
| versionName / versionCode | `0.2.48 / 149` |
| 大小 | 5,584,708 字节 |
| SHA-256 | `A06F95FAB9D522324E7D2EB30F25BA893348390E52C941F20426D27AC28A74B3` |
| 签名 | v3 通过，长期证书 |
| 证书 SHA-256 | `FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47` |
| 对齐 | `zipalign -c -P 16 -v 4` 通过 |

## 核验范围

最终 `:app:assembleRelease` 成功，用时 2 分 7 秒，附带 `lintVitalRelease` 通过。已核对非调试、包名/版本、长期证书与 v3 签名、16 KiB ZIP 对齐、包内新读取器/诊断标记、构建与归档哈希；619 个源码/构建/测试输入快照一致。

独立静态复核发现的错位重试丢唤醒、失败行重绑丢入口两项已修复并再次静态复核。`AiTestChatDialogTest` 本地定向测试通过；完整测试、独立 Release Lint 和 APK 合同未在本地运行，构建自带 vital Lint 通过。用户反馈人工测试通过，设备版本与覆盖范围未记录；真实图片、模型和抖音服务验证范围未单独记录。
