# DouAi 0.2.35 本地签名构建

版本 **0.2.35 / 136**，2026-11-02 本地签名 Release 构建，尚未发布到 GitHub 或 LSPosed。

本版改动：

- 消息页 AI 联系人置于同一父容器其他视图的最高 Z 层之上，避免原生加载/空状态视图遮挡。
- 自动回复浮窗在有前台任务期间按需刷新；如果宿主移除浮窗，会重新挂载。
- 评论回复收件扫描按账号持久化本人评论分页游标，每轮检查最新页并继续查找更早的评论。账本拒绝项新增固定状态标签诊断，不改变重试或防重复规则。

## APK

- 文件：`artifacts/DouAi-0.2.35-release.apk`
- 包名：`io.github.yudigaga.douai`
- 大小：5,413,206 字节
- SHA-256：`6DE47CA0A90C776DE53F268F50B07E751D631B08233C9AECB645E17C5348D6F8`
- 长期证书 SHA-256：`FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47`
- APK Signature Scheme v3 验证通过；16 KiB ZIP 对齐验证通过。
- R8 mapping：`artifacts/DouAi-0.2.35-mapping.txt`
- SHA-256 文件：`artifacts/DouAi-0.2.35-release.apk.sha256`

## 验证范围

Release 构建成功。按请求，本次构建未运行测试。构建前的源代码修正已通过 7 个相关套件、277 项定向测试，零失败、错误或跳过；Release Lint 为 0 错误、54 条警告。未运行完整测试集、真机、真实 LSPosed 或真实账号评论回复验证。

安装此包后，完全结束抖音进程再打开；让评论回复扫描运行数轮，以便继续遍历历史评论页。新日志中的 `ledger_status` 可区分已发送、基线忽略、失败或未知结果，未知及已发送记录不会自动重发。
