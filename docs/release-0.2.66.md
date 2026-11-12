# DouAi 0.2.66：导航恢复与运行时优化

正式版 **0.2.66 / 167**，长期签名 R8 Release，安装后彻底重启抖音。

修复组件隐藏开启时，评论区锁屏再点亮后底部导航不按宿主最新状态恢复的问题。试听失败释放播放器与临时文件并显示失败状态；普通 JSON 请求遵守总期限。减少导航遍历、AI 联系人反射与重复日志工作，合并尚未开始的过期报告读取，按日复用星期计算。

## 下载

[GitHub APK](https://github.com/yudigaga/DouAi/releases/download/v0.2.66/DouAi-0.2.66.apk) · [LSPosed APK](https://github.com/Xposed-Modules-Repo/io.github.yudigaga.douai/releases/download/167-0.2.66/DouAi-0.2.66.apk)

| 项目 | 值 |
| --- | --- |
| APK | `DouAi-0.2.66.apk` |
| versionName / versionCode | `0.2.66 / 167` |
| 大小 | 5,813,621 字节 |
| SHA-256 | `D3C1C8C55CA90E83D76A264106FD26D368D590CC402BAA1707E177536023AE63` |
| 签名与对齐 | 长期证书 v3、16 KiB ZIP 对齐通过 |

## 验证范围

R8 Release 构建成功；最终签名 APK 的 **3 套件 / 9 项 APK 与抖音 41.0.0 合同**全部通过，无失败、错误或跳过。Release Lint 为 0 错误、54 警告；警告预算检查通过，0 项新增未审警告。构建归档时 690 个输入与 APK、签名、对齐证据已核对。

**本版没有重跑完整测试集。**此前 0.2.65 优化候选的 3,439 项全量回归绑定不同 APK，不计作本版全量结果。真机、实际 LSPosed 回调及真实语音/模型服务未验；本版发布时的 CI 结果另行核对。

本项目仅用于学习与技术研究。用户的应用、配置、使用行为及结果由用户自行承担，与本项目及作者无关。
