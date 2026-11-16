# DouAi 0.2.89 正式版

版本 **0.2.89 / 190**，包名 `io.github.yudigaga.douai`。GitHub 与 LSPosed 同步发布同一长期签名 APK，每端仅附 APK。

## 本版变化

- 扩展播放循环、续播、作者速度规则、评论书签与整理、字幕导出和问答，以及临时观看偏好。
- 液态玻璃与重力感应（抖音 41）：选择液态玻璃主题后，可开启重力感应；导航、作者文案、进度区、右侧操作区及侧边栏可见项目随持机姿态轻微平移与倾斜，触摸时冻结，松手后平滑跟随，视频、输入与字幕保持稳定。
- 完善自定义及内置图标、显示项和选中反馈，修正底部内容避让与进度条触控。
- 修正配置发布与热更新的生命周期衔接，退役旧顶部标签过滤入口，保留已有自动化与语音行为。

## 下载

- [GitHub APK](https://github.com/yudigaga/DouAi/releases/download/v0.2.89/DouAi-0.2.89-release.apk)
- [LSPosed APK](https://github.com/Xposed-Modules-Repo/io.github.yudigaga.douai/releases/download/190-0.2.89/DouAi-0.2.89-release.apk)

文件 `DouAi-0.2.89-release.apk`，6,501,731 字节。

SHA-256：`EBDDAEB59B7F03636E54550A8AE11F3A979018AF3FDE5B278A0D5BC9F0DD29DD`。

## 验证范围

- 对应 886 项冻结源码、资源及构建输入；发布源码与该快照一致。
- 最新完整本地 JVM 验收为 385 套件、4,149 项通过，0 失败、错误或跳过；4,045 个源码方法无缺失。Windows 使用经验证的短临时路径，早期长路径环境的 5 项失败已由同源码对照及完整复验关闭。
- 同源码专项 25 套件、606 项通过；完整 Release Lint 0 错误、55 警告、3 提示。
- R8 Release 构建通过；长期证书 v3 签名、16 KiB ZIP 对齐及 ZIP CRC 已核验。证书 SHA-256 为 `FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47`。
- 源码 CI 独立运行，发布时未确认完成；不把本地结果算作远端 CI。维护者反馈本包“测试没问题”；尚无逐项真机、实际 LSPosed 回调及真实服务的覆盖记录。

本次发布直接使用已验收 APK，未重新构建或签名。历史验收材料保留在本地，不随 Release 上传。

## 使用与责任

安装及适配范围见[项目首页](../README.md)，功能说明见[帮助](help.md)。本项目仅用于学习与技术研究；用户使用行为及结果由其自行承担，与本项目及作者无关，详见[免责协议](../DISCLAIMER.md)。如果喜欢 DouAi，欢迎[请作者喝杯咖啡](../README.md#赞助)。
