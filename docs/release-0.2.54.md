# DouAi 0.2.54：自动锁定最高画质

版本 **0.2.54 / 155**，包名 `io.github.yudigaga.douai`，沿用长期签名正式 Release。GitHub `v0.2.54` 与 LSPosed `155-0.2.54` 每端仅附同一 APK。

## 使用与范围

在“模块设置 → 自动播放 → 锁定最高画质”中开启，再彻底重启抖音。该功能默认关闭，自动选择当前视频原生支持的最高档位，通过实际播放器读回进行有限纠偏；暂停或播放源变化会撤销旧任务。

新功能仅适配已核实的抖音 **41.0.0 / 410001 / 41009900**。原生仅支持 Auto、未知播放器或无法安全切换的模式保留原生策略，不伪造 4K 或解码支持。

关闭会停止后续保持，当前档位可能保留至播放页重建，重启后恢复原生选择。不写入原生持久化画质偏好，旧版适配和规则资产保留。真实片源与设备上的切档、保持效果仍需安装确认。

## 下载

[GitHub APK](https://github.com/yudigaga/DouAi/releases/download/v0.2.54/DouAi-0.2.54-release.apk) · [LSPosed APK](https://github.com/Xposed-Modules-Repo/io.github.yudigaga.douai/releases/download/155-0.2.54/DouAi-0.2.54-release.apk)

| 项目 | 值 |
|---|---|
| APK | `DouAi-0.2.54-release.apk` |
| versionName / versionCode | `0.2.54 / 155` |
| 大小 | 5,633,869 字节 |
| SHA-256 | `BA54B5EA9C0AD1AD76E1D61D66EE032BC726687F8173726923151739D014B0A7` |
| 签名 | 长期证书，v3 验证通过 |
| 证书 SHA-256 | `FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47` |
| 对齐 | 16 KiB ZIP 对齐通过 |

## 核验范围

`:app:assembleRelease` 成功，构建自带 vital Lint 通过。包名、版本、非调试、长期签名、16 KiB 对齐、构建与归档哈希及 653 个输入快照已核对。

按要求未运行测试、完整 Lint 或 APK 合同，也未操作设备或调用真实服务。新增回归仅编写未执行；静态审查与构建不代表真机行为验证。按维护者要求直接发布，不等待 CI，未声明本版 CI 通过。
