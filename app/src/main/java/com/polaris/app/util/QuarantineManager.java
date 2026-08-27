package com.polaris.app.util;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 隔离区管理器：把风险文件移入应用私有隔离目录，并记录原路径，
 * 支持「恢复」（移回原位）与「放行」（恢复并加入白名单，不再提示）。
 *
 * 支持两种来源：
 * - full：全盘扫描的 java.io.File（隔离 = 移动到私有目录 + 删除原件）；
 * - saf：SAF 授权目录内的文件（隔离 = 读内容写入私有目录 + 删除源文档；
 *        恢复 = 在授权树内重建文档并写回内容 + 删除隔离文件）。
 *
 * 隔离记录持久化在 SQLite；放行名单持久化在 {@link Prefs}。
 */
public class QuarantineManager extends SQLiteOpenHelper {

    private static final String TAG = "QuarantineManager";
    private static final String DB_NAME = "polaris_quarantine.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "quarantine_records";

    /** 一条隔离记录。 */
    public static class QuarantineRecord {
        public long id;
        public String originalPath;    // 展示/恢复用的原始路径
        public String quarantineName;  // 隔离目录中的文件名
        public String fileName;        // 原名
        public long size;
        public String reason;
        public int riskScore;
        public String source;          // full | saf
        public String originalUri;     // saf: 源文档 uri
        public String treeUri;         // saf: 授权树 uri
        public long quarantinedAt;
    }

    private final Context context;
    private final File quarantineDir;

    public QuarantineManager(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        this.context = context.getApplicationContext();
        // 应用私有隔离目录（无需存储权限，系统备份/还原会一并携带）
        this.quarantineDir = new File(this.context.getDir("quarantine", Context.MODE_PRIVATE),
                "files");
        //noinspection ResultOfMethodCallIgnored
        this.quarantineDir.mkdirs();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "original_path TEXT NOT NULL,"
                + "quarantine_name TEXT NOT NULL,"
                + "file_name TEXT NOT NULL,"
                + "size INTEGER DEFAULT 0,"
                + "reason TEXT DEFAULT '',"
                + "risk_score INTEGER DEFAULT 0,"
                + "source TEXT DEFAULT 'full',"
                + "original_uri TEXT DEFAULT '',"
                + "tree_uri TEXT DEFAULT '',"
                + "quarantined_at INTEGER DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 预留升级
    }

    // ---------- 隔离 ----------

    /** 隔离一个全盘扫描发现的文件。成功返回记录，失败返回 null。 */
    public QuarantineRecord quarantineFile(File src, String reason, int score) {
        if (src == null || !src.exists() || !src.isFile()) return null;
        String qName = uniqueName(src.getName());
        File dst = new File(quarantineDir, qName);
        try {
            copyFile(src, dst);
            if (!src.delete()) {
                // 删除失败则回滚（避免出现两份）
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
                Log.w(TAG, "source delete failed: " + src);
                return null;
            }
        } catch (IOException e) {
            Log.e(TAG, "quarantine copy failed", e);
            return null;
        }
        QuarantineRecord r = new QuarantineRecord();
        r.originalPath = src.getAbsolutePath();
        r.quarantineName = qName;
        r.fileName = src.getName();
        r.size = src.length();
        r.reason = reason;
        r.riskScore = score;
        r.source = "full";
        r.quarantinedAt = System.currentTimeMillis();
        return insert(r);
    }

    /** 隔离一个 SAF 授权目录内的文件。成功返回记录，失败返回 null。 */
    public QuarantineRecord quarantineSaf(Uri treeUri, Uri docUri, String displayPath,
                                          String name, long size, String reason, int score) {
        String qName = uniqueName(name != null ? name : "doc");
        File dst = new File(quarantineDir, qName);
        try (InputStream in = context.getContentResolver().openInputStream(docUri);
             OutputStream out = new FileOutputStream(dst)) {
            if (in == null) return null;
            copyStream(in, out);
            // 删除源文档
            if (!DocumentsContract.deleteDocument(
                    context.getContentResolver(), docUri)) {
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
                Log.w(TAG, "saf source delete failed: " + docUri);
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "quarantine saf failed", e);
            return null;
        }
        QuarantineRecord r = new QuarantineRecord();
        r.originalPath = displayPath;
        r.quarantineName = qName;
        r.fileName = name != null ? name : "doc";
        r.size = size;
        r.reason = reason;
        r.riskScore = score;
        r.source = "saf";
        r.originalUri = docUri.toString();
        r.treeUri = treeUri.toString();
        r.quarantinedAt = System.currentTimeMillis();
        return insert(r);
    }

    // ---------- 恢复 / 放行 ----------

    /**
     * 恢复：把隔离文件移回原始位置。成功返回 true。
     * 放行 = 恢复 + 加入白名单（prefs.addToFileAllowlist）。
     */
    public boolean restore(long id) {
        return restoreInternal(id, false);
    }

    public boolean allow(long id) {
        return restoreInternal(id, true);
    }

    private boolean restoreInternal(long id, boolean allow) {
        QuarantineRecord r = get(id);
        if (r == null) return false;
        File quarantined = new File(quarantineDir, r.quarantineName);
        if (!quarantined.exists()) {
            removeRecord(id);
            return false;
        }
        boolean ok;
        if ("saf".equals(r.source)) {
            ok = restoreSaf(r, quarantined);
        } else {
            ok = restoreFull(r, quarantined);
        }
        if (ok) {
            removeRecord(id);
            if (allow) {
                Prefs prefs = new Prefs(context);
                prefs.addToFileAllowlist(r.originalPath);
            }
        }
        return ok;
    }

    private boolean restoreFull(QuarantineRecord r, File quarantined) {
        File target = new File(r.originalPath);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) return false;
        try {
            copyFile(quarantined, target);
            //noinspection ResultOfMethodCallIgnored
            quarantined.delete();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "restore full failed", e);
            return false;
        }
    }

    private boolean restoreSaf(QuarantineRecord r, File quarantined) {
        try {
            Uri treeUri = Uri.parse(r.treeUri);
            Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getDocumentId(treeUri));
            // 若记录了父目录 docId 则用父目录；否则尝试根目录
            String mime = "application/octet-stream";
            if (r.fileName != null && r.fileName.toLowerCase(java.util.Locale.US).endsWith(".apk")) {
                mime = "application/vnd.android.package-archive";
            }
            Uri created = DocumentsContract.createDocument(
                    context.getContentResolver(), parentUri, mime, r.fileName);
            if (created == null) return false;
            try (InputStream in = new FileInputStream(quarantined);
                 OutputStream out = context.getContentResolver().openOutputStream(created)) {
                if (out == null) return false;
                copyStream(in, out);
            }
            //noinspection ResultOfMethodCallIgnored
            quarantined.delete();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "restore saf failed", e);
            return false;
        }
    }

    // ---------- 查询 ----------

    /** 全部隔离记录（按时间倒序）。 */
    public List<QuarantineRecord> list() {
        List<QuarantineRecord> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(TABLE, null, null, null,
                null, null, "quarantined_at DESC")) {
            while (c.moveToNext()) out.add(readRecord(c));
        }
        return out;
    }

    public QuarantineRecord get(long id) {
        try (Cursor c = getReadableDatabase().query(TABLE, null,
                "id=?", new String[]{String.valueOf(id)}, null, null, null)) {
            if (c.moveToFirst()) return readRecord(c);
        }
        return null;
    }

    public int count() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE, null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    // ---------- 内部 ----------

    private QuarantineRecord insert(QuarantineRecord r) {
        ContentValues v = new ContentValues();
        v.put("original_path", r.originalPath);
        v.put("quarantine_name", r.quarantineName);
        v.put("file_name", r.fileName);
        v.put("size", r.size);
        v.put("reason", r.reason);
        v.put("risk_score", r.riskScore);
        v.put("source", r.source);
        v.put("original_uri", r.originalUri);
        v.put("tree_uri", r.treeUri);
        v.put("quarantined_at", r.quarantinedAt);
        long id = getWritableDatabase().insert(TABLE, null, v);
        if (id < 0) return null;
        r.id = id;
        return r;
    }

    private void removeRecord(long id) {
        getWritableDatabase().delete(TABLE, "id=?", new String[]{String.valueOf(id)});
    }

    private QuarantineRecord readRecord(Cursor c) {
        QuarantineRecord r = new QuarantineRecord();
        r.id = c.getLong(c.getColumnIndexOrThrow("id"));
        r.originalPath = c.getString(c.getColumnIndexOrThrow("original_path"));
        r.quarantineName = c.getString(c.getColumnIndexOrThrow("quarantine_name"));
        r.fileName = c.getString(c.getColumnIndexOrThrow("file_name"));
        r.size = c.getLong(c.getColumnIndexOrThrow("size"));
        r.reason = c.getString(c.getColumnIndexOrThrow("reason"));
        r.riskScore = c.getInt(c.getColumnIndexOrThrow("risk_score"));
        r.source = c.getString(c.getColumnIndexOrThrow("source"));
        r.originalUri = c.getString(c.getColumnIndexOrThrow("original_uri"));
        r.treeUri = c.getString(c.getColumnIndexOrThrow("tree_uri"));
        r.quarantinedAt = c.getLong(c.getColumnIndexOrThrow("quarantined_at"));
        return r;
    }

    private String uniqueName(String name) {
        String safe = name != null && !name.isEmpty() ? name : "file";
        return System.currentTimeMillis() + "_" + (int) (Math.random() * 10000) + "_" + safe;
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            copyStream(in, out);
        }
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
    }
}
