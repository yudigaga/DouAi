# Fish 公开音色窗口误判与 Breeze 目录上限修复

Fish 公开库能返回可选音色，但 0.2.26 把“目录受搜索窗口限制”视为整次请求失败，在读取条目前抛出异常。用户日志确认了实际获取失败；随后对官方公开库的匿名只读请求和本地红测试，独立复现了这项设计错误。Breeze 后补详细日志和封存源码映射则确认：旧实现收到多页 HTTP 200 后，累计有效音色超过 2048 项便拒绝整份目录。单独界面红测试也确认了后页等待会阻塞已成功的首屏。两家分别按自己的协议改为逐页浏览，已加载音色可以先选择，后续按需追加。

本记录区分四类证据：手机日志及对应封存映射、开发机匿名公开 GET、本地模拟接口回放、尚未执行的手机及认证合成验证。早期 ZIP 日志没有保留原始响应，不能断言每一次失败都由同一窗口分支引起。后补 Breeze 详细日志的 2048 项上限异常与早期只有取消结果的记录分开，不套用 Fish 的窗口原因。

## 范围与原始输入

范围为 Fish 与 Breeze 的 TTS 音色目录获取、浏览、缓存和选择，不更改语音生成参数、正文、音频文件或业务重试策略。使用用户提供的日志作离线分析；线上只读验证不携带用户 Key，不合成音频，不保存用户的音色选择。Fish 验证匿名公开页面；Breeze 仅验证匿名 HTTP 可达性，未验证账户音色。

原始日志只引用位置和摘要，不复制到仓库或发布产物：

- 路径：`F:/qq/MobileFile/LSPosed_20261027_082502.zip`
- SHA-256：`840141613D0B88B4585006FAC0E4B03D447AE9B2B4AE55DED421C1DDB4134478`
- 本地脱敏证据目录：`work/fish-catalog-fix-20261027/`。

用户后补的详细日志同样只引用位置和摘要：

- 路径：`F:/qq/MobileFile/DouAi-debug-20261027-091633.log.txt`
- SHA-256：`4C8DE82737EF7FBE19ADC266FC5ACF216C0FD681A580AD38C16718DA0C1ED4E2`

脱敏记录不含用户凭据、聊天正文、私人响应、作者资料或原始音色 ID。公开目录夹具保留响应形状和分页标志，ID、名称改为测试值，无关作者及媒体字段移除。

## 证据

### E-001：手机日志确认不同阶段的失败

0.2.26、抖音 40.8.0 的 Fish 目录操作去重后共 9 次：

| 结果 | 次数 | 证据信号 |
| --- | --- | --- |
| 读取响应后的协议校验失败 | 5 | `body_read` 后 `FishSpeechClient$ProtocolFailure`，最终 `failed` |
| 请求阶段超时 | 3 | `request_started` 后 `IOException`，约 20 秒，最终 `timed_out` |
| 空目录成功 | 1 | `body_read` 后 `catalog_parsed`，`entries=0` |

最近两次在北京时间 **2026-10-27 08:24:35** 和 **08:24:46** 结束，均属于第一类。对应记录来自 ZIP 内 `log/modules_2026-10-27T08:24:09.896158.log` 的第 43–44、56–57 行；同一事件在 verbose/full 日志中出现时不重复计数。

当前日志模式为 `simple`。异常消息由安全摘要统一替换成 `message_omitted_untrusted_content`，没有原始 JSON 或具体窗口字段。依据对应 0.2.26 严格 HTTP 200 的读取代码，`body_read` 后的 `ProtocolFailure` 支持调查本地目录校验，但无法仅凭日志确定是哪一个校验分支。三次超时也不能由窗口修复自动解释。

本地证据：[脱敏事件](../work/fish-catalog-fix-20261027/agent-log/events-redacted.json)、[汇总与记录位置](../work/fish-catalog-fix-20261027/agent-log/summary.json)。

### E-002：官方匿名公开 GET 返回有效的窗口页面

开发机于 **2026-10-27 08:34:20–08:34:40（北京时间）** 对官方接口只读获取：

```http
GET https://api.fish.audio/model?page_size=100&page_number=1&self=false
Accept: application/json
```

没有提供认证头，返回 HTTP 200、100 个条目，观察到的类型和状态分别为 `tts`、`trained`。响应同时包含：

```json
{
  "window_limited": true,
  "total_is_exact": false,
  "total": 1000,
  "has_more": true,
  "max_offset": 1000,
  "accessible_upper_bound": 1000
}
```

原始响应体当时记录的 SHA-256 为 `9DC83A4B50878C01C9521A2B5064C1502028F422FE535C560E0C88ACCFAC237B`；回放使用的是脱敏形状，不把此摘要误称为脱敏夹具摘要。补充只读观察中，第 10 页仍有 100 项且 `has_more=false`，第 11 页为空，两者仍标记窗口受限及总数非精确。

这些标志限制可浏览范围或总数精度，不否定已返回的页面。官方 schema 没有定义 `max_offset`、`accessible_upper_bound` 的精确单位与边界公式，修复不据字段名称推导偏移量。匿名读取成功是本次现场观察；API 参考页仍标注 Bearer 鉴权，不能由这一次公开读取推广为所有接口或未来服务均无需 Key。

本地证据：[请求证明](../work/fish-catalog-fix-20261027/contract/public-first-page-proof.json)、[脱敏首页](../work/fish-catalog-fix-20261027/contract/public-first-page-redacted.json)、[窗口边界观察](../work/fish-catalog-fix-20261027/contract/pagination-observations.json)。官方依据：[音色列表 API](https://docs.fish.audio/api-reference/endpoint/model/list-models)。

### E-003：旧实现被最小红测试命中

修复前执行 `FishSpeechClientTest.aValidWindowLimitedPublicPageStillProvidesSelectableReadyVoices`：模拟服务器返回 HTTP 200、有效的就绪 TTS 音色及窗口标志，调用真实 `FishSpeechClient` 目录路径，断言音色仍可选择。结果为 **1 项测试、1 项失败**，异常是 `FishSpeechClient$ProtocolFailure`。日志保存在 [repro-before.log](../work/fish-catalog-fix-20261027/repro-before.log)。

旧逻辑在解析有效音色前执行相当于 `if (windowLimited) throw ...` 的检查；此前测试还把此标志作为必须失败的条件，因而本地测试通过并没有证明真实公开库可浏览。这次用官方现场响应的脱敏分页形状建立回放，检查的是“返回的音色仍能选择”，而不是简单检查有无异常。

### E-004：明确测试路由后，应用客户端读取 Fish 公开首页

可选的 `FishVoicePublicProbeTest` 让 JVM/Robolectric 中的真实应用客户端读取匿名公开首页。开发机 Java 默认路由初次结果为 `read_failed`、`IOException`，约 15.164 秒，没有观测到 HTTP 状态，保存在 [probe-result-direct-failed.json](../work/fish-catalog-fix-20261027/probe-result-direct-failed.json)。这与另一 HTTP 客户端完成 E-002 可以同时成立，不能直接套为手机的根因。

随后仅给临时测试 JVM 显式配置开发机本地代理 `127.0.0.1:7897`，未改模块或全局网络配置，也未关闭 TLS 校验。**2026-10-27 09:14:02（北京时间）** 开始的真实客户端探针在约 4.700 秒取得 HTTP 200、100 个原始条目和 100 个就绪 TTS 音色，返回 `windowLimited=true`、`totalIsExact=false`、`hasMore=true`。没有提供凭据，没有尝试合成或保存选择。

当前结果由 [probe-result.json](../work/fish-catalog-fix-20261027/probe-result.json)、[真实目录探针执行日志](../work/fish-catalog-fix-20261027/real-catalog-probes.log) 和[可选探针说明](../work/fish-catalog-fix-20261027/live-probe.md) 保存。它验证了此路由下应用客户端的匿名公开读取，不等于手机、带 Key 目录或付费 TTS 验收。临时探针不进入正式发布所选套件。

### E-005：Breeze 匿名 HTTP 可达，账户目录仍未验证

同一显式本地代理路由下，`BreezeVoiceConnectivityProbeTest` 通过真实共享传输层读取官方音色列表，不提供认证头。**2026-10-27 09:14:00（北京时间）** 开始的探针约 1.086 秒观测到 HTTP 401；结果为 `http_reachable_auth_unchecked`、`accountCatalogVerified=false`、`authenticatedSynth=false`。

这个 401 表示匿名请求已得到鉴权响应，不能把它解释成用户填写的 Key 无效，也不能把网络可达当作账户音色已成功获取。证据见 [breeze-connectivity-result.json](../work/fish-catalog-fix-20261027/breeze-connectivity-result.json)；本轮未使用用户凭据主动复测 Breeze 账户目录或合成。E-007 是用户后补日志中的响应证据，与这里的匿名 401 探针不是同一次请求。

### E-006：Breeze 后页等待会阻塞已成功首屏

界面用例 `SpeechSettingsDialogTest.breezeShowsTheFirstVoicePageBeforeLoadingTheNextPage` 返回一个带可选音色且 `has_more=true` 的成功首页，再让第二页不响应。断言要求首页先显示和可选择，且用户未请求更多前只有一次目录请求。旧界面仍调用整批目录拉取，等待后页期间首页没有进入可见缓存，该断言实际失败。

证据为 [breeze-repro-before-fish-after.log](../work/fish-catalog-fix-20261027/breeze-repro-before-fish-after.log)：当次 13 项中只有该 Breeze 首屏用例失败。这个 Mock 界面回放证明了产品等待策略问题；它不能反推用户日志中的 5 次取消全部由后页等待引起。

### E-007：后补 Breeze 详细日志已记录连续成功响应

用户后补详细日志中的 0.2.26 操作，trace 为 `17181ac8-4e59-49bc-8bc8-163c740f2aaa`，从北京时间 **2026-10-27 09:15:59.380** 开始，到 **09:16:14.484** 结束，记录耗时约 15.104 秒。详细模式下保留了 63 个阶段事件：21 次 `request_started`、21 次 `http_response`，且状态均为 200，以及 21 次 `body_read`；随后抛出 `BreezeSpeechClient$ProtocolFailure`。

这次证据明确表示请求已多次得到响应并读取内容，不能再笼统表述为“Breeze 仅有取消、尚未收到 HTTP”或据匿名探针 401 归罪用户 Key。协议异常发生在本地目录处理路径；E-008 进一步定位到累计音色上限分支，而非仅凭 21 次计数猜定。原始配置和响应不公开，脱敏阶段记录见 [new-091633-events-redacted.json](../work/fish-catalog-fix-20261027/new-091633-events-redacted.json)。

### E-008：封存源码与 R8 映射确认 Breeze 的 2048 项拒绝分支

新日志的 R8 map ID 与封存的 0.2.26（127）一致。栈中的虚拟行 362 落在 `fetchVoicesChecked` 唯一对应的 351–391 范围，映射到原始 `BreezeSpeechClient.java:252`。重构旧源码的 LF UTF-8 字节 SHA-256 为 `644469dc32ae6fdc83950145fe8a7866300a9f452262cab7839a046d3461a029`，与该版本封存 `source-inputs.json` 完全一致；原始第 252 行是：

```java
if (voices.size() > MAX_VOICES) throw invalid("音色列表超过 " + MAX_VOICES + " 项，可手动填写音色 ID");
```

`MAX_VOICES` 为 2048，`voices` 按有效音色 ID 去重累计，所以达到第 2049 个有效唯一音色即抛出协议异常。映射与源码字节共同证明了此分支，21 次成功页只作旁证；它们不能推导每页原始条目数或目录精确总量。日志导出中没有原样保留该固定错误文本，结论不依赖公开异常正文。

证据见 [精确定位 JSON](../work/fish-catalog-fix-20261027/new-log-diagnosis.json)、[定位说明](../work/fish-catalog-fix-20261027/new-log-diagnosis.md)、[源码摘要比对](../work/fish-catalog-fix-20261027/sealed-breeze-source-reconstruction.json) 和[封存 R8 映射](../artifacts/targeted-verification/20261027-tts-docs-0.2.26/reports/mapping/mapping.txt)。此结论只定位这次后补日志，不能替早期取消操作补上未保留的中间响应或解释 Fish 的网络超时。

## Finding：窗口误判会丢弃正常公开音色

**F-001，设计缺陷，证据 E-002 + E-003，已复现。** 目录层将 `window_limited=true` 或总数非精确与“响应不可用”混同，试图先下载一个完整全局列表才交给界面。官方公开库正常首页因此也可能被拒绝，已有 100 个就绪音色却无一展示。E-001 与此症状一致，但日志缺少响应内容，不能将全部 9 次操作归为 F-001。

修复位于 `FishSpeechClient`、`SpeechSettingsDialog` 的 Fish 目录分支，以及 Fish 默认范围设置：

1. 引入 `VoicePage`，分别传递当前音色、原始条目数和 ID、页码、继续状态、窗口标志与总数精度。
2. 一次加载一页，立即展示首屏；有明确后续页时“加载更多”，按 ID 去重追加。分页推进使用原始条目，筛掉非 TTS 或未就绪模型不会误判为停滞。
3. 窗口受限、估计总数和窗口到底作为状态显示；不称为全库。继续状态未知、窗口到底或达到本机 2048 项预算时，保留已加载音色，允许缩小搜索。
4. 后续页失败、页码异常、重复页或过期回调不覆盖已有列表与当前音色选择。HTTP 206、无效结构和不前进的分页仍受控拒绝。
5. 未保存过范围时默认公开；用户已保存的范围不强制覆盖。公开目录可不填 Key 先浏览，已填写 Key 时仍沿用它；“我的音色”和实际合成、试听继续要求有效 Key。

### P-001：从公开首页到可选择音色

`公开范围及可选搜索 → GET 第 1 页 → 校验 HTTP/响应结构 → 过滤就绪 TTS 项 → 展示首屏与窗口状态 → 按 has_more 请求后页 → 去重追加或保留当前结果`

这条修复路径保留 E-002 的有效条目，消除 E-003 的一见窗口标志就失败的决策。它不承诺绕过服务窗口限制、获取无限全库、修复所有网络超时，或证明某个音色的合成权限。

## Finding：Breeze 整批获取会阻塞首屏，并在超过 2048 项时丢弃结果

**F-002，目录累计及界面等待策略缺陷，证据 E-006 + E-007 + E-008，已确认。** 旧界面先等待完整目录，第一页有合法音色也不会先显示；而累计唯一有效音色超过模块的 2048 项限制时，旧客户端抛错，前面成功读取的整批结果无法选择。新详细日志通过源码字节与映射精确命中了这项拒绝分支；Mock 回放另行确认了后页等待的首屏症状。早期手机日志中的 `owner_cancelled` 仍不足以唯一确认各次取消的根因。

Breeze 的修复使用自己的 `VoicePage`、`has_more`、分页游标或页码，不套用 Fish 的窗口元数据：

1. 每次读取一页并显示，只有“加载更多”才继续；页间维持 `voice_type/search/language_code` 筛选，按 ID 去重追加。
2. 后页失败、游标重复、页码不前进或达到本机 2048 项浏览预算时，保留已加载项和当前选择；超过预算不再使整份可见列表失败。缓存只表示已经浏览的结果，不称为完整账户目录。
3. 每页及模型目录读取施加 45 秒模块等待预算，关闭、切换来源或查询条件时取消旧请求，拒绝过期回写。这是本地等待边界，不是服务端时限或无限重试承诺。
4. Breeze 目录继续要求自己的有效 Key，匿名可达与认证目录读取分别记录；音色列表操作不触发生成请求。

### P-002：Breeze 首屏先显示，后页显式加载

`当前筛选与 Key → GET 首屏 → 展示并保存已加载结果 → 用户点击加载更多 → 携同一筛选与游标/页码取后页 → 追加去重或保留先前页面`

这条路径解决 E-006 的首屏等待及 E-007、E-008 的整份目录拒绝问题。Breeze 的新版本手机表现、其他账户权限、手机网络和实际合成结果仍需各自的证据，不因分页修复或 E-005 的 401 而宣布通过。

## 本地回放命令

从仓库根目录执行，使用 MockWebServer 和脱敏夹具；不请求真实 Fish 合成服务：

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:testDebugUnitTest `
  --tests com.douai.module.feature.FishSpeechClientTest.aValidWindowLimitedPublicPageStillProvidesSelectableReadyVoices `
  --tests com.douai.module.feature.FishSpeechClientTest.theObservedPublicWindowContractLoadsOneHundredReadyVoicesAnonymously `
  --tests com.douai.module.feature.FishSpeechClientTest.explicitPageNavigationPreservesSearchAndKeepsTheWindowBoundary `
  --tests com.douai.module.feature.FishSpeechClientTest.inexactUnknownContinuationDoesNotDiscardTheReturnedVoices `
  --tests com.douai.module.feature.FishSpeechClientTest.ownedVoicesStillRequireTheirOwnAccountKeyBeforeAnyRequest `
  --max-workers=1 --console=plain `
  '-Dorg.gradle.jvmargs=-Xms32m -Xmx768m -XX:+UseSerialGC -Dfile.encoding=UTF-8' `
  '-Pkotlin.compiler.execution.strategy=in-process'
```

本记录不填最终 APK 及完整专项计数；最终构建、签名、Lint、所选套件与设备边界由对应发布记录给出。

Breeze 首屏回放同样只用 MockWebServer，不需要真实 Key：

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:testDebugUnitTest `
  --tests com.douai.module.ui.SpeechSettingsDialogTest.breezeShowsTheFirstVoicePageBeforeLoadingTheNextPage `
  --tests com.douai.module.ui.SpeechSettingsDialogTest.breezeCatalogBudgetRetainsTheFirst2048VoicesRatherThanFailingTheWholeList `
  --tests com.douai.module.feature.BreezeSpeechClientTest.aBrowsablePageReturnsBeforeLoadingTheNextNativeCursor `
  --max-workers=1 --console=plain `
  '-Dorg.gradle.jvmargs=-Xms32m -Xmx768m -XX:+UseSerialGC -Dfile.encoding=UTF-8' `
  '-Pkotlin.compiler.execution.strategy=in-process'
```

## 早期 ZIP 中 Breeze 记录的边界

去重后的 Breeze 音色目录操作共 5 次，0.2.26 有 3 次，0.2.25 有 2 次。它们结束时的最后阶段均为 `request_started`，最终为 `CancellationException`、`owner_cancelled`；0.2.26 的耗时分别约 66.510、19.248、45.096 秒。没有保留下来的 `http_response`、`body_read`、目录成功或 `ProtocolFailure` 记录。

这些操作使用简单日志模式，逐阶段记录会省略；`last_stage` 又只描述最后一次阶段。因此不能证明取消发生在第一页，也不能排除之前的分页已经成功读取。这里的“没有记录”不是“没有发生”。同样，Fish 的请求阶段超时也不能仅凭最后阶段排除更早的成功页。

仅凭这些早期记录，不能认定 Breeze 与 Fish 同样是窗口问题，也不能断言鉴权已成功或未成功；所有者取消还可能来自关闭页面、切换配置或线程中断，不能仅凭该结果判定用户按了哪个按钮。原始 ZIP 全文没有保留官方 Breeze 域名，也没有与这些操作 PID 匹配的底层网络异常类名可供进一步归因。后补 E-007 已提供更强的响应阶段证据，不应再用早期记录的缺失覆盖它。

本地证据：[Breeze 脱敏事件](../work/fish-catalog-fix-20261027/agent-log/breeze-events-redacted.json)、[对应进程网络类名计数](../work/fish-catalog-fix-20261027/agent-log/breeze-process-network-counts.json)。Breeze 的记录应按各次证据区分：E-005 只验证开发机匿名 HTTP 可达，E-006 确认首屏等待设计问题，E-007 与 E-008 确认后补操作在成功接收后因本地 2048 项上限失败；不能由 Fish 的 F-001 代替 Breeze 的定位与验收。
