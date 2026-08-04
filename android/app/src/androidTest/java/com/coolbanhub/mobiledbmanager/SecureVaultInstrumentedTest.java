package com.coolbanhub.mobiledbmanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SecureVaultInstrumentedTest {
    private static final String TEST_KEY = "instrumented-connection";
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final SecureVaultStore store = new SecureVaultStore(context);

    @After
    public void cleanVault() {
        store.remove(TEST_KEY);
    }

    @Test
    public void secureVaultEncryptsRoundTripsAndRemovesConnection() throws Exception {
        String profile = "{\"name\":\"orders\",\"password\":\"device-secret\"}";
        store.put(TEST_KEY, profile);

        assertEquals(profile, store.get(TEST_KEY));

        store.remove(TEST_KEY);
        assertNull(store.get(TEST_KEY));
    }
}
