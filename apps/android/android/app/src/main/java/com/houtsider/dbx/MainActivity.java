package com.houtsider.dbx;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        registerPlugin(SecureVaultPlugin.class);
        registerPlugin(NativeHttpPlugin.class);
        registerPlugin(DirectDatabasePlugin.class);
        registerPlugin(MobileKeyboardPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
