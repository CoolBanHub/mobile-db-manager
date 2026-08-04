package com.coolbanhub.mobiledbmanager;

import android.os.Build;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        registerPlugin(DirectDatabasePlugin.class);
        registerPlugin(MobileKeyboardPlugin.class);
        super.onCreate(savedInstanceState);
        applyEdgeToEdgeInsets();
    }

    private void applyEdgeToEdgeInsets() {
        // Android 15 强制 edge-to-edge；只在受影响版本补系统栏内边距，避免旧系统重复留白。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return;

        View content = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(content);
    }
}
