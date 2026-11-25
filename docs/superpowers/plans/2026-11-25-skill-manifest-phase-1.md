# DouAi 自定义 Skill 清单第一阶段

基线为 `8f26a27`。本轮用已配置的普通模型承接文本技能，用现有图片适配器承接生图，保留技能历史与图片查看器。仅在 AI 专项工作树交候选，由小智独立复测和构建。

Agent Skills 将说明及元数据放在 SKILL.md。DouAi 的本地声明扩展使用应用控制的能力与表单，宿主负责验证及执行。兼容模型只提供 JSON 文本时继续严格解析。 [Agent Skills 规范](https://agentskills.io/specification) · [OpenAI Skills](https://developers.openai.com/api/docs/guides/tools-skills) · [结构化输出](https://developers.openai.com/api/docs/guides/structured-outputs)

## 清单与存储

- Manifest v1 使用 `schemaVersion: 4`，与已有 v1–3 定义区分。新清单包含命令名、简介、使用说明、指令、能力 ID、配置字段、一次运行的输入、输出类型与模拟样例。稳定技能 ID 由应用保存，模型提供该字段时拒绝。
- `text_model` 使用一次必填的文本参数及已配置的普通模型。自定义设置支持单行、多行、整数、小数、布尔与枚举，字段有稳定键、可选说明、约束及非敏感默认值。第一阶段输入只支持当前斜杠命令可以提供的单个 `argument`。供应商、模型和密钥引用预留给应用管理的适配器字段。
- `image_generation` 保留旧图片适配器的 API 地址、模型、独立密钥与授权。能力注册表提供这些必填项。图片清单不增加自定义网络或凭据字段。
- 单 Skill 的值和定义分开保存。修改值或声明时核对界面所见版本。兼容字段按键和类型保留，改类型或约束不再匹配的值丢弃。第一份草稿在用户明确填写保存并具备必填值后启用，已有在用版本的修改仍需确认启用新版。

## 实施与验收

1. 在工作 AI 发中文生成并内置请求，模拟响应返回带必填语气和输出长度的清单。基线应无法留下该清单草稿。实现后应给出缺项与确切技能详情入口。
2. 用清单解析器和能力注册表校验字段、输出、输入及模拟样例。文本样例带入当前配置值，沿用实际执行的请求长度校验。旧定义和图片授权继续由已有存储及服务处理。
3. 详情按清单生成字段。填写一部分应保留待配，关闭重开后能继续填。编辑声明或配置时不覆盖在用版本，取消、删除及旧确认沿用现有身份校验。
4. 在联系人和工作 AI 执行同一已启用文本技能。测试替身应看到分开的指令、本次文本和普通参数。普通文本技能无需填写 API，未知能力、重复键、非预期参数和敏感值都要拒绝。
5. 回归生图配置、模型目录、授权、离线重开、助手图片和缩放手势。测试使用合成数据，保持现有图片地址和授权语义。

## 后续边界

- 表格扩展类型化结果及有限行列，先用本地或既有文本模型，再验收 CSV 公式注入处理、大小限制和重开恢复。
- 视频先走模拟任务协议，再用有文档且经用户授权的供应商。任务查询沿用原任务 ID，验收重启恢复、失败、取消、超时和下载结果。不把图片适配器当作视频服务。
- 自定义 HTTP JSON 连接仅接受用户明确配置的 HTTPS 目标、数据映射、费用与授权。跨源跳转、私有地址、任意脚本和自动重发维持禁用。进入此阶段前先完成两个内置输出类型的适配器验收。


## 离线复测入口

从 AI 专项工作树运行，Android SDK 路径沿用本地开发配置。

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain --tests 'com.douai.module.ai.*' --tests '*AiSkillSettingsDialogTest' --tests '*AiSkillImageProviderTest' --tests '*ModuleSettingsActionSeparationTest' --tests '*AiTestChatDialogTest' --tests '*NativeAiConversationTest' --tests '*ChatCompletionClientTest' --tests '*TestChatMemoryDisplayTest' --tests '*AiSearchVideoPlaybackHandoffTest' --tests '*MiuixEditorsInteractionTest'
```

专项用例 `AiTestChatDialogTest.workManifestWaitsForRequiredConfigurationAndRunsFromContact` 从工作 AI 的中文创建请求出发，覆盖进入同一条详情、部分填写后重开、补齐后启用及两个聊天入口。`SkillManifestTest` 覆盖字段与能力拒绝规则、设置继承、确认替换和旧图片定义。
