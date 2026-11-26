# DouAi 通用技能引擎实施计划

> **执行方式** 使用测试驱动开发验证存储、设置和两个聊天入口，从 `9668604` 开始。

## 交付目标

同一个技能的输入、配置项、服务引用、请求与响应映射及输出类型由版本化清单描述。改接已支持协议的服务时，用户在技能配置保存候选，核对目标后启用。申请编写和确认执行分别通过现有两聊天入口进入。

## 第一步　固定清单与运行合同

- [x] 保留 `SkillManifest` v4 和 HTTPS 声明 v1 的读写。将服务引用限制为应用已知的本地绑定方式，旧记录从能力推导。清单允许必填、可选、默认值和有类型的配置；设置从声明渲染，地址、凭据和授权保留在本地服务绑定。
- [x] `SkillCapabilityRegistry` 继续承载文本模型、表格模型、现有图片服务和受限 HTTPS。HTTPS 直接返回支持文本、图与表；异步视频需要受限的 JSON 提交、原 ID 的查询、状态映射和同源成片读取。保留现有可选取消行为。
- [x] 通过旧清单及四类结果回归。本次明确保留单一文本主输入、受限字段映射和同源 HTTPS 范围； multipart、任意脚本、多步编排及未声明的协议保持不可运行。

## 第二步　在写技能的同一轮吸收资料

- [x] `SkillAuthoring` 和 `AiTestChatDialog` 保留斜杠编写、局部修改和跨重开恢复。新建同一轮可以采用用户给出的资料、明确指向的同会话资料，或已配置且唯一兼容的公开视频协议形状。多个来源时显式选择。空缺只提示下一项需要的资料。
- [x] `SkillVideoFacts` 先验证证据，之后才编译清单。保留来源和用户已有值。新技能复用公开的协议形状，不复制密钥、授权或服务调用历史；选中的聊天模型也不构成视频服务证明。

## 第三步　让设置能改接服务

- [x] `SkillStore` 增加有旧记录身份检查的替换流程。视频从一份新资料形成完整候选，区别于同一契约补项。直接 HTTPS 使用同一声明的字段映射。保留原生效版本和兼容配置值，确认才发布同名下一版。
- [x] `AiSkillDetailDialog` 在单技能配置提供字段表单和更换入口。先显示原生效状态和候选。目标或发送范围变化取消旧绑定和确认；新地址和密钥走原 HTTPS 配置。验证、预览、换配置都不触发网络。

## 第四步　离线验收与交接

- [x] 让工作 AI 和 AI 联系人从用户原话各创建一次，内置前核对具体字段。给同一个命令配置两份不同的合成视频契约，覆盖重开、只改指定字段、旧版本保持、替换确认和旧凭据隔离。
- [x] 测试显式斜杠和已启用技能的自然语言调用，逐次授权后只提交一次，使用原任务 ID 查询，并把视频卡放回发起会话。覆盖结果未知以及不兼容协议的不可运行状态。
- [x] 在同一源码候选验证文本、图片、表格现有流程和旧记录升级，记录离线测试及 Release Lint。真实视频验收须有明确授权的测试环境、账号、费用和目的地。候选不打包、不发布。

## 独立复测

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain --tests 'com.douai.module.ai.*' --tests '*AiTestChatDialogTest' --tests '*AiSkillSettingsDialogTest' --tests '*AiSkillImageProviderTest' --tests '*TestChatMemory*Test' --tests '*MiuixEditorsInteractionTest' --tests '*ChatCompletionClientTest' --tests '*ManualAiSearchClientTest' --tests '*IsolatedTextRequestTest'
.\gradlew.bat :app:lintRelease --offline --console=plain
```

- 工作 AI 的 `oneTurnVideoDocsDriveConfirmedSlashCallAndReturnOriginalJobMedia` 使用同一条创建消息中的接口资料，核对配置、取消和确认、按原任务 ID 查询、取回文件和在当前会话重开。随后用设置暂存一份不同契约，确认生效前仍显示原调用目标。
- AI 联系人的 `visibleVideoDocsDriveNaturalCallAndRestoreUnknownWithoutRetry` 使用明确指向的上文，验证另一种请求、任务 ID 和结果映射。自然语言选中技能后仍需逐次确认。模拟提交结果未知，重开后保留状态。
- `uniqueConfiguredVideoSourcePrefillsFreshContactDraftWithoutSharingBinding` 验证只有一份已配置的兼容契约时，同轮预填非敏感字段。新技能的连接和授权独立配置。
- `SkillStoreTest` 复测同一技能切换不同契约，以及旧调用确认失效、旧密钥不继承、候选失败时保留原版本。原有清单、直接 HTTPS、图片、文本和表格继续使用离线测试夹具。

最终候选通过 481 项相关离线测试、Release Lint 和 Git 差异检查。两种视频契约使用受控进程内响应。真实视频仍待提供明确授权的测试环境、账号、费用和目标地址。
