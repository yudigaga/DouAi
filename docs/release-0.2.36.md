# DouAi 0.2.36 本地签名构建

版本 **0.2.36 / 137**，2026-11-02 本地签名 Release 构建，未发布 GitHub 或 LSPosed。

取消消息页 AI 联系人区域最多三行的固定高度。空间足够时显示全部已添加联系人；屏幕空间不足时保留原生会话空间，超出的联系人可在区域内滚动访问，并显示滚动条。

用户的 `DouAi-debug-20261102-200352.log.txt` 在 20:02:44 和 20:03:29 均记录 `contact_count=4`、`width=1440;height=720`，可确认第四项已保留在数据和视图中。旧代码将显示高度限制为三行，因此第四项落在滚动区域下方。本版修正显示高度，不改变已保存的联系人配置。

## APK

- 文件：`artifacts/DouAi-0.2.36-release.apk`
- 包名：`io.github.yudigaga.douai`
- 大小：5,413,208 字节
- SHA-256：`3270B31B553391323D4EA9F7E175C73105A02D11A334D17C06E35C100891D1B4`
- 长期证书 SHA-256：`FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47`
- 原密钥 v3 签名、非 debuggable 和 16 KiB ZIP 对齐核验通过，归档与构建输出 SHA-256 一致。
- 构建记录：`artifacts/DouAi-0.2.36-build.json`
- R8 mapping：`artifacts/DouAi-0.2.36-mapping.txt`
- 校验文件：`artifacts/DouAi-0.2.36-release.apk.sha256`

## 验证范围

Release 构建成功，按用户要求未运行测试，也未单独运行完整 Lint。构建自带的 Release vital Lint 任务通过。未进行真机或 LSPosed 验证；安装后完全结束抖音进程再打开，检查四个联系人是否完整显示。
