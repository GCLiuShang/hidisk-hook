# 图库云相册：预览转圈 / 云端图片未同步 原因分析

> 说明：本文为实验记录，设备 IP、账号、手机号等已脱敏；文中 `analysis/`、`hidisk-hook/` 为仓库内相对路径。


- 日期：2026-11-16
- 设备：HONOR REP-AN00，MagicOS 9.0.0，Android 15（SDK 35）
- 图库：`com.hihonor.photos`（uid 10205）
- 云空间：`com.huawei.hidisk` 17.0.1.701（uid 10054）
- 本轮范围：只检查原因，不修复；未修改模块、应用或系统文件。

## 结论

云相册的**云端元数据能拉到**，但云空间 SDK 把缩略图解密落盘到
`/storage/emulated/0/.photoShare/thumb/thumb/...` 时，被 Honor 的
MediaProvider/FUSE 以 `EPERM` 拒绝，文件实际没有生成，下载回调返回
`DownloadFailReason: 101`。因此图库云端视图的缩略图一直转圈/空白。

这不是网络、账号或华为云信任检查问题：同一条日志里，云端下载任务本身
（分片下载、解密前步骤）是成功的，失败发生在**解密后写本地文件**。

## 现象与复现

1. 图库“所有照片”筛选菜单有“所有 / 本地 / 云端”。
2. 切到“云端”后，标题显示“所有照片(云端)”，但网格为空；`uiautomator`
   没有可读节点，截图 `analysis/g6.png`。
3. 在“所有照片”打开一个云端视频，界面有“下载”按钮，bubble 提示
   “点击可下载原图到本机。”，说明该媒体只有云端元数据，原图未落本机。
4. 日志中缩略图下载连续失败；`gallery-logcat.txt` 8,624 行，
   `grep -c 'download failed'` = 70，`grep -c 'EPERM'` = 140，
   `thumbType` 只有 `2`。

## 证据链

### 1. 云空间 SDK 明确报解密写文件失败

```text
Cloud[17.0.1.701]Album.AssetDownloader: calBatchDownloadInfo,
  traceId:04004_01_1794838877_01403832, fileSize:17296, thumbType:2

Cloud[17.0.1.701]Album.AssetDownloader: decrypt file error:
  {code=4005, message=decrypt slice file error:
   /storage/emulated/0/.photoShare/thumb/thumb/default-album-1/....jpg:
   open failed: EPERM (Operation not permitted), hasPermission = true}

Cloud[17.0.1.701]Album.AssetDownloader: hmac retry decrypt file error:
  ... 同样的 EPERM ...

Cloud[17.0.1.701]Album.PhotoFileCheckUtil:
  ... downloadAssetValid downloadFile not exist

Cloud[17.0.1.701]Album.SDKDownloadTaskCallable:
  different size delete file isSuccess = false

Cloud[17.0.1.701]Album.SDKDownloadPhoto:
  download failed: ***.jpg, type: 2, DownloadFailReason: 101,
  ret: 1, needCallback: true

Cloud[17.0.1.701]Album.CloudAlbumSdkServiceImpl:
  sendCallbackMsg msgId: 9143
```

关键点：`downloadFile not exist` 说明不是“下载到了但校验失败”，而是目标文件
根本没有写成功；`DownloadFailReason: 101` 是上层对这次失败的归类。

### 2. MediaProvider 同时拒绝这个顶层目录

```text
MediaProvider: insertFileIfNecessary failed with path:
  /storage/emulated/0/.photoShare/thumb/thumb/album-1/....jpg,
  uid:10054, pkName:com.huawei.hidisk

java.lang.IllegalArgumentException: Primary directory .photoShare not allowed
  for content://media/external_primary/file;
  allowed directories are [Download, Documents],
  uid:10054, pkName:com.huawei.hidisk

  at com.android.providers.media.MediaProvider.ensureFileColumns(MediaProvider.java:4816)
  at com.android.providers.media.MediaProvider.ensureUniqueFileColumns(MediaProvider.java:4434)
  at com.android.providers.media.MediaProvider.insertFile(MediaProvider.java:5243)
  at com.android.providers.media.MediaProvider.insertInternal(MediaProvider.java:5895)
  at com.android.providers.media.MediaProvider.insert(MediaProvider.java:5565)
  at com.android.providers.media.MediaProvider.insertFileForFuse(MediaProvider.java:10818)
  at com.android.providers.media.MediaProvider.insertFileIfNecessaryForFuse(MediaProvider.java:10931)
```

这条是直接原因：FUSE 把写文件转给 MediaProvider 建索引时，MediaProvider 认为
`.photoShare` 不是允许的顶层目录，拒绝插入，最终 `open()` 返回 `EPERM`。

### 3. 目录归属与权限

```text
drwxrws--- 3 10178 1023 3452 ... /storage/emulated/0/.photoShare
drwxrws--- 7 10178 1023 3452 ... /storage/emulated/0/.photoShare/thumb/thumb
```

- uid `10178` = `com.android.providers.media.module`（MediaProvider）。
- group `1023` = `media_rw`。
- 云空间进程是 `u0_a54`（uid 10054），不是目录 owner，也不在 `media_rw` 组内。
- 目录 mode `2770`，云空间没有直接的 Unix 写权限。

`default-album-1` 为空，`default-album-2` 只有一张旧截图；没有新的缩略图落盘。

### 4. 应用权限状态

`com.huawei.hidisk` 的 `targetSdkVersion=30`，声明了：

- `WRITE_EXTERNAL_STORAGE` / `READ_EXTERNAL_STORAGE`：`granted=true`
- `com.android.providers.media.permission.SCAN_FOLDER`：`granted=true`
- `WRITE_MEDIA_STORAGE`：仅声明，未出现在 granted 列表
- `MANAGE_EXTERNAL_STORAGE`：`appops` 为 `default`，未授予
- 没有 `requestLegacyExternalStorage`

所以 `hasPermission = true` 只是运行时存储权限检查通过，并不代表能绕过
MediaProvider 对 `.photoShare` 的目录策略。Huawei ROM 上云空间通常靠
`WRITE_MEDIA_STORAGE`/系统应用身份获得该目录的写权限；Honor 上这个身份条件
不成立。

### 5. 反编译对应位置

- `e7/e.java:147-172`
  - `k()` 返回 `f42903a + "/.photoShare/thumb/lcd/"`
  - `n()` 返回 `f42903a + "/.photoShare/thumb/thumb/"`
- `db/b.java:44-82`
  - `decryptSliceFile` 先 `parentFile.mkdirs()`，再
    `new RandomAccessFile(file, "rw")`
  - `IOException` 被包装成
    `dk.b(4005, "decrypt slice file error: " + e.getMessage())`
  - `EPERM` 就是在 `RandomAccessFile` 打开目标文件时发生。
- `e7/c.java:1292`
  - `b0()` 的 `hasPermission` 只检查
    `WRITE_EXTERNAL_STORAGE`/`READ_EXTERNAL_STORAGE`，因此在 Android 15
    上仍然返回 true，不能代表能写 `.photoShare`。
- `e7/c.java:1348`
  - 上传压缩缓存路径同样固定为
    `/.photoShare/uploadCache/<albumId>`（`c1(String)`）。
  - 这说明 `.photoShare` 不只是缩略图缓存，也是云相册上传压缩的缓存目录。

## 为什么预览一直转圈

1. 图库向云空间 SDK 请求缩略图（`thumbType=2`）。
2. SDK 下载分片成功，解密后尝试写
   `/storage/emulated/0/.photoShare/thumb/thumb/...`。
3. MediaProvider/FUSE 拒绝该顶层目录，`open` 返回 `EPERM`。
4. 文件不存在，`PhotoFileCheckUtil` 报 `downloadFile not exist`。
5. `SDKDownloadPhoto` 返回 `DownloadFailReason 101`，回调 `msgId 9143` 给图库。
6. 图库拿不到缩略图，网格项一直处于加载态；云端视图则可能整页空白。

## 关于“本机应该同步的图片没有同步”

目前能确证的是：

- 云端**元数据**已经拉到（否则不会连续请求 70 个缩略图）；
- 云端视图的**缩略图缓存**没有落盘，所以看起来“没有同步/没有图”；
- 云相册的上传压缩缓存也使用 `.photoShare/uploadCache`，同一目录策略会同时
  影响上传压缩链路。

但当前日志只触发了 `thumbType=2`（缩略图），没有触发 `thumbType=0`（原图）
的下载分支；原图下载的 `savePath` 由图库调用方传入，不能仅凭现有日志断言
“所有原图下载都失败”。可以确定的是：**只要目标路径落在 `.photoShare` 下，
就会被同一策略拦截**；要判断原图是否也失败，需要单独触发一次原图下载并抓
`SDKDownloadPhoto type: 0` 的日志。

## 待核实

- 原图下载（`thumbType=0`）的实际 `savePath` 是否也落在 `.photoShare` 下。
- 图库的“云端”视图是否会在缩略图失败后完全空白，还是会显示占位图标。
- Honor MediaProvider 是否对 `MANAGE_EXTERNAL_STORAGE` 或
  `WRITE_MEDIA_STORAGE` 有特殊白名单（本轮未做任何授权/修改）。

## 产物

- 图库加载日志：`analysis/gallery-logcat.txt`
- 云端视图日志：`analysis/cloudview-logcat.txt`
- 云端视图截图：`analysis/g6.png`
- 本轮未修改模块、应用或系统文件。
