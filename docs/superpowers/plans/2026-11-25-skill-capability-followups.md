# DouAi Skill 能力后续

基线为 `39f5c4a`。每阶段从聊天的手动斜杠入口验收，单独提交，使用离线数据。

1. 表格由当前文本模型通过隔离请求生成。能力注册表接收限长的 `columns` / `rows` 对象，历史把结果作为仅本地展示的内容保存。预览显示有限行列，CSV 通过系统文件选择器导出完整结果。验收包括不等长行、公式前缀、落盘后重开和两聊天入口。
2. 视频使用本地模拟服务。聊天明确发起任务并保存会话范围内的操作 ID，恢复时查询同一任务。取消、超时和会话清空使用持久状态约束，完成后从应用自有文件播放。真实视频供应商及协议等用户选定后再接入。
3. 自定义服务使用应用注册的地址及固定 JSON 映射。用户在技能详情绑定服务，聊天逐次展示目标及待传字段，再确认执行。凭据独立密封，确认绑定到本次参数和服务配置且只能使用一次。离线模拟验证完整链路；真实运行需要有文档的供应商和能校验实际连接地址的传输实现。

## 独立复测

在 AI 专项工作树使用已缓存的 Android SDK 和 Gradle 依赖。

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain --tests 'com.douai.module.ai.*' --tests '*AiTestChatDialogTest' --tests '*AiSkillSettingsDialogTest' --tests '*TestChatMemoryDisplayTest' --tests '*AiSkillImageProviderTest' --tests '*MiuixEditorsInteractionTest'
```

- `tableSkillSavesBeforeShowingPreviewAndRestoresWithoutReplay` 从工作 AI 发送「帮我创建一个输出表格技能并内置」，执行 `/值班表 本周值班安排`，验证预览、重开和联系人入口。`SkillTableTest` 覆盖表格边界和 CSV 引号、换行及公式前缀。
- `videoSkillResumesTheSameOperationAndCancelsWithinItsConversation` 用已开启本地历史的会话创建 `/模拟视频`，发起后关闭再打开，核对同一任务与播放入口。在联系人中另发任务并取消。`SkillVideoTasksTest` 用固定时钟覆盖去重、超时、清空和会话隔离。内置 H.264 文件是两秒本地合成样例。
- `customServiceSettingsRequireConfirmationForEachChatRequest` 创建「自定义服务技能」，从聊天打开技能详情，选中并保存唯一的本地模拟预设。调用 `/接口整理 样例` 时核对目标、方法、字段及费用，先取消再确认。`SkillCustomServiceTest` 覆盖确认只能使用一次、凭据隔离和异常响应。

系统 CSV 文件选择器、宿主实际播放及真实供应商接入需要分别验收。模拟服务使用保留域名 `skill-mock.invalid`，执行留在本机。
