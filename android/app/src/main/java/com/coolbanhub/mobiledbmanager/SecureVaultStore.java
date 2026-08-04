package com.coolbanhub.mobiledbmanager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

final class SecureVaultStore {
    static final String KEY_ALIAS = "mobile_db_manager_session_v1";
    static final String PREFERENCES = "dbx_secure_vault";
    static final String VALUE_SUFFIX = ".value";
    static final String IV_SUFFIX = ".iv";

    private final Context context;

    SecureVaultStore(Context context) {
        this.context = context.getApplicationContext();
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private SecretKey encryptionKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            generator.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
    }

    void put(String key, String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey());
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        if (!preferences().edit()
                .putString(key + VALUE_SUFFIX, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(key + IV_SUFFIX, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .commit()) {
            throw new IllegalStateException("无法保存加密的数据库连接");
        }
    }

    String get(String key) throws Exception {
        String encrypted = preferences().getString(key + VALUE_SUFFIX, null);
        String iv = preferences().getString(key + IV_SUFFIX, null);
        if (encrypted == null || iv == null) return null;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.DECRYPT_MODE,
                encryptionKey(),
                new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
        return new String(
                cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)),
                StandardCharsets.UTF_8);
    }

    void remove(String key) {
        preferences().edit()
                .remove(key + VALUE_SUFFIX)
                .remove(key + IV_SUFFIX)
                .commit();
    }
}
