package com.houtsider.dbx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SecureVaultInstrumentedTest {
    private static final String TEST_KEY = "instrumented-session";
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final SecureVaultStore store = new SecureVaultStore(context);

    @After
    public void cleanVault() {
        store.remove(TEST_KEY);
    }

    @Test
    public void secureVaultEncryptsRoundTripsAndRemovesSession() throws Exception {
        String token = "dbx-device-secret-token";
        store.put(TEST_KEY, token, false);

        assertEquals(token, store.get(TEST_KEY));
        assertFalse(store.requiresUnlock(TEST_KEY));
        assertFalse(token.equals(store.encryptedValue(TEST_KEY)));

        store.remove(TEST_KEY);
        assertNull(store.get(TEST_KEY));
        assertNull(store.encryptedValue(TEST_KEY));
    }

    @Test
    public void biometricAvailabilityMatchesAndroidSecureLockState() throws Exception {
        KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        boolean expected = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                && keyguard != null
                && keyguard.isDeviceSecure();

        assertEquals(expected, SecureVaultPlugin.isSecureUnlockAvailable(context));
        store.put(TEST_KEY, "guarded-token", true);
        assertTrue(store.requiresUnlock(TEST_KEY));
    }
}
