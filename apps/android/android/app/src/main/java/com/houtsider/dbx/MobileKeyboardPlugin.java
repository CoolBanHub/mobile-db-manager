package com.houtsider.dbx;

import android.content.Context;
import android.view.inputmethod.InputMethodManager;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "MobileKeyboard")
public class MobileKeyboardPlugin extends Plugin {
    @PluginMethod
    public void show(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            getBridge().getWebView().requestFocus();
            InputMethodManager keyboard =
                    (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (keyboard == null) {
                call.reject("Android input method service is unavailable");
                return;
            }
            keyboard.showSoftInput(getBridge().getWebView(), InputMethodManager.SHOW_IMPLICIT);
            call.resolve();
        });
    }
}
