# 配置 Fish Audio 文本转语音

进入“扩展功能 → 文本转语音设置”，将“语音来源”切换为“Fish Audio”。Key、引擎、音色、朗读与高级参数独立于其它来源保存。此页仅将完整正文生成音频，等待完整文件后试听或交给既有发送入口，不包括 ASR、声音模型训练、发布或管理。[官方 TTS 指南](https://docs.fish.audio/features/text-to-speech.md)

## 标签优先、搜索和中文译名（0.2.28）

目录默认先按中文筛选，每页请求 20 项，后续手动加载；每组筛选最多保留 200 项。公开范围需要至少一个有效的语言、标签、搜索或该平台特有筛选条件。Breeze 可选语言、类别、多选性别/年龄/语气、口音、未标注口音、来源、收藏、自定义标签、范围和排序；Fish 可选多种音色语言/名称语言、自由标签、作者、许可标记、范围和排序，不套用 Breeze 的性别枚举。Breeze 同组性别/年龄/语气按任一匹配，自定义标签要求全部匹配；Fish 多值条件交由其原生接口处理，不臆造相同语义。排序不会被当作缩小目录的条件；Fish `licensed=false` 也不算有效缩小条件。显示的标签来自接口元数据，不能据此保证质量。

列表内搜索匹配已加载的中文译名、原名、ID 和标签，不联网；“搜索全部音色”才带着同一组筛选查询接口。缓存绑定供应商、接口、账户密钥及完整查询条件；旧版无查询标记的缓存需要重新刷新。快捷音色同样支持本地搜索和缓存译名。

“翻译名称”使用当前启用的文字 AI，每次最多 100 个未缓存名称、每批最多 40 个，长名称按完整 JSON 的长度进一步缩小批次。不会自动调用或自动重试；只发送名称和本批序号，独立请求不读写聊天历史。成功后显示“中文译名（原名）”，ID 和原始名称保留；中途失败保留成功批次与原名。译名是模型辅助显示，官方没有提供统一中文名称字段。关闭或切换目录、筛选、账户、文字模型后拒绝旧译名回调。译名与查询标记是有界本地缓存，不加入配置备份；标签筛选配置参与正常备份。

## 先选择语音引擎

公开音色可先浏览，不必先填写 Key。需要合成、试听或读取“我的音色”时，再到“接口设置”填写独立的 Fish API Key；地址默认 `https://api.fish.audio`，Key 只填密钥本身。主页面的“语音引擎”表示 HTTP `model` 请求头；当前固定可选 `s2.1-pro`（默认）、`s2.1-pro-free`、`s2-pro`、`s1`、`drama-3`，不提供无效的通用模型刷新或引擎手填入口。

这些名称来自[原生 TTS API](https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech.md)的 `model` 枚举。引擎与音色 ID 分开；列表不是账户权限或额度证明，未知引擎不能靠填音色 ID 代替。

## 选择音色来源

打开“音色 → 音色来源”。切换模式保留其它模式的配置，不改写待朗读正文或自动回复/评论提示词。

| 模式 | 音色条件 | 使用方式 |
| --- | --- | --- |
| 单音色 | 一个库内 `reference_id` | 可从音色库选择或手填；留空使用服务默认音色 |
| 多说话人音色 | 按顺序排列的 `reference_id` 数组 | 每个说话人选择库内音色，正文显式写编号标签 |
| 参考音频 | 本机导入的参考样本与精确原文 | 作为本次 TTS 的声音条件，不创建或训练远端模型 |

单/多说话人、`reference_id` 和 `references` 的语义见[原生 API](https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech.md)。S1 不支持多说话人模式；S2 家族与 Drama 3 支持多说话人。单音色和各组参考样本分别保存。

### 使用已就绪的 TTS 音色

在“音色 → 音色库”设置“音色范围”，可填“音色搜索”，再点“刷新音色”。未保存过范围时默认“公开音色”；已保存的范围保留，若之前选过“我的音色”，可在这里切换。“我的音色”指当前 active workspace，需要有效 Key。公开目录可匿名读取；填写了 Key 时请求沿用该 Key。真正合成或试听始终需要有效 Key。[音色列表 API](https://docs.fish.audio/api-reference/endpoint/model/list-models.md)

列表先显示首屏，标题标明已加载项数、页码和当前查询状态。还有后续页面时可点“加载更多”，新页按 ID 去重追加；无需先把整个公开库下载完。模块只保留 `type=tts` 且 `state=trained` 的结果，变声模型和未就绪模型不进入选择列表；分页仍按接口原始条目推进。

公开目录可能返回“搜索窗口内结果”和非精确总数，这是正常的浏览边界，不是接口失败，也不表示已得到全库。窗口到底、无法确认更多或达到本机 200 项浏览预算时，已加载音色仍可选择，可缩小名称搜索后刷新。后续页失败或重复时保留已加载结果及当前选择，不把部分目录标为完整全库。[本次窗口行为核对与复现](2026-10-27_fish-catalog-window-fix.md)

`/model` 返回音色模型，界面中的“语音引擎”则是合成引擎。打开选择框只读取缓存，不自动合成；更换 Key、地址或目录筛选后重新刷新，选中的音色 ID 不因修改筛选自动清空。

### 配置多说话人正文

选择“多说话人音色”，依次“添加说话人”，为说话人 0、1 等选择音色后“保存并使用”。同一音色 ID 可以用于不同编号。界面最多 8 位是本模块编辑预算，不是 Fish API 宣称的最大说话人数。

正文中的编号从 0 开始，并与列表顺序对应，例如：

```text
<|speaker:0|>早上好。<|speaker:1|>早上好，今天开始新的工作。
```

标签需要由你明确写入正文。编号超出已配置列表、标签格式错误或多说话人正文缺少标签时会提示；模块不自动插入标签、不重排台词，也不改变 AI 的提示词。标签依据：[TTS API](https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech.md)。

### 导入本次 TTS 的参考音频

1. 选择“参考音频”，点击“添加参考音频 → 选择音频文件”。支持 WAV、MP3、FLAC；文件内容须符合对应格式。
2. 填写“说话人编号”和“样本原文”，原文须准确对应音频实际说出的内容；可填样本名称。
3. 保存样本，再“保存并使用”。同一说话人可以有多个样本，编号从 0 开始连续排列。单个分组可直接用于单说话人 TTS；多个分组的正文需要对应编号标签，S1 只允许一个分组。

官方建议使用 10–30 秒、清晰且背景噪声少的语音；这是质量建议，不是模块伪造的时长硬上限。8 个分组、每组最多 4 份样本和合计 8 MiB 是本模块素材与编辑预算。[参考音频格式及原文要求](https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech.md)

参考音频会先复制到当前宿主的本机素材目录，配置保存路径与样本原文；合成时将音频原始字节与逐字原文作为 `references` 通过 MessagePack binary 发送到 Fish，不把音频编码为配置中的 Base64。取消未保存编辑会清理此次未使用的导入副本；不会把手写任意文件路径作为可读素材。

参考素材和路径不导出到普通配置备份，也不能从备份注入。可携带的说话人 ID 列表正常备份；同一设备已有合法素材绑定会保留，跨设备恢复后需重新导入本机参考音频。此模式仅为 `/v1/tts` 提供本次声音条件，不执行模型创建、训练或远端发布。

## 调整朗读与文本表达

| 朗读设置 | 范围与默认值 | 适用性 |
| --- | --- | --- |
| 语速 | 0.5–2，默认 1 | 所选 TTS 引擎 |
| 音量（dB） | -20–20，默认 0 | 分贝增益，不是倍率 |
| 响度归一化 | 默认开启 | S2 家族有效；选择 S1 时隐藏且请求不发送此字段 |

音量范围采用官方 [SDK 类型](https://docs.fish.audio/api-reference/sdk/python/types.md)，原生 OpenAPI 未标注数值上下限；S1 响度无效的说明见 [TTS API](https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech.md)。隐藏控件时保存值保留，切回支持的引擎可恢复。

“文本标签说明”按当前引擎说明写法：S2 系列使用方括号中的自然语言提示，例如 `[softly]你好。`；S1 使用其固定圆括号标签，例如 `(happy)你好。`；Drama 3 使用自己的自然语言指示、声音、停顿及成对标签。模块不把某个引擎的标签强行转换给另一个引擎，也不自动插入正文。[表达控制](https://docs.fish.audio/developer-guide/core-features/emotions.md) · [Drama 3](https://docs.fish.audio/features/text-to-speech/drama-3.md)

## 选择输出音频

进入“Fish Audio 高级设置 → 音频输出”。默认 MP3，采样率“接口默认”表示不发送 `sample_rate`。

| 格式 | 采样率 | 码率 |
| --- | --- | --- |
| MP3 | 32000、44100 Hz；接口默认 44100 | 64、128、192 kbps；默认 128 |
| WAV | 8000、16000、24000、32000、44100 Hz；默认 44100 | 不发送码率 |
| Ogg/Opus | 48000 Hz | 自动，或 24、32、48、64 kbps |

范围和单位见[原生 API](https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech.md)。Opus 页面显示 kbps，原生 API 使用 bps：自动为 `-1000`，其余为 `24000/32000/48000/64000`；MP3 则发送 kbps，不可混用。WAV 不发送任何码率字段。

输出为单声道，HTTP 音频可逐块接收，但需完整结束并通过文件校验后才使用。裸 PCM 没有容器，当前试听和发送入口不提供该选项，请选 WAV。模块不悄悄转码、补容器或替换为其它格式。

## 保留原生生成参数

在“Fish Audio 高级设置 → 生成参数”调整以下原生字段。服务端分块不代表模块拆分或截断正文。

| 参数 | 范围与默认值 |
| --- | --- |
| 温度、Top P | 0–1，默认均为 0.7 |
| 延迟模式 | `normal/balanced/low`，默认 `normal` |
| 分段长度 | 100–300，默认 300 |
| 最短分段长度 | 0–100，默认 50 |
| 最大生成 Token | 正整数，默认 1024；每个服务端分块 |
| 重复惩罚 | 有限正数，默认 1.2 |
| 关联前序分段 | 默认开启 |
| 提前结束阈值 | 0–1，默认 1 |
| 质量保护 | 默认关闭；开启发送 `features: ["quality-guard"]` |

采用[原生 OpenAPI](https://api.fish.audio/openapi.json)的默认值 `normal/300`；功能指南写的是 `balanced/200`，与原生 OpenAPI 默认值不同；本模块明确采用后者，不悄悄改写已保存值。官方 schema 未给最大音频 Token 和重复惩罚声明额外上限，不应据界面预算杜撰服务限制。特性是否对当前引擎可用仍由后端决定。

## 设置文本归一化和发音

“语言与发音”中的“文本归一化”默认开启，用于数字、日期、单位等读法。开启时可填 `zh-CN` 等最多 35 字符的 BCP-47 标签，或留空/`auto` 自动检测；该语言不更改音色、口音或模型的发音语言。[TTS 语言字段](https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech.md)

关闭文本归一化后，页面隐藏归一化语言并在本次纯音频请求中省略该字段，保存值仍保留。本功能不请求字词时间戳，因此不提供只影响时间戳对齐的语言选项。

“发音字典（JSON）”可填最多 3 个同类字典：全部使用已发布字典的 `id/version` 引用，或全部使用内联 `items`，不能混合。每个字典最多 5000 项；`key` 为 1–256 字符，`value` 为 1–1024 字符，`case_sensitive` 可选。[官方发音字典](https://docs.fish.audio/developer-guide/core-features/fine-grained-control/pronunciation-dictionaries.md)

```json
[{"items":[{"key":"Kubernetes","value":"K UW2 B ER0 N EH1 T IY0 Z","case_sensitive":false}]}]
```

`key` 按字面子串匹配，`value` 是发音符号而非任意替换正文或自动 IPA 转换；英语用 CMU Arpabet，中文用带声调数字的拼音。不要自行添加 `<|phoneme_start|>`、`<|phoneme_end|>` 包装标记。引用须填写准确版本，不能用 `latest`；服务可能跳过找不到的字典，所以合成成功不证明字典已经生效。

## 备份与验证边界

普通 Fish 设置和说话人 ID 列表独立参与备份，默认去除 Key；音色缓存、本机音频样本、路径和样本原文不导出。恢复旧 Key 受服务地址边界约束，不借用其它来源密钥。更换账户或地址后刷新音色库。

正文和生成音频保持完整；模块不套用 Breeze 的字符上限，仍受 Fish 账户、引擎、既有合成截止、20 MiB 音频预算及发送入口校验约束。新增 HTTP 层不自动重发生成，原有评论语音重试和文字回退、自动回复失败处理不变。

本说明描述原生接口与当前实现，不声称已完成真实 Fish 账户合成、发音质量、手机解码或抖音发布验收。本地测试与最终安装包结果由对应版本记录单独给出。

## 原始接口依据

核对日期：2026-10-27。以下均为 Fish 自己的原生接口和指南，未使用 Breeze 或 MiniMax 的专有字段：

- [TTS 指南](https://docs.fish.audio/features/text-to-speech.md) · [TTS API](https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech.md)
- [原生 OpenAPI](https://api.fish.audio/openapi.json) · [文档站 OpenAPI](https://docs.fish.audio/openapi.json)
- [音色列表](https://docs.fish.audio/api-reference/endpoint/model/list-models.md) · [SDK 类型](https://docs.fish.audio/api-reference/sdk/python/types.md)
- [发音字典](https://docs.fish.audio/developer-guide/core-features/fine-grained-control/pronunciation-dictionaries.md)

原始快照位于 `work/fish-tts-20261027/`、`work/fish-docs-comparison-20261027/` 和 `work/tts-docs-v2-20261027/`。
