# Random TTS source scope implementation plan

**Goal:** 大随机可以多选参与来源，默认全部，未选择的来源绝不参与。

**Spec:** `docs/superpowers/specs/2026-11-07-random-tts-source-scope-design.md`

## Tasks

- [ ] 配置和筛选：新增 `SpeechSettings.RANDOM_SOURCES="tts_random_sources"`，JSON 字符串保存来源 ID 列表，缺省为全部；严格编辑/导入验证；持久配置异常时按空范围处理。大随机过滤来源，未选系统时跳过探测；小随机和关闭保留现有行为。配置备份登记并回归。
- [ ] 设置与说明：大随机下新增“来源范围”多选，至少一项，确认保存/取消不保存，重开恢复，更新试听快照和帮助；保留其它模式的范围值。
- [ ] 分别先运行有意义的失败测试，再实现并运行相关配置、选择器、合成、设置/帮助测试；协调 Gradle 单进程，独立审阅最终差异。
- [ ] 记录真实测试范围；本次不执行 APK 构建，不宣称真机或真实服务验收。
