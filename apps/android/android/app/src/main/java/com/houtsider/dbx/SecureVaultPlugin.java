package com.houtsider.dbx;

import android.app.KeyguardManager;
import android.content.Context;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.concurrent.Executor;

@CapacitorPlugin(name = "SecureVault")
public class SecureVaultPlugin extends Plugin {
    static boolean isSecureUnlockAvailable(Context context) {
        KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && keyguard != null && keyguard.isDeviceSecure();
    }

    private SecureVaultStore store() {
        return new SecureVaultStore(getContext());
    }

    @PluginMethod
    public void set(PluginCall call) {
        String key = call.getString("key");
        String value = call.getString("value");
        if (key == null || value == null) {
            call.reject("key and value are required");
            return;
        }
        try {
            store().put(key, value, call.getBoolean("requireUnlock", true));
            call.resolve();
        } catch (Exception error) {
            call.reject("Unable to encrypt the DBX session", error);
        }
    }

    @PluginMethod
    public void get(PluginCall call) {
        String key = call.getString("key");
        if (key == null) {
            call.reject("key is required");
            return;
        }
        SecureVaultStore store = store();
        if (store.encryptedValue(key) == null) {
            JSObject result = new JSObject();
            result.put("value", null);
            call.resolve(result);
            return;
        }
        if (!store.requiresUnlock(key)) {
            decryptAndResolve(call, key);
            return;
        }
        authenticate(call, key, call.getString("prompt", "解锁 DBX"));
    }

    private void authenticate(PluginCall call, String key, String title) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            call.reject("此 Android 版本不支持应用内安全解锁");
            return;
        }
        if (!isSecureUnlockAvailable(getContext())) {
            call.reject("请先在 Android 系统设置中启用锁屏凭据或生物识别");
            return;
        }

        Executor executor = getContext().getMainExecutor();
        BiometricPrompt.Builder builder = new BiometricPrompt.Builder(getContext())
                .setTitle(title)
                .setSubtitle("验证后读取加密的移动登录令牌");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG |
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setDeviceCredentialAllowed(true);
        } else {
            builder.setNegativeButton("取消", executor, (dialog, which) -> call.reject("已取消安全解锁"));
        }

        BiometricPrompt prompt = builder.build();
        prompt.authenticate(new CancellationSignal(), executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                decryptAndResolve(call, key);
            }

            @Override
            public void onAuthenticationError(int errorCode, CharSequence errString) {
                call.reject(errString == null ? "安全解锁失败" : errString.toString());
            }
        });
    }

    private void decryptAndResolve(PluginCall call, String key) {
        try {
            String value = store().get(key);
            if (value == null) {
                JSObject result = new JSObject();
                result.put("value", null);
                call.resolve(result);
                return;
            }
            JSObject result = new JSObject();
            result.put("value", value);
            call.resolve(result);
        } catch (Exception error) {
            call.reject("Unable to decrypt the DBX session", error);
        }
    }

    @PluginMethod
    public void remove(PluginCall call) {
        String key = call.getString("key");
        if (key == null) {
            call.reject("key is required");
            return;
        }
        store().remove(key);
        call.resolve();
    }
}
