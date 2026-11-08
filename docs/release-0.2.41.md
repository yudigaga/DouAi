# DouAi 0.2.41 正式版：41.0.0 手势与关联适配修复

版本 **0.2.41 / 142**，正式包名 `io.github.yudigaga.douai`。沿用项目长期签名和 R8 Release 配置。GitHub [`v0.2.41`](https://github.com/yudigaga/DouAi/releases/tag/v0.2.41)、LSPosed [`142-0.2.41`](https://github.com/Xposed-Modules-Repo/io.github.yudigaga.douai/releases/tag/142-0.2.41) 使用同一 APK，每端仅上传 APK 附件。

本版累计纳入 0.2.40 的抖音 **41.0.0 / 410001 / 内部构建 41009900** 独立适配，保留 40.6.0 和 40.8.0。原生聊天、相册、下载、点赞、输入、长按语音、实况照片和隐藏视频安全任务按新宿主映射；历史候选验证范围见 [0.2.40 记录](release-0.2.40.md)，不计作本版新增执行。

## 修复内容

- 修复 41.0.0 的视频参数字段迁移：手势和自动播放从正确的 `B2` 读取 `VideoItemParams`；40.6.0、40.8.0 继续使用 `A2`，严格类型与对象关系检查保留。
- 本地浏览统计使用相同映射，恢复对非当前邻居视频的排除检查。
- 修复 41.0.0 的获赞、关注、粉丝、朋友数字字段，保护原标签，并保留仅本人本地显示、关闭或切换页面时恢复的行为。
- 补充手势安装失败的结构化错误记录、新宿主形状的行为回归及真实 DEX 字段/语义合同。

长按倍速的持续选择、松手保持、再次切换及短按/滑动/多指交回策略保持原有行为；未修改语音生成、重试与文本回退。根因和其余日志分类见 [日志排查与修复报告](2026-11-08_日志-手势与关联适配修复-report.md)。

## 安装包

[GitHub 下载 APK](https://github.com/yudigaga/DouAi/releases/download/v0.2.41/DouAi-0.2.41-release.apk) · [LSPosed 正式版](https://github.com/Xposed-Modules-Repo/io.github.yudigaga.douai/releases/tag/142-0.2.41)

| 项目 | 结果 |
| --- | --- |
| APK | `artifacts/DouAi-0.2.41-release.apk` |
| versionName / versionCode | `0.2.41 / 142` |
| 大小 | 5,453,641 字节 |
| SHA-256 | `169A6C231468AB03C4C92A9E9FD8F835E0509F2229CC64D71CF1486585151D7C` |
| 签名 | 项目长期证书，APK v3 验证通过 |
| 证书 SHA-256 | `FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47` |
| 对齐 | `zipalign -c -P 16 -v 4` 通过 |

构建基于 `f4a21f1a05d18747a2cd8fe3ed1019c519ec66fd` 及当前未提交的 41.0.0 适配、本轮修复和版本修改。严格验收锁定 610 个实际源码、构建和工具输入，不能把安装包描述成仅由该提交生成。

## 验证范围

最终包严格验收目录：`artifacts/release-verification/20261108T005433Z-29988871/`，清单状态为 `verified-local`、`published=false`，SHA-256 为 `D80EC5A26AD9B1E1E84DC5D836FE93A0C58C6C98B4EB3BC466AFFD62103AA699`。

- 最终签名 APK 完整回归：259 个套件 / 2,973 项，零失败、零错误、零跳过；包含最终 R8 APK 的五项打包合同和抖音 41.0.0 原 APK 静态合同。
- Release Lint：零错误、54 条既有警告、零新增警告，警告预算通过。
- Windows 原生 ICU 预检及 42 项 Python 发布工具测试通过；APK v3 签名、16 KiB ZIP 对齐通过。
- 清单绑定 610 个源码/构建/工具输入和 666 份证据，保留 JUnit/XML/HTML、Lint、R8 mapping、签名、对齐及宿主合同。
- 验收归档、Gradle 输出和交付 APK 的 SHA-256 一致；证据完整性复核通过。

结果汇总另存于 `artifacts/DouAi-0.2.41-release.json`；摘要文件为 `artifacts/DouAi-0.2.41-SHA256SUMS.txt`。

已经完成的修复专项：

- 三条视频参数行为回归先失败，最小修复后五类相关专项 151 项通过。
- 个人资料现代回归先因旧字段类型失败，修复后视图与原始 41 APK 合同 8 项通过。
- 40.6.0、40.8.0 原始 APK 的字段/速度合同、资源映射及资料视图专项各 19 项通过，均零失败、错误、跳过。
- 42 项 Python 发布工具测试及 Windows 原生 ICU 预检通过。
- 独立代码复核未发现有证据的阻断问题；保留原输入策略、旧版本字段与失败回滚。

严格主验宿主为抖音 41.0.0 / 410001 / 41009900。新增个人资料 DEX 语义合同明确针对该宿主，没有把旧版跳过算成通过。相关专项、失败复现、旧宿主及日志分类证据归档在验收目录的 `supplemental-log-fix/`。

原验收清单保留 `verified-local`、`published=false` 快照。发布源码、远端 CI、正式标签及双端下载核验另存本地 `artifacts/DouAi-0.2.41-publication.json`，不改写封存证据。

新包没有执行真机安装、真实 LSPosed Hook 或真实服务请求。安装后需完全重启抖音，再复验长按、双击、松手保持、切视频及个人资料页面。

## 免责协议

本项目仅用于学习与技术研究。用户的应用、配置、二次开发及使用行为和结果由用户自行决定并承担责任，与本项目及作者无关。完整边界见 [免责协议](../DISCLAIMER.md)。
