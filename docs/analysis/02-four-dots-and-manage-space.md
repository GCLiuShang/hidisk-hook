# 华为云空间两个新问题：原因分析

> 说明：本文为实验记录，设备 IP、账号、手机号等已脱敏；文中 `analysis/`、`hidisk-hook/` 为仓库内相对路径。


- 日期：2026-11-16
- 设备：HONOR REP-AN00，MagicOS 9.0.0，Android 15（SDK 35）
- 应用：`com.huawei.hidisk` 17.0.1.701（versionCode 170001701）
- 模块：`com.hidisk.hook` 1.0（LSPosed，scope `com.huawei.hidisk`）
- 本轮范围：只检查原因，不修复；未修改模块或应用。

## 结论

两个现象与网络、账号、华为云信任检查无关，根因相同：

> 当前安装的是**华为 EMUI 版本**的云空间 APK，代码和布局直接引用了
> `huawei.android.widget.*` 等华为 framework 类；Honor MagicOS 9.0 的 ROM
> 没有这些类。运行到对应界面时抛 `NoClassDefFoundError` / `InflateException`。

| 现象 | 真正原因 | 直接结果 |
|---|---|---|
| 点右上角四个点没反应 | `MainActivity` 菜单字段类型为 `huawei.android.widget.ListView`，Honor ROM 无此类 | 主线程 `FATAL EXCEPTION`，进程崩溃重启 |
| 点“管理空间”黑屏 | `StorageManagerCardViewLayout` 布局使用 `huawei.android.widget.HwTextView`，Honor ROM 无此类 | `InflateException`，Activity 窗口创建失败 |

## 一、四个点：不是没响应，是进程崩溃

点击 `ic_more_hot_space`（bounds `[1029,137][1185,293]`，中心 `(1107,215)`）后：

```text
22:08:19.000 E LSPosedFramework: Crash unexpectedly: pkg=com.huawei.hidisk
java.lang.NoClassDefFoundError: Failed resolution of: Lhuawei/android/widget/ListView;
    at com.huawei.android.hicloud.ui.activity.MainActivity.jb(Unknown Source:17)
    at com.huawei.android.hicloud.ui.activity.MainActivity.x3(Unknown Source:0)
    at com.huawei.android.hicloud.ui.activity.MainActivity$t.onClick(Unknown Source:36)

22:08:19.001 E AndroidRuntime: FATAL EXCEPTION: main
Process: com.huawei.hidisk, PID: 17665
Caused by: java.lang.ClassNotFoundException: huawei.android.widget.ListView
```

反编译对应代码：

- `MainActivity.java:253`：

```java
public huawei.android.widget.ListView f13811u3;
```

- `MainActivity.java:8204-8206`：

```java
android.view.View inflate = LayoutInflater.from(this)
        .inflate(R$layout.main_menu_window, null);
this.f13811u3 = qk.f.d(inflate, R$id.main_menu_items);
```

- `res/layout/main_menu_window.xml` 第 25 行实际是普通 `<ListView>`（AOSP
  `android.widget.ListView`），布局本身可以 inflate；失败点是
  `qk.f.d(...)` 返回值赋给字段时的 `checkcast huawei/android/widget/ListView`。

因此用户看到“点四个点没反应”，实际是点击已执行到 `jb()`，构造菜单时
主线程崩溃，进程被杀并重启。

## 二、管理空间：`StorageManagerCardViewLayout` inflate 失败

点击“管理空间”（`ll_manager_space`，bounds `[910,649][1146,753]`，中心
`(1028,701)`）后：

```text
22:08:43.146  start HisyncSpaceDetailActivity
22:08:43.201  HisyncSpaceDetailActivity.onCreate
22:08:43.236  E TransactionExecutor: Failed to execute the transaction
22:08:43.237  E ExceptionDetector: Unable to start activity
  ComponentInfo{com.huawei.hidisk/com.huawei.android.hicloud.ui.activity.HisyncSpaceDetailActivity}:
  android.view.InflateException: Binary XML file line #159 in
  com.huawei.hidisk:layout/storage_manage:
  Error inflating class com.huawei.android.hicloud.ui.uiextend.StorageManagerCardViewLayout
```

`uiautomator dump` 返回 `ERROR: null root node returned by UiTestAutomationBridge`；
Activity 处于 `RESUMED`，但没有应用窗口，截图纯黑。

反编译核验：

- `res/layout/storage_manage.xml` 第 159 行：

```xml
<com.huawei.android.hicloud.ui.uiextend.StorageManagerCardViewLayout
    android:id="@0x7f0a0635" ... />
```

- `StorageManagerCardViewLayout.java:28`：

```java
protected huawei.android.widget.HwTextView numView;
```

- `StorageManagerCardViewLayout.java:73-95`：

```java
private void initView(Context context) {
    ...
    View.inflate(context, R$layout.storage_manage_cardview_item, this);
    ...
    this.numView = qk.f.d(this, R$id.size_view);
}
```

- `res/layout/storage_manage_cardview_top_item.xml` 第 99 行：

```xml
<huawei.android.widget.HwTextView
    android:id="@0x7f0a1211"
    ... />
```

Honor ROM 没有 `huawei.android.widget.HwTextView`，`LayoutInflater` 创建该
子 View 时抛 `ClassNotFoundException`，被包装为 `InflateException`，
`setContentView(R.layout.storage_manage)` 失败，`HisyncSpaceDetailActivity`
无窗口，表现为黑屏。

## 三、与之前主页黑屏的关系

之前主页黑屏的根因：

```text
java.lang.NoClassDefFoundError:
Failed resolution of: Lcom/huawei/android/fsm/HwFoldScreenManagerEx;
```

当时 hook 只处理 `d.j1()` / `d.g1()` 两个折叠屏方法，主页得以显示；但这次
两个入口使用另外两套华为 framework 类：

- `huawei.android.widget.ListView`
- `huawei.android.widget.HwTextView`

`j1/g1` hook 对它们无效。共同根因仍是 Honor ROM 不提供华为 EMUI
framework widget 类，而 hidisk 的布局/字段直接依赖这些类。

## 四、证据文件

- 崩溃现场截图：`analysis/space2.png`
- 管理空间异常日志：`analysis/space-detail-error.log`
- 反编译源码：
  - `analysis/jadx-installed/sources/com/huawei/android/hicloud/ui/activity/MainActivity.java`
  - `analysis/jadx-installed/sources/com/huawei/android/hicloud/ui/uiextend/StorageManagerCardViewLayout.java`
- APK：`analysis/apk/installed-base.apk`

## 五、说明

本轮只做原因检查，未修改模块、应用或系统文件；上述“修复方向”未实施。
