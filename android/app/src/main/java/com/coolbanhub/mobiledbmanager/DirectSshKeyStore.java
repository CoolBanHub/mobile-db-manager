package com.coolbanhub.mobiledbmanager;

import android.content.Context;
import android.content.SharedPreferences;

import com.getcapacitor.JSObject;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Stores reusable SSH private keys in the Android Keystore-backed encrypted vault. */
final class DirectSshKeyStore {
    private static final String PREFERENCES = "dbx_direct_ssh_keys";
    private static final String IDS = "key_ids";
    private static final String VAULT_PREFIX = "direct.ssh.key.";

    private final Context context;
    private final SecureVaultStore vault;

    DirectSshKeyStore(Context context) {
        this.context = context.getApplicationContext();
        this.vault = new SecureVaultStore(this.context);
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    List<JSONObject> all() throws Exception {
        List<JSONObject> values = new ArrayList<>();
        for (String id : preferences().getStringSet(IDS, Collections.emptySet())) {
            JSONObject value = get(id);
            if (value != null) values.add(value);
        }
        values.sort((left, right) -> left.optString("name").compareToIgnoreCase(right.optString("name")));
        return values;
    }

    JSONObject get(String id) throws Exception {
        String raw = vault.get(VAULT_PREFIX + id);
        return raw == null ? null : new JSONObject(raw);
    }

    JSONObject save(JSONObject incoming) throws Exception {
        String id = incoming.optString("id", "").trim();
        if (id.isEmpty()) id = UUID.randomUUID().toString();
        JSONObject existing = get(id);
        JSONObject merged = existing == null ? new JSONObject() : new JSONObject(existing.toString());
        boolean replacesPrivateKey = !incoming.optString("privateKey").isEmpty();
        JSONArray names = incoming.names();
        if (names != null) {
            for (int index = 0; index < names.length(); index++) {
                String name = names.getString(index);
                Object value = incoming.get(name);
                // 编辑页面不会读取旧秘密；空字符串表示继续使用保险箱中的原值。
                if (isSecret(name) && value instanceof String && ((String) value).isEmpty() && existing != null
                        && (!name.equals("privateKeyPassphrase") || !replacesPrivateKey)) continue;
                merged.put(name, value);
            }
        }
        if (replacesPrivateKey && incoming.optString("privateKeyPassphrase").isEmpty()) {
            merged.remove("privateKeyPassphrase");
        }
        merged.put("id", id);
        String name = DirectJson.required(merged.optString("name"), "SSH 密钥名称");
        String privateKey = DirectJson.required(merged.optString("privateKey"), "SSH 私钥");
        KeyMetadata metadata = inspect(privateKey, merged.optString("privateKeyPassphrase"));
        merged.put("name", name);
        merged.put("keyType", metadata.keyType);
        merged.put("fingerprint", metadata.fingerprint);
        vault.put(VAULT_PREFIX + id, merged.toString());

        Set<String> ids = new LinkedHashSet<>(preferences().getStringSet(IDS, Collections.emptySet()));
        ids.add(id);
        if (!preferences().edit().putStringSet(IDS, ids).commit()) {
            throw new IllegalStateException("无法更新 SSH 密钥索引");
        }
        return merged;
    }

    JSONObject saveLegacy(String profileId, String name, String privateKey, String passphrase) throws Exception {
        String id = "legacy-" + profileId;
        JSONObject existing = get(id);
        if (existing != null) return existing;
        return save(new JSONObject()
                .put("id", id)
                .put("name", name)
                .put("privateKey", privateKey)
                .put("privateKeyPassphrase", passphrase));
    }

    boolean remove(String id) {
        Set<String> ids = new LinkedHashSet<>(preferences().getStringSet(IDS, Collections.emptySet()));
        boolean existed = ids.remove(id);
        vault.remove(VAULT_PREFIX + id);
        preferences().edit().putStringSet(IDS, ids).commit();
        return existed;
    }

    static JSObject summary(JSONObject key) {
        return new JSObject()
                .put("id", key.optString("id"))
                .put("name", key.optString("name"))
                .put("keyType", key.optString("keyType"))
                .put("fingerprint", key.optString("fingerprint"))
                .put("hasPrivateKey", !key.optString("privateKey").isEmpty())
                .put("hasPassphrase", !key.optString("privateKeyPassphrase").isEmpty())
                .put("usageCount", 0);
    }

    static KeyMetadata inspect(String privateKey, String passphrase) {
        if (privateKey.getBytes(StandardCharsets.UTF_8).length > 256 * 1024) {
            throw new IllegalArgumentException("SSH 私钥文件不能超过 256 KB");
        }
        KeyPair pair = null;
        try {
            pair = KeyPair.load(new JSch(), privateKey.getBytes(StandardCharsets.UTF_8), null);
            if (pair.isEncrypted() && (passphrase.isEmpty() || !pair.decrypt(passphrase))) {
                throw new IllegalArgumentException("SSH 私钥口令不正确或未填写");
            }
            byte[] publicKey = pair.getPublicKeyBlob();
            if (publicKey == null || publicKey.length == 0) {
                throw new IllegalArgumentException("无法读取 SSH 私钥的公钥信息");
            }
            String digest = Base64.getEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(publicKey));
            return new KeyMetadata(pair.getKeyTypeString(), "SHA256:" + digest);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("无法解析 SSH 私钥，请使用 OpenSSH 或 PEM 格式", error);
        } finally {
            if (pair != null) pair.dispose();
        }
    }

    private static boolean isSecret(String name) {
        return name.equals("privateKey") || name.equals("privateKeyPassphrase");
    }

    static final class KeyMetadata {
        final String keyType;
        final String fingerprint;

        KeyMetadata(String keyType, String fingerprint) {
            this.keyType = keyType;
            this.fingerprint = fingerprint;
        }
    }
}
