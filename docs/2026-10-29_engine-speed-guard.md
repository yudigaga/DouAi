# 持续倍速的 SDK 异步与底层写入保护

用户反馈 0.2.31 长按倍速短暂生效后又恢复。目标继续保持：手动开启使用模块倍速，松手和切换视频不关闭，直到下一次操作改为 1 倍；全区域原生长按拦截保持。

## 当前日志能证明什么

输入为 `LSPosed_20261029_090243.zip`，SHA-256 `1A6267CE0CA339E0CDA22EFD6B46C48B8FCF85CDAAACA5A4A30320F498EE0ACA`。只读解析、重组校验分片并脱敏，见 分析记录（本地证据：`work/gesture-durable-20261029/log/analysis.md`）。

当前 0.2.31 / 抖音 40.8.0 有四次倍速操作：两次请求 2，三次读回后仍为 1.7；一次手动关闭 1→1 成功；一次切页继承 1 后读回不可用。没有记录到“实际 2 倍确认成功，随后实际读回 1”的完整链路，不能用这一缺口否定用户的实际感受。

原生手势检测器有 15 次 `held` 和 15 次 `native_down_held`，各绑定一个对象，安装正常。没有 facade 层的 `persistent_manual_speed_rewritten` 记录；采样缺少事件不能证明没有更深层的速度变化。

## 确认的覆盖缺口

原播放保护只拦 `FeedPlayerWrapper` 和 `PlayerManager` 的设置、播放、恢复入口。原 APK 还存在以下实际链路：

`TTVideoEngine.setPlaybackParams → 同一参数对象跨异步消息 → TTMediaPlayer.setPlaybackParams → TTPlayerClient → TTPlayer.setFloatOption(60, speed) → JNI`

异步到达时，上层 ThreadLocal 已退出，SDK 后续也可以直接写底层速度。新增独立回归先使实际引擎达到 2，再仅于 3 秒后调用底层 option 60 写 1，旧代码确实失败，见 修复前红例（本地证据：`work/gesture-durable-20261029/deep-repro-before.log`）。3 秒是确定性测试时机，不是从当前日志测得的真实覆盖延迟。

原 APK 的 `MTTVideoEngine` 还会比较缓存的 speed、pitch 和相关参数，在相等时跳过父类调用。这意味着重新请求同一倍速未必会重新触达引擎。该分支存在的静态证据明确，但当前用户日志没有缓存值，不能断言每次 2→1.7 都由此造成。

原 SDK、异步同对象与两版本规则证据见 静态报告（本地证据：`work/gesture-speed-deep-20261029/static/2026-10-29_reverse-native-speed-boundary-report.md`）。

## 实现

- 使用弱归属 token 记录模块设置时的参数对象；在 SDK 异步队列之后恢复该次手动选择的归属。
- 绑定实际引擎、媒体播放器和原生播放器的对象身份。已认领引擎更换参数或重建媒体/原生对象时继续继承；未认领的独立播放器不采用全局 fallback。
- 仅改写原生速度 option 60。其它 option、pitch、参数字段和原生 int 返回值保持原样。关闭模块或 owner 失效后放行；排队的恢复请求保留恢复语义。
- 模块已认领的缓存入口若没有进入父类设置，通过原实现的非虚调用补发一次。已进入父类的不重复发送，不修改宿主私有缓存字段。
- 对四个扫描确认的直接调用方执行定向去优化，并记录框架返回结果。它用于处理短方法可能被内联的调用点；JVM 用例只核对计划元数据，不模拟或宣称设备 ART 优化行为通过。
- 记录各层 requested/configured、最终原生入口 forwarded_speed、restore 状态及返回码。转发事件仍为未确认，不把 JNI 返回或缓存参数冒充实际播放成功。

实现见 [EnginePlaybackSpeedHooks](../app/src/main/java/com/douai/module/feature/EnginePlaybackSpeedHooks.java) 与 [归属状态](../app/src/main/java/com/douai/module/feature/GestureSpeedOverride.java)。框架接口还核对了当前编译所用 API 102 的 Invoker 签名（本地证据：`work/gesture-durable-20261029/api-invoker-102.txt`）。

## 验证与限制

新增引擎回归覆盖迟到直接写、异步新参数、1 倍选择、关闭后的放行、恢复基线、精确对象隔离、播放器重建、缓存补发不重复及定向调用方计划。最终相关专项与两原 APK 扫描结果分别见 `work/gesture-durable-20261029/targeted-final.log` 和 `host-408/406-scan-final.log`；规则各 242 条，新增 SDK 入口和直接调用方都必须唯一匹配。

本轮没有真机或物理音视频速度测量。若引擎 native 内部自行调整且不经过这些 Java 入口，这份静态证明与保护不覆盖它；未知优化调用路径也不能仅凭规则唯一就称为已执行。旧日志的 1.7 来源仍不能由本地用例确定，新包需要将最终写入值、返回码、实际速度读回与用户体验对应验证。未运行完整测试集，未发布远端。最终 APK 身份和验收范围以本版构建记录为准。
