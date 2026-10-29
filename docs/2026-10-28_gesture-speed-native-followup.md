# 长按倍速未持续生效的后续修复

用户反馈 0.2.29 仍未达到“长按开启、松手保持、再次长按关闭”的效果。新日志证实前一轮的 FPS 误读已消失，但一次请求 2 倍仍读回 1 倍，另外六次匹配只覆盖提交后约 150 ms 的参数值。不能把这些匹配当成用户问题已经解决。

## 日志证据

输入为 `DouAi-debug-20261028-191834.log.txt`，SHA-256 为 `58D1DC6F040145C4BC6D2FEABA6874CA0E2C617C028E7AF130509CE3415CA1BD`。原文件未改动；脱敏摘要（本地证据：`work/gesture-speed-followup-20261028/log/summary.json`）与七次操作时序（本地证据：`work/gesture-speed-followup-20261028/log/speed-operations-redacted.json`）仅保留本问题相关字段。

| 观察 | 证据与边界 |
| --- | --- |
| 模块 0.2.29、宿主 40.8.0；手势及原生长按拦截已安装，自动倍速关闭 | 配置位于原日志 168、171、189 行。安装不等于播放效果通过。 |
| 七次请求为 `2→1→2→1→2→1→2` | 六次参数读回匹配。第三次请求在 1341–1365 行，三次尝试后仍为 `reported=1.0`，耗时约 1.03 秒。 |
| 七次模块 setter 均转发 | `manual_gesture_scope / original_speed_write_forwarded=true`；七次原生 gate 均属于独立 trace，在对应模块动作之前 111–125 ms，不能称为模块自身 setter 被 gate 吞掉。 |
| 缺少松手后的持续观测 | MotionEvent 仅保留类型，成功事件仍为 `later_playback=unobserved`。日志存在采样抑制，不能凭缺少事件排除其它调用。 |

## 原 APK 调用链

1. `VideoPlaySpeed` 命令读取 `TTVideoEngine.getPlaybackParams().getSpeed()`；`getPlaybackParams()` 返回缓存参数。底层 `setPlaybackParams` 先写缓存，再通过消息或直接调用下沉到媒体播放器。参数已是 2 倍时，实际引擎不一定已确认变速。
2. `RealVideoPlaySpeed` 调用 `TTVideoEngine.getRealSpeed()`；相关实际值由宿主 `player callback speed changed` 回调更新，无可用引擎时可返回 `-1`。效果确认应读这个命令，不能退回请求参数冒充实际确认。
3. `LongPressLayout` 的 DOWN 会启动自己的长按任务，并把输入交给宿主触摸监听器。仅拦长按 Runnable 或 UP 不能阻止已经从 DOWN 建立的另一条识别流程。
4. `DVideoGestureContainer` 及另一视频容器先向子控件分发，再将同一事件送给各自检测器。因此，子控件消费 DOWN 后，祖先仍可能收到原生检测输入。

两版检测器对应为：40.8.0 的 `LX/1eom` 与容器 `LX/07rz`；40.6.0 的 `LX/1Z4d` 与容器 `LX/06ns`。同名 `DVideoGestureContainer` 的 `getDetector()` 也各自返回该版本检测器。规则分别核对实际字段、方法签名和调用关系，不跨版本猜测混淆名称。

静态证据位于 `work/gesture-speed-followup-20261028/static/`，包括命令语义、引擎参数写入及两版容器分发顺序。它们证明代码存在这些路径；旧日志没有记录实际祖先对象，不能据此断言手机上是哪一个容器导致了每次失败。

## 修改与回归

倍速长按从 DOWN 暂存候选输入，并独立计时执行模块动作；确认长按后消费该次手势的尾事件。短按、滑动或多指在确认前交回时，回放原始 DOWN，保留正常操作。检测器拦截仅绑定到该触摸控件父链上实际取得的对象和同一个 downTime；交回时补发各检测器自己收到的原始 DOWN，取消时消费尾事件，避免孤立 UP。

实际速度读取与请求参数读取分开。基线继续保留请求速度契约；效果确认只查询 `RealVideoPlaySpeed`，缺失、无效或 `-1` 不回退请求值。原暂停视图查找保护与倍速候选分开，避免把正常播放中的倍速长按误当作暂停保护。

修复前已运行 迟到恢复回归（本地证据：`work/gesture-speed-followup-20261028/repro-before.log`），在先显示成功后实际恢复 1 倍的断言处失败。仅完成子控件接管后，祖先检测器回归（本地证据：`work/gesture-speed-followup-20261028/ancestor-repro-before.log`）仍在相同断言处失败，补齐检测器绑定后通过。夹具中 900 ms 的恢复延迟是用来锁定覆盖时序的测试假设，不是从日志测得的宿主时延。

修复后 相关专项（本地证据：`work/gesture-speed-followup-20261028/targeted-after.log`）共 5 类、115 项测试零失败、零错误、零跳过，包含实际原 40.8.0 APK 契约；另 40.6.0 原 APK 合同（本地证据：`work/gesture-speed-followup-20261028/host-406-contract.log`）6 项复验全部通过。最终签名 APK 的打包检查和身份以本次构建记录为准。

## 验证边界

本地夹具、静态原 APK 合同及构建检查不能代替真实 LSPosed 回调和手机播放验收。本次没有连接可用 ADB 设备。安装后需要完全重启抖音，复测长按开启、松手保持、再次关闭，以及短按、滑动、多指取消、切换视频和返回前台。未运行完整测试集，也未上传远端 Release。
