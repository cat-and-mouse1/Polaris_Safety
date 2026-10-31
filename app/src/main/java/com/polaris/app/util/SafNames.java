package com.polaris.app.util;

import android.net.Uri;
import android.provider.DocumentsContract;

import com.polaris.app.scan.FileRiskInfo;

/**
 * SAF 授权文件夹的显示名与扫描分组键（纯函数，MainActivity 拆分第一刀）。
 * 从 MainActivity 抽出：两处均无 UI 状态依赖，可独立测试。
 */
public final class SafNames {

    private SafNames() {
    }

    /**
     * 授权树的显示名：优先取 tree document id 中 ':' 之后的末段路径
     * （主存储形如 "primary:Download/xx"，取最后一级），异常时回退 lastPathSegment。
     */
    public static String displayName(Uri treeUri) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            if (docId != null) {
                int colon = docId.indexOf(':');
                String tail = colon >= 0 ? docId.substring(colon + 1) : docId;
                if (!tail.isEmpty()) {
                    int slash = tail.lastIndexOf('/');
                    return slash >= 0 ? tail.substring(slash + 1) : tail;
                }
                return docId;
            }
        } catch (Exception ignored) {
        }
        String seg = treeUri.getLastPathSegment();
        return seg != null ? seg : treeUri.toString();
    }

    /**
     * 文件段结果的恒星分组键：SAF 授权扫描按 treeUri（每个授权文件夹一颗恒星）；
     * 全盘扫描按存储根下一级目录（根目录直属文件归存储根恒星 "/"）。
     */
    public static String keyFor(FileRiskInfo info) {
        if (info.treeUri != null && info.treeUri.length() > 0) {
            return info.treeUri;
        }
        String root = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
        String p = info.path != null ? info.path : "";
        String rel = p.startsWith(root) ? p.substring(root.length()) : p;
        if (rel.startsWith("/")) rel = rel.substring(1);
        int slash = rel.indexOf('/');
        return slash < 0 ? "/" : rel.substring(0, slash);
    }
}
