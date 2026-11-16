# HiDisk Hook（LSPosed 模块）

本目录是 LSPosed 模块源码。功能、安装、作用域、风险与免责声明见仓库根目录 [README](../README.md)。

## 关键实现

- `d.h2(Context, Uri)` / `d.i2(Context, String)`：仅对 HMS Core 放行 provider 信任检查；
- `d.j1()` / `d.g1()`：非折叠屏固定返回 `false`；
- `huawei.android.widget.ListView` / `HwTextView`：通过 `assets/shims.dex` 注入兼容类；
- `LayoutInflater.inflate`：替换 `main_menu_items` 的 Honor ListView；
- `com.hihonor.photos`：放宽相册级 dirty 的上传候选 SQL；
- `com.android.providers.calendar`：补齐真实日历 + 修复 `realPkgName`；
- `com.huawei.hidisk`：联系人桥接、日历调用方放行、控制台。

## 构建

见根目录 README 的“从源码构建”。`build.sh` 需要 `ANDROID_HOME`、`ANDROID_BUILD_TOOLS`、`JAVA_HOME`、`XPOSED_API_STUB`、`KEYSTORE` 等环境变量。

## 注意

仅供学习研究。使用可能导致账号/数据/系统风险，后果自负。
