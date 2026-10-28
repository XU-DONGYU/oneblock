package com.oneblock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 悬浮球：前台服务 + WindowManager 覆盖层。
 * 单击 = 跑流程；长按 = 抓当前界面控件（调选择器用）；拖动 = 挪位置。
 */
public class OverlayService extends Service implements FlowEngine.Listener {

    public static volatile boolean running;

    private static final String CHANNEL_ID = "oneblock";
    private static final int NOTIF_ID = 1001;

    private final Handler main = new Handler(Looper.getMainLooper());

    private WindowManager wm;
    private TextView ball;
    private WindowManager.LayoutParams lp;

    /** 左边预告「要屏蔽谁」的小箭头 */
    private ArrowView marker;
    private WindowManager.LayoutParams mlp;
    private int lastMarkerX = Integer.MIN_VALUE;
    private int lastMarkerY = Integer.MIN_VALUE;
    private String lastScanSig = "";
    private int missCount = 0;
    private String lastMissReason = "";

    private void missLog(String reason) {
        if (!reason.equals(lastMissReason)) {
            lastMissReason = reason;
            android.util.Log.i("oneblock", "marker miss: " + reason);
        }
    }
    private volatile boolean tickerRun;
    private Thread ticker;

    private final Runnable hideMarkerRunnable = new Runnable() {
        @Override
        public void run() {
            // 偶尔一次扫不到（界面正在动、root() 抢不到）不算，连续 3 次才真隐藏，免得箭头一闪一闪
            if (++missCount >= 3) {
                hideMarker();
            }
        }
    };

    private int startX;
    private int startY;
    private float downRawX;
    private float downRawY;
    private long downAt;
    private boolean dragged;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        Logx.init(this);
        FlowEngine.init(this);
        FlowEngine.setListener(this);
        createChannel();
        startForeground(NOTIF_ID, buildNotification());
        showBall();
        showMarker();
        startTicker();
        Logx.add(this, "悬浮球已启动");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        FlowEngine.setListener(null);
        tickerRun = false;
        if (ticker != null) {
            ticker.interrupt();
            ticker = null;
        }
        if (wm != null && ball != null) {
            try {
                wm.removeView(ball);
            } catch (Throwable ignored) {
            }
        }
        if (wm != null && marker != null) {
            try {
                wm.removeView(marker);
            } catch (Throwable ignored) {
            }
        }
        ball = null;
        marker = null;
        running = false;
        Logx.add(this, "悬浮球已停止");
        super.onDestroy();
    }

    private void showBall() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) {
            Logx.add(this, "拿不到 WindowManager");
            return;
        }
        ball = new TextView(this);
        ball.setText("拉");
        ball.setTextColor(Color.WHITE);
        ball.setTextSize(16f);
        ball.setGravity(Gravity.CENTER);
        ball.setBackground(dot(0xff22303c));

        int size = dp(62);
        lp = new WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        // 默认放右下：左边是箭头的地盘，右上角是各 App 的「更多」按钮，放中间偏右最不碍事
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        lp.x = Math.max(0, screenW - size - dp(10));
        lp.y = (int) (screenH * 0.62f);

        ball.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                return onBallTouch(v, e);
            }
        });
        try {
            wm.addView(ball, lp);
        } catch (Throwable t) {
            Logx.add(this, "加悬浮球失败：" + t);
        }
    }

    private boolean onBallTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = lp.x;
                startY = lp.y;
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                downAt = SystemClock.uptimeMillis();
                dragged = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                int dx = (int) (e.getRawX() - downRawX);
                int dy = (int) (e.getRawY() - downRawY);
                if (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8)) {
                    dragged = true;
                }
                if (dragged) {
                    // 夹在屏幕内，别让球被拖出可视区域（拖出去就再也点不到了）
                    int size = dp(62);
                    int screenW = getResources().getDisplayMetrics().widthPixels;
                    int screenH = getResources().getDisplayMetrics().heightPixels;
                    lp.x = Math.max(0, Math.min(startX + dx, screenW - size));
                    lp.y = Math.max(0, Math.min(startY + dy, screenH - size));
                    try {
                        wm.updateViewLayout(v, lp);
                    } catch (Throwable ignored) {
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                long held = SystemClock.uptimeMillis() - downAt;
                if (!dragged) {
                    if (held >= 600) {
                        BlockAccessibilityService acc = BlockAccessibilityService.instance;
                        if (acc == null) {
                            toast("先去设置里打开无障碍服务");
                        } else {
                            FlowEngine.dumpTree(acc);
                        }
                    } else {
                        onBallClick();
                    }
                }
                return true;
            }
            default:
                return false;
        }
    }

    private void onBallClick() {
        BlockAccessibilityService acc = BlockAccessibilityService.instance;
        if (acc == null) {
            toast("先去设置里打开无障碍服务");
            return;
        }
        FlowEngine.runAsync(acc, "悬浮球");
    }

    @Override
    public void onState(final int state, final String msg) {
        main.post(new Runnable() {
            @Override
            public void run() {
                int color = 0xff22303c;
                if (state == FlowEngine.STATE_RUNNING) {
                    color = 0xff1565c0;
                } else if (state == FlowEngine.STATE_OK) {
                    color = 0xff2e7d32;
                } else if (state == FlowEngine.STATE_FAIL) {
                    color = 0xffc62828;
                }
                if (ball != null) {
                    ball.setBackground(dot(color));
                }
                if (state != FlowEngine.STATE_IDLE) {
                    toast(msg);
                }
                if (state == FlowEngine.STATE_OK || state == FlowEngine.STATE_FAIL) {
                    main.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (ball != null) {
                                ball.setBackground(dot(0xff22303c));
                            }
                        }
                    }, 1200);
                }
            }
        });
    }

    // ---------- 左边的小箭头：从下往上顶住目标帖子的作者头像 ----------

    private void showMarker() {
        if (wm == null) {
            return;
        }
        marker = new ArrowView(this, 0xE6E53935);
        marker.setAlpha(0f);
        mlp = new WindowManager.LayoutParams(
                dp(24), dp(44),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        mlp.gravity = Gravity.TOP | Gravity.START;
        mlp.x = dp(2);
        mlp.y = dp(300);
        try {
            wm.addView(marker, mlp);
        } catch (Throwable t) {
            Logx.add(this, "加指示器失败：" + t);
            marker = null;
        }
    }

    /** cx = 箭头尖端所在的横坐标，tipY = 箭头尖端（会顶在头像下沿）。 */
    private void positionMarker(int cx, int tipY) {
        if (marker == null || wm == null || mlp == null) {
            return;
        }
        int w = dp(24);
        int h = dp(44);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int minTop = statusBarHeight() + dp(2);
        int maxTop = Math.max(minTop, screenH - h - dp(4));
        int x = Math.max(0, Math.min(cx - w / 2, Math.max(0, screenW - w)));
        int y = Math.max(minTop, Math.min(tipY, maxTop));
        missCount = 0;
        marker.setAlpha(1f);
        if (x == lastMarkerX && y == lastMarkerY) {
            return;
        }
        lastMarkerX = x;
        lastMarkerY = y;
        mlp.x = x;
        mlp.y = y;
        android.util.Log.i("oneblock", "marker -> x=" + x + " y=" + y);
        try {
            wm.updateViewLayout(marker, mlp);
        } catch (Throwable ignored) {
        }
    }

    private void hideMarker() {
        lastMarkerX = Integer.MIN_VALUE;
        lastMarkerY = Integer.MIN_VALUE;
        if (marker != null) {
            marker.setAlpha(0f);
        }
    }

    private void startTicker() {
        tickerRun = true;
        ticker = new Thread(new Runnable() {
            @Override
            public void run() {
                while (tickerRun) {
                    try {
                        scanTarget();
                    } catch (Throwable ignored) {
                    }
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "oneblock-marker");
        ticker.setDaemon(true);
        ticker.start();
    }

    /** 后台线程算「点一下会打哪条帖子」，找到那条帖子作者头像，把箭头尖端摆到它下沿。 */
    private void scanTarget() {
        BlockAccessibilityService acc = BlockAccessibilityService.instance;
        if (acc == null || FlowEngine.isRunning()) {
            missLog("acc=" + (acc != null) + " running=" + FlowEngine.isRunning());
            main.post(hideMarkerRunnable);
            return;
        }
        // 先用最近一次事件里的包名判断有没有活要干；没有的话连控件树都不碰
        String pkg = acc.lastPackage;
        // 同一个包可能配了好几套流程（X 就有播放器/主页/时间线三套），
        // 箭头只认带 pick:center 的那套——别用 forPackage 拿第一个，那样会拿到播放器流程然后永远不显示
        List<Flow> flows = Flow.load(this);
        Flow.Step step = null;
        for (int f = 0; f < flows.size() && step == null; f++) {
            Flow flow = flows.get(f);
            if (!flow.packages.contains(pkg)) {
                continue;
            }
            for (int i = 0; i < flow.steps.size(); i++) {
                Flow.Step s = flow.steps.get(i);
                if ("click".equals(s.action) && "center".equals(s.pick)) {
                    step = s;
                    break;
                }
            }
        }
        if (step == null) {
            missLog("no step, pkg=" + pkg);
            main.post(hideMarkerRunnable);
            return;
        }
        AccessibilityNodeInfo root = acc.root();
        if (root == null) {
            missLog("root null");
            main.post(hideMarkerRunnable);
            return;
        }
        List<AccessibilityNodeInfo> nodes = UiUtil.flatten(root, 900);
        List<AccessibilityNodeInfo> hits = UiUtil.findAll(nodes, step.by, step.compiled(), true);
        AccessibilityNodeInfo hit = UiUtil.pickCenter(hits, acc.screenHeight());
        if (hit == null) {
            missLog("no target, hits=" + hits.size());
            main.post(hideMarkerRunnable);
            return;
        }
        android.graphics.Rect btn = UiUtil.rect(hit);
        AccessibilityNodeInfo avatar = findAvatar(nodes, btn);
        final int cx;
        final int tipY;
        if (avatar != null) {
            android.graphics.Rect a = UiUtil.rect(avatar);
            cx = a.centerX();
            tipY = a.bottom + dp(3);
        } else {
            cx = dp(26);
            tipY = btn.bottom + dp(3);
        }
        String sig = btn.top + "/" + (avatar == null ? "-" : UiUtil.rect(avatar).toShortString()) + "/" + tipY + "/" + cx;
        if (!sig.equals(lastScanSig)) {
            lastScanSig = sig;
            android.util.Log.i("oneblock", "target btn.top=" + btn.top + " avatar="
                    + (avatar == null ? "null" : UiUtil.rect(avatar).toShortString())
                    + " -> tip=" + tipY + " cx=" + cx);
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                positionMarker(cx, tipY);
            }
        });
    }

    /**
     * 在「发帖选项」那一行里找作者头像：
     * 同一水平带、在按钮左侧、可点击、宽度像头像的那个，取最靠左的（头像永远贴左边栏）。
     */
    private AccessibilityNodeInfo findAvatar(List<AccessibilityNodeInfo> nodes, android.graphics.Rect btn) {
        AccessibilityNodeInfo best = null;
        int bestLeft = Integer.MAX_VALUE;
        for (int i = 0; i < nodes.size(); i++) {
            AccessibilityNodeInfo n = nodes.get(i);
            if (!n.isVisibleToUser() || !n.isClickable()) {
                continue;
            }
            String cls = UiUtil.s(n.getClassName());
            if (!cls.contains("ImageView")) {
                continue;
            }
            android.graphics.Rect r = UiUtil.rect(n);
            if (Math.abs(r.top - btn.top) > dp(18)) {
                continue;
            }
            if (r.right >= btn.left) {
                continue;
            }
            if (r.width() < dp(24) || r.width() > dp(96)) {
                continue;
            }
            if (r.left < bestLeft) {
                bestLeft = r.left;
                best = n;
            }
        }
        return best;
    }

    private GradientDrawable dot(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(dp(2), 0x66ffffff);
        return d;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        int v = id > 0 ? getResources().getDimensionPixelSize(id) : 0;
        return v > 0 ? v : dp(24);
    }

    private void toast(String msg) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "悬浮球", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("一键拉黑 · 悬浮球运行中")
                .setContentText("单击执行，长按抓界面控件")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentIntent(pi)
                .setOngoing(true);
        return b.build();
    }
}
