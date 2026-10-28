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

    private static void execute(BlockAccessibilityService svc, String source) {
        AccessibilityNodeInfo root = svc.root();
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
        int attempts = Math.max(1, flow.attempts);
        Logx.add(svc, "[" + source + "] 开始：" + flow.name + " / " + pkg + " / " + flow.steps.size() + " 步"
                + (attempts > 1 ? "（最多 " + attempts + " 次）" : ""));
        String lastFail = "";
        for (int attempt = 1; attempt <= attempts; attempt++) {
            String fail = runSteps(svc, flow);
            if (fail == null) {
                Logx.add(svc, "[" + source + "] 完成：" + flow.name);
                emit(STATE_OK, "完成：" + flow.name);
                return;
            }
            lastFail = fail;
            Logx.add(svc, "[" + source + "] 第 " + attempt + " 次尝试卡在：" + fail);
            if (attempt < attempts) {
                // 收一下现场：把可能弹出来的面板/弹窗关掉，再重来一遍
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                sleep(700);
            }
        }
        emit(STATE_FAIL, lastFail);
    }

    /** 跑一遍整套步骤：成功返回 null，失败返回卡住的那一步。 */
    private static String runSteps(BlockAccessibilityService svc, Flow flow) {
        for (int i = 0; i < flow.steps.size(); i++) {
            Flow.Step step = flow.steps.get(i);
            boolean ok = runStep(svc, step, i + 1);
            if (!ok && !step.optional) {
                return "第" + (i + 1) + "步 " + step.describe();
            }
            if (!ok) {
                Logx.add(svc, "第" + (i + 1) + "步（可跳过）未命中：" + step.describe());
            }
        }
        return null;
    }

    /**
     * 同一个文字可能既出现在正文里、又出现在按钮上（比如抖音视频页的 @作者，正文里也有一份）。
     * 命中多个时优先挑「能点的那个」，否则很可能点在正文上、什么都没发生。
     */
    private static List<AccessibilityNodeInfo> preferClickable(List<AccessibilityNodeInfo> hits) {
        if (hits.size() < 2) {
            return hits;
        }
        List<AccessibilityNodeInfo> out = new ArrayList<AccessibilityNodeInfo>();
        for (int i = 0; i < hits.size(); i++) {
            AccessibilityNodeInfo n = hits.get(i);
            AccessibilityNodeInfo target = n;
            try {
                if (!n.isClickable()) {
                    AccessibilityNodeInfo p = n;
                    for (int d = 0; d < 3 && p != null; d++) {
                        p = p.getParent();
                        if (p != null && p.isClickable()) {
                            android.graphics.Rect a = UiUtil.rect(n);
                            android.graphics.Rect b = UiUtil.rect(p);
                            long an = (long) a.width() * a.height();
                            long bn = (long) b.width() * b.height();
                            // 只接受「紧包着文字」的可点祖先，避免点到整个屏幕那么大的容器
                            if (bn > 0 && bn <= 8 * Math.max(1L, an)) {
                                target = p;
                            }
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            out.add(target);
        }
        return out;
    }

    /** 按 step 的条件找节点：文字优先走系统搜索（快），其他走全量遍历。 */
    private static List<AccessibilityNodeInfo> search(BlockAccessibilityService svc, Flow.Step step) {
        AccessibilityNodeInfo root = svc.root();
        if (root == null) {
            return new ArrayList<AccessibilityNodeInfo>();
        }
        if ("text".equals(step.by) && step.findText != null && step.findText.length() > 0) {
            List<AccessibilityNodeInfo> found;
            try {
                found = root.findAccessibilityNodeInfosByText(step.findText);
            } catch (Throwable t) {
                found = null;
            }
            return found == null ? new ArrayList<AccessibilityNodeInfo>()
                    : UiUtil.findAll(found, step.by, step.compiled(), true);
        }
        return UiUtil.findAll(UiUtil.flatten(root, 500), step.by, step.compiled(), true);
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
            // 坐标点击要有「验货」：点完等 expect（by/pattern）出现，没出现就再点一次。
            // 纯坐标最怕的就是页面还没加载完就点了，这样至少不会一次失手就整条流程死掉。
            int tries = Math.max(1, step.tries);
            boolean hasExpect = step.pattern != null && step.pattern.length() > 0;
            for (int t = 1; t <= tries; t++) {
                boolean ok = svc.tapPercent(step.x, step.y);
                Logx.add(svc, "第" + idx + "步 点坐标 " + step.x + "," + step.y
                        + (tries > 1 ? "（第" + t + "/" + tries + "次）" : "") + (ok ? " OK" : " 失败"));
                if (!ok) {
                    return false;
                }
                if (!hasExpect) {
                    sleep(step.gapMs);
                    return true;
                }
                long deadline = System.currentTimeMillis() + Math.max(400L, step.timeoutMs);
                while (System.currentTimeMillis() < deadline) {
                    if (!search(svc, step).isEmpty()) {
                        Logx.add(svc, "第" + idx + "步 坐标点击生效");
                        sleep(step.gapMs);
                        return true;
                    }
                    sleep(120);
                }
                Logx.add(svc, "第" + idx + "步 点了没反应，等不到 " + step.describe());
                sleep(350);
            }
            return false;
        }
        if ("dump".equals(a)) {
            dumpTree(svc);
            return true;
        }
        // 等某个控件出现（不发点击）：专门用来等页面加载完，替代拍脑袋的固定 wait
        if ("waitFor".equals(a)) {
            long deadline = System.currentTimeMillis() + Math.max(300L, step.timeoutMs);
            while (System.currentTimeMillis() < deadline) {
                if (!search(svc, step).isEmpty()) {
                    Logx.add(svc, "第" + idx + "步 已就绪 " + step.describe());
                    return true;
                }
                sleep(120);
            }
            return false;
        }
        // 不再「点完死等 gapMs」：每 60ms 扫一次，一出现就点。
        // 但要求连续两次位置一致才动手，避开菜单弹出动画还没停稳的时候。
        long deadline = System.currentTimeMillis() + Math.max(300L, step.timeoutMs);
        long started = System.currentTimeMillis();
        boolean revealed = false;
        android.graphics.Rect lastRect = null;
        int stable = 0;
        while (System.currentTimeMillis() < deadline) {
            List<AccessibilityNodeInfo> hits = preferClickable(search(svc, step));
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
