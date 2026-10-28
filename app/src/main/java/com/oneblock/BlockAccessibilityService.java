package com.oneblock;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * 无障碍服务：读当前窗口控件树，点击「更多 / 拉黑 / 确认」。
 */
public class BlockAccessibilityService extends AccessibilityService {

    public static volatile BlockAccessibilityService instance;

    /** 最近一次窗口所属包名，getRootInActiveWindow 拿不到时兜底用。 */
    public volatile String lastPackage = "";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Logx.init(this);
        Logx.add(this, "无障碍服务已连接");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        CharSequence p = event.getPackageName();
        if (p != null) {
            lastPackage = p.toString();
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        Logx.add(getApplicationContext(), "无障碍服务已断开");
        return super.onUnbind(intent);
    }

    public AccessibilityNodeInfo root() {
        try {
            return getRootInActiveWindow();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 点控件：自己能点就 performAction；不能点就按坐标点它中心。
     * 不再「信任某个可点击祖先」——B站的黑名单确认按钮就是这种坑：
     * 祖先 clickable=true，performAction 返回成功，实际什么都没发生。
     */
    public boolean clickNode(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        if (node.isClickable()) {
            try {
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        Rect r = UiUtil.rect(node);
        if (r.width() > 0 && r.height() > 0) {
            return tap(r.centerX(), r.centerY());
        }
        AccessibilityNodeInfo target = UiUtil.clickableParent(node);
        if (target != null) {
            try {
                return target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    public boolean tap(int x, int y) {
        try {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(p, 0, 60);
            GestureDescription gd = new GestureDescription.Builder().addStroke(stroke).build();
            return dispatchGesture(gd, null, null);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 按屏幕相对坐标点一下（0~1），用于没有文字/描述的头像之类。 */
    public boolean tapPercent(float px, float py) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int x = (int) (dm.widthPixels * px);
        int y = (int) (dm.heightPixels * py);
        return tap(x, y);
    }

    public int screenHeight() {
        return getResources().getDisplayMetrics().heightPixels;
    }
}
