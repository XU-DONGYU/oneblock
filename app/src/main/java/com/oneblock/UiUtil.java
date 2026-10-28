package com.oneblock;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 控件树工具：扁平化、按文本/描述/ID 匹配、找可点击祖先。
 */
public final class UiUtil {

    private UiUtil() {
    }

    public static String s(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }

    /** 广度优先把控件树摊平成列表，limit 防止超大列表把内存吃满。 */
    public static List<AccessibilityNodeInfo> flatten(AccessibilityNodeInfo root, int limit) {
        List<AccessibilityNodeInfo> out = new ArrayList<AccessibilityNodeInfo>();
        if (root == null) {
            return out;
        }
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<AccessibilityNodeInfo>();
        q.add(root);
        int guard = 0;
        while (!q.isEmpty() && out.size() < limit && guard < 5000) {
            guard++;
            AccessibilityNodeInfo n = q.pollFirst();
            if (n == null) {
                continue;
            }
            out.add(n);
            int count = n.getChildCount();
            for (int i = 0; i < count; i++) {
                AccessibilityNodeInfo ch = n.getChild(i);
                if (ch != null) {
                    q.addLast(ch);
                }
            }
        }
        return out;
    }

    /**
     * by = text | desc | id
     */
    public static AccessibilityNodeInfo find(List<AccessibilityNodeInfo> nodes, String by, Pattern p, boolean visibleOnly) {
        if (p == null) {
            return null;
        }
        for (int i = 0; i < nodes.size(); i++) {
            AccessibilityNodeInfo n = nodes.get(i);
            String v;
            if ("desc".equals(by)) {
                v = s(n.getContentDescription());
            } else if ("id".equals(by)) {
                v = s(n.getViewIdResourceName());
            } else {
                v = s(n.getText());
            }
            if (v.isEmpty()) {
                continue;
            }
            if (visibleOnly && !n.isVisibleToUser()) {
                continue;
            }
            if (p.matcher(v).find()) {
                return n;
            }
        }
        return null;
    }

    /** 返回所有命中项，交给 pickCenter 之类去挑。 */
    public static List<AccessibilityNodeInfo> findAll(List<AccessibilityNodeInfo> nodes, String by, Pattern p, boolean visibleOnly) {
        List<AccessibilityNodeInfo> out = new ArrayList<AccessibilityNodeInfo>();
        if (p == null) {
            return out;
        }
        for (int i = 0; i < nodes.size(); i++) {
            AccessibilityNodeInfo n = nodes.get(i);
            String v;
            if ("desc".equals(by)) {
                v = s(n.getContentDescription());
            } else if ("id".equals(by)) {
                v = s(n.getViewIdResourceName());
            } else {
                v = s(n.getText());
            }
            if (v.isEmpty()) {
                continue;
            }
            if (visibleOnly && !n.isVisibleToUser()) {
                continue;
            }
            if (p.matcher(v).find()) {
                out.add(n);
            }
        }
        return out;
    }

    /**
     * 屏幕上同时有好几条同样的按钮时（时间线里每条帖子一个「更多」），
     * 取「顶部不超过屏幕中线」中最靠下的那个 —— 也就是屏幕中间正在看的那条。
     * 一条都不满足就退回最上面那个。
     */
    public static AccessibilityNodeInfo pickCenter(List<AccessibilityNodeInfo> nodes, int screenHeight) {
        if (nodes == null || nodes.isEmpty()) {
            return null;
        }
        int mid = screenHeight / 2;
        AccessibilityNodeInfo best = null;
        int bestTop = Integer.MIN_VALUE;
        for (int i = 0; i < nodes.size(); i++) {
            AccessibilityNodeInfo n = nodes.get(i);
            int top = rect(n).top;
            if (top <= mid && top > bestTop) {
                bestTop = top;
                best = n;
            }
        }
        return best != null ? best : nodes.get(0);
    }

    /** 自己不可点就往上找，最多 5 层。 */
    public static AccessibilityNodeInfo clickableParent(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 5 && cur != null; i++) {
            if (cur.isClickable()) {
                return cur;
            }
            AccessibilityNodeInfo p = cur.getParent();
            if (p == null) {
                break;
            }
            cur = p;
        }
        return null;
    }

    public static Rect rect(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return r;
    }

    public static String describe(AccessibilityNodeInfo n) {
        Rect r = rect(n);
        return s(n.getClassName())
                + " text=" + s(n.getText())
                + " desc=" + s(n.getContentDescription())
                + " id=" + s(n.getViewIdResourceName())
                + " bounds=" + r.left + "," + r.top + "," + r.right + "," + r.bottom
                + (n.isClickable() ? " [clickable]" : "")
                + (n.isVisibleToUser() ? "" : " [invisible]");
    }
}
