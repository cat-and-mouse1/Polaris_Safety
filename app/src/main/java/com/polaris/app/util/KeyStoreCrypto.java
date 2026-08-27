package com.polaris.app.util;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 基于 Android Keystore 的 AES/GCM 加密工具。
 *
 * 用途：将 AI API Key 加密后落盘，避免明文写入 SharedPreferences。
 * - 密钥由系统 Keystore 硬件/软件保管，应用进程无法直接导出；
 * - AES-256-GCM 自带完整性校验，篡改即解密失败；
 * - 不使用用户锁屏认证（setUserAuthenticationRequired(false)），
 *   保证后台定时扫描时也能在无交互状态下解密 Key。
 *
 * 容错：密钥在系统还原/迁移后可能丢失，此时 decrypt 返回 null，
 * 上层（Prefs）应视为「未配置」，引导用户重新录入 Key。
 */
public final class KeyStoreCrypto {

    private static final String TAG = "KeyStoreCrypto";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "polaris_ai_key_v1";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;

    private KeyStoreCrypto() {}

    /** 加密明文，返回 "ivBase64:cipherBase64"；失败返回 null。 */
    public static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return null;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] iv = cipher.getIV();
            byte[] out = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(iv, Base64.NO_WRAP)
                    + ":"
                    + Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "encrypt failed", e);
            return null;
        }
    }

    /** 解密 "ivBase64:cipherBase64"；失败（含非本机加密的数据）返回 null。 */
    public static String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) return null;
        // 兼容旧版明文存储：不含分隔符的视为历史明文，直接返回（平滑升级）。
        int sep = stored.indexOf(':');
        if (sep <= 0 || sep >= stored.length() - 1) return stored;
        try {
            byte[] iv = Base64.decode(stored.substring(0, sep), Base64.NO_WRAP);
            byte[] cipherText = Base64.decode(stored.substring(sep + 1), Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "decrypt failed", e);
            return null;
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        KeyStore.SecretKeyEntry entry =
                (KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null);
        if (entry != null) return entry.getSecretKey();

        KeyGenerator kg = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .setRandomizedEncryptionRequired(true)
                .build());
        return kg.generateKey();
    }

    /** 生成随机的非对称盐（备用，暂未使用）。 */
    @SuppressWarnings("unused")
    private static String randomSalt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return Base64.encodeToString(salt, Base64.NO_WRAP);
    }
}
