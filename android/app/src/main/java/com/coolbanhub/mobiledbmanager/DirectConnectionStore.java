package com.coolbanhub.mobiledbmanager;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Stores complete direct database profiles encrypted by Android Keystore.
 * The unencrypted preference contains connection ids only, never credentials.
 */
final class DirectConnectionStore {
    private static final String PREFERENCES = "dbx_direct_connections";
    private static final String IDS = "connection_ids";
    private static final String VAULT_PREFIX = "direct.connection.";

    private final Context context;
    private final SecureVaultStore vault;

    DirectConnectionStore(Context context) {
        this.context = context.getApplicationContext();
        this.vault = new SecureVaultStore(this.context);
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    List<String> ids() {
        return new ArrayList<>(preferences().getStringSet(IDS, Collections.emptySet()));
    }

    List<JSONObject> all() throws Exception {
        List<JSONObject> values = new ArrayList<>();
        for (String id : ids()) {
            JSONObject value = get(id);
            if (value != null) values.add(value);
        }
        return values;
    }

    JSONObject get(String id) throws Exception {
        String raw = vault.get(VAULT_PREFIX + id);
        return raw == null ? null : new JSONObject(raw);
    }

    JSONObject save(JSONObject incoming) throws Exception {
        String id = incoming.optString("id", "").trim();
        if (id.isEmpty()) id = java.util.UUID.randomUUID().toString();
        JSONObject existing = get(id);
        JSONObject merged = existing == null ? new JSONObject() : new JSONObject(existing.toString());
        JSONArray names = incoming.names();
        if (names != null) {
            for (int index = 0; index < names.length(); index++) {
                String name = names.getString(index);
                Object value = incoming.get(name);
                if (isWriteOnlySecret(name) && value instanceof String && ((String) value).isEmpty() && existing != null) {
                    continue;
                }
                merged.put(name, value);
            }
        }
        merged.put("id", id);
        stripInlineSshFieldsWhenProfileSelected(merged);
        vault.put(VAULT_PREFIX + id, merged.toString());
        Set<String> ids = new LinkedHashSet<>(preferences().getStringSet(IDS, Collections.emptySet()));
        ids.add(id);
        if (!preferences().edit().putStringSet(IDS, ids).commit()) {
            throw new IllegalStateException("Unable to update direct connection index");
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

    private boolean isWriteOnlySecret(String name) {
        return name.equals("password")
                || name.equals("proxyPassword")
                || name.equals("sshPassword")
                || name.equals("sshPrivateKey")
                || name.equals("sshPrivateKeyPassphrase")
                || name.equals("connectionString");
    }

    private static void stripInlineSshFieldsWhenProfileSelected(JSONObject connection) {
        if (connection.optString("sshProfileId", "").trim().isEmpty()) return;
        // 可复用配置是 SSH 参数的唯一来源。清理连接中可能由旧版手动配置遗留的字段，
        // 避免同一凭据在两个加密记录中重复保存，也防止后续维护时出现来源歧义。
        for (String name : new String[]{
                "sshHost", "sshPort", "sshUsername", "sshHostKeyFingerprint",
                "sshPassword", "sshAuthMethod", "sshPrivateKey", "sshPrivateKeyPassphrase"}) {
            connection.remove(name);
        }
    }
}
