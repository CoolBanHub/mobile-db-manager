package com.coolbanhub.mobiledbmanager;

import android.content.Context;
import android.content.SharedPreferences;

import com.getcapacitor.JSObject;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Stores reusable SSH jump-host profiles without exposing credentials to WebView. */
final class DirectSshProfileStore {
    private static final String PREFERENCES = "dbx_direct_ssh_profiles";
    private static final String IDS = "profile_ids";
    private static final String VAULT_PREFIX = "direct.ssh.profile.";

    private final Context context;
    private final SecureVaultStore vault;
    private final DirectSshKeyStore keys;

    DirectSshProfileStore(Context context) {
        this.context = context.getApplicationContext();
        this.vault = new SecureVaultStore(this.context);
        this.keys = new DirectSshKeyStore(this.context);
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
        if (raw == null) return null;
        JSONObject profile = new JSONObject(raw);
        // 旧版本把私钥嵌在跳板机记录中。首次读取时迁移到独立密钥库，跳板机 ID
        // 保持不变，因此已有数据库连接无需修改即可继续使用。
        if ("private-key".equals(profile.optString("authMethod"))
                && profile.optString("keyId").isEmpty()
                && !profile.optString("privateKey").isEmpty()) {
            JSONObject key = keys.saveLegacy(
                    id,
                    profile.optString("name", "SSH") + " 密钥",
                    profile.optString("privateKey"),
                    profile.optString("privateKeyPassphrase"));
            profile.put("keyId", key.optString("id"));
            profile.remove("privateKey");
            profile.remove("privateKeyPassphrase");
            vault.put(VAULT_PREFIX + id, profile.toString());
        }
        return profile;
    }

    JSONObject save(JSONObject incoming) throws Exception {
        String id = incoming.optString("id", "").trim();
        if (id.isEmpty()) id = UUID.randomUUID().toString();
        JSONObject existing = get(id);
        JSONObject merged = existing == null ? new JSONObject() : new JSONObject(existing.toString());
        JSONArray names = incoming.names();
        if (names != null) {
            for (int index = 0; index < names.length(); index++) {
                String name = names.getString(index);
                Object value = incoming.get(name);
                if (isSecret(name) && value instanceof String && ((String) value).isEmpty() && existing != null) continue;
                merged.put(name, value);
            }
        }
        merged.put("id", id);
        validate(merged);
        if ("private-key".equals(merged.optString("authMethod"))
                && keys.get(merged.optString("keyId")) == null) {
            throw new IllegalArgumentException("选择的 SSH 密钥不存在，请刷新后重试");
        }
        // 切换认证方式时主动清理不再使用的凭据。空字符串通常表示“沿用旧值”，
        // 因此必须在合并完成后清理，避免保险箱长期保留无用途的旧密码或私钥。
        if ("password".equals(merged.optString("authMethod"))) {
            merged.remove("keyId");
        } else {
            merged.remove("password");
        }
        merged.remove("privateKey");
        merged.remove("privateKeyPassphrase");
        vault.put(VAULT_PREFIX + id, merged.toString());
        Set<String> ids = new LinkedHashSet<>(preferences().getStringSet(IDS, Collections.emptySet()));
        ids.add(id);
        if (!preferences().edit().putStringSet(IDS, ids).commit()) {
            throw new IllegalStateException("无法更新 SSH 配置索引");
        }
        return merged;
    }

    boolean remove(String id) {
        Set<String> ids = new LinkedHashSet<>(preferences().getStringSet(IDS, Collections.emptySet()));
        boolean existed = ids.remove(id);
        vault.remove(VAULT_PREFIX + id);
        preferences().edit().putStringSet(IDS, ids).commit();
        return existed;
    }

    JSONObject applyToConnection(JSONObject connection) throws Exception {
        String id = connection.optString("sshProfileId", "").trim();
        if (id.isEmpty() || !connection.optBoolean("sshEnabled", false)) return connection;
        JSONObject profile = get(id);
        if (profile == null) throw new IllegalArgumentException("已保存的 SSH 配置不存在，请在设置中重新选择");
        // 只在原生调用栈中构造一次性有效配置，保存的数据库连接仍只包含 profile ID，
        // SSH 密码和私钥不会复制进连接记录，也不会通过 Capacitor 返回 WebView。
        JSONObject effective = new JSONObject(connection.toString());
        effective.put("sshEnabled", true);
        effective.put("sshHost", profile.optString("host"));
        effective.put("sshPort", profile.optInt("port", 22));
        effective.put("sshUsername", profile.optString("username"));
        effective.put("sshHostKeyFingerprint", profile.optString("hostKeyFingerprint"));
        effective.put("sshAuthMethod", profile.optString("authMethod", "password"));
        effective.put("sshPassword", profile.optString("password"));
        if ("private-key".equals(profile.optString("authMethod"))) {
            JSONObject key = keys.get(profile.optString("keyId"));
            if (key == null) throw new IllegalArgumentException("跳板机引用的 SSH 密钥不存在，请在设置中重新选择");
            effective.put("sshPrivateKey", key.optString("privateKey"));
            effective.put("sshPrivateKeyPassphrase", key.optString("privateKeyPassphrase"));
        }
        return effective;
    }

    JSObject summary(JSONObject profile) throws Exception {
        String keyId = profile.optString("keyId");
        JSONObject key = keyId.isEmpty() ? null : keys.get(keyId);
        return new JSObject()
                .put("id", profile.optString("id"))
                .put("name", profile.optString("name"))
                .put("host", profile.optString("host"))
                .put("port", profile.optInt("port", 22))
                .put("username", profile.optString("username"))
                .put("hostKeyFingerprint", profile.optString("hostKeyFingerprint"))
                .put("authMethod", profile.optString("authMethod", "password"))
                .put("hasPassword", !profile.optString("password").isEmpty())
                .put("keyId", keyId)
                .put("keyName", key == null ? "" : key.optString("name"));
    }

    static void validate(JSONObject profile) {
        validateFields(
                profile.optString("name"),
                profile.optString("host"),
                profile.optInt("port", 22),
                profile.optString("username"),
                profile.optString("authMethod", "password"),
                profile.optString("password"),
                profile.optString("keyId"));
    }

    static void validateFields(String name, String host, int port, String username,
                               String method, String password, String keyId) {
        DirectJson.required(name, "SSH 配置名称");
        DirectJson.required(host, "SSH 主机");
        DirectJson.required(username, "SSH 用户名");
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("SSH 端口必须在 1 到 65535 之间");
        }
        if (!method.equals("password") && !method.equals("private-key")) {
            throw new IllegalArgumentException("不支持的 SSH 认证方式");
        }
        if (method.equals("password") && password.isEmpty()) {
            throw new IllegalArgumentException("密码认证需要填写 SSH 密码");
        }
        if (method.equals("private-key") && keyId.isEmpty()) {
            throw new IllegalArgumentException("私钥认证需要选择 SSH 密钥");
        }
    }

    String firstProfileUsingKey(String keyId) throws Exception {
        for (JSONObject profile : all()) {
            if (keyId.equals(profile.optString("keyId"))) return profile.optString("name");
        }
        return null;
    }

    int countProfilesUsingKey(String keyId) throws Exception {
        int count = 0;
        for (JSONObject profile : all()) {
            if (keyId.equals(profile.optString("keyId"))) count++;
        }
        return count;
    }

    private static boolean isSecret(String name) {
        return name.equals("password") || name.equals("privateKey") || name.equals("privateKeyPassphrase");
    }
}
