package com.polaris.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.polaris.app.scan.Notifier;
import com.polaris.app.service.GuardService;
import com.polaris.app.util.Prefs;

/**
 * 开机自启：恢复守护。
 *
 * - 已选定守护模式（Accessibility / Shizuku / Root）时重新拉起前台守护服务，
 *   保证重启后后台拦截守护不中断；
 * - 拦截模式开启时恢复常驻置顶通知。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Prefs prefs = new Prefs(context);
        int mode = prefs.getActiveMode();
        if (mode == Prefs.MODE_ACCESSIBILITY
                || mode == Prefs.MODE_SHIZUKU
                || mode == Prefs.MODE_ROOT) {
            try {
                GuardService.start(context, mode);
            } catch (Exception ignored) {
            }
        }
        if (prefs.isBlockMode()) {
            Notifier.updateBlockNotification(context);
        }
    }
}
