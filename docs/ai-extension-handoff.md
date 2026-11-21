# DouAi AI 扩展候选交接

工作树 `E:\vs\claude\douai\work\ai-extension`，分支 `codex/ai-extension`，基线为 `51a04f8f680c1ea8d25c281e629d93a1361fb48e`（0.3.1 / 202）。

## 阶段 1 · 聊天 Skills

- `AiConversationDialog.java`、`ModuleSettingsScreen.java` 分别从 AI 联系人、侧栏的「AI 对话」启用共享能力。模型设置中的测试对话继续使用原路径。
- `AiTestChatDialog.java` 与 `AiSkills.java` 共享命令匹配、菜单、执行与停止逻辑。`/摘要 文本` 在本地提取前两句，`/查询 DA-1001` 返回模拟订单状态。技能结果只显示在当前对话，不进入模型历史。两个内置技能只接收文本。
- 对应测试在 `AiSkillsTest.java`、`AiTestChatDialogTest.java`、`NativeAiConversationTest.java` 和 `ModuleSettingsActionSeparationTest.java`。

```powershell
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain --tests '*AiSkillsTest' --tests '*AiTestChatDialogTest' --tests '*NativeAiConversationTest' --tests '*ModuleSettingsActionSeparationTest' --tests '*AiModelContactConfigTest'
```

专项测试 70 项通过，无失败或跳过。待独立验收抖音真机的两种聊天布局、软键盘及菜单交互。

## 阶段 2 · 自动生成和服务登记

- `AiSkillSettingsDialog.java`、`ModuleSettingsScreen.java` 提供总开关、按已有模型 ID 生成、离线模拟生成、停用、启用、回退和服务登记。生成预览列出首次或变更后的真实服务目标和费用，再由用户确认。
- `SkillStore.java` 校验文本声明、参数及模拟预期，分别写版本化 `SKILL.md` 和 `execution.json`，再原子切换目录索引。试跑失败保留原版本。内置和自定义技能共享两处入口。
- `SkillServices.java`、`AiCredentialVault.java`、`AiExtensionHttp.java` 实现 HTTPS 服务授权、本地加密和单次调用。端点、费用或凭据变化时重新授权。付费请求发送后结果未知时报告状态，客户端无自动重发。
- `ChatCompletionClient.java` 新增隔离的单轮 HTTPS 模型请求，复用四套协议，限制输出与截止时间。该入口使用现有模型配置的常用认证头，清除会影响隔离的自定义参数及历史。要求特殊自定义认证头或 Chat Completions `max_completion_tokens` 的模型代理仍需独立验证。
- 专项覆盖 `AiCredentialVaultTest`、`SkillServicesTest`、`SkillStoreTest`、`IsolatedTextRequestTest` 及原聊天、模型和设置用例。

```powershell
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain --tests 'com.douai.module.ai.*' --tests '*IsolatedTextRequestTest' --tests '*ChatCompletionClientTest' --tests '*ManualAiSearchClientTest' --tests '*AiTestChatDialogTest' --tests '*NativeAiConversationTest' --tests '*ModuleSettingsActionSeparationTest' --tests '*AiModelContactConfigTest'
```

专项 144 项通过，无失败或跳过。测试使用模拟模型响应、模拟订单和 HTTPS 传输替身。待独立验收真机 Keystore、设置交互、抖音宿主和真实模型服务。
