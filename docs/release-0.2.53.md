# DouAi 0.2.53：历史跳转与首页侧栏

版本 **0.2.53 / 154**，包名 `io.github.yudigaga.douai`，沿用长期签名正式 Release。GitHub `v0.2.53` 与 LSPosed `154-0.2.53` 每端仅附同一 APK。

## 更新

- 评论、回复历史增加定位入口，可按记录信息查看评论、回复、作品或私信会话；旧记录按实际信息回退并核对账号。
- 默认关闭的“首页右滑打开侧边栏”支持 23 项可选模块页面，默认显示六项；修复宿主缓存和组合卡识别，按自然高度展示所选入口。
- 侧栏主列表只显示一份“模块功能”，按准确通知组件定位到“通知消息”下方；关闭或切换个人侧栏时恢复原生内容和原顺序。无法定位准确通知组件时保留原位置。

安装后彻底重启抖音，在“模块设置 → 界面相关”开启首页右滑并设置侧栏显示项。新增侧栏适配限已核实的抖音 **41.0.0 / 410001 / 41009900**，40.6/40.8 既有规则保留。历史跳转与侧栏实际显示、排序和恢复仍需设备确认。

## 下载

[GitHub APK](https://github.com/yudigaga/DouAi/releases/download/v0.2.53/DouAi-0.2.53-release.apk) · [LSPosed 正式版](https://github.com/Xposed-Modules-Repo/io.github.yudigaga.douai/releases/tag/154-0.2.53)

| 项目 | 值 |
|---|---|
| APK | `DouAi-0.2.53-release.apk` |
| versionName / versionCode | `0.2.53 / 154` |
| 大小 | 5,617,479 字节 |
| SHA-256 | `8940AFA9BB7719F721BBBC09C286FBE9A47F5469E52243A9C5FDC33B3732CDDC` |
| 签名 | 长期证书，v3 验证通过 |
| 证书 SHA-256 | `FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47` |
| 对齐 | 16 KiB ZIP 对齐通过 |

## 核验范围

`:app:assembleRelease` 成功，附带 vital Lint 通过。包名、版本、非调试、长期签名、16 KiB 对齐、构建与归档哈希及 650 个输入快照已核对。

按要求未运行测试、独立 Release Lint 或 APK 合同，也未操作设备或调用真实服务。新增回归仅编写未执行；静态审查与构建不代表真机行为验证。按维护者要求直接发布，不等待 CI，未声明本版 CI 通过。
