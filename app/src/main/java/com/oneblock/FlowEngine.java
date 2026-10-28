package com.oneblock;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 流程执行器：按当前前台包名挑一套步骤，逐步执行。
 */
public final class FlowEngine {

    public static final int STATE_IDLE = 0;
    public static final int STATE_RUNNING = 1;
    public static final int STATE_OK = 2;
    public static final int STATE_FAIL = 3;

    public interface Listener {
        void onState(int state, String msg);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Listener listener;
    private static volatile boolean running;
    private static volatile long startedAt;
    private static volatile int runSeq;
    private static volatile Context appContext;

    private FlowEngine() {
    }

    public static void init(Context c) {
        appContext = c.getApplicationContext();
    }

    public static void setListener(Listener l) {
        listener = l;
    }

    public static boolean isRunning() {
        return running;
    }

    private static void emit(final int state, final String msg) {
        Listener l = listener;
        if (l != null) {
            l.onState(state, msg);
        }
    }

    private static void toast(final String msg) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                Context ctx = appContext;
                if (ctx == null) {
                    return;
                }
                try {
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    public static void runAsync(final BlockAccessibilityService svc, final String source) {
        if (svc == null) {
            toast("先去设置里打开无障碍服务");
            return;
        }
        if (running) {
            // 抖音这种界面偶尔会把某次 root()/点击卡住，线程不返回，running 就永远为 true。
            // 超过这个时间就当上一次已经死了，强行放行。
            long elapsed = System.currentTimeMillis() - startedAt;
            if (elapsed < 90000L) {
                toast("上一步还没跑完");
                return;
            }
            Logx.add(svc, "上一次执行卡了 " + (elapsed / 1000) + " 秒，强制重置");
            running = false;
        }
        running = true;
        startedAt = System.currentTimeMillis();
        final int myRun = ++runSeq;
        emit(STATE_RUNNING, "执行中…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    execute(svc, source);
                } catch (Throwable t) {
                    Logx.add(svc, "执行异常：" + t);
                    emit(STATE_FAIL, "异常：" + t);
                } finally {
                    // 只清自己这一轮的标志，别把后来那一轮清掉
                    if (runSeq == myRun) {
                        running = false;
                    }
                }
            }
        }, "oneblock-flow").start();
    }

    /** 同一个包可能配了多套流程（比如抖音的视频页和直播间），按 detect 特征挑。 */
    private static Flow pickFlow(AccessibilityNodeInfo root, List<Flow> flows, String pkg) {
        Flow fallback = null;
        for (int i = 0; i < flows.size(); i++) {
            Flow f = flows.get(i);
            if (!f.packages.contains(pkg)) {
                continue;
            }
            if (f.detect() == null) {
                if (fallback == null) {
                    fallback = f;
                }
                continue;
            }
            if (matchesDetect(root, f)) {
                return f;
            }
        }
        return fallback;
    }

    private static boolean matchesDetect(AccessibilityNodeInfo root, Flow f) {
        Pattern p = f.detect();
        if (root == null || p == null) {
            return false;
        }
        List<AccessibilityNodeInfo> cands;
        if (f.detectFind != null && f.detectFind.length() > 0) {
            try {
                cands = root.findAccessibilityNodeInfosByText(f.detectFind);
            } catch (Throwable t) {
                cands = null;
            }
            if (cands == null) {
                return false;
            }
        } else {
            cands = UiUtil.flatten(root, 400);
        }
        return UiUtil.find(cands, f.detectBy, p, true) != null;
    }

    private static void execute(BlockAccessibilityService svc, String source) {        AccessibilityNodeInfo root = svc.root();
        String pkg = root != null ? UiUtil.s(root.getPackageName()) : "";
        if (pkg.isEmpty()) {
            pkg = svc.lastPackage;
        }
        List<Flow> flows = Flow.load(svc);
        Flow flow = pickFlow(root, flows, pkg);
        if (flow == null) {
            String shown = pkg.isEmpty() ? "当前界面" : pkg;
            Logx.add(svc, "[" + source + "] " + shown + " 没有配置步骤");
            emit(STATE_FAIL, "没有适配 " + shown);
            return;
        }
        Logx.add(svc, "[" + source + "] 开始：" + flow.name + " / " + pkg + " / " + flow.steps.size() + " 步");
        for (int i = 0; i < flow.steps.size(); i++) {
            Flow.Step step = flow.steps.get(i);
            boolean ok = runStep(svc, step, i + 1);
            if (!ok && !step.optional) {
                Logx.add(svc, "[" + source + "] 第" + (i + 1) + "步失败：" + step.describe());
                emit(STATE_FAIL, "第" + (i + 1) + "步失败：" + step.describe());
                return;
            }
            if (!ok) {
                Logx.add(svc, "第" + (i + 1) + "步（可跳过）未命中：" + step.describe());
            }
        }
        Logx.add(svc, "[" + source + "] 完成：" + flow.name);
        emit(STATE_OK, "完成：" + flow.name);
    }

    private static boolean runStep(BlockAccessibilityService svc, Flow.Step step, int idx) {
        String a = step.action;
        if ("wait".equals(a)) {
            sleep(step.ms);
            return true;
        }
        if ("back".equals(a)) {
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            sleep(450);
            return true;
        }
        if ("gesture".equals(a)) {
            boolean ok = svc.tapPercent(step.x, step.y);
            Logx.add(svc, "第" + idx + "步 点坐标 " + step.x + "," + step.y + (ok ? " OK" : " 失败"));
            sleep(step.gapMs);
            return ok;
        }
        if ("dump".equals(a)) {
            dumpTree(svc);
            return true;
        }
        // 不再「点完死等 gapMs」：每 60ms 扫一次，一出现就点。
        // 但要求连续两次位置一致才动手，避开菜单弹出动画还没停稳的时候。
        long deadline = System.currentTimeMillis() + Math.max(300L, step.timeoutMs);
        long started = System.currentTimeMillis();
        boolean revealed = false;
        android.graphics.Rect lastRect = null;
        int stable = 0;
        while (System.currentTimeMillis() < deadline) {
            AccessibilityNodeInfo root = svc.root();
            List<AccessibilityNodeInfo> hits;
            if ("text".equals(step.by) && step.findText != null && step.findText.length() > 0) {
                // 抖音那种几千个控件的树，全量遍历要好几秒还容易漏。让系统自己按字面量搜一遍，快得多。
                List<AccessibilityNodeInfo> found = null;
                try {
                    found = root == null ? null : root.findAccessibilityNodeInfosByText(step.findText);
                } catch (Throwable ignored) {
                }
                hits = found == null ? new ArrayList<AccessibilityNodeInfo>()
                        : UiUtil.findAll(found, step.by, step.compiled(), true);
            } else {
                hits = UiUtil.findAll(UiUtil.flatten(root, 500), step.by, step.compiled(), true);
            }
            AccessibilityNodeInfo hit = null;
            if (!hits.isEmpty()) {
                hit = "center".equals(step.pick)
                        ? UiUtil.pickCenter(hits, svc.screenHeight())
                        : hits.get(0);
            }
            if (hit == null) {
                lastRect = null;
                stable = 0;
                // 播放器这类界面，控制层会自动隐藏；找不到就先点一下屏幕正中把它唤出来
                if (step.reveal && !revealed && System.currentTimeMillis() - started > 600) {
                    revealed = true;
                    svc.tapPercent(0.5f, 0.5f);
                }
                sleep(60);
                continue;
            }
            android.graphics.Rect r = UiUtil.rect(hit);
            if (lastRect != null && r.equals(lastRect)) {
                stable++;
            } else {
                stable = 0;
                lastRect = r;
            }
            if (stable >= 1) {
                boolean ok = svc.clickNode(hit);
                String extra = hits.size() > 1 ? "（" + hits.size() + " 个候选，取" + ("center".equals(step.pick) ? "中间" : "第一个") + "）" : "";
                Logx.add(svc, "第" + idx + "步 点击 " + step.describe() + extra + (ok ? " OK" : " 点击未生效"));
                if (ok) {
                    sleep(step.gapMs);
                    return true;
                }
                return false;
            }
            sleep(60);
        }
        return false;
    }

    /** 长按悬浮球：把当前界面能看到的控件打出来，方便照着改 flows.json。 */
    public static void dumpTree(final BlockAccessibilityService svc) {
        AccessibilityNodeInfo root = svc.root();
        if (root == null) {
            Logx.add(svc, "抓取失败：读不到当前界面（锁屏或安全窗口？）");
            return;
        }
        List<AccessibilityNodeInfo> nodes = UiUtil.flatten(root, 500);
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < nodes.size(); i++) {
            AccessibilityNodeInfo n = nodes.get(i);
            if (!n.isVisibleToUser()) {
                continue;
            }
            String t = UiUtil.s(n.getText());
            String d = UiUtil.s(n.getContentDescription());
            if (t.isEmpty() && d.isEmpty() && !n.isClickable()) {
                continue;
            }
            sb.append(UiUtil.describe(n)).append('\n');
            shown++;
            if (shown >= 150) {
                break;
            }
        }
        final String all = sb.toString();
        Logx.add(svc, "界面控件 " + shown + " 个（已复制到剪贴板）：");
        String[] lines = all.split("\n");
        int max = Math.min(lines.length, 40);
        for (int i = 0; i < max; i++) {
            Logx.add(svc, "  " + lines[i]);
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    ClipboardManager cm = (ClipboardManager) svc.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("oneblock", all));
                    }
                } catch (Throwable ignored) {
                }
                try {
                    Toast.makeText(svc, "控件树已复制（" + all.length() + " 字）", Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
