# DouAi 构建与当前状态

源码仓库：https://github.com/yudigaga/DouAi

下载入口：https://github.com/yudigaga/DouAi/releases

LSPosed 模块仓库：https://github.com/Xposed-Modules-Repo/io.github.yudigaga.douai

正式包名：`io.github.yudigaga.douai`。Java namespace 保持 `com.douai.module`，Xposed 入口使用完整 Java 类名。

## 当前状态

本文是当前版本、验证范围与发布状态的统一入口，历史版本记录保留各自当时的证据。

| 范围 | 版本 | 状态与证据 |
| --- | --- | --- |
| 当前正式发布 | **0.2.34 / 135** | GitHub `v0.2.34`、LSPosed `135-0.2.34` 使用同一原签名 APK。AI 模型管理仅保存配置，快捷菜单按功能选择模型，修复全局切换误取消独立自动化请求。340 项相关专项及最终 APK 的 5 项合同通过，Release Lint 0 错误/54 警告/0 新增未审代码警告，原密钥 v3、16 KiB 对齐通过。未跑本地完整测试集或真机/真实服务，远端 CI 独立记录，见 [0.2.34](release-0.2.34.md) |
| 既有正式发布 | **0.2.33 / 134** | GitHub `v0.2.33`、LSPosed `134-0.2.33` 使用同一原签名 APK。新增仅模块控制倍速、七档预设含 4 倍与旧值迁移；倍速目标提示不再被未确认吐司覆盖。210 项相关专项及最终 APK 的 5 项合同通过，Release Lint 0 错误/54 警告/0 新增未审代码警告，原密钥 v3、16 KiB 对齐通过。未跑完整测试集或新包真机，见 [0.2.33](release-0.2.33.md) |
| 既有本地正式构建 | **0.2.32 / 133** | 增加 SDK 异步、缓存跳过及原生 option 60 的对象归属保护，保留持续倍速开关；148 项相关专项、两版各 242 条规则唯一匹配及速度合同通过。用户反馈修改有效，但读回仍有 1.7 未确认，0.2.33 单独修正提示和新增控制方式；原构建证据保留，见 [0.2.32](release-0.2.32.md) |
| 既有本地正式构建 | **0.2.31 / 132** | 跨视频持续开关、全区域原生拦截和配置同步。原专项与签名记录保留；用户后续反馈短暂生效后回落，新日志两次 2→1.7 未确认，继续排查 SDK 入口，见 [0.2.31](release-0.2.31.md) 与 [底层保护记录](2026-10-29_engine-speed-guard.md) |
| 既有本地正式构建 | **0.2.30 / 131** | 从 DOWN 接管并绑定真实祖先检测器，短按/滑动回放原输入，改用实际引擎速度确认。原相关专项、双版本合同及签名核验记录保留；用户后续明确需要跨视频持续选择，并提供 2→1.7 的读回日志，见 [0.2.30](release-0.2.30.md) 与 [持续倍速修复](2026-10-29_persistent-gesture-speed.md) |
| 既有本地正式构建 | **0.2.29 / 130** | 修正倍速读取误用 FPS 指标、异常恢复基线与过早成功提示。98 项相关测试及最终签名 APK 的 5 项打包检查通过，Release Lint 0 错误/54 警告/0 新增未审代码警告，长期证书 v3 与 16 KiB 对齐通过。用户后续反馈仍未持续生效，见 [0.2.29](release-0.2.29.md) 与 [后续修复](2026-10-28_gesture-speed-native-followup.md) |
| 既有正式发布 | **0.2.28 / 129** | GitHub `v0.2.28`、LSPosed `129-0.2.28` 使用同一原签名 APK。Fish 与 Breeze 增加原生标签筛选、列表与全库搜索、按需 AI 译名；保留 0.2.27 分页修复。Release Lint 0 错误/54 既有警告/0 新增，长期证书 v3 与 16 KiB 对齐通过。维护者确认手动测试通过；自动化专项捕获退出码 1，未产生 JUnit 或封存清单，不报告自动化通过数；完整全量未运行，见 [0.2.28](release-0.2.28.md) |
| 既有本地正式构建 | **0.2.27 / 128** | 修复 Fish 公开目录窗口误判和 Breeze 2048 项整目录失败；两家独立逐页浏览、失败保留与取消归属。最终签名 APK 的 37 套件 / 701 项专项零失败/错误/跳过；Release Lint 0 错误/54 警告/0 新增，长期证书 v3 和 16 KiB 对齐通过。Fish 匿名公开 GET 通过，Breeze 仅做开发机匿名可达性检查；见 [0.2.27](release-0.2.27.md) |
| 既有本地正式构建 | **0.2.26 / 127** | 分别按 Fish 与 Breeze 的原生 TTS 完善引擎/音色、多说话人/参考音频、自动模型、三种交付、指令优化与有效参数展示。最终签名 APK 的 37 套件 / 665 项专项零失败/错误/跳过，含实际请求合同、Native/Miuix 设置、备份、旧语音/自动化及最终 APK/40.8.0 静态合同；Release Lint 0 错误/54 警告/0 新增，长期证书 v3 与 16 KiB 对齐通过。未跑全量、真机或真实账户合成，未发布远端，见 [0.2.26](release-0.2.26.md) |
| 既有本地正式构建 | **0.2.25 / 126** | 新增 Breeze Blue 与 Fish Audio 独立语音配置，支持模型、音色、高级参数、快捷选择、备份和帮助。最终签名 APK 的 36 套件 / 610 项专项零失败/错误/跳过，含请求、Native/Miuix 设置、既有语音/自动化与最终 APK/40.8.0 静态合同；Release Lint 0 错误/54 警告/0 新增，长期证书 v3 与 16 KiB 对齐通过。未跑全量、真机或真实账户合成，未发布远端，见 [0.2.25](release-0.2.25.md) |
| 既有正式发布 | **0.2.24 / 125** | GitHub `v0.2.24`、LSPosed `125-0.2.24` 使用同一份原签名 APK。本地分析独立模型与二级提示词，累计纳入 0.2.23 的自动化独立模型选择与语音缓存并发修复；本版 33 套件 / 573 项专项零失败/错误/跳过，含分析请求、Native/Miuix 设置、备份、PNG/PDF 导出及最终 APK/40.8.0 静态合同。Release Lint 0 错误/54 警告/0 新增代码警告，长期证书 v3、16 KiB 对齐通过。未跑全量、真机或真实服务，见 [0.2.24](release-0.2.24.md) |
| 既有本地正式构建 | **0.2.23 / 124** | 自动回复与自动评论独立选择已有供应商配置，修复首次并发语音缓存目录创建误失败；25 套件 / 470 项专项零失败/错误/跳过，含最终签名 APK 与抖音 40.8.0 静态合同。Release Lint 0 错误/54 警告/0 新增代码警告，长期证书 v3、16 KiB 对齐通过。未跑全量、真机或真实服务，未发布远端，见 [0.2.23](release-0.2.23.md) |
| 既有正式发布 | **0.2.22 / 123** | GitHub `v0.2.22`、LSPosed `123-0.2.22` 使用同一份原签名 APK，累计纳入 0.2.19 至 0.2.21 的 Miuix 查找、删除修复与运行时优化。评论回复从准备阶段登记共享浮窗；本版 13 套件 / 368 项专项零失败/错误/跳过，含抖音 40.8.0 / 40809900 原 APK 及最终签名包合同；Release Lint 0 错误/54 警告/0 新增代码警告，原证书 v3、16 KiB 对齐通过。未重跑全量，修复包真机与真实服务待验，见 [0.2.22](release-0.2.22.md) |
| 保留的完整本地基线 | **0.2.21 / 122** | 运行时开销优化；以抖音 40.8.0 / 40809900 主验，236 套件 / 2,430 项完整本地测试零失败/错误/跳过，Release Lint 0 错误/54 警告/0 新增代码警告，原证书 v3、16 KiB 对齐与完整性通过；真机性能未测、未发布，见 [0.2.21](release-0.2.21.md) |
| 保留的本地正式构建 | **0.2.20 / 121** | 查找器统一 Miuix UI，修复删除确认框缺少导航环境的闪退，补足宿主生命周期隔离。15 套件/223 项专项及最终 APK 合同、Release Lint、原证书 v3 签名和 16 KiB 对齐通过；未重跑全量、真机待验、未发布，见 [0.2.20 本地正式版](release-0.2.20.md) |
| 保留的功能基线 | **0.2.19 / 120** | 用户确认查找功能可用后追加 Miuix UI 要求，全量验收已中止，原签名包及 mapping 保留；不计作完整验收通过，见 [0.2.19 构建记录](release-0.2.19.md) |
| 既有远端正式版 | **0.2.18 / 119** | 修正播放过渡空白与手动翻页衔接，增加信息流手势快捷功能、模型切换、系统离线音色选择及直接打开模块设置；2,313 项本地测试、Release Lint、长期证书 v3 签名、16 KiB 对齐和宿主 40.8.0 静态合同通过，真机与真实服务未执行。产物与边界见 [0.2.18 正式发布记录](release-0.2.18.md) |
| 既有远端正式版 | **0.2.16 / 117** | 纳入日志重构与缺口修复，统一模块日志和 LSPosed 的简单/详细输出；原构建与验证范围见 [0.2.16 正式发布记录](release-0.2.16.md) |
| 既有本地候选 | **0.2.15 / 116** | 扩展 40.8 视频分类筛选：商品/购物卡、热点榜单卡独立开关，附近广告并入去广告，推荐商家分类收窄至生活 SPU。保留 0.2.14 自动滑动与长按修复。本地验证与 APK 见 [0.2.15 候选记录](release-0.2.15.md)，未发布 |
| 既有本地候选 | **0.2.14 / 115** | 自动滑动晚安装恢复、Activity 暂停保护及长按释放拦截；详细验证与 APK 见 [0.2.14 本地候选记录](release-0.2.14.md) |
| 既有远端正式版 | **0.2.13 / 114** | 配置入口显式化、音频草稿导入、群会话 ID 手填和模型可选输入；10 套件/154 项专项零失败/错误/跳过，Lint、原签名、对齐及源码/产物一致性通过，当时未重跑全量，手机待验，见 [0.2.13 正式发布记录](release-0.2.13.md) |
| 既有远端正式版 | **0.2.12 / 113** | 修复 Miuix 日志页无界测量闪退；32 项相关检查通过，原验证与产物见 [0.2.12 正式发布记录](release-0.2.12.md) |
| 既有已验收本地包 | **0.2.11 / 112** | 全局#长按语音、原大小双色图标浮窗与淡化微移、提前聊天进度、自动IM JSON和日志分类搜索；全量本地验收通过，见[0.2.11记录](release-0.2.11.md)，设备待验，未远端发布 |
| 既有本地候选 | **0.2.10 / 111** | 生成语音非空波形修正、关于页全局简要/详细日志及自动脱敏评论 JSON、评论查看器、独立R8反射名修复；保持40809900与40.6。构建与专项结果见[0.2.10记录](release-0.2.10.md)；全量本地验收通过，设备效果待复测，未远端发布 |
| 既有本地候选 | **0.2.9 / 110** | 40.8只保留40809900构建、移除40809985，保留40.6；新229规则全唯一，构建与必要检查见[0.2.9记录](release-0.2.9.md)。按用户要求不跑全量验收，未远端发布 |
| 既有本地候选 | **0.2.8 / 109** | 评论原文件发送；MiniMax实验AAC、Opus、μ-law WAV、URL/Hex及音色混合，语音设置分组整理。签名构建完成，专项151项及平台2项通过；按用户要求停止全量验收，见[0.2.8记录](release-0.2.8.md)，尚未远端发布 |
| 既有远端正式版 | **0.2.7 / 108** | 抖音40.6.0 / 400601与40.8.0 / 400801双版本适配；各229条规则唯一匹配及51项资源核验，最终签名包191套件/2037次执行零失败/错误/跳过；Lint 0错误/57警告/0新增，签名、对齐与完整性通过，见 [0.2.7 正式发布记录](release-0.2.7.md) |
| 既有已验收本地基线 | **0.2.6 / 107** | 安全校验自动移除旁路修复；修复前 12 项/5 失败保留，最终签名包 186 套件/1993 次执行零失败/错误/跳过，229 条规则唯一匹配、34 工具及 3 平台合同通过；Lint 0 错误/57 警告/0 新增，签名、16 KiB 对齐与完整性通过，见 [0.2.6 本地记录](release-0.2.6.md) |
| 已验收本地基线 | **0.2.5 / 106** | 发送账本核对、后台持久化、头像加载、配置目录与 21 项设置搜索、Android 平台烟测已实现；最终签名包 185 套件/1971 次执行零失败/错误/跳过，另 34 项工具检查与 3 项平台合同通过；Lint 0 错误/57 警告/0 新增，签名、16 KiB 对齐与完整性通过，见 [0.2.5 本地记录](release-0.2.5.md) |
| 既有正式版 | **0.2.4 / 105** | GitHub `v0.2.4`、LSPosed `105-0.2.4`，原验证与产物见 [0.2.4 发布记录](release-0.2.4.md) |
| 当前正式发布标签 | **0.2.34 / 135** | GitHub `v0.2.34`、LSPosed `135-0.2.34`，两端使用同一原签名 APK，纳入模型保存、按功能切换及独立任务隔离修复 |
| 既有 Android 平台烟测 | Android 15 / API 35 x86_64 模拟器 | 0.2.6 Debug/测试 APK 重新构建后，三项模块平台合同通过、本地与实际安装摘要相等；本轮单次指标见 [0.2.6 平台记录](release-0.2.6.md#平台模拟器证据)，不代表隐藏场景通过。0.2.5 记录独立保留 |
| 手机宿主与真实服务 | 0.2.34 未执行 | 本版模型选择与任务隔离未做真实 LSPosed、模型或 TTS 服务验收。旧倍速、语音、浮窗等变更继续保留各自设备边界，历史反馈不代替新包验收 |

0.2.34 的原构建记录为 `artifacts/DouAi-0.2.34-build.json`，20 个 suite / 340 次相关专项及最终签名 APK 的 5 项合同全部通过，共 21 个 suite / 345 次执行，零失败/错误/跳过。包含 18 个完整类和两个 UI 类的 10 个指定方法；原签名 v3、16 KiB 对齐、Release Lint 与预算通过，未跑本地完整测试集、双宿主重扫、模拟器、真机或真实服务。原记录 `published=false` 保留，源码提交的远端 CI 与双端发布核验另存 `artifacts/DouAi-0.2.34-publication.json`，见 [0.2.34](release-0.2.34.md)。

0.2.33 的原始构建记录为 `artifacts/DouAi-0.2.33-build.json`，专项与工具日志位于 `work/gesture-toast-20261029/`。9 个 suite、210 项相关专项包含 7 个完整测试类及两个 UI 类的 8 个指定方法，另有最终签名 APK 的 5 项合同，合计 10 个 suite、215 次执行，零失败/错误/跳过。原密钥 v3、16 KiB 对齐、Release Lint 与预算通过，未运行完整测试集、新包真机、模拟器或物理倍率测量；0.2.32 双宿主扫描不计作本版重跑。原构建记录的 `published=false` 保留为本地快照，本轮发布和下载核验另存 `artifacts/DouAi-0.2.33-publication.json`，见 [0.2.33 正式发布记录](release-0.2.33.md)。

0.2.28 的候选记录位于 `artifacts/targeted-verification/20261027-voice-tags-final-r3-0.2.28/`，构建前锁定 596 个源码/构建/工具输入；候选 Release APK 5,379,561 字节，SHA-256 `FEE0709E6FF10FD4B111B6C93E72337A8D617626B4FD3242EE8321C624AABDE8`，原证书 v3、16 KiB 对齐和 Release Lint 通过。维护者确认手动测试通过。自动化专项捕获最后退出码 1，未生成 JUnit 报告或 `targeted-manifest.json`，因此不计作自动化通过，也不声称已封存。本次已按维护者的手动验收决定发布，并单独记录该自动化验证缺口；未重跑完整全量或 40.6.0 补充专项。细节见 [0.2.28 正式发布记录](release-0.2.28.md)。

0.2.27 专项记录为 `artifacts/targeted-verification/20261027-voice-catalog-0.2.27/`，本地候选的 37 套件 / 701 项专项和 Fish 匿名目录检查通过；该版未远端发布，新包设备与真实合成验收未执行，见 [0.2.27](release-0.2.27.md)。

0.2.26 专项封存为 `artifacts/targeted-verification/20261027-tts-docs-0.2.26/targeted-manifest.json`，构建前锁定 589 个源码、构建和工具输入及 37 个完整测试类的方法清单。最终签名包的 665 项全部通过，源码输入、实际工具执行、最终 APK/原始宿主、报告和签名/对齐证据一致；`verified-targeted-release`、`fullSuiteRun=false`、`published=false`。各家原生 TTS 合同、增量官方快照、新增包内标记及既有 0.2.25 对照独立归档。未跑全量、40.6.0 补充专项、平台、真机或真实账户合成验收，见 [0.2.26 本地正式版](release-0.2.26.md)。

0.2.25 专项封存为 `artifacts/targeted-verification/20261027-breeze-fish-0.2.25/targeted-manifest.json`，构建前锁定 585 个源码、构建和工具输入。最终签名 APK 的 36 个完整测试类、610 项检查全部通过，保留 `verified-targeted-release`、`fullSuiteRun=false`、`published=false`。清单绑定实际工具检查、测试环境、输入/报告哈希、JUnit/HTML、Lint、mapping 和 49 份官方语音资料快照；首次测试环境依赖失败与官方摘要校验后的缓存修复证明另行保存。未进行全量、40.6.0 补充专项、平台、真机或真实账户合成验收，未发布远端，见 [0.2.25 本地正式版](release-0.2.25.md)。

0.2.24 专项封存为 `artifacts/targeted-verification/20261026-analytics-ai-0.2.24/targeted-manifest.json`，在构建前锁定 577 个源码/构建/工具输入，最终签名包的 33 套件 / 573 项完整类级专项全部通过；保留本地验收快照 `verified-targeted-release`、`fullSuiteRun=false`、`published=false`。保存 JUnit/HTML、Lint、mapping、包内新设置标记、元数据、长期证书及对齐证据。配置与请求固定各自的模型和二级提示词，默认保持原分析任务；既有自动回复和评论选择保持。发布前复核源码输入、封存证据、原签名与对齐，两处使用同一 APK；远端发布证明独立保存在本地 `artifacts/DouAi-0.2.24-publication.json`。未重跑全量、40.6.0 补充专项、原生 ICU、工具或平台测试，未操作手机或调用真实服务，远端 CI 另行核对，见 [0.2.24 正式版](release-0.2.24.md)。

0.2.23 专项封存为 `artifacts/targeted-verification/20261026-automation-models-0.2.23/targeted-manifest.json`，绑定 573 个源码/构建/工具输入、最终签名包、25 套件 / 470 项完整类级专项报告、Lint、mapping 和签名/对齐证据；`verified-targeted-release`、`fullSuiteRun=false`、`published=false`。新增模型选择沿用各自供应商配置，不修改全局激活模型；评论区回复共用自动回复的选择。未重跑全量、40.6.0 补充专项、原生 ICU、工具或平台测试；未操作手机或调用真实服务。安装包与范围见 [0.2.23 本地正式版](release-0.2.23.md)。

0.2.22 专项封存为 `artifacts/targeted-verification/20261025-comment-reply-overlay-0.2.22/targeted-manifest.json`，绑定 568 个源码/构建/工具输入、最终签名包与 13 套件 / 368 项完整类级专项报告；零失败/错误/跳过，保留本地验收快照 `verified-targeted-release`、`fullSuiteRun=false`、`published=false`。相对 0.2.21 输入仅变更两个生产类、各自测试和版本配置。本版完成 40.8.0 相关静态合同，未重跑 40.6.0 专项、全量、平台模拟器或设备验证；远端发布与 CI 另行核对。发布前复核全部输入与证据摘要、原签名及对齐，GitHub/LSPosed 同步发布这份 APK，发布证明独立保存在本地 `artifacts/DouAi-0.2.22-publication.json`。历史日志与本地失败回归的证明边界见[修复记录](2026-10-25_comment-reply-overlay-report.md)。

0.2.21 严格本地清单为 `artifacts/release-verification/20261025T103522Z-5749781b/manifest.json`，绑定 568 个源码/构建/工具输入与最终签名包，`verified-local`、`fullSuiteRun=true`、`published=false`。2,430 项完整回归与 40.8.0 原 APK 合同通过，同包另完成 40.6.0 的 15 套件 / 89 项补充静态兼容检查，另 42 项工具检查和原生 ICU 合同通过，工作量与 R8 对比另行封存；真实设备性能与服务仍未验证。详见[实施记录](2026-10-25_performance-implementation-report.md)。

0.2.20 专项封存为 `artifacts/targeted-verification/20261025-miuix-finder-0.2.20-final/targeted-manifest.json`，绑定 554 个源码/构建输入及最终签名包，15 套件/223 项零失败/错误/跳过，Lint 0 错误/54 警告/0 新增代码警告，原签名与对齐通过；`fullSuiteRun=false`，未发布。0.2.19 完整验收已按后续 UI 要求中止，首轮 0.2.20 的 Lint 写法提示已在最终包修正，旧目录保留各自状态，不覆盖本次结果。

0.2.18 构建目录为 `artifacts/release-verification/20261024T090210Z-df562738/`，完成 ICU 预检、正式签名构建、2,313 项测试、Release Lint、宿主合同、签名、16 KiB 对齐和完整性封存。源码树在验收时包含待提交的版本与功能改动；发布提交会保留相同代码并补充 README、发布记录和台账。自动 CI、发布标签及远端资产摘要分别记录，真机与真实服务仍未执行。

0.2.16 构建目录为 `artifacts/release-verification/20261023T050901Z-0d4e459c/`，仅完成正式签名构建与产物核对，按此前要求在应用测试及 Lint 前停止。该目录不表示严格验收通过，原本地构建记录保留其当时范围。

0.2.13 当时的本地验证目录为 `artifacts/targeted-verification/20261022-menu-0.2.13/`，`targeted-manifest.json` 保留验收时的 `verified-targeted-release`、`fullSuiteRun=false` 和 `published=false`。当时核对其 524 个源码、构建、测试与工具文件与 `source-inputs.json`、清单中的快照一致；独立汇总该目录的 10 份 JUnit XML，154 项全部通过，并核对实际 Lint XML、增量预算、原签名及最终 APK。该目录的 28 份文件摘要另行记录，历史结果不作为 0.2.16 全量验收。

0.2.12 当时的专项封存为 `artifacts/targeted-verification/20261022-debug-log-0.2.12/targeted-manifest.json`，保留验收时的 `verified-targeted-release`、`fullSuiteRun=false` 和 `published=false`。当时复核其 521 个源码、构建、测试与工具文件及全部 35 份证据摘要一致；相对 0.2.11 的生产改动只有版本和日志页修复，另增加真实 Miuix 回归。0.2.11 完整验收清单为 `artifacts/release-verification/20261021T170847Z-6cd01b59/manifest.json`，其 2186 项结果保留为历史基线。历史结果不作为 0.2.13 全量验收。

0.2.7 当时的本地封存入口为 `artifacts/release-verification/20261021T050149Z-8a1c5172/manifest.json`，保留验收当时的 `verified-local`、`published=false`；该版封存、canonical 与当次构建 APK 摘要一致。同一 APK 的 40.6 原宿主 61 项复验已通过，兼容清单为 `artifacts/host-compatibility/0.2.7-20261021T050149Z-8a1c5172-406/compatibility.json`。其源码及证据属于当时的 0.2.7，不能作为当前构建的验收结果。`artifacts/`、`work/`、宿主 APK 和原始日志为本地材料，不随公共源码分发。

0.2.6 最终封存入口为 [manifest.json](../artifacts/release-verification/20261021T033649Z-d32fbaa1/manifest.json)，状态 `verified-local`、`published=false`；canonical APK 为 [`DouAi-0.2.6-release.apk`](../artifacts/DouAi-0.2.6-release.apk)，4,665,659 字节。19 个案例原始证据已绑定清单；APK 摘要、原证书及边界见 [0.2.6 最终归档](release-0.2.6.md#最终本地归档)。

0.2.5 最终封存入口为 [`20261021T024248Z-5a07a8a2/manifest.json`](../artifacts/release-verification/20261021T024248Z-5a07a8a2/manifest.json)，状态 `verified-local`、`published=false`；canonical APK 为 [`DouAi-0.2.5-release.apk`](../artifacts/DouAi-0.2.5-release.apk)，4,665,556 字节。摘要、原证书及平台 APK 对应关系见 [0.2.5 本地签名归档](release-0.2.5.md#本地签名归档)。

当前沿用正式包名、长期签名、libxposed API 102、抖音 `40.6.0 / 400601` 与 `40.8.0 / 400801` 独立适配与既有黑边回退结果。40.8 必须为真实 APK 内部构建 `40809900`，旧 `40809985` 与缺少构建号的同名版本不加载适配。新交付按下述进位规则递增 versionName，并独立递增 versionCode；同一批修复的重复验证不重复递增。

语音按用户最终要求在评论入口保留生成原文件，超过30秒仍继续尝试提交，不截断正文或音频。0.2.8新增MiniMax实验AAC选项，直接向接口请求而不做本地转换；不调整既有重试/回退策略，也不把较长音频视为必然失败。

## 既有版本证据

**0.2.4 / 105** 当时的严格本地验收为 182 个套件、1933 次执行通过，零失败、零错误、零跳过，包含本地宿主 DEX、227 条规则唯一性、22 项评论回复宿主合同和最终签名 APK 合同；另 34 项工具测试、2 个原生 ICU 编译及 16 项匹配检查通过。Lint 0 错误、57 条既有警告、0 新增。上述计数和产物仅属于 0.2.4，不能作为 0.2.5 的验收结果。

AI 消息联系人于 0.1.76 发布，入口和原验证范围见 [历史记录](release-0.1.76.md)。0.1.77、0.1.78 的聊天界面与模型头像、0.1.79 的附件和后台回复功能继续保留，详细行为见 [0.1.79](release-0.1.79.md)。

**0.2.4 / 105** 的原证书 v3 签名、最终包摘要、16 KiB ZIP 对齐及证据完整性通过。封存清单 `artifacts/release-verification/20261020T175556Z-01d40a32/manifest.json` 保留本地验收时的 `verified-local`、`published=false`，绑定 0.2.4 当时实际生成的完整长图、13 页 A4 PDF 及解析/渲染/目视报告；其状态不改写为远端发布证据。正式标签为 GitHub `v0.2.4`、LSPosed `105-0.2.4`，两边使用同一份已验收 APK。用户已确认旧版手机备份导入恢复；0.2.4 新 Toast、保存及分享接收仍需设备复验。

历史候选 **0.2.3 / 104** 修复备份导入过程中 Android ICU 对未转义右花括号的拒绝，并在本地严格入口加入原生 ICU 编译与匹配门禁。严格验收 **180 个套件、1921 次执行**全部通过，0 失败、0 错误、0 跳过；备份专项 99 项、原生 2 个编译及 16 项匹配检查、工具 34 项通过。Release、原长期证书 v3 签名、最终 APK 哈希、16 KiB ZIP 对齐及证据完整性通过；Lint 0 错误、57 条已审警告，无新增，预算通过。清单 `artifacts/release-verification/20261020T162817Z-f39c66a0/manifest.json` 标记 `verified-local`、`published=false`，绑定原生 ICU 修前与修后报告、最终 APK 字面量对照及对齐证据。完整产物与当时手机复验边界见 [0.2.3 本地记录](release-0.2.3.md)；用户现已确认 0.2.3 可以在手机导入备份，此反馈不代替本轮 Toast 与报告导出的设备验收。

历史候选 **0.2.2 / 103** 完成报告内 AI 结果、完整报告图片分享和受控诊断，其 180 套件、1921 次执行、58 条 Lint 警告及报告 PNG 样例封存记录保留在 [0.2.2 本地记录](release-0.2.2.md)。0.2.3 清单绑定的是当次原生 ICU 与产物验证，没有重复封存这些报告图片。

历史候选 **0.2.1 / 102** 的 177 个套件、1877 次执行及原签名产物保留在 [0.2.1 本地记录](release-0.2.1.md)，不作为本版的验收结果。其独立对齐日志未纳入当时的封存清单。

历史本地候选 **0.1.100 / 101** 曾通过严格验收：177 个套件、1843 次测试执行及当时的 32 项工具回归全部通过，签名、对齐、归档哈希及证据完整性已核对。其版本、产物和结果保留在 [0.1.100 本地记录](release-0.1.100.md)，不作为当前版本的验收结果。

## 版本进位规则

versionName 使用三段非负整数。末段达到 100 时向中段进位，中段达到 10 时向首段进位；规范化后中段为 `0–9`，末段为 `0–99`。例如 `0.1.99` 的下一版为 `0.2.0`，`0.9.99` 的下一版为 `1.0.0`。此前草稿号 `0.1.101` 据此规范化为 `0.2.1 / 102`，后续本地候选依次为 `0.2.2 / 103`、`0.2.3 / 104`、`0.2.4 / 105`、`0.2.5 / 106`、`0.2.6 / 107`；versionCode 独立递增，同一未发布批次的重建保留批次版本。

Gradle 配置与严格验收入口均检查版本段范围。既有归档的版号、文件名、标签和摘要保留其历史事实，已归档的 `0.1.100 / 101` 不追溯改名或重新标记。

## 环境

当前工作区保持 0.1.69 与 0.1.73 两轮黑边增量的回退结果。视频显示仍为一屏一条、保留完整画面，接受比例所需的必要留白；未新增裁剪、页距或切换黑带修复。最终归档、构建输出与测试所用 APK 应核对哈希一致，历史归档仍代表各自当时源码。

0.1.75 的本地构建与验证见[历史记录](release-0.1.75.md)，该版当时尚未进行真机验收。0.1.74 的回退范围与本地验证见 [此前记录](release-0.1.74.md)。已发布 0.1.72 的包体信息、验证及真机待验范围见 [更新记录](release-0.1.72.md)。评论回复并入父设置的 `0.1.71 / 72` 与 1274 项回归见 [此前记录](release-0.1.71.md)；`0.1.70 / 71` 首版与 1242 项回归见 [历史记录](release-0.1.70.md)。这两轮评论回复变更均包含于 0.1.72，本轮继续保留这些功能。

需要 JDK 21、Android SDK Platform 37。Gradle Wrapper 为 9.3.1。

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:testDebugUnitTest :app:lintRelease --console=plain
```

宿主 DEX 集成测试需要本地抖音 40.8.0 APK，通过环境变量 `DOUAI_TEST_APK` 指定绝对路径。第三方 APK 不由本仓库分发；没有配置该路径时，相关集成测试会跳过。

## 签名

在用户级 `%USERPROFILE%\.gradle\gradle.properties` 中配置以下四个属性：

| Gradle 属性 | 对应环境变量 | 用途 |
| --- | --- | --- |
| `douaiReleaseStoreFile` | `DOUAI_RELEASE_STORE_FILE` | 长期发布密钥库绝对路径 |
| `douaiReleaseStorePassword` | `DOUAI_RELEASE_STORE_PASSWORD` | 密钥库密码 |
| `douaiReleaseKeyAlias` | `DOUAI_RELEASE_KEY_ALIAS` | 签名密钥别名 |
| `douaiReleaseKeyPassword` | `DOUAI_RELEASE_KEY_PASSWORD` | 密钥密码 |

Windows 路径在 properties 中使用正斜杠。私钥和密码仅在本机保存，更新发布必须使用同一密钥。未配置签名时 Gradle 仅生成未签名 APK，不能作为已签名正式版发布。

```powershell
.\gradlew.bat :app:assembleRelease --console=plain
```

签名 APK 位于 `app/build/outputs/apk/release/app-release.apk`。Release 关闭调试，启用 R8 代码压缩与依赖优化，资源裁剪关闭。`app/proguard-release.pro` 保留模块类和成员、Xposed 入口，以及媒体选择器反射宿主 AndroidX ActivityResult 所需的类名和成员；其余依赖允许优化和改名。

用户于 2026-10-12 反馈 R8 试验包已测试且没有问题，随后将同一保留配置用于正式 Release。具体设备和覆盖场景未提供，真实发送与性能测量结果仍单独记录。`r8Experiment` 保留为兼容构建名，继承正式 Release 的压缩配置；正式 mapping 位于 `app/build/outputs/mapping/release/`。

构建后执行正式 APK 契约检查：

```powershell
$env:DOUAI_VERIFY_RELEASE_APK = (Resolve-Path 'app/build/outputs/apk/release/app-release.apk').Path
.\gradlew.bat :app:testDebugUnitTest --tests com.douai.module.scan.ApkArtifactContractTest --console=plain
```

CI 在构建未签名 Release 后提供该路径并运行测试。未配置路径时本地测试可跳过；配置了不存在或不可读的路径应失败。检查覆盖模块元数据、源码规则资产、入口签名、宿主反射名称及 compileOnly API 不被打包。

此前 Debug 版本使用 `com.douai.module` 和另一把签名密钥。切换到正式版时卸载旧模块，再安装并在 LSPosed 启用新的模块，避免两份模块同时作用于抖音。抖音私有目录中保存的宿主功能配置不属于模块应用数据。

## LSPosed 发布

收录申请：https://github.com/Xposed-Modules-Repo/submission/issues/2133

官方模块仓库按官方要求仅上传模块介绍、免责协议和 APK，源码托管在个人 GitHub 仓库。正式 APK 两处使用同一产物，官方 Release 标签必须为 `versionCode-versionName`。发布时从该次已验收 APK 元数据读取 `versionName` 与 `versionCode`，GitHub 标签使用 `v<versionName>`，官方模块仓库标签使用 `<versionCode>-<versionName>`；当前正式标签以本文“当前状态”为准。上传后核对两个仓库的 asset SHA-256 与本地归档一致；旧 Release 和本地旧 APK 保留。

## Android 平台烟测

在专用模拟器或已准备的测试设备中运行以下入口，显式指定 `adb devices` 显示的序列号；`-Build` 构建 Debug 与测试 APK，`-Install` 安装它们，不卸载已有应用或清空应用数据。

```powershell
$deviceSerial = Read-Host '请输入 adb devices 显示的设备序列号'
.\tools\run-platform-smoke.ps1 -Serial $deviceSerial -Build -Install
```

入口只使用独立测试配置和新建报告文件，覆盖 Android 原生正则下的去密钥备份，以及 Android 10+ MediaStore PNG/PDF 保存、重新解码/渲染和清理。Android 9 的交互式 SAF 保存没有被自动替代，相应存储项会跳过，汇总标为 `partial`。脚本保存设备信息、已安装 APK 摘要、逐项结果、仪器化日志及耗时/Java 堆前后快照；这些不是峰值内存或帧率测试。详细范围和设备执行状态见 [0.2.5 本地记录](release-0.2.5.md)。

## 严格本地发布验收

在源码稳定后，从仓库根目录执行下面一条命令。它只构建和归档本地产物，不创建标签或上传 Release。

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA/Android/Sdk"
.\tools\verify-release.ps1 -HostApk (Resolve-Path '抖音-40.8.0_40809900_20261019 (400801).apk').Path
```

需要 JDK 21、Python 3.10+、Android SDK Platform 37 和 build-tools，以及前述四项本机签名配置。`-HostApk` 可由 `DOUAI_TEST_APK` 提供；脚本检查宿主的包名及版本与 `HostAdaptation` 一致。完整回归还保留一项旧 smali 对照，需本地 `douyin-40.6.0/apktool-no-res/` 的对应文件；缺材料会在构建前失败。宿主 APK、smali 与密钥均不上传。

每次验收创建独立的 `artifacts/release-verification/<UTC时间>-<随机ID>/`，保留原有归档。流程先执行原生 ICU 预检，再构建正式 R8 APK，检查当前 Gradle 输出元数据，复制最终签名 APK，再核对包名、版本、原长期证书、v3 签名及两份 APK 的哈希。全量回归只检查这份最终归档，不在结尾用三项 APK 合同覆盖完整 JUnit 结果。

`-PreleaseVerification=true` 固化测试堆 `1g`、单测试工作进程和每 20 个测试类回收 JVM；入口同时指定 Gradle 堆 768 MiB 和单构建 worker。实际参数写入 `test-profile.json`。全部源码 JUnit suite 和测试方法必须有结果，包括所有宿主合同与最终 APK 合同，任何缺失、失败、错误或跳过均失败；Robolectric 的多个 SDK 变体分别计数。源码、宿主 APK、签名证据和归档在验收期间发生变化也会失败。

从 0.2.3 起，Windows 严格入口在 `assembleRelease` 前强制运行 `tools/verify_config_secrets_icu.py`。它直接提取 `ConfigSecrets` 的两处占位符模式，用本机原生 ICU4C 编译并执行固定合成匹配合同；原生编译或合同失败退出 1，源码模式无法提取或 ICU 不可用退出 2，均停止，没有 JDK 回退。报告记录源码 SHA-256、ICU 版本及各项结果，保存为本次目录的 `android-icu-placeholder.json` 和 `android-icu-placeholder.log` 并纳入封存。该门禁用于覆盖 JDK/Robolectric 与 Android ICU 的已知差异，不读取用户备份；只验证这两处模式，不能替代 Android 设备验收。普通 Linux CI 不增加 Windows ICU 硬依赖。

成功目录含 `manifest.json`、`SHA256SUMS.txt`、签名及 Manifest 证据、完整 JUnit/XML/HTML、Lint、R8 mapping 和命令日志；失败保留 `failure.json` 及已产生的报告。机器清单分别记录源码 commit、未提交状态、输入文件哈希、测试范围与实际参数，并明确标为本地已验证、未发布、真机与真实服务未执行。归档后的完整性复核命令如下；这是对已存证据哈希的核对，不会重跑 Android 验收。

```powershell
python -B tools/release_verification.py integrity --run-dir artifacts/release-verification/<本次目录>
```

普通 CI 仍允许没有宿主 APK，但会按全部宿主合同 suite 单独显示通过、跳过和缺失。设置了宿主 APK 却跳过相应检查、缺报告或缺必要方法会失败。每次 CI 都保存 JUnit、Lint 和 R8 报告；普通 CI 绿色不代表严格发布验收或真机成功。

Lint 代码警告使用 `tools/lint-baseline.json` 的可审查预算，以问题 ID、相对文件和归一化消息为键，不依赖行号；同键数量减少允许，新增键或数量增加失败。`GradleDependency`、`NewerVersionAvailable`、`AndroidGradlePluginVersion` 中完整匹配 `A newer version of ... than ... is available: ...` 的 Warning 单独作为依赖/工具链升级建议显示，不纳入代码告警增量：联网仓库是否可用、最新版本号变化不应改变同一源码的 CI 结论。其他文案、未知问题 ID、真实告警增量及所有 Error/Fatal 继续失败。

JSON 中 `warnings` / `approvedWarnings` 保留报告与基线总数，`budgetWarnings` / `approvedBudgetWarnings` 记录代码门禁数量，`advisoryWarnings` 保留每条升级建议及次数；GitHub Summary 同时展示两类信息。0.2.4 起曾因本地未出现的联网提示误触发门禁，原始 0.2.4、0.2.7、0.2.12 报告已按该策略重放通过。回归覆盖最新版本号变化、重复次数、未知文案和错误级别，避免放宽真实代码问题。历史标签和其失败运行记录保持原状，修复通过新的主分支 CI 验证。

修复后的报告通过检查即可，无须为了减少警告更新预算。确需保留新增代码警告时，说明理由并显式更新清单，检查差异后再提交：

```powershell
python -B tools/lint_budget.py check --report app/build/reports/lint-results-release.xml --output app/build/reports/lint-budget.json
python -B tools/lint_budget.py update --report app/build/reports/lint-results-release.xml --reason '说明新增警告的原因与取舍'
python -B -m unittest discover -s tools/tests -v
```

## R8 正式配置验证记录

2026-10-13：沿用用户测试通过的 R8 试验配置，正式 Release 启用代码压缩，资源裁剪关闭。版本仍为 0.1.46 / 47，本次为本地构建，未重新发布。

- 659 项测试通过，0 失败、0 错误、0 跳过，包含本地抖音 40.6.0 全 DEX 扫描及正式 Release APK 契约检查。
- Release Lint：0 错误、44 警告；Release 构建通过。
- APK 为 `app/build/outputs/apk/release/app-release.apk`，3,811,746 字节，比同轮未压缩基线 25,455,763 字节小 85.03%。
- APK SHA-256：`1224002D8C094C967701EAAB0D02864F73F4ECF306425937A18B6C05380CD8D8`。
- APK v3 签名验证通过，证书 SHA-256 为 `FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47`，与既有发布证书一致。
- 用户反馈试验包测试无问题；代理完成构建和静态契约验证。具体设备测试场景、真实发送与性能测量未补充记录。

产物测试允许 R8 合法扩大 Activity 生命周期方法访问权限、改名 AndroidX 依赖，仍验证 Activity 继承链以及 Xposed 回调的公开权限、非静态属性与完整签名。

## 0.1.46 原发布验证结果

- 591 项测试通过，0 失败、0 错误、0 跳过，包括抖音 40.6.0 全 DEX 规则扫描、正式包名的权限/诊断、重启组件、启动器和宿主资源检查。
- Release Lint：0 错误、41 警告。
- 正式包未开启调试；APK v3 签名校验通过，适用于本项目最低版本 Android 9。
- APK 内含 GitHub、LSPosed 和 Miuix 地址以及 libxposed 模块元数据。
- 未进行真机安装、真实账号网络调用或实际评论/回复发送验证。

正式 APK：`DouAi-0.1.46-release.apk`，25,390,227 字节。

APK SHA-256：`95C71D4F4397634BBBC0BC1802C148208E55FFCF21FF01EEBD78C62AD482343F`

签名证书 SHA-256：`FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47`
