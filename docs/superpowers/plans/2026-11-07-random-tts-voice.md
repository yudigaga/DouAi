# Random TTS voice implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 每次合成支持关闭、小随机和大随机，分别使用固定音色、当前来源音色和跨来源音色。

**Architecture:** 随机选择在共享合成入口完成，以不可变请求快照覆盖来源和音色。系统音色在引擎初始化后选择，网络音色使用有效的已加载目录。

**Tech Stack:** 现有 Java、Android TextToSpeech、Robolectric/JUnit、Miuix 设置组件；不增加依赖。

**Spec:** `docs/superpowers/specs/2026-11-07-random-tts-voice-design.md`

## Global constraints

- 默认关闭；小随机不离开当前来源；大随机先等概率抽来源再抽音色。
- 不修改持久保存的手动来源和音色；普通随机允许重复。
- 仅使用有效已加载目录；保持账户/地址/筛选绑定和系统离线语言限制。
- 混音、多说话人和参考音频模式不参与随机单音色。
- 保留正文、语速、音调、音频格式、取消/超时、既有重试和文本回退行为。

## Review focus

- 设置试听必须携带目录样本和绑定标记，不能只在自动流程中生效。
- 切换来源保留有效目录，换账户或筛选不能使用旧目录。
- 不使用 `LocalOptions.withDefault` 覆盖已有键；请求之间不能串音色。
- 第三方 GET 未使用 `{voice}` 时不能宣称随机生效。
- 发布重试复用音频；取消请求不能继续触发合成。

## Task 1: 随机选择与共享合成

**Files:** `SpeechSettings.java`、`LocalOptions.java`、新增 `RandomSpeechVoice.java`；`SpeechSynthesis.java`、`LocalSpeechSynthesis.java`；相应配置、随机选择与合成测试。

**Interfaces:** `SpeechSettings` 提供随机模式常量与规范化读取；`LocalOptions.withOverrides(Map<String, ?>)` 返回不可变覆盖副本；`RandomSpeechVoice.select(LocalOptions, java.util.Random)` 返回已选择来源/音色的请求快照，或抛出可展示的配置错误。

- [ ] 编写并运行失败测试：关闭原样、小随机不跨来源、大随机可选各有效来源、非法/空池排除、目录绑定、混音模式、快照隔离和随机边界。
- [ ] 实现最小随机选择器与模式读取；来源先抽取一次，音色再抽取一次。
- [ ] 在共享合成入口派生请求配置，系统引擎初始化后在语言匹配的离线音色内随机。
- [ ] 运行新增测试及 `SpeechSettingsTest`、`SpeechSynthesisTest`、`SpeechPreparationTest`，确认固定模式和失败不转移来源的既有契约。

## Task 2: 设置、目录生命周期和使用说明

**Files:** `SpeechSettingsDialog.java`、`SpeechSettingsDialogTest.java`、`HelpContent.java`、`docs/help.md`。

**Interfaces:** 复用 Task 1 的随机模式常量；在“语音来源”后显示“随机音色”，三档分别说明范围。

- [ ] 编写设置持久化、重开恢复、切换来源和试听目录快照测试，并运行确认失败。
- [ ] 添加三档选择和简短提示；在试听快照补齐各来源目录及标记；保留可用于跨来源选择的有效目录。
- [ ] 更新帮助，说明先刷新目录、可用来源、随机允许重复和单音色限制。
- [ ] 运行相关 UI/目录测试并执行 `./gradlew.bat :app:lintRelease --console=plain`。
- [ ] 独立审阅完整差异，修正发现的问题，记录实际测试范围及未执行的设备/远端检查。未获发布授权时仅保留本地改动。
