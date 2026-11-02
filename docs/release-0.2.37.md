# DouAi 0.2.37 本地签名构建

版本 **0.2.37 / 138**，2026-11-02 本地签名 Release 构建，未发布 GitHub 或 LSPosed。

40.8.0 上，消息页 AI 联系人现在打开抖音 Flow `//flow/chat` 页面，并把被点击的模块模型 ID 保存为当前 Flow 联系人选择。40.6.0 继续使用模块聊天窗口。当前 Flow 插件是独立动态下载组件；此构建尚未替换其模型回复或头像绑定。

## APK

- 文件：`artifacts/DouAi-0.2.37-release.apk`
- 包名：`io.github.yudigaga.douai`
- 大小：5,413,203 字节
- SHA-256：`FC5BBB732A558A59CD79226B85612E2718CF177093B7B2A957501864B0E105E7`
- 长期证书 SHA-256：`FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47`
- v3 签名和 16 KiB ZIP 对齐核验通过；构建 APK 与归档 APK 的 SHA-256 一致。
- 严格构建归档：`artifacts/release-verification/20261102T135412Z-1766ad27/`

## 验证范围

Release 构建和构建自带的 Release vital Lint 通过。`FlowChatLauncherTest` 3 项、`AiMessageEntryHookTest` 9 项在构建前通过。按用户要求，未运行完整 JUnit 回归或独立 `lintRelease`；没有连接测试设备，未做 LSPosed 或 Flow 页面运行时验证。未创建远端 Release。
