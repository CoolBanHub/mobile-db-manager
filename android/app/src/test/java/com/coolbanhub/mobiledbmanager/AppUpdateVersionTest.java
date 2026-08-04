package com.coolbanhub.mobiledbmanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AppUpdateVersionTest {
    @Test
    public void extractsVersionFromReleaseTags() {
        assertEquals("0.0.4", AppUpdateVersion.versionFromTag("release/v0.0.4"));
        assertEquals("1.2.3", AppUpdateVersion.versionFromTag("v1.2.3"));
        assertEquals("2.0.0", AppUpdateVersion.versionFromTag("2.0.0"));
    }

    @Test
    public void comparesSemanticVersionsNumerically() {
        assertTrue(AppUpdateVersion.compare("release/v0.0.10", "release/v0.0.9") > 0);
        assertTrue(AppUpdateVersion.compare("0.1.0", "0.0.99") > 0);
        assertEquals(0, AppUpdateVersion.compare("release/v1.2.0", "1.2"));
    }

    @Test
    public void generatesPositiveMonotonicAndroidVersionCodes() {
        assertEquals(1, AppUpdateVersion.versionCode("release/v0.0.0"));
        assertEquals(4, AppUpdateVersion.versionCode("release/v0.0.3"));
        assertEquals(1_002_004, AppUpdateVersion.versionCode("release/v1.2.3"));
    }
}
