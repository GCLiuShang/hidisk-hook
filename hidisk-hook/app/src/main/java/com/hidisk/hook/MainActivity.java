package com.hidisk.hook;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Small control panel for the module.
 *
 * The hooks run inside the target processes; this activity only inspects the
 * device state (root / AppOps / LSPosed injection log / Honor package state)
 * and can restart the scoped apps. It deliberately does not try to enable the
 * LSPosed scopes itself: LSPosed owns that database and caches it in lspd.
 */
public class MainActivity extends Activity {

    private static final String HIDISK = "com.huawei.hidisk";
    private static final String PHOTOS = "com.hihonor.photos";
    private static final String CALENDAR = "com.android.providers.calendar";

    private static final String[] SCOPES = {HIDISK, PHOTOS, CALENDAR};
    private static final String[] HONOR_PACKAGES = {
            "com.hihonor.contacts.sync",
            "com.hihonor.assetsync",
            "com.hihonor.assetsyncservice",
            "com.hihonor.collectcenter",
            "com.hihonor.hncloud"
    };

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("HiDisk Hook 控制台");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("检查作用域注入记录、AppOps 与 Honor 包状态；重启作用域应用。\n"
                + "Root 已授权时才能执行设备命令。");
        hint.setTextSize(13);
        hint.setPadding(0, dp(6), 0, dp(8));
        root.addView(hint);

        ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, scrollParams);

        status = new TextView(this);
        status.setTextSize(12);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextIsSelectable(true);
        status.setPadding(dp(8), dp(8), dp(8), dp(8));
        scroll.addView(status);

        root.addView(button("检查状态 / 作用域注入", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshStatus();
            }
        }));

        root.addView(button("重启作用域应用", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restartScopes();
            }
        }));

        root.addView(button("应用推荐 Honor 包状态", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyRecommendedPackages();
            }
        }));

        root.addView(button("恢复全部 Honor 包（回滚）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restoreHonorPackages();
            }
        }));

        setContentView(root);
        refreshStatus();
    }

    private Button button(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private void refreshStatus() {
        status.setText("检查中…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String report = buildReport();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        status.setText(report);
                    }
                });
            }
        }).start();
    }

    private void restartScopes() {
        status.setText("正在重启作用域应用…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                StringBuilder out = new StringBuilder();
                for (String pkg : SCOPES) {
                    out.append(runRoot("am force-stop " + pkg)).append('\n');
                }
                // Make the provider eligible to start again on the next request.
                out.append(runRoot("cmd package unstop " + CALENDAR)).append('\n');
                final String msg = "已强制停止作用域应用；下次打开目标应用时会重新注入。\n\n"
                        + buildReport() + "\n\n命令输出:\n" + trim(out.toString());
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        status.setText(msg);
                    }
                });
            }
        }).start();
    }

    private void applyRecommendedPackages() {
        status.setText("正在应用推荐包状态…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                StringBuilder out = new StringBuilder();
                out.append(runRoot("pm disable-user --user 0 com.hihonor.contacts.sync")).append('\n');
                out.append(runRoot("am force-stop com.hihonor.contacts.sync")).append('\n');
                for (String pkg : new String[]{
                        "com.hihonor.assetsync",
                        "com.hihonor.assetsyncservice",
                        "com.hihonor.collectcenter",
                        "com.hihonor.hncloud"}) {
                    out.append(runRoot("pm enable " + pkg)).append('\n');
                }
                out.append(runRoot("appops set " + HIDISK
                        + " MANAGE_EXTERNAL_STORAGE allow")).append('\n');
                final String msg = "已应用推荐包状态。\n\n" + buildReport()
                        + "\n\n命令输出:\n" + trim(out.toString());
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        status.setText(msg);
                    }
                });
            }
        }).start();
    }

    private void restoreHonorPackages() {
        status.setText("正在恢复 Honor 包…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                StringBuilder out = new StringBuilder();
                for (String pkg : HONOR_PACKAGES) {
                    out.append(runRoot("pm enable " + pkg)).append('\n');
                }
                final String msg = "已恢复全部 Honor 包。\n"
                        + "注意：联系人桥接依赖停用 com.hihonor.contacts.sync；"
                        + "恢复后 Honor 云可能重新接管联系人，可能产生冲突。\n\n"
                        + buildReport() + "\n\n命令输出:\n" + trim(out.toString());
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        status.setText(msg);
                    }
                });
            }
        }).start();
    }

    private String buildReport() {
        StringBuilder b = new StringBuilder();
        String id = runRoot("id");
        boolean root = id.contains("uid=0");
        b.append("模块: ").append(moduleVersion()).append('\n');
        b.append("Root: ").append(root ? "可用" : "不可用").append('\n');
        b.append("AppOps: ").append(oneLine(runRoot(
                "appops get " + HIDISK + " MANAGE_EXTERNAL_STORAGE"))).append('\n');

        b.append("\n作用域注入记录（以 LSPosed 日志为准）:\n");
        for (String pkg : SCOPES) {
            String line = runRoot("grep -h 'loaded in package=" + pkg
                    + "' /data/adb/lspd/log/modules_*.log 2>/dev/null | tail -1").trim();
            b.append("  ").append(pkg).append(": ")
                    .append(line.isEmpty() ? "未发现（重启目标应用后再检查）" : "✓ " + oneLine(line))
                    .append('\n');
        }

        b.append("\nHonor 包状态:\n");
        String disabled = runRoot("pm list packages -d");
        for (String pkg : HONOR_PACKAGES) {
            boolean isDisabled = disabled.contains("package:" + pkg);
            b.append("  ").append(pkg).append(": ")
                    .append(isDisabled ? "disabled" : "enabled").append('\n');
        }
        String hidden = runRoot("pm list packages -e com.hidisk.hook");
        b.append("\n模块安装: ").append(hidden.trim().isEmpty() ? "未检测到" : "已安装").append('\n');
        b.append("提示: 图库上传/下载还依赖 MANAGE_EXTERNAL_STORAGE=allow；"
                + "若 AppOps 为 default，请先执行“应用推荐 Honor 包状态”。");
        return b.toString();
    }

    private String moduleVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName
                    + " (" + getPackageName() + ")";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private String runRoot(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            StringBuilder out = new StringBuilder();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroy();
                return "TIMEOUT: " + command;
            }
            return out.toString();
        } catch (Throwable t) {
            return "ERROR: " + t;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > 180 ? t.substring(0, 180) + "…" : t;
    }

    private static String trim(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() > 1400 ? t.substring(0, 1400) + "…" : t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
