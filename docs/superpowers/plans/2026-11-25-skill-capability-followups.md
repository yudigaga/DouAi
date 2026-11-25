# DouAi Skill 能力后续

基线为 `39f5c4a`。每阶段从聊天的手动斜杠入口验收，单独提交，使用离线数据。

1. 表格由当前文本模型通过隔离请求生成。能力注册表接收限长的 `columns` / `rows` 对象，历史把结果作为仅本地展示的内容保存。预览显示有限行列，CSV 通过系统文件选择器导出完整结果。验收包括不等长行、公式前缀、落盘后重开和两聊天入口。
2. 视频草稿保留为待配置，缺项是已选供应商的公开任务 API 合同。拿到合同后接提交、原 ID 查询、恢复、取消及下载。升级读取原有聊天历史时跳过旧视频样例记录。
3. 自定义服务使用用户在技能详情输入的完整公网 HTTPS 地址和固定 JSON 映射。聊天逐次展示 `POST` 目标和 `input` 字段；成功响应是只含字符串 `text` 的 JSON 对象。凭据按技能和完整接口地址独立密封。只有当前版本和配置一致的确认可以用一次，每进程每分钟最多尝试六次。出站传输核对 TLS 证书、主机名、经限制的 DNS 解析和实际连接地址，不使用代理、重定向或自动补发。服务商按自身条款计费。

## 独立复测

在 AI 专项工作树使用已缓存的 Android SDK 和 Gradle 依赖。

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain --tests 'com.douai.module.ai.*' --tests '*AiTestChatDialogTest' --tests '*AiSkillSettingsDialogTest' --tests '*TestChatMemoryDisplayTest' --tests '*AiSkillImageProviderTest' --tests '*MiuixEditorsInteractionTest'
```

- `tableSkillSavesBeforeShowingPreviewAndRestoresWithoutReplay` 从工作 AI 发送「帮我创建一个输出表格技能并内置」，执行 `/值班表 本周值班安排`，验证预览、重开和联系人入口。`SkillTableTest` 覆盖表格边界和 CSV 引号、换行及公式前缀。
- `authoredVideoStaysPendingAndSlashCallDoesNotSendOrStartAJob` 从工作 AI 生成视频技能，核对详情中的供应商缺项和命令不可用状态。`oldVideoArtifactIsSkippedWhileTextTableAndImageAreRestored` 验证升级时兼容原有聊天历史。
- `customServiceSettingsRequireConfirmationForEachChatRequest` 创建「自定义服务技能」，从聊天打开详情并保存测试地址。调用 `/接口整理 样例` 时核对目标、方法、字段和费用提示，先取消再确认。`SkillCustomServiceTest` 和 `SkillHttpsTransportTest` 使用合成凭据及本地测试响应验证确认、地址限制、TLS、凭据隔离和请求状态。

正式供应商调用须由用户配置并确认；本地回归仅使用受控测试端点，系统 CSV 文件选择器和真机联网仍需独立验收。
