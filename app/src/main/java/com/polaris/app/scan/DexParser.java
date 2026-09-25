package com.polaris.app.scan;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * DEX file parser for extracting API call patterns from APK files.
 * Used for ML feature extraction to detect malicious API usage patterns.
 */
public class DexParser {
    private static final String TAG = "DexParser";
    
    // Known malicious API patterns
    private static final String[] MALICIOUS_API_PATTERNS = {
        // Reflection (common in malware)
        "java.lang.reflect.Method.invoke",
        "java.lang.reflect.Field.setAccessible",
        "java.lang.Class.forName",
        
        // Dynamic class loading
        "dalvik.system.DexClassLoader",
        "dalvik.system.PathClassLoader",
        "java.lang.ClassLoader.loadClass",
        
        // Crypto operations
        "javax.crypto.Cipher",
        "javax.crypto.SecretKeySpec",
        "java.security.MessageDigest",
        
        // Network operations
        "java.net.HttpURLConnection",
        "java.net.URL.openConnection",
        "org.apache.http.impl.client.DefaultHttpClient",
        
        // SMS operations
        "android.telephony.SmsManager",
        "android.telephony.SmsMessage",
        
        // Phone operations
        "android.telephony.TelephonyManager",
        "android.telephony.PhoneStateListener",
        
        // Contacts operations
        "android.content.ContentResolver",
        "android.provider.ContactsContract",
        
        // Location operations
        "android.location.LocationManager",
        "android.location.LocationListener",
        
        // Camera operations
        "android.hardware.Camera",
        "android.hardware.camera2.CameraManager",
        
        // Audio operations
        "android.media.MediaRecorder",
        "android.media.AudioRecord",
        
        // File operations
        "java.io.File",
        "java.io.FileInputStream",
        "java.io.FileOutputStream",
        
        // SharedPreferences operations
        "android.content.SharedPreferences",
        
        // Intent operations
        "android.content.Intent",
        "android.content.IntentFilter",
        
        // Service operations
        "android.app.Service",
        "android.app.IntentService",
        
        // Broadcast operations
        "android.content.BroadcastReceiver",
        
        // Content Provider operations
        "android.content.ContentProvider",
        
        // Notification operations
        "android.app.NotificationManager",
        
        // Accessibility operations
        "android.accessibilityservice.AccessibilityService",
        "android.view.accessibility.AccessibilityEvent",
        
        // Device Admin operations
        "android.app.admin.DeviceAdminReceiver",
        "android.app.admin.DevicePolicyManager",
        
        // Package operations
        "android.content.pm.PackageManager",
        "android.content.pm.PackageInfo"
    };
    
    // API categories for feature extraction
    private static final String[][] API_CATEGORIES = {
        // reflection
        {"java.lang.reflect.Method.invoke", "java.lang.reflect.Field.setAccessible", "java.lang.Class.forName"},
        // dynamic_load
        {"dalvik.system.DexClassLoader", "dalvik.system.PathClassLoader", "java.lang.ClassLoader.loadClass"},
        // crypto
        {"javax.crypto.Cipher", "javax.crypto.SecretKeySpec", "java.security.MessageDigest"},
        // network
        {"java.net.HttpURLConnection", "java.net.URL.openConnection", "org.apache.http.impl.client.DefaultHttpClient"},
        // sms
        {"android.telephony.SmsManager", "android.telephony.SmsMessage"},
        // phone
        {"android.telephony.TelephonyManager", "android.telephony.PhoneStateListener"},
        // contacts
        {"android.content.ContentResolver", "android.provider.ContactsContract"},
        // location
        {"android.location.LocationManager", "android.location.LocationListener"},
        // camera
        {"android.hardware.Camera", "android.hardware.camera2.CameraManager"},
        // audio
        {"android.media.MediaRecorder", "android.media.AudioRecord"},
        // file
        {"java.io.File", "java.io.FileInputStream", "java.io.FileOutputStream"},
        // shared_prefs
        {"android.content.SharedPreferences"},
        // intent
        {"android.content.Intent", "android.content.IntentFilter"},
        // service
        {"android.app.Service", "android.app.IntentService"},
        // broadcast
        {"android.content.BroadcastReceiver"},
        // content_provider
        {"android.content.ContentProvider"},
        // notification
        {"android.app.NotificationManager"},
        // accessibility
        {"android.accessibilityservice.AccessibilityService", "android.view.accessibility.AccessibilityEvent"},
        // device_admin
        {"android.app.admin.DeviceAdminReceiver", "android.app.admin.DevicePolicyManager"},
        // package
        {"android.content.pm.PackageManager", "android.content.pm.PackageInfo"}
    };
    
    /**
     * Parse DEX file from APK and extract API call patterns.
     * @param context Android context
     * @param packageName Package name of the app
     * @return Set of API call patterns found
     */
    public static Set<String> extractApiCalls(Context context, String packageName) {
        Set<String> foundApis = new HashSet<>();
        
        try {
            ApplicationInfo appInfo = context.getPackageManager().getApplicationInfo(
                    packageName, 0);
            File apkFile = new File(appInfo.sourceDir);
            
            if (!apkFile.exists()) {
                Log.w(TAG, "APK file not found: " + appInfo.sourceDir);
                return foundApis;
            }
            
            // Open APK as ZIP file
            ZipFile zipFile = new ZipFile(apkFile);
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName();
                
                // Look for DEX files
                if (entryName.endsWith(".dex")) {
                    InputStream is = zipFile.getInputStream(entry);
                    Set<String> dexApis = parseDexFile(is);
                    foundApis.addAll(dexApis);
                    is.close();
                }
            }
            
            zipFile.close();
            
        } catch (Exception e) {
            Log.e(TAG, "Error parsing APK: " + packageName, e);
        }
        
        return foundApis;
    }
    
    /**
     * Parse a DEX file and extract API calls.
     * @param is InputStream of the DEX file
     * @return Set of API call patterns found
     */
    private static Set<String> parseDexFile(InputStream is) {
        Set<String> foundApis = new HashSet<>();
        
        try {
            // Read DEX file header
            byte[] header = new byte[8];
            if (is.read(header) != 8) {
                return foundApis;
            }
            
            // Check DEX magic number
            if (header[0] != 'd' || header[1] != 'e' || header[2] != 'x') {
                return foundApis;
            }
            
            // Read the rest of the file
            byte[] dexBytes = readAllBytes(is);
            
            // Search for API patterns in the DEX file.
            // DEX 内部是斜杠形式的类型描述符（如 Landroid/content/Intent;），
            // 而 MALICIOUS_API_PATTERNS 是点号 Java 名，故需转换后再匹配。
            for (String api : MALICIOUS_API_PATTERNS) {
                if (matchesInDex(dexBytes, api)) {
                    foundApis.add(api); // 保留原始点号名，供 API_CATEGORIES 精确匹配
                }
            }
            
        } catch (IOException e) {
            Log.e(TAG, "Error parsing DEX file", e);
        }
        
        return foundApis;
    }
    
    /**
     * Read all bytes from InputStream.
     */
    private static byte[] readAllBytes(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] data = new byte[4096];
        int bytesRead;
        
        while ((bytesRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, bytesRead);
        }
        
        return buffer.toByteArray();
    }
    
    /**
     * Check if byte array contains a string (simplified string matching).
     */
    private static boolean containsString(byte[] data, String str) {
        byte[] strBytes = str.getBytes();
        
        if (strBytes.length > data.length) {
            return false;
        }
        
        for (int i = 0; i <= data.length - strBytes.length; i++) {
            boolean found = true;
            for (int j = 0; j < strBytes.length; j++) {
                if (data[i + j] != strBytes[j]) {
                    found = false;
                    break;
                }
            }
            if (found) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 在 DEX 字节中匹配点号形式的 Java 名（DEX 内部为斜杠描述符）。
     * 先试全名斜杠形式；若末段为小写首字母（方法/字段），再试类前缀斜杠形式。
     * 例：'android.content.Intent' -> 'android/content/Intent'；
     *     'java.lang.reflect.Method.invoke' -> 'java/lang/reflect/Method'。
     */
    private static boolean matchesInDex(byte[] dexBytes, String dottedName) {
        String slashFull = dottedName.replace('.', '/');
        if (containsString(dexBytes, slashFull)) {
            return true;
        }
        int lastDot = dottedName.lastIndexOf('.');
        if (lastDot > 0) {
            String last = dottedName.substring(lastDot + 1);
            if (!last.isEmpty() && Character.isLowerCase(last.charAt(0))) {
                String slashPrefix = dottedName.substring(0, lastDot).replace('.', '/');
                return containsString(dexBytes, slashPrefix);
            }
        }
        return false;
    }
    
    /**
     * Get API feature vector (20 features).
     * @param foundApis Set of API calls found
     * @return float array of 20 API features
     */
    public static float[] getApiFeatureVector(Set<String> foundApis) {
        float[] features = new float[20];
        
        for (int i = 0; i < API_CATEGORIES.length; i++) {
            String[] categoryApis = API_CATEGORIES[i];
            for (String api : categoryApis) {
                if (foundApis.contains(api)) {
                    features[i] = 1.0f;
                    break;
                }
            }
        }
        
        return features;
    }
    
    /**
     * Get API feature names.
     * @return List of API feature names
     */
    public static String[] getApiFeatureNames() {
        return new String[]{
            "reflection", "dynamic_load", "crypto", "network", "sms",
            "phone", "contacts", "location", "camera", "audio",
            "file", "shared_prefs", "intent", "service", "broadcast",
            "content_provider", "notification", "accessibility", "device_admin", "package"
        };
    }

    // ================= mh1m 信号提取（DEX 字符串池 + method_ids） =================

    /** 从 DEX 提取的原始信号：全部字符串 + 方法引用(类描述符->方法名)。 */
    public static class DexSignals {
        public final Set<String> strings = new HashSet<>();
        public final Set<String> methodRefs = new HashSet<>();
        public final Set<Integer> opcodes = new HashSet<>();
    }

    /** 解析 APK 内所有 .dex，提取字符串池与方法引用。 */
    public static DexSignals extractSignals(Context context, String packageName) {
        DexSignals out = new DexSignals();
        try {
            ApplicationInfo appInfo = context.getPackageManager().getApplicationInfo(packageName, 0);
            File apkFile = new File(appInfo.sourceDir);
            if (!apkFile.exists()) {
                return out;
            }
            ZipFile zipFile = new ZipFile(apkFile);
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".dex")) {
                    InputStream is = zipFile.getInputStream(entry);
                    byte[] dex = readAllBytes(is);
                    is.close();
                    parseSignals(dex, out);
                }
            }
            zipFile.close();
        } catch (Exception e) {
            Log.e(TAG, "extractSignals failed: " + packageName, e);
        }
        return out;
    }

    private static void parseSignals(byte[] d, DexSignals out) {
        if (d == null || d.length < 112) return;
        if (d[0] != 'd' || d[1] != 'e' || d[2] != 'x') return;
        int stringIdsSize = readInt(d, 0x38), stringIdsOff = readInt(d, 0x3C);
        int typeIdsSize = readInt(d, 0x40), typeIdsOff = readInt(d, 0x44);
        int methodIdsSize = readInt(d, 0x58), methodIdsOff = readInt(d, 0x5C);
        if (stringIdsSize < 0 || stringIdsOff < 0 || typeIdsSize < 0 || typeIdsOff < 0
                || methodIdsSize < 0 || methodIdsOff < 0) {
            return;
        }

        String[] strings = new String[stringIdsSize];
        for (int i = 0; i < stringIdsSize; i++) {
            int off = readInt(d, stringIdsOff + i * 4);
            String s = readMutf8(d, off);
            strings[i] = s;
            if (s != null) {
                out.strings.add(s);
            }
        }
        String[] types = new String[typeIdsSize];
        for (int i = 0; i < typeIdsSize; i++) {
            int descIdx = readInt(d, typeIdsOff + i * 4);
            types[i] = (descIdx >= 0 && descIdx < stringIdsSize) ? strings[descIdx] : null;
        }
        for (int i = 0; i < methodIdsSize; i++) {
            int base = methodIdsOff + i * 8;
            if (base < 0 || base + 8 > d.length) break;
            int classIdx = readUShort(d, base);
            int nameIdx = readInt(d, base + 4);
            String cls = (classIdx >= 0 && classIdx < typeIdsSize) ? types[classIdx] : null;
            String name = (nameIdx >= 0 && nameIdx < stringIdsSize) ? strings[nameIdx] : null;
            if (cls != null && name != null) {
                out.methodRefs.add(cls + "->" + name);
            }
        }
        scanOpcodes(d, out);
    }

    private static int readInt(byte[] d, int off) {
        if (off < 0 || off + 4 > d.length) return -1;
        return (d[off] & 0xff) | ((d[off + 1] & 0xff) << 8)
                | ((d[off + 2] & 0xff) << 16) | ((d[off + 3] & 0xff) << 24);
    }

    private static int readUShort(byte[] d, int off) {
        if (off < 0 || off + 2 > d.length) return -1;
        return (d[off] & 0xff) | ((d[off + 1] & 0xff) << 8);
    }

    private static String readMutf8(byte[] d, int off) {
        if (off <= 0 || off >= d.length) return null;
        int p = off;
        while (p < d.length && (d[p] & 0x80) != 0) p++;  // 跳过 uleb128 长度
        p++;
        int start = p;
        while (p < d.length && d[p] != 0) p++;
        try {
            return new String(d, start, p - start, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    // ================= opcode 扫描（mh1m 的 5 个 opcode 特征） =================
    // 指令宽度表（单位: 16-bit code unit），索引为 opcode。
    private static final int[] INSN_WIDTH = {
        1, 1, 2, 3, 1, 2, 3, 1, 2, 3, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 2, 3, 2, 2, 3, 5, 2, 2, 3, 2, 1, 1, 2,
        2, 1, 2, 2, 3, 3, 3, 1, 1, 2, 3, 3, 3, 2, 2, 2,
        2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1,
        1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
        2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
        2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3,
        3, 3, 3, 1, 3, 3, 3, 3, 3, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
        2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
        2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 4, 4, 3, 3, 2, 2,
    };

    private static void scanOpcodes(byte[] d, DexSignals out) {
        int classDefsSize = readInt(d, 0x60), classDefsOff = readInt(d, 0x64);
        if (classDefsSize <= 0 || classDefsOff <= 0) return;
        for (int ci = 0; ci < classDefsSize; ci++) {
            int base = classDefsOff + ci * 32;
            if (base < 0 || base + 32 > d.length) break;
            int classDataOff = readInt(d, base + 24);
            if (classDataOff <= 0 || classDataOff >= d.length) continue;
            int[] p = {classDataOff};
            int sf = uleb(d, p);   // static_fields_size
            int inf = uleb(d, p);  // instance_fields_size
            int dm = uleb(d, p);   // direct_methods_size
            int vm = uleb(d, p);   // virtual_methods_size
            for (int i = 0; i < sf + inf; i++) { uleb(d, p); uleb(d, p); }
            for (int i = 0; i < dm + vm; i++) {
                uleb(d, p);                     // method_idx_diff
                uleb(d, p);                     // access_flags
                int codeOff = uleb(d, p);
                if (codeOff > 0) scanCodeItem(d, codeOff, out);
            }
        }
    }

    private static void scanCodeItem(byte[] d, int off, DexSignals out) {
        if (off < 0 || off + 16 > d.length) return;
        int insnsSize = readInt(d, off + 12);
        int insnsOff = off + 16;
        if (insnsSize <= 0 || insnsOff + insnsSize * 2 > d.length) return;
        Set<Integer> hits = new HashSet<>();
        int pos = 0;
        while (pos < insnsSize) {
            int unit = readUShort(d, insnsOff + pos * 2);
            int op = unit & 0xff;
            if (op == 0x00) {
                if (unit == 0x0000) {
                    pos += 1;
                } else if (unit == 0x0100) {
                    pos += 2 + 2 * readUShort(d, insnsOff + (pos + 1) * 2);
                } else if (unit == 0x0200) {
                    pos += 2 + 4 * readUShort(d, insnsOff + (pos + 1) * 2);
                } else if (unit == 0x0300) {
                    int ew = readUShort(d, insnsOff + (pos + 1) * 2);
                    int sz = readUShort(d, insnsOff + (pos + 2) * 2)
                            | (readUShort(d, insnsOff + (pos + 3) * 2) << 16);
                    pos += 4 + (sz * ew + 1) / 2;
                } else {
                    return; // 未知 payload，放弃整个方法
                }
                continue;
            }
            if (op == 0x1a || op == 0x6e || op == 0x71 || op == 0x0c || op == 0x38) {
                hits.add(op);
            }
            pos += INSN_WIDTH[op];
        }
        // 仅当精确走完整个指令流才采信，避免错位造成误报
        if (pos == insnsSize) {
            out.opcodes.addAll(hits);
        }
    }

    private static int uleb(byte[] d, int[] p) {
        int r = 0, s = 0;
        while (p[0] < d.length) {
            int b = d[p[0]++] & 0xff;
            r |= (b & 0x7f) << s;
            if ((b & 0x80) == 0) break;
            s += 7;
        }
        return r;
    }
}
