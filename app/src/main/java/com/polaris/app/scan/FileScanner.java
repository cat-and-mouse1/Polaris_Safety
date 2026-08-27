package com.polaris.app.scan;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import com.polaris.app.R;
import com.polaris.app.util.Prefs;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 文件级扫描引擎：递归扫描目录，识别风险文件。
 *
 * 支持两种来源：
 * - 全盘/目录扫描（{@link #scanDirectory(File, Callback)}）：基于 java.io.File 遍历，
 *   需要「所有文件访问」权限（MANAGE_EXTERNAL_STORAGE）；
 * - SAF 文件夹扫描（{@link #scanTree(Uri, Callback)}）：基于原生 DocumentsContract
 *   遍历用户选择的文件夹（ACTION_OPEN_DOCUMENT_TREE 授权），无需额外权限与依赖。
 *
 * 打分维度：APK 等可执行载荷、文件名恶意关键词（复用应用级签名库）、
 * 伪装文件（图片/文档头实为压缩包）、勒索提示、脚本外置、体积异常、隐藏文件。
 */
public class FileScanner {

    public interface Callback {
        /** 进度回调：当前正在遍历的路径（可能频繁，UI 层自行节流）。 */
        void onProgress(String currentPath);
        /** 完成回调：风险文件列表（按分数降序，仅含 LOW+）。 */
        void onResult(List<FileRiskInfo> risks);
    }

    private static final String TAG = "FileScanner";

    /** 最高扫描文件数，防止超大目录卡死。 */
    private static final int MAX_FILES = 20000;

    /** 危险扩展名 -> 描述。 */
    private static final Map<String, String> DANGEROUS_EXTS = new HashMap<>();
    static {
        DANGEROUS_EXTS.put(".apk", "APK 安装包（不可信来源风险高）");
        DANGEROUS_EXTS.put(".dex", "DEX 可执行字节码");
        DANGEROUS_EXTS.put(".jar", "JAR 可执行包");
        DANGEROUS_EXTS.put(".sh", "Shell 脚本");
        DANGEROUS_EXTS.put(".py", "Python 脚本");
        DANGEROUS_EXTS.put(".bat", "批处理脚本（Windows 载荷）");
        DANGEROUS_EXTS.put(".vbs", "VBS 脚本（常被勒索/木马利用）");
        DANGEROUS_EXTS.put(".ps1", "PowerShell 脚本");
        DANGEROUS_EXTS.put(".js", "JavaScript（可疑位置）");
        DANGEROUS_EXTS.put(".lock", "勒索加密文件");
        DANGEROUS_EXTS.put(".locked", "勒索加密文件");
        DANGEROUS_EXTS.put(".crypt", "勒索加密文件");
        DANGEROUS_EXTS.put(".encrypted", "勒索加密文件");
        DANGEROUS_EXTS.put(".onion", "暗网相关文件");
        DANGEROUS_EXTS.put(".crdownload", "未完成的下载文件");
    }

    /** 风险软件文件名关键词 -> 加分。 */
    private static final Map<String, Integer> FILE_RISK_KEYWORDS = new HashMap<>();
    static {
        FILE_RISK_KEYWORDS.put("crack", 35);
        FILE_RISK_KEYWORDS.put("keygen", 35);
        FILE_RISK_KEYWORDS.put("破解", 30);
        FILE_RISK_KEYWORDS.put("外挂", 30);
        FILE_RISK_KEYWORDS.put("刷机", 20);
        FILE_RISK_KEYWORDS.put("spy", 25);
        FILE_RISK_KEYWORDS.put("trojan", 30);
        FILE_RISK_KEYWORDS.put("backdoor", 35);
        FILE_RISK_KEYWORDS.put("hack", 25);
        FILE_RISK_KEYWORDS.put("红包", 20);
        FILE_RISK_KEYWORDS.put("秒到", 20);
        FILE_RISK_KEYWORDS.put("博彩", 20);
        FILE_RISK_KEYWORDS.put("贷款", 15);
        FILE_RISK_KEYWORDS.put("hook", 15);
    }

    private static final String[] DOC_COLUMNS = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
    };

    private final Context context;
    private final Prefs prefs;
    private final IocDatabase ioc;
    private int scanned = 0;

    public FileScanner(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = new Prefs(context);
        this.ioc = IocDatabase.getInstance(context);
    }

    // ---------- 对外入口 ----------

    /** 全盘/目录扫描（java.io.File）。 */
    public void scanDirectory(File root, Callback cb) {
        final List<FileRiskInfo> out = new ArrayList<>();
        new Thread(() -> {
            walkFile(root, out, cb, 0);
            finish(out, cb);
        }).start();
    }

    /** SAF 文件夹扫描（treeUri 来自 ACTION_OPEN_DOCUMENT_TREE 的返回）。 */
    public void scanTree(Uri treeUri, Callback cb) {
        final List<FileRiskInfo> out = new ArrayList<>();
        new Thread(() -> {
            walkTree(treeUri, treeUri, out, cb, 0);
            finish(out, cb);
        }).start();
    }

    private void finish(List<FileRiskInfo> out, Callback cb) {
        Collections.sort(out, (a, b) -> Integer.compare(b.score, a.score));
        cb.onResult(out);
    }

    // ---------- java.io.File 遍历 ----------

    private void walkFile(File dir, List<FileRiskInfo> out, Callback cb, int depth) {
        if (scanned >= MAX_FILES) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (scanned >= MAX_FILES) return;
            String path = f.getAbsolutePath();
            if (prefs.isFileAllowed(path)) continue;      // 放行名单跳过
            if (f.isDirectory()) {
                cb.onProgress(path);
                if (depth < 12) walkFile(f, out, cb, depth + 1);
                continue;
            }
            scanned++;
            if (f.length() > 0) {
                FileRiskInfo info = evaluateFile(path, f.getName(), f.length(), readHeader(f));
                if (info.level >= FileRiskInfo.LEVEL_LOW) out.add(info);
            }
        }
    }

    // ---------- SAF（DocumentsContract）遍历 ----------

    private void walkTree(Uri treeUri, Uri dirUri, List<FileRiskInfo> out,
                          Callback cb, int depth) {
        if (scanned >= MAX_FILES) return;
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getDocumentId(dirUri));
        try (Cursor c = context.getContentResolver().query(
                childrenUri, DOC_COLUMNS, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext() && scanned < MAX_FILES) {
                String docId = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                long size = c.getLong(3);
                String display = name != null ? name : docId;
                if (prefs.isFileAllowed(display)) continue;
                Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    cb.onProgress(display);
                    if (depth < 12) walkTree(treeUri, docUri, out, cb, depth + 1);
                    continue;
                }
                scanned++;
                FileRiskInfo info = evaluateFile(docUri.toString(), display, size,
                        readDocHeader(docUri));
                info.source = "saf";
                if (info.level >= FileRiskInfo.LEVEL_LOW) out.add(info);
            }
        } catch (Exception e) {
            Log.w(TAG, "walk tree failed: " + dirUri, e);
        }
    }

    // ---------- 打分核心（与来源无关） ----------

    /** 对单个文件评分并返回风险信息（等级 >= LOW 才会被收集）。 */
    private FileRiskInfo evaluateFile(String path, String name, long size, byte[] header) {
        FileRiskInfo info = new FileRiskInfo(path, name, size);
        int score = 0;
        String lower = name != null ? name.toLowerCase(Locale.US) : path.toLowerCase(Locale.US);
        String ext = extOf(lower);

        // 1) APK 等可执行载荷
        String extDesc = DANGEROUS_EXTS.get(ext);
        if (extDesc != null) {
            if (".apk".equals(ext)) {
                score += 20;
                info.isApk = true;
                info.reasons.add(extDesc);
            } else {
                score += 15;
                info.reasons.add(extDesc);
            }
        }

        // 2) 文件名命中应用级恶意签名库（APK 包名特征，去点匹配）
        String nameKey = lower.replace('.', ' ').replace('-', ' ').replace('_', ' ');
        for (String knownPkg : MalwareScanner.knownPackageNames()) {
            String pkgKey = knownPkg.replace('.', ' ');
            if (nameKey.contains(pkgKey)) {
                score += 45;
                info.reasons.add("文件名命中已知恶意家族：" + MalwareScanner.lookUpFamily(knownPkg));
                break;
            }
        }

        // 3) 文件名风险关键词
        for (Map.Entry<String, Integer> e : FILE_RISK_KEYWORDS.entrySet()) {
            if (lower.contains(e.getKey())) {
                score += e.getValue();
                info.reasons.add("文件名含风险关键词：" + e.getKey());
            }
        }

        // 4) 伪装文件：扩展名安全但文件头为 zip/apk / MZ / ELF
        boolean safeExt = ext.isEmpty() || DANGEROUS_EXTS.get(ext) == null;
        if (safeExt && header != null && header.length >= 4) {
            int b0 = header[0] & 0xFF, b1 = header[1] & 0xFF;
            int b2 = header[2] & 0xFF, b3 = header[3] & 0xFF;
            boolean isZip = b0 == 0x50 && b1 == 0x4B;                         // PK
            boolean isExe = b0 == 0x4D && b1 == 0x5A;                         // MZ
            boolean isElf = b0 == 0x7F && b1 == 0x45 && b2 == 0x4C && b3 == 0x46;
            if (isZip || isExe || isElf) {
                score += 40;
                info.reasons.add("伪装文件：扩展名 " + (ext.isEmpty() ? "缺失" : ext)
                        + " 实为" + (isZip ? "压缩/APK 包" : isExe ? "Windows 可执行" : "ELF 可执行"));
            }
        }

        // 5) 勒索提示文件（readme / 解密说明）
        if (name != null && (name.toLowerCase(Locale.US).contains("readme")
                || name.contains("解密") || name.contains("恢复说明")
                || name.contains("赎金") || name.contains("how_to"))) {
            score += 50;
            info.reasons.add("疑似勒索提示文件（赎金说明）");
        }

        // 6) 体积异常
        if (info.isApk && size > 200L * 1024 * 1024) {
            score += 10;
            info.reasons.add("APK 体积异常庞大");
        }

        // 7) 隐藏文件（点开头）
        if (name != null && name.startsWith(".")) {
            score += 5;
            info.reasons.add("隐藏文件");
        }

        // 8) 开源情报（Polar Region IOC）融合：SHA-256 优先，包名兜底
        if (isHashableExt(ext)) {
            String hash = computeSha256(path);
            if (hash != null) {
                info.sha256 = hash;
                IocDatabase.IocEntry hit = ioc.matchByHash(hash);
                if (hit == null) {
                    String candPkg = extractPkgToken(lower);
                    if (candPkg != null) hit = ioc.matchByPkg(candPkg);
                }
                if (hit != null) {
                    info.iocFamily = hit.family;
                    info.iocSeverity = hit.severity;
                    score += IocDatabase.severityScore(hit.severity);
                    info.reasons.add(context.getString(
                            R.string.file_ioc_hit, hit.family, hit.desc));
                }
            }
        }

        if (score < 0) score = 0;
        if (score > 100) score = 100;
        info.score = score;
        info.level = FileRiskInfo.levelOf(score);
        return info;
    }

    // ---------- 文件头读取 ----------

    private static byte[] readHeader(File f) {
        byte[] buf = new byte[8];
        try (InputStream in = new FileInputStream(f)) {
            int n = in.read(buf);
            return n > 0 ? Arrays.copyOf(buf, n) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private byte[] readDocHeader(Uri docUri) {
        byte[] buf = new byte[8];
        try (InputStream in = context.getContentResolver().openInputStream(docUri)) {
            if (in == null) return null;
            int n = in.read(buf);
            return n > 0 ? Arrays.copyOf(buf, n) : null;
        } catch (IOException e) {
            Log.w(TAG, "read doc header failed: " + docUri, e);
            return null;
        }
    }

    // ---------- 小工具 ----------

    private static String extOf(String lowerName) {
        int dot = lowerName.lastIndexOf('.');
        if (dot < 0 || dot == lowerName.length() - 1) return "";
        String ext = lowerName.substring(dot);
        return ext.length() <= 8 ? ext : "";
    }

    /** 仅对可执行载荷计算哈希（避免对图片/文档做无意义的高开销运算）。 */
    private static boolean isHashableExt(String ext) {
        return ".apk".equals(ext) || ".dex".equals(ext) || ".jar".equals(ext);
    }

    /** 从文件名中提取形如 a.b.c 的包名候选（用于 IOC 包名匹配）。 */
    private static String extractPkgToken(String lowerName) {
        int dot = lowerName.lastIndexOf('.');
        String base = dot > 0 ? lowerName.substring(0, dot) : lowerName;
        java.util.regex.Pattern p =
                java.util.regex.Pattern.compile("[a-z0-9_]+(\\.[a-z0-9_]+){2,}");
        java.util.regex.Matcher m = p.matcher(base);
        return m.find() ? m.group(0) : null;
    }

    /** 计算文件 SHA-256（全盘路径或 SAF content URI 均可）。大文件上限 200MB。 */
    private String computeSha256(String path) {
        InputStream in = null;
        try {
            if (path != null && path.startsWith("content:")) {
                in = context.getContentResolver().openInputStream(Uri.parse(path));
            } else if (path != null) {
                File f = new File(path);
                if (f.exists() && f.length() > 0 && f.length() <= 200L * 1024 * 1024) {
                    in = new FileInputStream(f);
                }
            }
            if (in == null) return null;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            byte[] dig = md.digest();
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) { }
            }
        }
    }
}
