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

## 聊天模型编写与修订 Skill · 基于 `27b7e8a`

AI 联系人和工作 AI 使用当前对话的模型配置编写声明。草案预览显示名称、输出类型和文本指令。修订请求只携带上一份通过校验的草案。发送「帮我内置」时保存到本机技能库，也可用一条消息明确要求编写并内置。 [交互确认](https://app.slack.com/archives/C0CK9FJ74UC/p1795393932849649)

### 变更范围

- `SkillAuthoring.java` 识别手动发送的明确请求、限制模型输入并严格解析 JSON。模型回复、引用、附件和普通聊天无法写入技能库。关闭或清空聊天会丢弃尚未保存的预览。
- `AiTestChatDialog.java` 复用两入口的草案状态和设置快捷入口，隔离普通聊天历史、搜索和附件。取消或迟到的输出不会提交。旧的聊天关键词模板已从该链路移除。
- `SkillStore.java`、`AiSkills.java` 加入 v3 声明、本地试跑和自定义指令文本执行。文本 Skill 通过当前入口已配置的 HTTPS 模型处理文本。图片 Skill 复用已登记并获授权的适配器，缺项进入现有单条设置。模型无法指定地址、费用、密钥、工具或执行方式以外的操作。同名版本经确认才能替换，可回退。v1/v2 定义继续按原约定读取。
- `SkillAuthoringTest.java`、`ConversationSkillCreationTest.java`、`AiTestChatDialogTest.java` 和 `NativeAiConversationTest.java` 覆盖声明、两个入口、取消、凭据和本地配置。模拟服务的图片仍为固定样图。

### 独立验收清单

1. 选择已配置的 HTTPS 模型，在工作 AI 发送「帮我写一个整理周报的 Skill」。核对聊天返回的草案和实际指令。说「把指令改成三点格式」，核对修订。确认此时技能库没有新条目。
2. 发送「帮我内置」，重新进入两个入口，使用生成的 `/技能名` 处理文本。也可在 AI 联系人发送「帮我做一个周报 Skill 并内置」验证单条路径。
3. 用「帮我写一个生图 Skill」生成图片草案，再内置。在没有可用授权时核对待配置和缺项。点开对应设置，选「本地模拟图片」，保存并确认授权，然后在两个入口分别调用并看助手消息的实际图片。用同名草案核对显式确认、旧版保留和回退。
4. 验证普通提及、附件和模型回显「帮我内置」不会创建或执行。取消编写请求，确认迟到结果不会安装。全屏回缩与顶部色带按 `27b7e8a` 的场景复查。

```powershell
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain --tests 'com.douai.module.ai.*' --tests '*AiSkillSettingsDialogTest' --tests '*AiTestChatDialogTest' --tests '*NativeAiConversationTest' --tests '*IsolatedTextRequestTest' --tests '*ChatCompletionClientTest' --tests '*ConfigTransfer*Test' --tests '*ModuleSettingsActionSeparationTest' --tests '*ModuleSettingsReferenceTest.referenceSubpagesHaveExactItemOrderAndNoExtraInlineInputRows' --tests '*FeedTranslatedTopBandTest' --tests '*FeedRenderScaleHooksTest' --tests '*FeedVideoFramingTest'
```

最终专项回归 335 项通过，0 失败，0 跳过。模型和图片请求使用替身，真实模型、真机宿主和发布构建待独立验收。

## 自定义 Skill 删除 · 基于 `03e16d1`

在「扩展功能 → AI Skills」点开单条 Skill，再选择「删除」。确认框显示准确命令、版本数及是否一并删除同名待配置草稿。纯草稿可单独删除。取消会保留当前记录。

- `SkillStore.java` 在锁内核对确认时所见的完整记录，并原子移除该名称的已安装记录、版本索引和待配置草稿。提交后清理已移除版本的本地文档，遇到文件系统阻碍时保留清理记录供重开后重试。
- `AiSkills.java` 绑定排队调用到原版本目录。就算删除后重新创建同名 v1，旧调用也无法进入新版本。
- `AiSkillDetailDialog.java` 提供删除确认，成功刷新设置。过期确认只重新读取当前记录。`AiSkillSettingsDialog.java` 的连接重试也核对原草稿身份。
- `AiTestChatDialog.java` 让同一技能库的已打开菜单响应删除。内置命令和其他 Skill 继续由各自记录管理。既有服务登记、授权和本机凭据仍可通过原有设置使用。

### 独立验收清单

1. 创建一个带旧版本的自定义 Skill，再保存同名待配置草稿。点「删除」核对名称、版本数和同名草稿，先取消，确认两个条目都还在。
2. 再次确认删除，检查设置列表、AI 联系人和工作 AI 的斜杠菜单。重开后仍不应出现已删命令。复测纯草稿删除及另一个使用相同授权服务的 Skill。
3. 自动化回归包含陈旧确认、删除后重建同名同版本、排队连接重试和版本文件清理重试。

```powershell
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain --tests '*SkillDeletionTest' --tests '*AiSkillSettingsDialogTest' --tests '*AiTestChatDialogTest'
```

本轮专项及相邻回归 347 项通过，0 失败，0 跳过。真机与签名包待独立验收。

## 编辑单条 Skill 与生图复数请求 · 基于 `68a6ad9`

在「扩展功能 → AI Skills」打开单条详情，点「编辑」。可修改简介、使用说明及适用的执行指令。命令名固定。保存会核对原记录身份，再写入待确认草稿。已有版本在确认「启用新版」前继续可用，随后可回退。取消编辑会保留原记录。

- `AiSkillDetailDialog.java`、`SkillStore.java` 增加编辑、草稿、旧版本复核和启用。继续读取 v1/v2/v3。图片草稿在待确认时也可进入「配置图片服务」。相同的已授权绑定可复用。调整服务目标、模型或密钥走单独的本地配置与授权，旧版和共享配置仍可供其他调用使用。
- `SkillAuthoring.java`、`AiTestChatDialog.java` 支持明确的单数、复数及中文写技能请求。比如「帮我写一个生图 skills 并内置」会要求图片输出；若模型返回文本，显示类型不符并拦截保存。图片缺少服务时留下真实待配置草稿和对应设置入口。
- `SkillEditingTest.java`、`AiSkillSettingsDialogTest.java`、`SkillAuthoringTest.java`、`AiTestChatDialogTest.java`、`NativeAiConversationTest.java` 覆盖编辑、回退、配置隔离、同名冲突、反馈原句和两入口。文件级删除仍走原有确认和原子目录流程。

### 独立验收清单

1. 打开文本 Skill 详情，改简介、说明和支持的指令。先取消，核对版本及当前命令。再保存，确认斜杠执行仍使用旧指令。点「启用新版」，执行新版本，再回退复测。
2. 编辑已安装图片 Skill 并保存草稿，从同一详情打开「配置图片服务」。用本地模拟服务验证保存、授权和启用新版。也可核对地址、模型、费用字段及空白密钥显示。其他 Skill 所用的共享服务继续通过原登记访问。
3. 在两个入口各用「帮我写一个生图 skills 并内置」验收。图片缺少授权时，应显示待配置，并可打开对应详情，配置本地模拟后调用查看位图。模型只返回文本时，应报告类型不符，且技能库没有因此新增 `/模拟技能`。普通问句和带引号的原句留在普通聊天。
4. 用同名条目制造待确认草稿，检查第一个编辑窗口的旧快照无法覆盖后续保存，再回归删除、回退、顶部色带和全屏回缩。

```powershell
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain --tests '*SkillEditingTest' --tests '*AiSkillSettingsDialogTest' --tests '*SkillAuthoringTest' --tests '*AiTestChatDialogTest' --tests '*NativeAiConversationTest'
```

本轮专项及相邻回归 362 项通过，0 失败，0 跳过。模型和图片服务使用模拟响应，真机待独立复测。

## 图片服务模型目录与密钥保存 · 基于 `6e40cb5`

### 基线复现与证据

在生产源码仍为 `6e40cb5` 时，用 Robolectric 走实际表单回调。已启用的专属模拟图片 v1 → 编辑 → 保存草稿 → 配置图片服务 → OpenAI Images 兼容服务。四项基线用例中，格式有效的合成密钥在可写测试密钥库里确实写入专属服务，授权仍关闭，模拟 v1 仍有原授权。缺少或无效的 HTTPS 地址在写入前拒绝。粘贴「Bearer 前缀加首尾空白」被拒绝，缺少 AndroidKeyStore 的测试环境也只显示相同的通用错误。基线在调用保存前就清空密钥字段。 [基线复现日志](../build/ai-extension-model-directory-baseline.log)

需区分上述可复现缺陷与用户设备的未知故障。按提供的脱敏摘要，14:45:29 LSPosed 导出缺少压缩包结束索引，`full.log` 仅部分可读，可读模块段和 verbose 没有保存失败、Keystore 或 SkillServices 的具体异常。本机 ADB 未发现可用设备，因此没有复现该手机的具体错误，也无法据此判断其凭据层失败。

### 变更范围

- `AiSkillDetailDialog.java` 增加可选模型列表地址、主动拉取和模型选择。选择只写入表单，可手填。保存错误区分字段、凭据、草稿状态和其他本地写入失败，失败时保留遮罩输入。保存成功后密钥只显示已填写状态。
- `SkillServices.java`、`SkillStore.java` 让图片配置接受密钥首尾空白及单个 `Bearer` 前缀。缺模型时也可保存可用的地址和凭据。单独保存模型目录地址。登记、授权、启用新版分别操作。
- `SkillImageModelCatalog.java` 沿用已有有界 HTTPS 传输和 OpenAI 目录格式，以同源 URL 发起一次 GET。请求使用本次填写的凭据，或复用所属草稿同一生图地址的本机凭据。禁用重定向、禁止凭据进入 URL，并拒绝返回值中的密钥回显。
- `AiSkillImageProviderTest.java`、`SkillImageDiscoveryTest.java` 和 `SkillImageModelCatalogTest.java` 检查表单、存储、请求、取消及保留旧授权。使用合成密钥、AES 测试密钥库和响应替身。

### 独立验收路径

1. 从已启用的模拟图片 v1 进入编辑、保存草稿、配置图片服务，切到 OpenAI Images 兼容服务。填写如 `https://provider.example/v1/images/generations` 的真实生图地址和密钥，模型先留空，保存后应能看到密钥已填写和待选模型。退出重开，密钥栏应为空，已填标志仍在。
2. 点击「拉取模型列表」。标准生图路径默认从同源 `/v1/models` 查询。网关使用自定义生图路径时可填同源模型列表地址。选择一项后应只填写模型字段。目录空、不可用或需认证时，可手填模型名。
3. 保存所选模型，核对原 v1 仍启用。授权后再确认启用新版。可按需求回退到 v1。目录验证用模拟 GET 响应，生图操作用本机模拟图复测。

```powershell
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain --tests '*AiSkillImageProviderTest' --tests '*SkillImageDiscoveryTest' --tests '*SkillImageModelCatalogTest'
```

本轮专项及相邻回归 379 项通过，0 失败，0 跳过。未发出真实服务请求，设备复测待独立执行。

## 图片服务按 AI 供应商方式填写 · 基于 `cbc49ac`

### 对照复现

用同一合成密钥和 `https://images.example.test/v1` 填充两套实际表单。AI 供应商页保存基础地址，获取目录时请求 `/v1/models`，测试替身返回可选条目。生图详情则把 `/v1` 原样登记为生成地址，合成密钥可以在测试凭据库保存，但随后目录解析返回「无法从此生图地址推导模型列表」。这验证了基础地址解释的差异。普通 AI 可以接受较短 Bearer 值，旧图片凭据库至少需要八个字符，这也是可重现的校验差异。 [对照复现日志](../build/ai-extension-supplier-parity-baseline.log)

复核了 15:46:57 的 LSPosed 导出，原件长度为 15643464 字节，SHA-256 与提供值一致。扫描中只统计匹配项，未输出原始日志。93 个可读日志文件未命中目标技能配置类、凭据异常或相关目录状态。点保存后密钥栏为空可以是遮蔽逻辑，用户手机当时是否加密、落盘或读取失败仍缺少直接证据。本机无已连接 ADB 设备。

### 变更范围

- `SkillImageProviderAddress.java`、`SkillImageModelCatalog.java` 解析表单基础地址。裸域名、`/v1` 和网关基址补齐生图操作路径，模型目录使用同一前缀的 `/models`。已知完整生图路径可直接填写，完整接口模式保留旧自定义地址。聊天和模型列表操作地址不能作为生图 POST 目标。
- `AiSkillDetailDialog.java` 使用与供应商页同一 Miuix 密码控件，采用独立技能配置。输入有遮罩并关闭视图状态保存和自动填充。保存成功后清空输入值，同时在字段旁显示已保存状态。保存失败时保留遮罩中的本次输入。选择目录条目仍只填写表单。
- `AiCredentialVault.java`、`SkillServices.java` 让图片技能使用与表单匹配的 4000 字符 Bearer 上限及短密钥支持，保留原 AES-GCM 及作用域/ID/实际生图地址绑定。新的密钥先加密并读取校验，校验失败不会写入服务配置。模型目录仍限 HTTPS、同一主机与有效端口，禁止重定向及凭据回显。
- `AiSkillImageProviderTest.java`、`SkillImageProviderAddressTest.java`、`SkillImageScopeCredentialTest.java`、`SkillImageModelCatalogTest.java` 覆盖实际表单对照、旧地址、隔离凭据、重开状态、读取校验、模型发现和 Miuix 密码控件。

### 独立验收清单

1. 从已有 Skills 模拟图片版本进入「编辑 → 保存草稿 → 配置图片服务」，切到 OpenAI Images 兼容服务。用 AI 那边同样的方式填服务商的 HTTPS 基础地址和这条 Skill 自己的密钥。保存后应显示已保存，输入值为空。重开应仍显示已保存。
2. 点击「拉取模型列表」，选择返回的模型后另行保存，再分别授权和启用新版。旧版可回退。可用本地替身复现目录认证失败和目录为空，继续用手填验证。
3. 复测一个旧的完整自定义生图路径，核对完整模式和实际目标。有的旧记录把基址当作完整接口存了。纠正这种记录时，可关闭完整模式再保存，并重新填写这条 Skill 的密钥以绑定实际生图地址。
4. 对照 AI 和生图页面的密码框，应使用同一 Miuix 控件。两个配置的密钥独立保存，生图页的已保存提示无需读取明文到表单。

```powershell
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain --tests '*AiSkillImageProviderTest' --tests '*SkillImageProviderAddressTest' --tests '*SkillImageScopeCredentialTest' --tests '*SkillImageModelCatalogTest' --tests '*MiuixEditorsInteractionTest'
```

本轮专项及相邻回归 409 项通过，0 失败，0 跳过。使用合成密钥与模拟目录，真机和签名构建待独立验收。

## 生图模型目录兼容与诊断 · 基于 `f07f5dc`

主 AI 与生图服务各有独立的配置和凭据。用合成密钥和目录替身对照两条代码路径，基线复现了六处差异。生图入口拒绝 MIME 标为非 JSON 的合法目录，也拒绝只提供 `name` 的可用模型项。旧版保存为完整地址的 `/v1` 无法推导目录。详情显示同一 Skill 后登记的专属配置时，目录可能找不到对应密钥。修改模型名或费用会丢弃排队中的目录回调，提交任务被拒后也无法立即重试。 [基线回归日志](../build/ai-extension-image-catalogue-baseline.log)

真机日志里的 5 项、约 698 ms 来自主 AI 的 `fetch_model_catalog`。现有导出缺少生图查询的阶段证据，真机失败原因仍未知。 [用户反馈](https://app.slack.com/archives/C0CK9FJ74UC/p1795431970830819) · [复测反馈](https://app.slack.com/archives/C0CK9FJ74UC/p1795432269447829)

- `SkillImageModelCatalog.java` 按有界 JSON 正文校验目录，保留精确的字符串模型标识。`id` 缺失或为 `null` 时接受 `name`，识别旧版直接保存的根地址和 `/vN`，查询同源 `/models`。
- `SkillStore.java`、`SkillServices.java` 用详情页显示的草稿绑定定位本机密钥，再核对 Skill 归属、适配器和精确生图地址。显式查询只单次 GET。
- `AiSkillDetailDialog.java` 在拉取按钮旁显示状态，区分查询输入和待填写的模型与费用。任务提交失败可立即重试。查询全程使用单独的诊断 trace。

### 独立验收清单

1. 打开已有图片 Skill，沿「编辑 → 保存草稿 → 配置图片服务」选择 OpenAI Images 兼容服务。按 AI 供应商界面的填写方式输入 HTTPS 基础地址与此 Skill 自己的密钥，模型可先留空。保存并重开，核对已保存标记和空白的密钥输入栏。留空直接点「拉取模型列表」，选择后核对模型字段，另行保存。
2. 用模拟目录分别返回 `text/plain` 标记的有效 JSON、只有 `name` 的模型项、认证失败、空目录、无效 JSON、重定向与超时。确认有效模型可选，失败有明确提示，手填始终可用。旧记录若把 `/v1` 保存为完整地址，本次显式查询可读取同源 `/v1/models`，原生图地址和绑定继续按记录使用。自定义生图路径仍可补填同源目录地址。
3. 查询排队时修改模型名或费用，应仍看到目录结果。修改查询地址、覆盖目录或本次密钥后，旧结果不会写回。关闭并重开时重复同样检查。
4. 打开「全局调试日志」后复现，按 `image_model_catalogue` 定位生图查询，同一 trace 可核对地址推导方式、凭据来源、HTTP 状态、解析及界面结果。记录使用固定标签或状态码，无服务地址、密钥、模型值、响应体或底层异常文本。鉴权、同源验证、禁重定向和原有生图授权仍按生图适配器执行。

```powershell
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain --tests '*SkillImageModelCatalogParityTest' --tests '*SkillImageModelCatalogTest' --tests '*SkillImageDiscoveryTest' --tests '*AiSkillImageProviderTest' --tests '*AiModelEditorTest' --tests '*MiuixEditorsInteractionTest'
```

专项及相邻回归共 437 项通过，0 失败，0 跳过，包括两个聊天入口、主 AI 表单、设置菜单、顶部色带及全屏回缩。使用合成密钥和模拟服务。真机待独立复测，本轮未构建安装包。 [运行日志](../build/ai-extension-image-catalogue-final.log)

## 2026-11-24 生图模型目录复测 · 基于 `bc38a6f`

真机同链路日志里，早期三次在已存凭据绑定检查处停止。其余八次发起了模型目录 GET，包含本次输入和绑定准确的已存凭据，两种方式均无 HTTP 状态。约五至六秒的失败归为传输错误，最后两次约十二秒耗尽总预算。将生图 API Base 加上 `/v1` 后，用户仍反馈拉取失败。 [问题反馈](https://app.slack.com/archives/C0CK9FJ74UC/p1795527625833179)

两入口按各自配置构建目录。普通 AI 的裸域默认拼 `/models`，版本基址或标准完整对话地址保留其前缀。同源的生图配置原来也在空前缀下拼 `/models`，`/v1/images/generations` 派生 `/v1/models`。本机 DZH 服务说明列出 `/v1/models`。本轮仅对该准确域名及 HTTPS 默认端口的空前缀默认值采用这一路径，显式同源目录优先，自定义完整接口继续使用原规则。原生图地址保持原样。

离线还复现了只修改同源目录时，已启用图片 Skill 的编辑草稿会失去已存凭据。现在仅更新目录元数据并核对原绑定与授权，密文、实际生图地址和费用授权保持原值。诊断新增路径类型、传输阶段和有限失败分类。有实际 HTTP 状态才记录数值。`WAIT_STATUS` 对延迟建连的 HTTPS 客户端也可能包含 DNS、TCP 和 TLS。总预算仍为十二秒，每次点击仅发起一次 GET。 [基线复现](../build/ai-extension-model-pull-baseline.log)

独立真机验收沿用已有生图配置和密钥，开启全局调试日志后点击一次「拉取模型列表」。同一条 `image_model_catalogue` trace 需有实际 2xx、成功解析的非空目录和 `options_presented`，界面可选择返回的模型。无状态时核对 `catalog_transport_failure` 的 `phase` 和 `transport_reason`。本候选未使用真实密钥或发出生图请求。

专项及相邻回归 236 项通过，0 失败，0 跳过。 [运行日志](../build/ai-extension-model-pull-verified.log)

```powershell
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain --tests 'com.douai.module.ai.*' --tests '*AiSkillImageProviderTest' --tests '*AiSkillSettingsDialogTest' --tests '*AiModelEditorTest' --tests '*ChatCompletionClientTest' --tests '*MiuixEditorsInteractionTest'
```

## 2026-11-25 聊天内本地大图 · 基于 `f99d5ae`

用户反馈附件的 219 个 ZIP 条目通过 CRC 检查。只统计日志的固定标签，确认 AI 聊天使用的宿主版本为 41.0.0。源 APK 静态核对使用同版 410001 原包。

- **可证实的本地能力**　40.6 已有反编译文件及 41.0 原包均包含小程序媒体选择器的 `PhotoView`。41.0 的 `classes50.dex` 显示它把 Drawable 交给标准图片控件，再刷新缩放矩阵。
- **复用限制**　41.0 的手势分支还会调用带开关的宿主钩子，现有静态证据无法确认整条手势路径的副作用。已检查的图片预览 Activity 依赖内部小程序或 IM 编辑流程，缺少可以独立接收 DouAi 本地图片的已验证入口。
- **使用方式**　在工作 AI 或 AI 联系人中使用本地模拟生图，待图片出现后点击缩略图。双击放大或复位，放大后可拖动，点「关闭」或按返回键退出。用同一入口和模型配置离线重开，再次点击同一图片。查看器从原有历史供给的图片数据后台解码，损坏数据提示「图片无法读取」。

124 项离线回归通过，0 失败，0 跳过。包括工作 AI 点击与重开、双击缩放、损坏图片、过期解码回调，以及宿主原包静态合同。真机手势和宿主控件的独立运行仍待验证。 [测试日志](../build/ai-extension-local-image-preview-final.log)

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain --tests '*AiTestChatDialogTest.workImageOpensALocalPreviewBeforeAndAfterReopeningWithoutReplay' --tests '*AiTestChatDialogTest.damagedSavedImageShowsALocalReadErrorWithoutReplay' --tests '*AiTestChatDialogTest.lateLocalDecodeCannotRenderIntoAClosedOrReplacedPreview' --tests '*ChatImageLoaderTest'
```
