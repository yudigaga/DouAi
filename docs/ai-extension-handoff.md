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

## 阶段 3 · 在线 MCP

- `McpClient.java` 复用有界 HTTPS 传输，支持显式选择 Streamable HTTP `2025-11-25` 或 `2026-07-28`，处理 JSON、SSE 和工具分页。协议不兼容时报告所选版本的失败。
- `McpRegistry.java` 维护服务 ID、工具名、别名和已审核的工具指纹。端点、协议、凭据或工具定义变化后重新确认。工具调用前重新检查远程定义和本地授权。调用发出后结果未知时保留该状态，客户端无自动重发。
- `McpSettingsDialog.java`、`ModuleSettingsScreen.java` 提供登记、授权后异步发现、手动绑定和 JSON 导入预览及导出。导入不联网，替换同 ID 也会清空凭据、授权和已审核指纹。
- `AiSkills.java`、`SkillStore.java`、`AiTestChatDialog.java` 将已审核的命令接入两个入口。用法示例为 `/mcp-weather {"city":"深圳"}`。未登记或未授权的命令有本地反馈。
- 专项使用模拟 HTTP 和 SAF 配置测试。当前接收文本工具结果与受限的 JSON Schema。OAuth、旧 HTTP+SSE、GET 续流、订阅、多轮服务器输入请求及超出子集的工具有单独的兼容性限制，真实服务及 Android 文件选择器待独立验收。

```powershell
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain --tests '*McpClientTest' --tests '*McpRegistryTest' --tests '*McpManualRoutingTest' --tests '*ModuleSettingsActionSeparationTest'
```

协议参考 [2025-11-25 Streamable HTTP](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports) 与 [2026-07-28 Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http)。

## 阶段 4 · 子代理

- `AiSubAgentSettingsDialog.java`、`ModuleSettingsScreen.java` 提供子代理模型和工具选择。模型按现有配置的稳定 ID 绑定，配置只保存 ID 和本地工具白名单。
- `AiSubAgents.java`、`AiSkills.java`、`AiTestChatDialog.java` 接入工作 AI 的 `/委派 任务`。一次委派最多两次模型请求、每次最多 600 输出 Token、一次本地工具调用，共用 30 秒截止时间。用户停止时取消在途模型请求，聊天界面丢弃迟到结果。
- 子代理只接收当次任务和被允许的工具结果。当前白名单可选摘要、模拟订单，默认均关。子代理无法调用在线 Skill、MCP 或再委派。普通模型对话及显式选中的文本技能无需模型支持原生工具调用。
- `AiSubAgentsTest.java`、`AiSubAgentSettingsDialogTest.java` 覆盖选定稳定 ID、共享截止时间、子代理权限、预算和设置入口。四套协议用 `IsolatedTextRequestTest.java` 的传输替身测试。

从同一 0.3.1 工作树运行最终专项和既有自动化回归。

```powershell
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain --tests 'com.douai.module.ai.*' --tests '*AiSubAgentSettingsDialogTest' --tests '*IsolatedTextRequestTest' --tests '*ChatCompletionClientTest' --tests '*ManualAiSearchClientTest' --tests '*AiTestChatDialogTest' --tests '*NativeAiConversationTest' --tests '*ModuleSettingsActionSeparationTest' --tests '*AiModelContactConfigTest' --tests '*Automation*Test' --tests '*ConfigTransfer*Test' --tests '*AutoReply*Test' --tests '*AutoComment*Test'
```

最终针对性回归 594 项，无失败，跳过 0 项。

独立真机验收可依次覆盖两种聊天布局、离线模拟生成与回退、MCP 文件选择器与真实服务、Android Keystore、既有模型协议的真实账号，以及子代理的停止按钮和权限提示。

## 图片结果修订 · 基于 `510f197`

真机反馈对应的路径会把“生图”当作文本技能生成，文本试跑也能通过。当前声明与执行层都有文本、图片两种结果类型。旧版执行定义及旧服务登记按文本读取。 [真机反馈](https://app.slack.com/archives/C0CK9FJ74UC/p1795320078558119)

### 变更文件

- `SkillResult.java`、`AiSkills.java`、`SkillStore.java` 定义并校验结果类型。图片需求走本地生成，用本地样图试跑。缺少已授权服务时持久保存待连接草稿，草稿不出现在斜杠菜单。同名已安装版本仅在用户确认后切换，旧版可回退。
- `SkillServices.java`、`AiExtensionHttp.java` 在原有服务登记与凭据层上增加输出类型，沿用同一 HTTPS 请求和授权。输出类型变化会清除原授权。拒绝纯文字、缺少内联图片、非法 Base64 或无法解码的图片。已发送的请求超时后不会重发。
- `AiSkillSettingsDialog.java` 显示待连接原因、输出类型及重试连接。可以登记图片服务，或授权和撤销本机模拟图片。新图片草稿优先匹配显式服务 ID。未指定时按 ID 稳定选取已授权的真实图片服务，本机模拟服务作为回退。安装回执显示已绑定的服务。
- `AiTestChatDialog.java` 在两个聊天入口显示助手图片，复用现有缩略图、取消、结果丢弃和会话内存上限。图片结果仅保留在当前会话。
- `SkillImageDraftTest.java`、`SkillStoreTest.java`、`SkillServicesTest.java`、`AiSkillSettingsDialogTest.java`、`AiTestChatDialogTest.java`、`NativeAiConversationTest.java` 覆盖本地生成、版本与授权、协议响应、显示及取消。

### 建议真机复测

1. 打开「扩展功能 → AI Skills」，在未授权任何图片服务时只输入“生图”并生成。应显示缺少已授权图片服务的新草稿。此前没有同名技能时，两个入口的菜单都不应出现 `/生图`。此前已安装同名文本技能时，它仍保留原版本。
2. 授权「模拟图片」，在待连接草稿点「重试连接」。如果有同名旧版，确认启用新版，随后通过回退验证旧文本仍可用。可以用“名为「封面设计」的生图技能”验证自定义命令名。
3. 在 AI 联系人和工作 AI 各运行 `/生图 一只白猫`，检查助手消息可见并成功显示固定样图。撤销「模拟图片」的授权后再次执行，应显示授权错误。模拟图片使用固定本地样图，适合全程离线验收。
4. 回归 `/摘要`、`/查询` 和普通文本对话。若独立验收使用模拟 HTTPS 适配端点，分别返回纯文字、坏图片和发出后超时，检查错误提示与请求计数。

登记的图片接口使用 HTTPS `POST {"input":"提示词"}`。支持 PNG、JPEG、WebP、GIF 响应，或 JSON 内联 `{mimeType,imageBase64}`、`data[0].b64_json`。服务只提供图片 URL 时会提示缺少内联图片。需要自定义请求结构的供应商可通过符合此约定的适配服务接入。

```powershell
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain --tests 'com.douai.module.ai.*' --tests '*AiSkillSettingsDialogTest' --tests '*AiTestChatDialogTest' --tests '*NativeAiConversationTest' --tests '*ModuleSettingsActionSeparationTest' --tests '*ChatImageLoaderTest' --tests '*ConfigTransfer*Test' --tests '*IsolatedTextRequestTest' --tests '*ChatCompletionClientTest'
```

最终专项回归 272 项通过，未使用真实付费 API。待独立验收真机布局、真实图片适配服务和签名产物。

## 从对话创建与配置单条 Skill · 基于 `a35292a`

用户可在 AI 联系人或工作 AI 的输入框发送「帮我做一个生图 Skill」。DouAi 本地生成可持久化声明，助手消息根据实际保存结果显示已启用、待配置或待确认替换，并打开同一条 Skill 的配置。普通提问、引用、附件内容和模型回复走原有流程。 [交互反馈](https://app.slack.com/archives/C0CK9FJ74UC/p1795323508798529)

### 变更文件

- `SkillCreationRequest.java`、`SkillStore.java` 识别手动输入的明确创建请求，保存草稿、缺项和授权状态，并在离线校验后发布命令。本地支持文本摘要、模拟订单和图片。其他明确创建请求会保留为待配置草稿，等待受支持的执行方式。
- `AiTestChatDialog.java` 显示提交结果和缺项，打开草稿或已安装的对应条目。关闭或停止时，等待提交的创建操作会被丢弃。
- `AiSkillDetailDialog.java`、`AiSkillSettingsDialog.java` 共用单条 Skill 设置。OpenAI Images 兼容适配器需要 HTTPS 地址、模型名、密钥和服务授权。本机模拟图片只需要向当前 Skill 授权。已填内容分步保存，密钥保留在本地凭据层。
- `SkillServices.java` 保存专用配置，运行时核对服务、适配器、模型、费用和授权。OpenAI Images 兼容适配器按固定 HTTPS 地址发送 `{model,prompt}`，沿用已有的内联图片响应。适用的既有授权可自动复用。同名新版使用独立配置，确认替换后仍可回退原版本。
- `ConversationSkillCreationTest.java`、`SkillServicesTest.java`、`AiSkillSettingsDialogTest.java`、`AiTestChatDialogTest.java`、`NativeAiConversationTest.java` 覆盖自然语句、设置定位、凭据隔离、分步保存、启用、回退以及两入口位图渲染。

### 建议真机复测

1. 在工作 AI 发送「帮我做一个生图 Skill」。无适用授权时，助手应显示草稿已保存，并列出 API 地址、模型名、密钥和授权的待办项。普通「如何创建一个生图 Skill？」保留正常对话行为。
2. 点助手消息的「打开该技能设置」，确认标题为 `/生图`。关闭聊天后，从「扩展功能 → AI Skills」点同名草稿，确认草稿和已保存的配置还在。
3. 离线验收时，在这条 Skill 选择「本地模拟图片」，保存并确认授权。完成后应直接启用，有同名旧版则先确认启用新版。回到两个入口分别执行 `/生图 一只白猫`，助手应显示固定样图。
4. 发送「创建一个文本摘要技能」。检查自动启用，并试用 `/文本摘要 要点。结论。附录。`。对同名已启用的 Skill 再创建，检查旧版留在当前目录，并在显式确认后验证回退。

本轮离线验证使用本机固定样图和传输替身。真实 HTTPS 适配、宿主界面和签名包待独立验收。

```powershell
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain --tests 'com.douai.module.ai.*' --tests '*AiSkillSettingsDialogTest' --tests '*AiTestChatDialogTest' --tests '*NativeAiConversationTest' --tests '*ModuleSettingsActionSeparationTest' --tests '*ChatImageLoaderTest' --tests '*ConfigTransfer*Test' --tests '*IsolatedTextRequestTest' --tests '*ChatCompletionClientTest'
```

最终专项回归 292 项通过，0 失败，0 跳过。两个入口均覆盖自然语言创建、跳转同一条设置、模拟授权和助手位图显示。
