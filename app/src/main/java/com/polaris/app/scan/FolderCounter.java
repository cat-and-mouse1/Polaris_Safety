package com.polaris.app.scan;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.util.Map;

/**
 * 扫描前的文件计数器（MainActivity 拆分第二刀）：
 * 统计本地目录 / SAF 授权树的文件总数与「按一级目录聚合」的分布，
 * 供扫描星域的局部进度条使用。纯逻辑，无 UI 依赖。
 *
 * 计数上限 cap 与 FileScanner 的遍历上限（COUNT_CAP=200000）保持一致：
 * 超限即停止计数并返回已数到的值。
 */
public final class FolderCounter {

    private FolderCounter() {
    }

    /** 递归计数本地目录；按一级目录聚合写入 folderTotals。 */
    public static int countLocal(File dir, Map<String, Integer> folderTotals, int cap) {
        if (dir == null) return 0;
        int[] c = {0};
        countFilesRec(dir, dir.getAbsolutePath(), c, 0, folderTotals, cap);
        return c[0];
    }

    private static void countFilesRec(File dir, String root, int[] c, int depth,
                                      Map<String, Integer> folderTotals, int cap) {
        if (c[0] >= cap) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (c[0] >= cap) return;
            if (f.isDirectory()) {
                if (depth < 20) countFilesRec(f, root, c, depth + 1, folderTotals, cap);
            } else {
                c[0]++;
                // 按一级目录聚合（与 SafNames.keyFor 一致），供局部进度条使用
                String key = keyOfPath(f.getAbsolutePath(), root);
                Integer old = folderTotals.get(key);
                folderTotals.put(key, old == null ? 1 : old + 1);
            }
        }
    }

    /** 路径 → 一级目录名（与 FileRiskInfo 的分组键同规则）。 */
    public static String keyOfPath(String path, String root) {
        String rel = path.startsWith(root) ? path.substring(root.length()) : path;
        if (rel.startsWith("/")) rel = rel.substring(1);
        int slash = rel.indexOf('/');
        return slash < 0 ? "/" : rel.substring(0, slash);
    }

    /** 递归计数 SAF 授权树；总数额外以 treeUri 为键写入 folderTotals。 */
    public static int countTree(Context ctx, Uri treeUri,
                                Map<String, Integer> folderTotals, int cap) {
        if (treeUri == null) return 0;
        int[] c = {0};
        countTreeRec(ctx, treeUri, treeUri, c, 0, cap);
        folderTotals.put(treeUri.toString(), c[0]);
        return c[0];
    }

    private static void countTreeRec(Context ctx, Uri treeUri, Uri dirUri, int[] c, int depth, int cap) {
        if (c[0] >= cap) return;
        Uri childrenUri;
        try {
            childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, DocumentsContract.getDocumentId(dirUri));
        } catch (Exception e) {
            return;
        }
        try (Cursor cur = ctx.getContentResolver().query(childrenUri,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cur == null) return;
            while (cur.moveToNext() && c[0] < cap) {
                String docId = cur.getString(0);
                String mime = cur.getString(1);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (depth < 20) {
                        countTreeRec(ctx, treeUri,
                                DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                                c, depth + 1, cap);
                    }
                } else {
                    c[0]++;
                }
            }
        } catch (Exception ignored) {
        }
    }
}
