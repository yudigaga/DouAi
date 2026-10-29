# 右下角长按倍速读回与提示修复

用户在模块 0.2.28、抖音 40.8.0 中设置右下角长按切换锁定倍速，出现“已触发”提示但实际效果不稳定。本次确认旧读取规则取到了媒体属性 10，对应宿主用于 FPS 检查的指标，而非命名的播放速度命令；日志中的五次请求均读回 `30.0`，不能把它当作有效恢复基线。修复改用 `VideoPlaySpeed` 命令读取，限制手势恢复基线，并将提交提示与读回确认分开。

范围和授权见本地 scope（本地证据：`work/gesture-speed-fix-20261028/scope.md`）。本记录只覆盖日志、原 APK 静态调用链及已建立的回归用例，不声明新包真机效果、最终 APK 或修复后测试总数。

## Evidence：可复核观察

| 编号 | 证据 | 观察 |
| --- | --- | --- |
| E-001 | 脱敏摘要（本地证据：`work/gesture-speed-fix-20261028/log/summary.json`）、五次倍速操作（本地证据：`work/gesture-speed-fix-20261028/log/speed-operations-redacted.json`） | `2026-10-28 15:07:44.433–15:07:51.011 +08:00` 提交 `2→1→2→1→2`，均在三次读回后记为 `readback_mismatch / unconfirmed`，`reported=30.0`。首次动作明确为 `long_press / speed / region=8`。 |
| E-002 | 首个操作的 trace/span 时序（本地证据：`work/gesture-speed-fix-20261028/log/first-speed-context.json`） | 原生 gate 于 `44.262` 被拦截；模块 `44.433` 开始、`44.435` 转发 setter、`44.437` 提交。gate 与模块属于不同 root trace，前者早约 171 ms；下一次 gate 也早于下一次模块请求约 119 ms。 |
| E-003 | 40.8.0 原 DEX 调用（本地证据：`work/gesture-speed-fix-20261028/static/current-speed-command.txt`）、命名速度读取（本地证据：`work/gesture-speed-fix-20261028/static/current-speed-delegates.txt`）、40.6.0 对照（本地证据：`work/gesture-speed-fix-20261028/static/legacy-speed-command.txt`） | 旧零参 `float` 方法分别为 `LJIIJ()`、`LJFF()`，下游查询属性 10。宿主自身速度读取使用 `PlayerCommand$Getter$VideoPlaySpeed.f`，经 `LJI(PlayerCommand):Object` 返回数值；它与旧媒体指标是不同契约。 |
| E-004 | 恢复基线红例（本地证据：`work/gesture-speed-fix-20261028/repro-before.log`） | `implausibleThirtyFpsReadbackMustNotBecomeTheRestorationSpeed` 在修复前已运行失败，复现将与日志相同的 `30.0` 读回污染恢复速度的问题。用例名称中的 Fps 不代表已经测得实际帧率。 |
| E-005 | 宿主读取规则红例（本地证据：`work/gesture-speed-fix-20261028/host-rule-before.log`） | `allSpeedReadRulesUseTheNamedSpeedCommandInsteadOfTheMediaMetric` 在修复前已运行失败，捕获旧 `()F` 规则与命名命令查询契约不符。 |
| E-006 | 旧属性的生命周期使用（本地证据：`work/gesture-speed-fix-20261028/static/current-property70-lifecycle.txt`） | 原 DEX 将同一字段用于 `open sr check fps` 日志（845–860 行附近），补充确认旧指标的 FPS 语义；不是本次实际播放帧率的独立测量。 |
| E-007 | 修复后针对性运行（本地证据：`work/gesture-speed-fix-20261028/targeted-after.log`） | 修复后针对性测试已记录 `BUILD SUCCESSFUL`。本记录不填最终通过总数或产物身份，避免代替后续构建记录。 |

五次原生 gate 均为独立 root trace，早于对应模块请求约 `171/119/112/95/129 ms`。模块 setter 所在 trace 记录了 `manual_gesture_scope / original_speed_write_forwarded=true`。这些记录不能证明 gate 在模块自身 setter 内部将其吞掉，也没有记录 `MotionEvent.ACTION_UP` 的具体值，不能据此认定是“松手恢复 1x”覆盖了模块状态。

原始输入保留在用户提供的位置，未复制到文档或发布产物：

| 输入 | SHA-256 |
| --- | --- |
| `DouAi-debug-20261028-150808.log.txt` | `86D3C6F9E5AFBB753DF3AAAA8499BF71D5814DC236939B68A4126AF2DAE3746E` |
| `LSPosed_20261028_151725.zip` | `09701215A52C0AB02EB6925A6B2E262EA93633DD5309EC5C43F0D62D7AD668A1` |

## Finding：确认的问题与修复

| 编号 | 结论与证据 | 修复路径 |
| --- | --- | --- |
| F-001 | **旧倍速读取规则错误，静态置信度高。** E-003、E-005、E-006 证明旧方法返回 FPS 指标；E-001 与 E-004 说明 `30.0` 会使读回失配并可能污染手势恢复基线。 | 两套宿主规则选择 `LJI(PlayerCommand):Object`，并定位 `VideoPlaySpeed.f` 单例；[HostPlaybackSpeedAccess](../app/src/main/java/com/douai/module/feature/HostPlaybackSpeedAccess.java) 统一读取。手势与自动倍速均使用这条语义明确的读取链。 |
| F-002 | **请求提交与效果确认混在提示中。** E-001 的五次动作都未确认，而旧提示在 setter 返回后便显示开启或关闭。 | [FeedGestureToolsHook](../app/src/main/java/com/douai/module/feature/FeedGestureToolsHook.java) 先提示“已请求”，仅在当前播放器读回与请求一致时提示已开启或已关闭；不一致仍保留 `unconfirmed`。手势恢复基线只接受有限的 `0.25–4` 倍值，异常读数使用正常基线，避免恢复到 `30.0`。 |

## Path：修复后的读取与确认

P-001 为播放调用路径：

1. 长按命中区域与已分配动作，模块提交所选倍速（E-001）。
2. 经 `IPlayerInfoProvider / FeedPlayerWrapper / PlayerManager` 的 `LJI(PlayerCommand)`，传入 `PlayerCommand$Getter$VideoPlaySpeed.f` 读取速度（E-003、F-001）。
3. 保存可信的手势恢复基线，拒绝把 `30.0` 之类越界值当作恢复倍速（E-004、F-002）。
4. 请求提交先显示“已请求”；当前 holder、播放器、修订和读回值全部匹配后才确认提示。切换目标或关闭时取消旧读回，失配不伪装成功（E-001、F-002）。

原生长按入口的启用/阻断继续按原配置处理。本修复不是通过吞掉所有原生松手事件来实现，也不扩大到其它长按动作、语音、评论或自动回复流程。

## 最小复核命令

在仓库根目录执行。日志脚本需要上表两份本机输入；只输出白名单事件并按 event ID 去重，不执行日志内容：

```powershell
python -B work/gesture-speed-fix-20261028/log/triage_gesture_logs.py
```

恢复基线回归用例：

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:testDebugUnitTest `
  --tests com.douai.module.feature.FeedGestureToolsHookTest.implausibleThirtyFpsReadbackMustNotBecomeTheRestorationSpeed `
  --max-workers=1 --console=plain `
  '-Dorg.gradle.jvmargs=-Xms32m -Xmx768m -XX:+UseSerialGC -Dfile.encoding=UTF-8' `
  '-Pkotlin.compiler.execution.strategy=in-process'
```

原 APK 读取合同；必须显式配置已验证宿主，未配置会跳过，不能算通过：

```powershell
$env:DOUAI_TEST_APK = (Resolve-Path -LiteralPath '抖音-40.8.0_40809900_20261019 (400801).apk').Path
.\gradlew.bat :app:testDebugUnitTest `
  --tests com.douai.module.scan.PlayerSpeedHostContractTest `
  --max-workers=1 --console=plain `
  '-Dorg.gradle.jvmargs=-Xms32m -Xmx768m -XX:+UseSerialGC -Dfile.encoding=UTF-8' `
  '-Pkotlin.compiler.execution.strategy=in-process'
```

40.6.0 对照使用仓库内 `抖音-40.6.0_40609900_20260922 (400601).apk` 设置同一环境变量后执行同一合同。以上是本地复核入口，修复后的通过结果以实际运行记录为准，不由本说明预先宣告。

## 验证边界

这次已确认的是读取契约与提示时机问题。静态证据明确了旧指标的 FPS 语义，但日志中的 `30.0` 不是实际 30 倍播放的证据，也不是对手机最终输出 30 fps 的独立测量；该指标不能替代命名速度命令。原日志不足以排除其它原生入口的影响，修复后仍需手机复测“长按开启、松手保持、再次长按关闭”和切换视频后的状态。静态原 DEX、模拟播放器及单元用例不能代替真实 LSPosed 回调与播放效果。
