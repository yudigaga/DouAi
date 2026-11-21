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

专项测试 69 项通过，无失败或跳过。待独立验收抖音真机的两种聊天布局、软键盘及菜单交互。
