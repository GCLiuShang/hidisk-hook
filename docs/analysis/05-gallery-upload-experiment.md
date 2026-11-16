# 图库上传失效：最小修改实验记录

> 说明：本文为实验记录，设备 IP、账号、手机号等已脱敏；文中 `analysis/`、`hidisk-hook/` 为仓库内相对路径。


日期：2026-11-17 00:34–00:37
设备：`192.0.2.10:5555`
图库：`com.hihonor.photos`（uid 10205）
云空间：`com.huawei.hidisk`（uid 10054）

## 目的

验证此前的推断：`gallery_album.dirty=0` 是否就是上传不启动的直接原因。

## 操作

1. 备份数据库到 host：
   - `analysis/db/backup/medialibrary.db`
   - `.../backup/medialibrary.db-wal`
2. 在 `/sdcard/DCIM/Screenshots/` 生成两张测试截图：
   - `Screenshot_20261117_003500_test.png`（44,233 B）
   - `Screenshot_20261117_003600_test.png`
3. 用 `content call --uri content://media --method scan_file --arg <path>` 让 MediaStore 收录；`gallery_media` 新增：
   - `_id=796`，`_data=.../Screenshot_20261117_003500_test.png`，`dirty=1`，`uniqueId=''`，`albumId=''`，`local_media_id=1000000537`
   - `_id=797`，`_data=.../Screenshot_20261117_003600_test.png`，`dirty=1`，`uniqueId=''`，`albumId=''`，`local_media_id=1000000543`
4. 将 `gallery_album` 的 `default-album-2`（截屏录屏）`dirty` 从 0 改为 1：
   ```sql
   update gallery_album set dirty=1 where albumId='default-album-2';
   ```
   写回后重启图库，确认 `default-album-2.dirty=1` 仍然保持。
5. 打开图库“所有照片”，切换筛选到“云端”，观察 logcat 与 hidisk 内部日志。
6. 再在设置中关闭再打开“图库数据同步”开关，观察状态变化。

## 结果

### 设置 dirty=1 后

图库只执行了云端缩略图下载，没有上传：

```text
00:34:43 CloudAlbumSdkServiceImpl: sendCallbackMsg msgId: 9143
00:34:43 CloudAlbumSdkServiceImpl: downloadGeneralFiles, files count: 1, thumbType: 1
00:34:43 CloudAlbumSdkServiceImpl: downloadGeneralFiles, files count: 1, thumbType: 2
00:35:08 CloudAlbumSdkServiceImpl: downloadGeneralFiles, files count: 36, thumbType: 2
```

没有出现：

```text
getCreatedGeneralAlbums albumData size
getCreatedGeneralFiles fileInfos size
createGeneralFiles files count
uploadCreatedGeneralFiles
```

### 手动触发同步（关键对照）

仅重启图库不会自动触发上传；需要向 Gallery 的 `CloudStateReceiver` 发送同步广播：

```bash
adb shell su -c 'am broadcast -a com.huawei.cg.action.CAN_START_SYNC --ei Module 2'
```

#### 对照 A：所有 `gallery_album.dirty=0`

```text
00:44:42.586 CloudAlbumSdk: startSyncGeneral: 7
00:44:42.587 GeneralAlbumSyncLogic: startSync: 7, isForce: false
00:44:42.812 GeneralAlbumSyncLogic: Fversion equals, start upload sync
00:44:42.812 GeneralAlbumSyncLogic: startUpload
00:44:42.944 GeneralAlbumSyncLogic: uploadCreatedGeneralAlbums
00:44:42.946 GeneralAlbumSyncLogic: uploadModifiedGeneralAlbums
00:44:42.947 GeneralAlbumSyncLogic: uploadModifiedGeneralFiles
00:44:42.956 GeneralAlbumSyncLogic: no upload file
00:44:42.956 GeneralAlbumSyncLogic: releaseLock
00:44:43.206 GeneralAlbumSyncLogic: sync complete
```

结论：上传阶段启动了，但 `getCreatedGeneralAlbums`/`getCreatedGeneralFiles` 没有候选，直接 `no upload file`。

#### 对照 B：把相机与截图相册 `dirty` 置 1

```text
00:45:11.911 GeneralAlbumSyncLogic: uploadCreatedGeneralFiles
00:45:11.911 CloudAlbumSdkServiceImpl: createGeneralFiles(List,String,boolean,String)
00:45:11.922 CloudAlbumSdkLogic: createGeneralFiles currentUploadFiles: 10
00:45:11.922 CloudAlbumSdkLogic: createGeneralFiles, end size: 10
00:45:11.926 SDKUploadPhoto: execUpload, upload file size: 10
00:45:11.969 SDKUploadPhoto: HandleCallable upload, ret: 1011
00:45:12.070 SDKUploadPhoto: this upload batch fail ... Attempt to invoke virtual method 'long java.lang.Long.longValue()' on a null object reference
```

结论：`dirty=1` 后上传确实启动并提交了 10 个文件；随后因另一个参数为 null 失败（`ret=1011`），但“未启动”的直接原因已经确认是相册级 `dirty`。

### 关闭再打开“图库数据同步”

```text
00:36:47 GalleryMainActivity: showUploadStatus isSwitchOn:true
00:36:47 SDKUploadPhoto: cancelGeneralUploadTask, errCode: 109
```

随后 `gallery_album` 被重置：

```text
default-album-1 | dirty=0
default-album-2 | dirty=0
```

## 解释

### 第一层：相册 dirty=0，上传不收集候选

`needSyncUploadGeneral()` 的条件是：

```sql
dirty != 0 AND (cloud = 1 OR uploadStatus = 1)
```

对照 A 所有 `gallery_album.dirty=0` 时，`startUpload` 虽然被调用，但 `getCreatedGeneralAlbums`/`getCreatedGeneralFiles` 没有候选，最后 `no upload file`。
对照 B 把相机/截图相册 `dirty` 置 1 后，同一广播立即进入 `createGeneralFiles`，提交 10 个文件。
因此：**`gallery_album.dirty=0` 是“上传阶段不收集文件”的直接原因**。

### 第二层：1011 是 `.photoShare` 缩略图读取权限

`ret=1011` 不是网络错误。实际失败点是 hidisk 读取 Gallery 生成的缩略图：

```text
SDKFileInfoCreateManage: cloudphoto.file.create, fileName: ... code: 1011
info: get hmac fileList error:
/storage/emulated/0/.photoShare/thumb/thumb/default-album-1/IMG_20261117_000139.jpg:
open failed: EACCES (Permission denied)
```

`Long.longValue()` NPE 是 hmac 失败后的次生异常，不是单独的空字段。

`.photoShare/thumb/...` 由 MediaProvider 持有（uid 10178，mode 2770/660）；Gallery 通过 MediaProvider 能写，但 hidisk（uid 10054）没有 `MANAGE_EXTERNAL_STORAGE`，直接 `open()` 被 FUSE 拒绝。`chmod 644` 会被 FUSE 重置，无法绕过。

### 修复验证：授予 all-files access

```bash
adb shell appops set com.huawei.hidisk MANAGE_EXTERNAL_STORAGE allow
```

再把相机相册 `gallery_album.dirty=1`、触发同步后：

```text
createGeneralFiles currentUploadFiles: 10
execUpload, upload file size: 10
HandleCallable upload, ret: 0, fileName: ***0139.jpg
...
upload Success for: FileEntity{...}
```

数据库随后出现：

```text
_id=794  IMG_20261117_000139.jpg  dirty=2  uniqueId=f8f68ba9...
_id=796  Screenshot_20261117_003500_test.png  dirty=2  uniqueId=b92c3604...
_id=797  Screenshot_20261117_003600_test.png  dirty=2  uniqueId=fd81613b...
```

云端 CDP 重新加载后：

```text
745 张照片 和 4 个视频  ->  779 张照片 和 4 个视频
出现了：
  IMG_20261117_000139.jpg
  Screenshot_20261117_003500_test.png
  Screenshot_20261117_003600_test.png
```

说明完整上传流程已经跑通：Gallery 生成缩略图 → hidisk 读取缩略图并创建云文件 → 上传成功 → 云端可见。

## 结论

1. **上传不启动**：`gallery_album.dirty` 一直是 0，`needSyncUploadGeneral()` 返回 false，上传阶段没有候选。
2. **上传启动后 1011**：hidisk 没有 all-files access，读 `.photoShare/thumb/thumb/...` 被 EACCES 拒绝；hmac 失败后抛 `Long.longValue()` NPE，统一映射为 1011。
3. 两个问题叠加，导致“媒体 dirty=1、云端没有、日志里只有下载/取消”的现象。

## 当前设备状态

- `MANAGE_EXTERNAL_STORAGE` 已设为 allow（这是让上传可读缩略图的必要条件）。
- `gallery_album.dirty` 在成功上传后由系统复位；本地 21 条媒体已获得 `uniqueId`，其余 13 条仍待后续批次。
- 两张测试截图仍保留在 `/sdcard/DCIM/Screenshots/`，并已上传到云端；如需清理可手工删除。
- 数据库备份在 `analysis/db/backup/`。
