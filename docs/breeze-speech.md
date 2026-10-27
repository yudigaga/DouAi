# 配置 Breeze Blue 文本转语音

进入“扩展功能 → 文本转语音设置”，将“语音来源”切换为“Breeze Blue”。接口、Key、模型、音色、朗读和高级参数独立于 Fish、MiniMax 与文本 AI 保存。相同界面分组不表示厂商参数互通。

本页只配置将完整正文生成音频并用于试听或既有发送入口。所有返回方式都在完整取得并校验文件后才播放或发送；评论使用生成原文件，私聊和群聊沿用既有宿主处理。模块不自动截断正文、拆成多个生成请求、转码或重封装。[官方 TTS 指南](https://docs.breezeblue.ai/guides/text-to-speech.md)

## 标签优先、搜索和中文译名（0.2.28）

目录默认先按中文筛选，每页请求 20 项，后续手动加载；每组筛选最多保留 200 项。公开范围需要至少一个有效的语言、标签、搜索或该平台特有筛选条件。Breeze 可选语言、类别、多选性别/年龄/语气、口音、未标注口音、来源、收藏、自定义标签、范围和排序；Fish 可选多种音色语言/名称语言、自由标签、作者、许可标记、范围和排序，不套用 Breeze 的性别枚举。Breeze 同组性别/年龄/语气按任一匹配，自定义标签要求全部匹配；Fish 多值条件交由其原生接口处理，不臆造相同语义。排序不会被当作缩小目录的条件；Fish `licensed=false` 也不算有效缩小条件。显示的标签来自接口元数据，不能据此保证质量。

列表内搜索匹配已加载的中文译名、原名、ID 和标签，不联网；“搜索全部音色”才带着同一组筛选查询接口。缓存绑定供应商、接口、账户密钥及完整查询条件；旧版无查询标记的缓存需要重新刷新。快捷音色同样支持本地搜索和缓存译名。

“翻译名称”使用当前启用的文字 AI，每次最多 100 个未缓存名称、每批最多 40 个，长名称按完整 JSON 的长度进一步缩小批次。不会自动调用或自动重试；只发送名称和本批序号，独立请求不读写聊天历史。成功后显示“中文译名（原名）”，ID 和原始名称保留；中途失败保留成功批次与原名。译名是模型辅助显示，官方没有提供统一中文名称字段。关闭或切换目录、筛选、账户、文字模型后拒绝旧译名回调。译名与查询标记是有界本地缓存，不加入配置备份；标签筛选配置参与正常备份。

## 先选择模型和音色

1. 在“接口设置”填写 Breeze Blue API Key，API 地址默认 `https://api.breeze.blue`。Key 只填密钥本身，不添加认证前缀。
2. 打开“语音模型”。新配置默认“自动按语言选择”，表示请求省略 `model_id`；显式语言 `en` 或 `zh` 由服务选择 `breeze-tts-2`，其它语言或省略语言时选择 `breeze-tts-2-multilingual`。服务不会据正文内容猜测并路由模型。
3. 需要固定引擎时选择内置或账户列表中的准确模型，也可手填。已保存的显式模型不自动改为“自动”；`breeze-tts-2` 只允许显式 `en`/`zh`，其它语言先选兼容模型。模型列表中的 `languages[].language_id` 是能力依据，目录不证明账户权限。
4. 打开“音色”，刷新账户音色首屏或手填 `voice_id`。还有后续页时可“加载更多”；无需先等整个目录下载完。此来源必须明确选择音色，没有虚构的默认音色；音色名称只用于显示。
5. 保留默认参数，用短句“试听”。试听不会给好友发消息或发布评论。

模型自动选择规则和显式模型不被替换的规则来自 [TTS 指南](https://docs.breezeblue.ai/guides/text-to-speech.md)；模型目录见 [List models](https://docs.breezeblue.ai/api-reference/models/list-models.md)。打开模型或音色选择框只看本地列表，点击刷新才联网。刷新失败保留原缓存；更换 Key 或地址会取消旧刷新并清除对应目录缓存。

## 逐页浏览和筛选音色

音色库可以按“全部”“系统”“个人”范围、名称搜索和音色语言筛选，还可按类别、多选性别/年龄/语气、口音、来源、收藏、自定义标签和排序查询。它们分别使用 `/v1/voices` 的原生参数；指定口音与 `accent_mode=unmarked` 互斥，`sort=trend` 仅在 `voice_type=default` 时可用。它们不是 Fish 的 `self/public` 规则。[音色列表 API](https://docs.breezeblue.ai/api-reference/voices/list-voices.md)

目录语言只过滤候选音色，独立于合成的 `language_code`，不会改动正文语言或已选模型。调整筛选只清除 Breeze 的音色列表缓存，已选 `voice_id` 保留；随后重新刷新。

刷新读取并显示首屏，标题标明已加载数量与继续状态。Breeze 按自己的 `has_more`、`next_page_token` 或页码推进，后续页面由“加载更多”触发；每页保留相同筛选，按音色 ID 去重追加，不使用 Fish 的窗口字段。后续页失败、游标或页码不前进时，已加载音色和当前选择保留。缓存可能只是此前浏览过的部分结果，不称为完整账户目录；达到本机 200 项浏览预算时保留已加载项，可缩小搜索后刷新。

音色目录每页请求和模型目录读取施加 45 秒的模块等待预算；这是本地等待和取消边界，不是 Breeze 服务的时限声明。关闭页面、切换来源或改变当前目录条件会取消未完成请求，过期结果不能覆盖新列表。Breeze 的模型、音色目录仍需自己的有效 Key，不能套用 Fish 公开目录的匿名读取规则。HTTP 401/403 应检查凭据或权限；仅匿名请求能到达 401，不等于账户音色已验证可读。[目录问题证据与修复](2026-10-27_fish-catalog-window-fix.md)

## 调整朗读和语音指令

| 页面设置 | API 字段 | 范围与默认值 |
| --- | --- | --- |
| 语速 | `voice_settings.speed` | 0.5–2，默认 1；保持音高 |
| 音量 | `voice_settings.volume` | 0.01–2，默认 1；线性幅度倍率 |
| 语音指令 | `instructions` | 可留空，使用与正文相同的语言描述表现 |
| 语言代码 | `language_code` | 可留空，显式两字母代码须受所选模型支持 |
| 指导强度 | `voice_settings.guidance_scale` | 1–10，默认 1 |
| 说话人增强 | `voice_settings.use_speaker_boost` | 默认关闭 |

参数、范围及交互规则见 [合成 API](https://docs.breezeblue.ai/api-reference/text-to-speech/convert-text-to-speech.md)、[TTS 指南](https://docs.breezeblue.ai/guides/text-to-speech.md)和[语言能力](https://docs.breezeblue.ai/concepts/multilingual.md)。语音指令示例：“用自然、温和的语气朗读，句间稍作停顿。”指令控制表现，不替换待朗读正文。

非空指令与大于 1 的指导强度同时存在时，说话人增强不生效；其它情况下启用增强会使有效指导强度为 1，指令仍然应用。页面隐藏当前组合中无效的控件并显示原因，保存的原值保留；调整组合后可再次生效。音量倍率不等于响度归一化，数值大于 1 可能使峰值削波。

### 明确点击后优化指令

在“语言与表达 → 语音指令”编辑草稿，再点“优化指令”。此操作把当前指令草稿及可选语言发送到 Breeze 的 `/v1/text-to-speech/enhance`，不发送待合成正文；服务保持指令语言，不把它翻译成其它语言。优化结果先显示为可检查、可继续编辑的草稿，只有点击“保存”才生效。[Instruction Enhance API](https://docs.breezeblue.ai/api-reference/text-to-speech/enhance-text-to-speech-instruction.md)

优化接口要求非空、最多 1000 字符的指令；这是优化操作的限制，不是给普通 `instructions` 杜撰统一上限。保存指令、切换来源或修改正文不会自动调用优化。修改草稿、关闭编辑器或切换来源会停止本次优化并拒绝过期结果。

## 选择输出和返回方式

进入“Breeze Blue 高级设置 → 音频输出”。输出支持 MP3、WAV、FLAC、AAC、Opus；可选采样率和码率，也可手填 `编码_采样率_码率`。码率单位为 kbps，显式码率必须先指定采样率。[输出格式](https://docs.breezeblue.ai/concepts/output-format.md)

| 示例 | 含义 |
| --- | --- |
| `mp3` | 原生 24000 Hz；文档不承诺固定默认码率 |
| `mp3_44100_128` | MP3，44100 Hz，128 kbps |
| `wav_48000` | WAV，48000 Hz，不带码率 |
| `flac_48000` | FLAC，48000 Hz，不带码率 |
| `opus_48000_64` | Opus，48000 Hz，64 kbps |
| `aac_44100_137` | AAC，44100 Hz，137 kbps |

采样率支持 8000、11025、12000、16000、22050、24000、32000、44100、48000 Hz；Opus 只支持 8000、12000、16000、24000、48000 Hz。MP3 按采样率列出合法码率；AAC 为 8–320、Opus 为 6–510 kbps，可手填范围内整数。WAV/FLAC 不带码率。输出固定单声道；提高输出采样率不增加原生音频带宽。

“生成返回方式”可选：

| 选项 | 处理方式 |
| --- | --- |
| 同步完整音频 | `delivery=sync`，一次合成请求返回完整音频 |
| HTTP 流式接收 | 使用 HTTP 流式 TTS，逐块接收，完整结束后再使用；当前仅 MP3/WAV |
| 后台生成 | `delivery=async`，一次 POST 创建任务，再查询该任务并下载完成音频 |

依据：[Convert](https://docs.breezeblue.ai/api-reference/text-to-speech/convert-text-to-speech.md)、[HTTP stream](https://docs.breezeblue.ai/api-reference/text-to-speech/stream-text-to-speech.md)、[任务状态](https://docs.breezeblue.ai/api-reference/text-to-speech/get-generation-job.md)、[任务音频](https://docs.breezeblue.ai/api-reference/text-to-speech/download-generation-job-audio.md)。

后台生成只创建一次任务，模块构造同一服务的 `/v1/generation-jobs/{id}` 状态请求和 `/{id}/audio` 下载，不跟随响应中的 `download_url`，也不因轮询而再次生成。任务失败、超时或取消会受控结束；超时或取消不能证明服务没有继续生成或计费。HTTP 流式在连接完整结束前不会交付半成品，不会边生成边发布评论。

API 的裸 PCM 没有音频容器，当前试听和发送入口不提供该选项；请选 WAV。字词时间戳、实时会话控制和 ASR 不放入此纯音频发送设置。

## 正文、备份和验证边界

默认账户正文上限为 1000 字符，获批账户可按配置提高至最多 2000；模块保留完整正文，超限失败，不截断或自行拆请求。服务对部分语言和长度可能自行分段并合并，仍受账户总字符上限约束；服务端分段不保证每个字都已实际朗读。[TTS 字符及分段规则](https://docs.breezeblue.ai/guides/text-to-speech.md)

接口、生成、返回方式和音色筛选独立参与配置备份，默认去除 Key；旧密钥只在服务边界匹配时保留，不借用其它来源密钥，目录缓存不导出。新增合成 HTTP 层不自动重复生成，自动评论原有语音重试和文字回退、自动回复失败策略保持。既有请求截止和 20 MiB 音频上限继续适用。

本说明只描述当前实现和官方接口合同，未声称已完成真实 Breeze 账户合成、音质、手机解码或抖音发布验收。本地测试和最终 APK 验证结果由对应版本发布记录单独给出。

## 原始接口依据

核对日期：2026-10-27。以 Breeze 自己的原生 API、指南与输出格式为依据，不套用 Fish 或 MiniMax 参数。

- [官方 TTS 指南](https://docs.breezeblue.ai/guides/text-to-speech.md)
- [原生 OpenAPI](https://api.breeze.blue/openapi.json) · [文档站 OpenAPI](https://docs.breezeblue.ai/openapi.json)
- [模型列表](https://docs.breezeblue.ai/api-reference/models/list-models.md) · [音色列表](https://docs.breezeblue.ai/api-reference/voices/list-voices.md)
- [语音指令编写](https://docs.breezeblue.ai/guides/voice-instruction-prompting.md)

原始公开快照保存在 `work/breeze-tts-20261027/`；本轮补充的返回方式与指令优化文档保存在 `work/tts-docs-v2-20261027/`。
