package com.oneblock;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 流程模型 + assets/flows.json 解析。
 * 加一个新 App = 在 json 里加一段，不用改 Java。
 */
public final class Flow {

    public String name = "未命名";
    public final List<String> packages = new ArrayList<String>();
    public final List<Step> steps = new ArrayList<Step>();

    /** 可选：只有当前界面命中这个特征时才用这套流程（同一 App 多个入口靠它区分）。 */
    public String detectBy = "";
    public String detectPattern = "";
    public String detectFind = "";

    /** 整套流程最多跑几次（失败会先收拾现场再重来），默认 1。 */
    public int attempts = 1;

    /** 重试前要检查的字面量：这些控件还在（面板没关）才按返回，避免盲按把人踢出 App。 */
    public final java.util.List<String> resetFind = new java.util.ArrayList<String>();
    private Pattern detectCompiled;

    public Pattern detect() {
        if (detectPattern == null || detectPattern.isEmpty()) {
            return null;
        }
        if (detectCompiled == null) {
            detectCompiled = Pattern.compile(detectPattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        }
        return detectCompiled;
    }

    public static final class Step {
        public String action = "click"; // click | wait | back | gesture | dump
        public String by = "text";      // text | desc | id
        public String pattern = "";
        public String pick = "first";   // first = 取第一个 | center = 取屏幕中间那条
        public String findText = "";    // 可选：先让系统按字面量搜一次（抖音这种超大控件树必须用）
        public long ms = 600;           // wait 用
        public long timeoutMs = 2000;   // click 等待上限
        public long gapMs = 500;        // 点完等界面反应
        public float x = 0.5f;          // gesture 用，0~1 相对坐标
        public float y = 0.5f;
        public boolean optional = false;
        public boolean reveal = false;  // 找不到就先点一下屏幕正中（唤出会隐藏的控制层）再找
        public int tries = 1;           // gesture：点几次。配合 by/pattern 当「点完应该出现什么」的验收条件

        private Pattern compiled;

        public Pattern compiled() {
            if (compiled == null) {
                compiled = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            }
            return compiled;
        }

        public String describe() {
            if ("wait".equals(action)) {
                return "等待 " + ms + "ms";
            }
            if ("back".equals(action)) {
                return "返回键";
            }
            if ("dump".equals(action)) {
                return "抓取界面";
            }
            if ("gesture".equals(action)) {
                return "点坐标 " + x + "," + y;
            }
            return "[" + by + "] " + pattern;
        }
    }

    private static volatile List<Flow> cache;
    private static volatile String source = "内置";

    /** 配置来自哪里，界面上显示用。 */
    public static String source() {
        return source;
    }

    /** 改了配置（导入/替换文件）后调一下，下次就用新的。 */
    public static void reload() {
        cache = null;
    }

    /** 指示器每 500ms 查一次，这里缓存住，别每次读文件解析 JSON。 */
    public static List<Flow> load(Context ctx) {
        List<Flow> c = cache;
        if (c != null) {
            return c;
        }
        synchronized (Flow.class) {
            if (cache == null) {
                cache = parse(ctx);
            }
            return cache;
        }
    }

    /**
     * 配置优先级：外部目录 > 已导入的内部副本 > 内置 assets。
     * 外部目录 = Android/data/com.oneblock/files/flows.json，可以用 adb push 或文件管理器改，
     * 改完在 App 里点「重新加载」就生效，不用重新编译。
     */
    private static String readConfig(Context ctx) {
        try {
            java.io.File ext = new java.io.File(ctx.getExternalFilesDir(null), "flows.json");
            String s = readFile(ext);
            if (s != null) {
                source = "外部文件";
                return s;
            }
        } catch (Throwable ignored) {
        }
        try {
            java.io.File inner = new java.io.File(ctx.getFilesDir(), "flows.json");
            String s = readFile(inner);
            if (s != null) {
                source = "已导入";
                return s;
            }
        } catch (Throwable ignored) {
        }
        source = "内置";
        return readAsset(ctx, "flows.json");
    }

    private static String readFile(java.io.File f) {
        java.io.FileInputStream in = null;
        try {
            if (f == null || !f.isFile() || f.length() <= 0) {
                return null;
            }
            in = new java.io.FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            String text = new String(bos.toByteArray(), "UTF-8");
            return stripBom(text);
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String stripBom(String text) {
        if (text != null && text.startsWith("\uFEFF")) {
            return text.substring(1);
        }
        return text;
    }

    private static List<Flow> parse(Context ctx) {
        List<Flow> out = new ArrayList<Flow>();
        String json = readConfig(ctx);
        if (json == null) {
            Logx.add(ctx, "读不到 assets/flows.json");
            return out;
        }
        try {
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.optJSONArray("flows");
            if (arr == null) {
                return out;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Flow f = new Flow();
                f.name = o.optString("name", "未命名");
                f.attempts = o.optInt("attempts", 1);
                JSONArray rf = o.optJSONArray("resetFind");
                if (rf != null) {
                    for (int k = 0; k < rf.length(); k++) {
                        String s = rf.optString(k, "");
                        if (s.length() > 0) {
                            f.resetFind.add(s);
                        }
                    }
                }
                JSONObject dt = o.optJSONObject("detect");
                if (dt != null) {
                    f.detectBy = dt.optString("by", "text");
                    f.detectPattern = dt.optString("pattern", "");
                    f.detectFind = dt.optString("find", "");
                }
                JSONArray pk = o.optJSONArray("packages");
                if (pk != null) {
                    for (int j = 0; j < pk.length(); j++) {
                        f.packages.add(pk.getString(j));
                    }
                }
                JSONArray st = o.optJSONArray("steps");
                if (st != null) {
                    for (int j = 0; j < st.length(); j++) {
                        JSONObject so = st.getJSONObject(j);
                        Step s = new Step();
                        s.action = so.optString("action", "click");
            s.tries = so.optInt("tries", 1);
                        s.by = so.optString("by", "text");
                        s.pattern = so.optString("pattern", "");
                        s.pick = so.optString("pick", "first");
                        s.findText = so.optString("find", "");
                        s.ms = so.optLong("ms", 600);
                        s.timeoutMs = so.optLong("timeoutMs", 2000);
                        s.gapMs = so.optLong("gapMs", 500);
                        s.optional = so.optBoolean("optional", false);
                        s.reveal = so.optBoolean("reveal", false);
                        s.x = (float) so.optDouble("x", 0.5);
                        s.y = (float) so.optDouble("y", 0.5);
                        f.steps.add(s);
                    }
                }
                out.add(f);
            }
        } catch (Throwable t) {
            String msg = String.valueOf(t.getMessage());
            if (msg.length() > 160) {
                msg = msg.substring(0, 160) + "…";
            }
            Logx.add(ctx, "flows.json 解析失败：" + msg);
        }
        return out;
    }

    public static Flow forPackage(List<Flow> flows, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return null;
        }
        for (int i = 0; i < flows.size(); i++) {
            if (flows.get(i).packages.contains(pkg)) {
                return flows.get(i);
            }
        }
        return null;
    }

    private static String readAsset(Context ctx, String name) {
        InputStream in = null;
        try {
            in = ctx.getAssets().open(name);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            String text = new String(bos.toByteArray(), "UTF-8");
            // 用记事本/PowerShell 改过 json 可能会带 BOM，org.json 认不出来
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            return text;
        } catch (Throwable t) {
            return null;
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
