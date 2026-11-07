# DouAi 0.2.39 正式版：随机音色

版本 **0.2.39 / 140**，正式包名 `io.github.yudigaga.douai`，沿用长期签名与 R8 Release 配置。本批补齐刷新管理与诊断后重新构建。GitHub [`v0.2.39`](https://github.com/yudigaga/DouAi/releases/tag/v0.2.39)、LSPosed [`140-0.2.39`](https://github.com/Xposed-Modules-Repo/io.github.yudigaga.douai/releases/tag/140-0.2.39) 使用同一 APK，每端仅上传 APK 附件。

## 本次更新

- 文本转语音新增随机音色：小随机在当前语音来源内选择，大随机跨来源选择；默认关闭，每次生成重新抽取。
- 大随机的“来源范围”支持多选系统引擎、第三方、MiniMax、Breeze Blue 和 Fish Audio；默认全选，至少选择一项。仅从已选且配置、音色目录有效的来源中选择。
- 大随机新增“音色列表管理”，可直接逐个刷新已选网络来源，并显示有效音色数量与排除原因，无需切换手动来源。Fish/Breeze 使用已有筛选，只刷新首屏最多 20 项，更多音色仍从各来源的选择器加载。刷新失败或分页停滞保留旧缓存，关闭管理窗口取消请求。
- 随机诊断记录模式、来源范围、各来源候选数量和排除原因、选中来源及完整音色 ID；系统引擎在实际选音色时补记候选数及设置结果。较长 ID 分段记录，URL、控制字符、疑似凭据及不符合保守字符规则的 ID 略去。按同一 `trace_id` 对照每次合成。
- 音色目录 marker 单独变化也会更新后台语音入口，避免列表响应内容相同但绑定条件改变时仍使用旧配置。
- 系统来源只使用当前引擎匹配语言的离线音色；大随机的系统引擎探测最多等待 5 秒，过慢引擎可能不参与当次选择。MiniMax 混音以及 Fish 多说话人/参考音频模式不参与随机单音色。
- 选择使用请求快照，不改写原有手动音色；已有语音合成参数、发布重试和文本回退规则保持原有行为。

## 安装包

[GitHub 下载 APK](https://github.com/yudigaga/DouAi/releases/download/v0.2.39/DouAi-0.2.39-release.apk) · [LSPosed 正式版](https://github.com/Xposed-Modules-Repo/io.github.yudigaga.douai/releases/tag/140-0.2.39)

| 项目 | 结果 |
| --- | --- |
| APK | `artifacts/DouAi-0.2.39-release.apk` |
| versionName / versionCode | `0.2.39 / 140` |
| 大小 | 5,446,028 字节 |
| SHA-256 | `A8635CEDD19A4BFA13B83D5CF9B9F7FB564C1B98CC55FF699D1F935C2DED2CC6` |
| 签名 | 项目长期证书，APK v3 验证通过 |
| 证书 SHA-256 | `FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47` |
| 对齐 | 16 KiB ZIP 对齐检查通过 |

严格验收归档、构建输出和上述安装包三份 SHA-256 一致。构建基于 `70b562d8965c149f7da80b93f7b62d87476404a6` 及本批未提交修改，验收清单逐文件绑定实际源码；不是仅由该提交生成。此前同版号构建与摘要保留在 `artifacts/release-verification/20261107T114312Z-0ad682ec/`，当前安装入口更新为本次产物。

## 验证范围

- 严格本地验收：258 个套件、2,955 项通过，0 失败、0 错误、0 跳过；包含最终签名 APK 合同和抖音 40.8.0 / 40809900 宿主静态合同。
- Release lint：0 错误、54 警告，警告预算通过，0 个未审阅新增警告。
- Windows 原生 ICU 检查通过；签名、16 KiB 对齐、源码和验收证据完整性通过。
- 验收清单：`artifacts/release-verification/20261107T142932Z-fd12f521/manifest.json`，状态 `verified-local`、`published=false`。该目录保留签名、对齐、测试、Lint、宿主合同与 R8 mapping 证据，并已逐一核对证据摘要。
- 原流程在全量测试完成后、Lint 分析期间中断；源码快照不变，保留同轮完整 JUnit，单独重跑 Release Lint 后调用同一验收器完成封存。续验过程见 `resumption.json`、`resumed-lint.log`。

发布前另补齐发布工具测试中的两个新 Miuix 用例名称，42 项工具测试通过。该改动只涉及 `tools/tests/test_release_verification.py`，不改应用源码或 APK；原验收清单保留本地封存状态，远端提交、CI 与双端下载核验另存本地 `artifacts/DouAi-0.2.39-publication.json`。

尚未进行新包真机安装、实际 LSPosed Hook、真实 TTS 服务请求或抖音语音发送验证；本地宿主静态合同不能代替这些检查。本轮严格宿主为 40.8.0 / 40809900，未重新执行 40.6.0 的补充扫描。

## 免责协议

本项目仅用于学习与技术研究。用户的应用、配置、二次开发及使用行为和结果由用户自行决定并承担责任，与本项目及作者无关。完整边界见 [免责协议](../DISCLAIMER.md)，APK 关于页与 LSPosed 仓库同步保留。
