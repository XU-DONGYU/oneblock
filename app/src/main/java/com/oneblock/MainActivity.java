package com.oneblock;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_IMPORT = 20;

    private TextView statusView;
    private TextView logView;
    private final Handler h = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            h.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Logx.init(this);
        FlowEngine.init(this);

        statusView = findViewById(R.id.status);
        logView = findViewById(R.id.log);

        findViewById(R.id.btnOverlay).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                try {
                    startActivity(i);
                } catch (Throwable t) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
                }
            }
        });

        findViewById(R.id.btnAcc).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    Toast.makeText(MainActivity.this,
                            "在列表里找到「一键拉黑」并打开", Toast.LENGTH_LONG).show();
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "打不开无障碍设置", Toast.LENGTH_SHORT).show();
                }
            }
        });

        findViewById(R.id.btnToggle).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleBall();
            }
        });

        findViewById(R.id.btnCopy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String log = Logx.dump();
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("oneblock-log", log));
                }
                Toast.makeText(MainActivity.this, "日志已复制", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.btnClear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Logx.clear();
                render();
            }
        });

        findViewById(R.id.btnImport).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickConfig();
            }
        });

        findViewById(R.id.btnReload).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Flow.reload();
                int n = Flow.load(MainActivity.this).size();
                Toast.makeText(MainActivity.this,
                        "已重载：" + Flow.source() + "，" + n + " 套流程", Toast.LENGTH_LONG).show();
                render();
            }
        });

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        h.postDelayed(tick, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
    }

    private void toggleBall() {
        if (OverlayService.running) {
            stopService(new Intent(this, OverlayService.class));
            Toast.makeText(this, "已停止悬浮球", Toast.LENGTH_SHORT).show();
        } else {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "先点第 1 步授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent i = new Intent(this, OverlayService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
            Toast.makeText(this, "已启动，回桌面/切到目标 App 就能看到", Toast.LENGTH_SHORT).show();
        }
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                render();
            }
        }, 500);
    }

    private void render() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean acc = BlockAccessibilityService.instance != null;
        boolean ball = OverlayService.running;
        String s = (overlay ? "✅" : "⬜️") + " 悬浮窗权限\n"
                + (acc ? "✅" : "⬜️") + " 无障碍服务\n"
                + (ball ? "✅" : "⬜️") + " 悬浮球运行中\n"
                + "⚙️ 配置：" + Flow.source() + "（" + Flow.load(this).size() + " 套流程）";
        statusView.setText(s);
        String log = Logx.dump();
        logView.setText(log.isEmpty() ? "（暂无日志）" : log);
    }

    /** 选一个 flows.json 导入到 App 内部，导入后立即生效，不用重新编译。 */
    private void pickConfig() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_IMPORT);
        } catch (Throwable t) {
            Toast.makeText(this, "打不开文件选择器：" + t, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        java.io.InputStream in = null;
        try {
            in = getContentResolver().openInputStream(data.getData());
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            String text = new String(bos.toByteArray(), "UTF-8");
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            java.io.File dst = new java.io.File(getFilesDir(), "flows.json");
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            out.write(text.getBytes("UTF-8"));
            out.close();
            Flow.reload();
            int count = Flow.load(this).size();
            if (count == 0) {
                Toast.makeText(this, "导入的配置解析不出流程，检查一下 JSON", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "已导入 " + count + " 套流程，立即生效", Toast.LENGTH_LONG).show();
            }
            render();
        } catch (Throwable t) {
            Toast.makeText(this, "导入失败：" + t, Toast.LENGTH_LONG).show();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
