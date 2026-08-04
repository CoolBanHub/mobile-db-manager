package com.houtsider.dbx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class EtcdInstrumentedTest {
    @Test
    public void statusPutRangeGetAndDeleteWorkOnAndroid() throws Exception {
        JSONObject config = new JSONObject()
                .put("host", "10.0.2.2")
                .put("port", 12379)
                .put("ssl", false)
                // 账号只属于一次性测试容器，不得复用于真实环境。
                .put("username", "root")
                .put("password", "dbx-android-test-only")
                .put("connectTimeoutSecs", 10)
                .put("queryTimeoutSecs", 10);
        String key = "/dbx/android-instrumentation";

        assertFalse(DirectEtcdConnection.status(config).optString("version").isEmpty());
        DirectEtcdConnection.put(config, key, "安卓 etcd 测试");

        JSONArray range = DirectEtcdConnection.range(config, "/dbx/", 20).getJSONArray("kvs");
        assertTrue(range.length() >= 1);
        JSONObject stored = DirectEtcdConnection.get(config, key).getJSONArray("kvs").getJSONObject(0);
        assertEquals(key, DirectEtcdConnection.decode(stored.getString("key")));
        assertEquals("安卓 etcd 测试", DirectEtcdConnection.decode(stored.getString("value")));

        DirectEtcdConnection.delete(config, key);
        JSONArray deleted = DirectEtcdConnection.get(config, key).optJSONArray("kvs");
        assertTrue(deleted == null || deleted.length() == 0);
    }
}
