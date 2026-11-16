# HiDisk AppOps（KernelSU 伴随模块）

这是一个极小的 KernelSU 模块，只做一件事：开机后为 `com.huawei.hidisk` 保持：

```text
MANAGE_EXTERNAL_STORAGE=allow
```

## 为什么需要

- 荣耀图库把云相册缓存写到 `/storage/emulated/0/.photoShare/thumb/...`，这些文件属于 MediaProvider；
- `com.huawei.hidisk` 直接打开会得到 `EACCES`/`EPERM`，上传报 `ret=1011`，云端缩略图下载也可能失败；
- LSPosed 模块运行在目标应用进程内，不能给另一个包授予 AppOp，因此需要设备级伴随模块。

## 安装

1. 在 KernelSU 管理器中安装 `hidisk_appops.zip`；
2. **软重启**（KernelSU 模块变更需要软重启；完整重启也可以）；
3. 验证：

```bash
adb shell appops get com.huawei.hidisk MANAGE_EXTERNAL_STORAGE
# MANAGE_EXTERNAL_STORAGE: allow
```

也可以手动执行：

```bash
su -c 'sh /data/adb/modules/hidisk_appops/service.sh'
```

## 范围

只设置一条 AppOp。不修改系统分区、图库数据库或华为云空间 APK。

## 免责声明

仅供学习研究，使用风险自负。
