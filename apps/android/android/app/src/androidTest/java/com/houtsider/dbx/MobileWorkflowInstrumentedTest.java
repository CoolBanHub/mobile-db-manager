package com.houtsider.dbx;

import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtraWithKey;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation.ActivityResult;
import android.content.Intent;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class MobileWorkflowInstrumentedTest {
    private static final long UI_TIMEOUT_MS = 12_000;
    private DeviceMockDbxServer server;
    private ActivityScenario<MainActivity> scenario;
    private volatile boolean authenticated;
    private volatile boolean delayQuery;

    @Before
    public void setUp() throws Exception {
        authenticated = true;
        delayQuery = false;
        server = new DeviceMockDbxServer(this::dispatch);
        new SecureVaultStore(androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation()
                .getTargetContext()).remove("device-e2e");
        scenario = ActivityScenario.launch(MainActivity.class);
        waitFor("document.readyState === 'complete'");
        installProfileAndReload();
    }

    @After
    public void tearDown() throws Exception {
        if (scenario != null) scenario.close();
        if (server != null) server.close();
        try {
            Intents.release();
        } catch (IllegalStateException ignored) {
            // Intents is initialized only by the sharing test.
        }
    }

    @Test
    public void loginUsesNativeDeviceTransportAndStoresSession() throws Exception {
        authenticated = false;
        installProfileAndReload();
        waitFor("document.querySelector('input[type=password]') !== null");

        eval("(() => { const input = document.querySelector('input[type=password]');"
                + "input.value='correct horse';input.dispatchEvent(new Event('input',{bubbles:true}));"
                + "return [...document.querySelectorAll('button')].find(b => b.textContent.includes('登录服务器')).click(); })()");

        waitFor("document.body.innerText.includes('登录成功')");
        DeviceMockDbxServer.Request request = waitForRequest("POST", "/api/auth/mobile-login");
        assertNotNull(request);
        assertTrue(request.body().contains("\"password\":\"correct horse\""));
        assertNotNull(new SecureVaultStore(targetContext()).encryptedValue("device-e2e"));
    }

    @Test
    public void connectionEditorLoadsAndSavesThroughDeviceWebView() throws Exception {
        waitFor("document.body.innerText.includes('Primary DB')");
        clickButton("编辑");
        waitFor("document.body.innerText.includes('编辑连接')");
        setLabelInput("名称", "Primary DB Mobile");
        clickButton("保存连接");

        DeviceMockDbxServer.Request request = waitForRequest("POST", "/api/mobile/connections/save");
        assertNotNull(request);
        assertTrue(request.body().contains("\"name\":\"Primary DB Mobile\""));
    }

    @Test
    public void queryCancellationDisconnectsNativeRequestAndCallsServerCancel() throws Exception {
        delayQuery = true;
        openQueryWorkbench();
        setSql("SELECT pg_sleep(30);");
        clickButton("执行查询");
        waitFor("document.body.innerText.includes('取消查询')");
        clickButton("取消查询");

        assertNotNull(waitForRequestStartingWith("DELETE", "/api/mobile/query/"));
        waitFor("document.body.innerText.includes('查询已取消')");
    }

    @Test
    public void queryExportOpensAndroidFileShareChooser() throws Exception {
        openQueryWorkbench();
        setSql("SELECT 1;");
        clickButton("执行查询");
        waitFor("document.body.innerText.includes('EXPORT')");

        Intents.init();
        intending(hasAction(Intent.ACTION_CHOOSER))
                .respondWith(new ActivityResult(Activity.RESULT_CANCELED, null));
        clickButton("EXPORT");

        intended(allOf(hasAction(Intent.ACTION_CHOOSER), hasExtraWithKey(Intent.EXTRA_INTENT)));
    }

    private DeviceMockDbxServer.Response dispatch(DeviceMockDbxServer.Request request) {
        String path = request.path();
        if (path.startsWith("/api/auth/check")) {
            return DeviceMockDbxServer.Response.json(
                    "{\"authenticated\":" + authenticated + ",\"required\":true,\"setup_required\":false}");
        }
        if (path.equals("/api/auth/mobile-login")) {
            authenticated = true;
            return DeviceMockDbxServer.Response.json(
                    "{\"token\":\"device-token\",\"expiresAt\":\"2099-01-01T00:00:00Z\"}");
        }
        if (path.startsWith("/api/mobile/connections/conn-1")) {
            return DeviceMockDbxServer.Response.json(connectionEditorJson());
        }
        if (path.equals("/api/mobile/connections/save")) {
            return DeviceMockDbxServer.Response.json(connectionSummaryJson("Primary DB Mobile"));
        }
        if (path.startsWith("/api/mobile/connections")) {
            return DeviceMockDbxServer.Response.json("[" + connectionSummaryJson("Primary DB") + "]");
        }
        if (path.startsWith("/api/schema/databases")) {
            return DeviceMockDbxServer.Response.json("[{\"name\":\"app\"}]");
        }
        if (path.startsWith("/api/schema/schemas")) {
            return DeviceMockDbxServer.Response.json("[\"public\"]");
        }
        if (path.startsWith("/api/schema/tables")) {
            return DeviceMockDbxServer.Response.json("[]");
        }
        if (path.startsWith("/api/mobile/query/") && request.method().equals("DELETE")) {
            return DeviceMockDbxServer.Response.json("{\"cancelled\":true}");
        }
        if (path.equals("/api/mobile/query") && request.method().equals("POST")) {
            return DeviceMockDbxServer.Response.json(
                    "{\"columns\":[\"value\"],\"rows\":[[1]],\"affected_rows\":0,"
                            + "\"execution_time_ms\":2,\"truncated\":false,\"has_more\":false}",
                    delayQuery ? 10_000 : 0);
        }
        return new DeviceMockDbxServer.Response(404, "{\"error\":\"not found\"}", 0);
    }

    private String connectionSummaryJson(String name) {
        return "{\"id\":\"conn-1\",\"name\":\"" + name + "\",\"note\":\"device test\","
                + "\"dbType\":\"postgres\",\"host\":\"127.0.0.1\",\"port\":5432,\"database\":\"app\","
                + "\"color\":\"#c7ff3d\",\"ssl\":false,\"readOnly\":true,\"isProduction\":false,"
                + "\"connectTimeoutSecs\":10,\"queryTimeoutSecs\":30,\"hasProxy\":false,"
                + "\"hasCaCertificate\":false}";
    }

    private String connectionEditorJson() {
        return "{\"id\":\"conn-1\",\"name\":\"Primary DB\",\"note\":\"device test\","
                + "\"dbType\":\"postgres\",\"host\":\"127.0.0.1\",\"port\":5432,\"database\":\"app\","
                + "\"color\":\"#c7ff3d\",\"ssl\":false,\"readOnly\":true,\"isProduction\":false,"
                + "\"connectTimeoutSecs\":10,\"queryTimeoutSecs\":30,\"hasProxy\":false,"
                + "\"hasCaCertificate\":false,\"hasPassword\":true,\"username\":\"dbx\","
                + "\"idleTimeoutSecs\":60,\"keepaliveIntervalSecs\":30,\"caCertPath\":\"\","
                + "\"clientCertPath\":\"\",\"clientKeyPath\":\"\",\"proxyEnabled\":false,"
                + "\"proxyType\":\"socks5\",\"proxyHost\":\"\",\"proxyPort\":1080,"
                + "\"proxyUsername\":\"\",\"hasProxyPassword\":false}";
    }

    private void installProfileAndReload() throws Exception {
        JSONObject profile = new JSONObject();
        profile.put("id", "device-e2e");
        profile.put("name", "Device E2E");
        profile.put("baseUrl", server.baseUrl());
        profile.put("network", new JSONObject()
                .put("requestTimeoutMs", 30_000)
                .put("proxyUrl", "")
                .put("certificatePin", "")
                .put("allowInvalidCertificate", false));
        JSONObject state = new JSONObject()
                .put("activeId", "device-e2e")
                .put("profiles", new org.json.JSONArray().put(profile));
        eval("localStorage.setItem('dbx-mobile.server-profiles.v2',"
                + JSONObject.quote(state.toString()) + ");location.reload();true");
        waitFor("document.readyState === 'complete' && document.body.innerText.includes('Device E2E')");
    }

    private void openQueryWorkbench() throws Exception {
        waitFor("document.body.innerText.includes('Primary DB')");
        clickButton("查询");
        waitFor("document.querySelector('.context-grid select') !== null");
        eval("(() => { const select=document.querySelector('.context-grid select');"
                + "select.value='conn-1';select.dispatchEvent(new Event('change',{bubbles:true}));return true; })()");
        waitFor("[...document.querySelectorAll('button')].some(b => "
                + "b.textContent.includes('执行查询') && !b.disabled)");
        waitFor("document.querySelector('textarea') !== null");
    }

    private void setSql(String sql) throws Exception {
        eval("(() => { const input=document.querySelector('textarea');input.value=" + JSONObject.quote(sql)
                + ";input.dispatchEvent(new Event('input',{bubbles:true}));return true; })()");
    }

    private void setLabelInput(String label, String value) throws Exception {
        String script = "(() => { const label=[...document.querySelectorAll('label')].find(l => "
                + "l.querySelector('span')?.textContent.trim()===" + JSONObject.quote(label) + ");"
                + "const input=label?.querySelector('input');if(!input)return false;input.value="
                + JSONObject.quote(value)
                + ";input.dispatchEvent(new Event('input',{bubbles:true}));return true; })()";
        assertTrue(Boolean.parseBoolean(eval(script)));
    }

    private void clickButton(String text) throws Exception {
        String script = "(() => { const button=[...document.querySelectorAll('button')].find(b => "
                + "b.textContent.includes(" + JSONObject.quote(text) + "));"
                + "if(!button)return false;button.click();return true; })()";
        assertTrue(Boolean.parseBoolean(eval(script)));
    }

    private DeviceMockDbxServer.Request waitForRequest(String method, String path) throws Exception {
        long deadline = System.currentTimeMillis() + UI_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            DeviceMockDbxServer.Request request = server.last(method, path);
            if (request != null) return request;
            Thread.sleep(50);
        }
        return null;
    }

    private DeviceMockDbxServer.Request waitForRequestStartingWith(String method, String pathPrefix) throws Exception {
        long deadline = System.currentTimeMillis() + UI_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            DeviceMockDbxServer.Request request = server.lastStartingWith(method, pathPrefix);
            if (request != null) return request;
            Thread.sleep(50);
        }
        return null;
    }

    private void waitFor(String expression) throws Exception {
        long deadline = System.currentTimeMillis() + UI_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.parseBoolean(eval(expression))) return;
            Thread.sleep(75);
        }
        throw new AssertionError("Timed out waiting for WebView condition: " + expression);
    }

    private String eval(String javascript) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        String[] result = new String[1];
        scenario.onActivity(activity -> {
            WebView webView = activity.getBridge().getWebView();
            webView.evaluateJavascript(javascript, value -> {
                result[0] = value;
                latch.countDown();
            });
        });
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("WebView JavaScript timed out: " + javascript);
        }
        if (result[0] == null || result[0].equals("null")) return "";
        if (result[0].startsWith("\"")) {
            return new org.json.JSONArray("[" + result[0] + "]").getString(0);
        }
        return result[0];
    }

    private android.content.Context targetContext() {
        return androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation()
                .getTargetContext();
    }
}
