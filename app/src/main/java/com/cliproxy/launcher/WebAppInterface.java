package com.cliproxy.launcher;

import android.util.Log;
import android.webkit.JavascriptInterface;

/** JS bridge exposed to the management panel web page. */
public class WebAppInterface {

    public interface Callbacks {
        void onImportPlugin();
    }

    private final Callbacks callbacks;

    public WebAppInterface(Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    @JavascriptInterface
    public void importPlugin() {
        callbacks.onImportPlugin();
    }

    @JavascriptInterface
    public void log(String message) {
        Log.i("CLIProxyWeb", message);
    }
}
