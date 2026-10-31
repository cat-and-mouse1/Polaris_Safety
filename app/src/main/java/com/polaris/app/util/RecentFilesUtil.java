package com.polaris.app.util;

import android.os.Environment;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 「最近添加的文件」统一数据源：java.io.File 直读（不依赖 MediaStore 索引——
 * 新文件入库滞后会导致漏文件），覆盖外部存储根 + 各公共目录及其一层子目录。
 * 两处消费方：主页下拉面板（MainActivity）与工具箱最近文件 Tab（ToolsPanelView）。
 */
public final class RecentFilesUtil {

    /** 最近文件条目。 */
    public static class Entry {
        public final String name;
        public final String path;
        public final String dateText;
        public final String sizeText;
        public final long lastModified;
        public final File file;

        Entry(String name, String path, String dateText, String sizeText,
              long lastModified, File file) {
            this.name = name;
            this.path = path;
            this.dateText = dateText;
            this.sizeText = sizeText;
            this.lastModified = lastModified;
            this.file = file;
        }
    }

    /** 时间窗：只收录 30 天内的文件。 */
    private static final long WINDOW_MS = 30L * 24 * 60 * 60 * 1000;
    /** 每目录最多下探一层子目录（平衡覆盖面与遍历耗时）。 */
    private static final int MAX_DEPTH = 1;

    private RecentFilesUtil() {
    }

    /** 扫描并返回最近文件（按 lastModified 降序，最多 limit 条）。 */
    public static List<Entry> load(int limit) {
        List<Entry> out = new ArrayList<>();
        long windowStart = System.currentTimeMillis() - WINDOW_MS;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

        File root = Environment.getExternalStorageDirectory();
        File[] dirs = {
                root,
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_ALARMS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_NOTIFICATIONS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RECORDINGS)
        };

        for (File dir : dirs) {
            collect(dir, 0, windowStart, sdf, out);
        }

        Collections.sort(out, (a, b) -> Long.compare(b.lastModified, a.lastModified));
        if (out.size() > limit) out.subList(limit, out.size()).clear();
        return out;
    }

    /** 递归收集：本层文件全收（时间窗内），子目录按 MAX_DEPTH 下探。 */
    private static void collect(File dir, int depth, long windowStart,
                                SimpleDateFormat sdf, List<Entry> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) {
                if (depth < MAX_DEPTH) collect(f, depth + 1, windowStart, sdf, out);
            } else if (f.isFile() && f.lastModified() > windowStart) {
                out.add(new Entry(
                        f.getName(),
                        f.getAbsolutePath(),
                        sdf.format(new Date(f.lastModified())),
                        formatSize(f.length()),
                        f.lastModified(),
                        f));
            }
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
        }
        return String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }
}
