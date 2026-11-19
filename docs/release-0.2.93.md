# DouAi 0.2.93

版本 **0.2.93 / 194**，包名 `io.github.yudigaga.douai`。

- 整理设置首页分类，合并手势、快捷菜单和侧边栏的重复入口，保留原有开关和已保存配置。“最近配置”可查看宿主最近一次读取和应用配置的状态。
- 改进抖音 41.0.0 的液态玻璃导航布局，以及开启重力感应时的底栏避让和布局恢复。在“界面相关”选择液态玻璃即可使用重力随动；触摸时冻结，松手后恢复。
- 首页内置图标按位置匹配白色线框和填充两态，自定义图标仍可分别导入或恢复。

## 下载

- [GitHub APK](https://github.com/yudigaga/DouAi/releases/download/v0.2.93/DouAi-0.2.93-release.apk)
- [LSPosed APK](https://github.com/Xposed-Modules-Repo/io.github.yudigaga.douai/releases/download/194-0.2.93/DouAi-0.2.93-release.apk)

APK 大小 7,373,943 字节，SHA-256：`317FFF7E020A9F082DB8960C0A96A3B8C677010CA12D5E5B8F1FC5063853051E`。

## 验证

- APK 元数据、R8 合同 7/7、帮助路由 8/8、配置传输测试类复跑 22/22 通过。
- 完整 JVM 测试 4,181 项中 1 项失败、43 项跳过。失败项 `ConfigTransferDialogTest.successfulReadsLogOnlyByteCountAndSha256WithoutDocumentOrConfigurationContents` 未找到 `file_read` 诊断事件；该测试类单独复跑 22/22 通过，因此完整套件仍记为未全绿。
- Release Lint 0 错误、61 警告，告警预算无新增未审项；长期证书 v3 和 16 KiB ZIP 对齐通过。证书 SHA-256：`FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47`。
- 未配置抖音 41.0.0 原始 APK，未运行对应宿主 DEX 合同、设备、LSPosed 或真实服务验收。远端 CI 结果独立核对。

每个 Release 仅附正式 APK。喜欢 DouAi，欢迎[请作者喝杯咖啡](../README.md#赞助)。本项目仅用于学习与技术研究，用户使用行为及结果由用户承担，与项目及作者无关，详见[免责协议](../DISCLAIMER.md)。
