package com.polaris.app.util;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 行为日志（「行为」板块数据层）。
 *
 * 记录 Polaris 的所有防护动作：强制退出、拦截、隔离、救援、
 * 紧急遇险、应用监控告警等，以时间线形式供用户回溯检查。
 * 存储于 SharedPreferences（JSON 数组，最新在前，上限 300 条）。
 */
public final class BehaviorLog {

    // 动作类型
    public static final String TYPE_FORCE_STOP = "force_stop";     // 强制退出程序
    public static final String TYPE_BLOCK = "block";               // 拦截恶意应用
    public static final String TYPE_QUARANTINE = "quarantine";     // 隔离风险文件
    public static final String TYPE_RESCUE = "rescue";             // 救援覆盖层
    public static final String TYPE_EMERGENCY = "emergency";       // 紧急遇险（三击音量键）
    public static final String TYPE_MONITOR = "monitor";           // 应用监控告警
    public static final String TYPE_UNINSTALL = "uninstall";       // 卸载恶意应用
    public static final String TYPE_FREEZE = "freeze";             // 冻结恶意应用

    /** 一条行为记录。 */
    public static class Entry {
        public long ts;
        public String type;
        public String target;   // 应用名或文件名
        public String detail;   // 具体描述
    }

    private BehaviorLog() {}

    /** 记录一条防护动作（在任意线程调用均可）。 */
    public static void log(Context context, String type, String target, String detail) {
        try {
            JSONObject o = new JSONObject();
            o.put("ts", System.currentTimeMillis());
            o.put("type", type == null ? "" : type);
            o.put("target", target == null ? "" : target);
            o.put("detail", detail == null ? "" : detail);
            new Prefs(context).appendBehaviorLog(o.toString());
        } catch (Exception ignored) {
        }
    }

    /** 读取全部记录（最新在前）。 */
    public static java.util.List<Entry> list(Context context) {
        java.util.List<Entry> out = new java.util.ArrayList<>();
        JSONArray arr = new Prefs(context).getBehaviorLog();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Entry e = new Entry();
            e.ts = o.optLong("ts", 0L);
            e.type = o.optString("type", "");
            e.target = o.optString("target", "");
            e.detail = o.optString("detail", "");
            out.add(e);
        }
        return out;
    }

    /** 便于展示：目标应用名（传入已是 label）。 */
    public static String timeText(long millis) {
        return new java.text.SimpleDateFormat("MM-dd HH:mm:ss",
                java.util.Locale.getDefault()).format(new java.util.Date(millis));
    }
}
