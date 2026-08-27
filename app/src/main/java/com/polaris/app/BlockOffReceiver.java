package com.polaris.app;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.polaris.app.util.Prefs;

/**
 * 拦截模式常驻通知的「关闭」动作接收器。
 * 用户点击通知上的「关闭」后，关闭拦截模式、撤销置顶通知，并广播刷新界面。
 */
public class BlockOffReceiver extends BroadcastReceiver {

    public static final String ACTION_BLOCK_OFF =
            "com.polaris.app.action.BLOCK_OFF";
    /** 拦截状态变化广播（关闭后用于通知界面刷新）。 */
    public static final String ACTION_BLOCK_STATE =
            "com.polaris.app.action.BLOCK_STATE";
    public static final int BLOCK_NOTIFICATION_ID = 7;

    @Override
    public void onReceive(Context context, Intent intent) {
        Prefs prefs = new Prefs(context);
        prefs.setBlockMode(false);

        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(BLOCK_NOTIFICATION_ID);

        // 通知界面刷新（扫描中心若在前台则同步关闭开关）
        Intent refresh = new Intent(ACTION_BLOCK_STATE);
        context.sendBroadcast(refresh);
    }
}
