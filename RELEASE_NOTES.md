# HiDisk Hook v1.0.0

> 仅供学习研究，后果自负。使用前请备份联系人、日历、图库与相关数据库。

## 本次发布

这是首个公开版本，包含：

- **HiDiskHook-v1.0.0.apk**：LSPosed 模块，包名 `com.hidisk.hook`，versionCode `10000`，versionName `1.0.0`。
- **hidisk_appops.zip**：KernelSU 伴随模块，开机为 `com.huawei.hidisk` 保持 `MANAGE_EXTERNAL_STORAGE=allow`。

## 修复与增强

- 修复华为云空间“无法连接服务器，请点击屏幕再试”；
- 修复进入主页黑屏（Honor 缺 `HwFoldScreenManagerEx`）；
- 修复右上角四个点无响应、“管理空间”黑屏（Honor 缺华为 framework 控件类）；
- 图库本地→云端自动上传（相册级 dirty 兼容），云端→本地缩略图/原图下载（AppOps 前提）；
- 联系人双向桥接（停用 `com.hihonor.contacts.sync`，账户类型/调用包兼容）；
- 日历双向桥接（本地日历选择 + `realPkgName` 修复）；
- 模块控制台：状态检查、作用域重启、Honor 包状态应用/回滚。

## 安装顺序

1. 安装 `HiDiskHook-v1.0.0.apk`；
2. LSPosed 启用模块，作用域勾选：华为云空间、图库、日历存储；
3. 安装 `hidisk_appops.zip` 为 KernelSU 模块，软重启；
4. 打开 HiDisk Hook 控制台，授予 root，检查状态；
5. 点“应用推荐 Honor 包状态”；
6. 强制停止/重新打开云空间、图库、日历存储。

## 校验值

```text
HiDiskHook-v1.0.0.apk
SHA-256: 1bb636e6337d47cca03d2d8f61f9651b9d0627244b5054fc1278da4886692b75
大小: 58,317 B

hidisk_appops.zip
SHA-256: 40894db8c4cf1e4620c4239702db2ed0711105dbcf0267f8328498835455ffed
大小: 1,669 B
```

## 已知限制

- 应用升级后 hook 可能失效，无版本守卫；
- 联系人桥接依赖停用 Honor 同步包，可能与 Honor 云冲突；
- 日历 hook 依赖 CalendarProvider 具体实现；
- 非折叠屏假设；
- 备忘录未实现桥接；
- 控制台的状态检查基于 LSPosed 日志，不是配置库真值。

完整说明、分析文档与构建方法见仓库 README。

## 免责声明

本项目仅供学习研究。使用可能导致账号异常、数据丢失、系统不稳定或违反服务条款；作者不提供担保，不承担任何后果。
