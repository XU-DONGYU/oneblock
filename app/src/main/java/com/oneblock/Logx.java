package com.oneblock;

import android.content.Context;
import android.content.Intent;
import android.text.format.DateFormat;
import android.util.Log;

import java.util.ArrayDeque;

/**
 * 极简日志：同时写 logcat、内存环形缓冲、广播给界面。
 */
public final class Logx {

    public static final String ACTION_LOG = "com.oneblock.LOG";
    public static final String EXTRA_LINE = "line";

    private static final int MAX_LINES = 300;
    private static final ArrayDeque<String> LINES = new ArrayDeque<String>();
    private static final Object LOCK = new Object();
    private static Context appContext;

    private Logx() {
    }

    public static void init(Context c) {
        if (c != null) {
            appContext = c.getApplicationContext();
        }
    }

    public static void add(Context c, String msg) {
        if (c != null) {
            init(c);
        }
        String ts = DateFormat.format("HH:mm:ss", System.currentTimeMillis()).toString();
        String line = ts + "  " + msg;
        Log.i("oneblock", line);
        synchronized (LOCK) {
            LINES.addLast(line);
            while (LINES.size() > MAX_LINES) {
                LINES.removeFirst();
            }
        }
        Context ctx = appContext;
        if (ctx != null) {
            try {
                Intent i = new Intent(ACTION_LOG);
                i.setPackage(ctx.getPackageName());
                i.putExtra(EXTRA_LINE, line);
                ctx.sendBroadcast(i);
            } catch (Throwable ignored) {
            }
        }
    }

    public static String dump() {
        StringBuilder sb = new StringBuilder();
        synchronized (LOCK) {
            for (String s : LINES) {
                sb.append(s).append('\n');
            }
        }
        return sb.toString();
    }

    public static void clear() {
        synchronized (LOCK) {
            LINES.clear();
        }
    }
}
