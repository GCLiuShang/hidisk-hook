# 华为云空间：右上角四个点 / 管理空间 修复记录

> 说明：本文为实验记录，设备 IP、账号、手机号等已脱敏；文中 `analysis/`、`hidisk-hook/` 为仓库内相对路径。


## 现象

1. 云空间主页点击右上角四个点，无反应；实际是进程崩溃后重启。
2. 点击“管理空间”，进入 `HisyncSpaceDetailActivity` 后黑屏。

## 真机日志

设备：`192.0.2.10:5555`（Honor REP-AN00，Android 15）。

### 四个点

修复前：

```text
java.lang.NoClassDefFoundError: Failed resolution of: Lhuawei/android/widget/ListView;
  at com.huawei.android.hicloud.ui.activity.MainActivity.jb(Unknown Source:17)
  at com.huawei.android.hicloud.ui.activity.MainActivity.x3(Unknown Source:0)
  at com.huawei.android.hicloud.ui.activity.MainActivity$t.onClick(Unknown Source:36)
FATAL EXCEPTION: main
Caused by: java.lang.ClassNotFoundException: huawei.android.widget.ListView
```

给模块 dex 追加兼容类后，字段类型能解析了，但 Honor 布局给的是自己的子类：

```text
java.lang.ClassCastException: com.hihonor.android.widget.ListView
    cannot be cast to huawei.android.widget.ListView
  at com.huawei.android.hicloud.ui.activity.MainActivity.jb(Unknown Source:17)
```

`MainActivity.java:253`：

```java
public huawei.android.widget.ListView f13811u3;
```

`jb()` 先 inflate `main_menu_window`，再把 `main_menu_items` 的控件赋给该字段。
Honor ROM 的 `ListView` 不是 `huawei.android.widget.ListView`，所以字段赋值失败。

### 管理空间

```text
InflateException: Binary XML file line #159 in com.huawei.hidisk:layout/storage_manage:
  ... Error inflating class com.huawei.android.hicloud.ui.uiextend.StorageManagerCardViewLayout
FATAL EXCEPTION: main
```

`StorageManagerCardViewLayout` 的字段：

```java
protected huawei.android.widget.HwTextView numView;
```

`res/layout/storage_manage_cardview_top_item.xml` 第 99 行直接使用
`<huawei.android.widget.HwTextView android:id="@0x7f0a1211">`，Honor ROM 没有
这个类，inflate 时抛 `NoClassDefFoundError`（被包装成 `InflateException`）。

## 为什么不能只用 ClassLoader hook

先试过 hook `ClassLoader.loadClass(String, boolean)`，把
`huawei.android.widget.ListView` / `HwTextView` 指向模块里的同名单例类。
但点击时仍然抛 `NoClassDefFoundError`，LSPosed 日志里也没有
`injected ...`：ART 解析字段类型走的是 native class linker，不经过 Java
`ClassLoader.loadClass()`。

也试过把模块的 dex element 原样追加到 app 的 `pathList`，结果：

```text
java.lang.InternalError: Attempt to register dex file
  Anonymous-DexFile@... with multiple class loaders
```

因为同一个 `DexFile` 已经注册给模块 classloader，不能再注册给 app classloader。

## 最终方案

1. 模块内新增两个兼容类：
   - `app/src/main/java/huawei/android/widget/ListView.java`
   - `app/src/main/java/huawei/android/widget/HwTextView.java`
2. 在目标进程 `Application.onCreate` 时：
   - 从 `com.hidisk.hook` 的 `sourceDir` 取出模块 APK；
   - 复制其中的 `classes.dex` 到 app cache；
   - 用 `DexFile.loadDex()` 新建一个独立的 `DexFile`；
   - 用 `DexPathList$Element(DexFile)` 构造 element，追加到 app 的
     `BaseDexClassLoader.pathList.dexElements`。新的 `DexFile` 只注册给 app
     classloader，不再冲突。
3. `LayoutInflater.inflate(int, ViewGroup)` after hook：
   - 找到 `R.id.main_menu_items`（0x7f0a0b74）；
   - 如果它不是 `huawei.android.widget.ListView`（Honor 的
     `com.hihonor.android.widget.ListView` 也算），用 app classloader 加载
     兼容类并创建实例；
   - 复制 id/divider/dividerHeight/selector/cacheColorHint/padding/
     scrollBarStyle/layoutParams，替换到原 parent 的同 index。
4. `R.id.size_view`（0x7f0a1211）做兜底替换：如果它不是
   `huawei.android.widget.HwTextView`，用兼容 `TextView` 替换，保留文本、
   字号、颜色、typeface、gravity、padding、visibility。

## 验证

LSPosed 日志：

```text
HiDiskHook: hooked Application.onCreate for compat dex
HiDiskHook: appended copied compat dex to app loader (1 -> 2)
HiDiskHook: replaced main-menu ListView
```

截图：

- `analysis/final-menu.png`：四个点菜单正常展开，
  含“帮助与客服 / 付费管理 / 权益兑换 / 更多设置”；
- `analysis/final-space.png`：“管理空间”正常显示
  “已使用 13.3 GB / 50 GB”、云备份 6.8 GB、笔记 3.6 GB、图库 2.8 GB 等；
- 点击“更多设置”可进入 `MoreSettingActivity`。

## 产物

- 模块源码：`hidisk-hook`
- APK：`hidisk-hook/build/HiDiskHook.apk`
- SHA-256：`a5924433e6d694fbee13e075f1043d9cfe82e9018b346350ead548ddc890085e`
- 包名：`com.hidisk.hook`
