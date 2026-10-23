package com.cliproxy.launcher;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements WebAppInterface.Callbacks {

    private static final String TAG = "CLIProxyActivity";
    private static final String BASE_URL = "http://127.0.0.1:8317/management.html";
    private static final String INJECT_JS = "injected.js";
    private static final int PORT = 8317;

    private WebView webView;
    private View loading;
    private TextView loadingText;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean loaded;
    /** Set after an update so the next load clears the WebView cache and busts the URL. */
    private boolean refreshFrontend;
    private String panelBust = "0";

    private BroadcastReceiver stateReceiver;
    private ActivityResultLauncher<Intent> pluginPicker;
    private ActivityResultLauncher<Intent> filePicker;
    private android.webkit.ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Immersive.apply(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        webView = findViewById(R.id.webview);
        loading = findViewById(R.id.loading);
        loadingText = findViewById(R.id.loadingText);

        pluginPicker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            importPluginFrom(uri);
                        }
                    }
                });

        filePicker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (fileCallback == null) {
                        return;
                    }
                    Uri[] uris = null;
                    Intent data = result.getData();
                    if (result.getResultCode() == RESULT_OK && data != null) {
                        if (data.getClipData() != null) {
                            int n = data.getClipData().getItemCount();
                            uris = new Uri[n];
                            for (int i = 0; i < n; i++) {
                                uris[i] = data.getClipData().getItemAt(i).getUri();
                            }
                        } else if (data.getData() != null) {
                            uris = new Uri[]{data.getData()};
                        }
                    }
                    fileCallback.onReceiveValue(uris);
                    fileCallback = null;
                });

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.addJavascriptInterface(new WebAppInterface(this), "NativeBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage msg) {
                Log.i("CLIProxyWeb", msg.message() + " @" + msg.lineNumber());
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView view,
                                             android.webkit.ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    // Let the user pick any installed file manager.
                    filePicker.launch(Intent.createChooser(intent, getString(R.string.choose_file)));
                    return true;
                } catch (Exception e) {
                    Log.e(TAG, "onShowFileChooser failed", e);
                    fileCallback = null;
                    return false;
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                loading.setVisibility(View.GONE);
                injectMenuScript();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String host = uri.getHost();
                if ("127.0.0.1".equals(host) || "localhost".equals(host)) {
                    return false;
                }
                openInInternalBrowser(uri);
                return true;
            }
        });

        stateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean running = intent.getBooleanExtra(ServerService.EXTRA_RUNNING, false);
                if (running) {
                    waitForServerAndLoad();
                }
            }
        };
        IntentFilter filter = new IntentFilter(ServerService.BROADCAST_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }

        startService(ServerService.ACTION_START);
        waitForServerAndLoad();
    }

    private void injectMenuScript() {
        io.execute(() -> {
            try (InputStream in = getAssets().open(INJECT_JS)) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                String js = new String(bos.toByteArray(), "UTF-8");
                handler.post(() -> {
                    webView.evaluateJavascript(js, null);
                    webView.postDelayed(() -> webView.evaluateJavascript(js, null), 1500);
                });
            } catch (IOException e) {
                Log.w(TAG, "failed to load injected.js", e);
            }
        });
    }

    private void startService(String action) {
        Intent svc = new Intent(this, ServerService.class);
        svc.setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_reload) {
            // Re-fetch the panel without touching backend (busts WebView cache).
            loadPanel(true);
            return true;
        } else if (id == R.id.action_restart) {
            restartBackendAndReload();
            return true;
        } else if (id == R.id.action_check_update) {
            checkForUpdate();
            return true;
        } else if (id == R.id.action_lan) {
            showLanDialog();
            return true;
        } else if (id == R.id.action_battery) {
            showBatteryDialog();
            return true;
        } else if (id == R.id.action_about) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.menu_about)
                    .setMessage(R.string.about_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void checkForUpdate() {
        final RuntimeEnv env = new RuntimeEnv(this);
        Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            try {
                Updater.Release release = Updater.fetchLatest();
                if (release == null) {
                    handler.post(() -> Toast.makeText(this,
                            getString(R.string.update_failed, "未找到发布包"),
                            Toast.LENGTH_LONG).show());
                    return;
                }
                String current = env.currentVersion();
                if (!Updater.isNewer(current, release.version)) {
                    // The backend is already current; the WebUI panel ships
                    // separately and may still be behind. Only touch the service
                    // when the panel actually changed.
                    final String cur = current;
                    final Updater.PanelResult panel = Updater.updatePanel(env);
                    handler.post(() -> {
                        if (panel.updated) {
                            Toast.makeText(this,
                                    getString(R.string.update_panel_only, cur, panel.version),
                                    Toast.LENGTH_LONG).show();
                            refreshFrontend = true;
                            restartBackendAndReload();
                        } else if (panel.version != null) {
                            Toast.makeText(this,
                                    getString(R.string.update_latest, cur),
                                    Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(this,
                                    getString(R.string.update_failed, "无法获取管理面板版本"),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                    return;
                }
                handler.post(() -> confirmUpdate(env, release, current));
            } catch (Exception e) {
                Log.e(TAG, "check update failed", e);
                handler.post(() -> Toast.makeText(this,
                        getString(R.string.update_failed, e.getMessage()),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private void confirmUpdate(RuntimeEnv env, Updater.Release release, String current) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_check_update)
                .setMessage(getString(R.string.update_available, release.version, current))
                .setPositiveButton(R.string.update_confirm, (d, w) -> startUpdate(env, release))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startUpdate(RuntimeEnv env, Updater.Release release) {
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle(R.string.menu_check_update)
                .setMessage(getString(R.string.update_downloading, 0))
                .setCancelable(false)
                .create();
        progress.show();

        io.execute(() -> {
            try {
                Updater.downloadAndInstall(release, env, (done, total) -> {
                    if (total > 0) {
                        int pct = (int) (done * 100 / total);
                        handler.post(() -> progress.setMessage(
                                getString(R.string.update_downloading, pct)));
                    }
                });
                env.setCurrentVersion(release.version);
                handler.post(() -> {
                    progress.setMessage(getString(R.string.update_done));
                    progress.dismiss();
                    Toast.makeText(this, R.string.update_done, Toast.LENGTH_LONG).show();
                    // Force the WebView to drop the cached panel and re-fetch it,
                    // otherwise the updated frontend would not be shown.
                    refreshFrontend = true;
                    restartBackendAndReload();
                });
            } catch (Exception e) {
                Log.e(TAG, "update failed", e);
                handler.post(() -> {
                    progress.dismiss();
                    Toast.makeText(this,
                            getString(R.string.update_failed, e.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ---- WebAppInterface.Callbacks ----

    @Override
    public void onImportPlugin() {
        handler.post(() -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/octet-stream", "application/zip", "application/x-zip-compressed", "*/*"});
            try {
                pluginPicker.launch(Intent.createChooser(intent, getString(R.string.choose_plugin)));
            } catch (Exception e) {
                Log.e(TAG, "plugin chooser failed", e);
                Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void importPluginFrom(Uri uri) {
        String name = queryName(uri);
        if (name == null) {
            Toast.makeText(this, "无法读取所选文件", Toast.LENGTH_LONG).show();
            return;
        }
        String lower = name.toLowerCase();
        boolean isZip = lower.endsWith(".zip");
        if (!lower.endsWith(".so") && !isZip) {
            Toast.makeText(this, "请选择 .so 或 .zip 插件文件", Toast.LENGTH_LONG).show();
            return;
        }
        final String fileName = name;
        final boolean zip = isZip;
        io.execute(() -> {
            try {
                RuntimeEnv env = new RuntimeEnv(this);
                env.ensureReady();
                File dir = env.pluginsDir();

                File target;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException("无法读取所选文件");
                    if (zip) {
                        target = PluginImporter.extractSoFromZip(in, dir);
                    } else {
                        if (!dir.exists() && !dir.mkdirs()) {
                            throw new IOException("无法创建插件目录");
                        }
                        target = new File(dir, fileName);
                        try (FileOutputStream out = new FileOutputStream(target)) {
                            byte[] buf = new byte[64 * 1024];
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                out.write(buf, 0, n);
                            }
                        }
                        target.setExecutable(true, false);
                    }
                }

                final String id = PluginImporter.pluginId(target.getName());
                if (id.isEmpty()) {
                    throw new IOException("无法识别插件名称");
                }
                PluginImporter.enablePlugin(env.configFile(), id);

                handler.post(() -> {
                    Toast.makeText(this,
                            "插件已导入并启用: " + id + "\n正在重启服务…",
                            Toast.LENGTH_LONG).show();
                    restartBackendAndReload();
                });
            } catch (Exception e) {
                Log.e(TAG, "import plugin failed", e);
                handler.post(() -> Toast.makeText(this,
                        "导入失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private String queryName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    return c.getString(idx);
                }
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : null;
    }

    private void restartBackendAndReload() {
        loadingText.setText("正在重启服务...");
        loading.setVisibility(View.VISIBLE);
        startService(ServerService.ACTION_RESTART);
        waitForRestartThenLoad();
    }

    private void waitForRestartThenLoad() {
        new Thread(() -> {
            // Let teardown happen, then poll for the new instance (up to 60s).
            try {
                Thread.sleep(1200);
            } catch (InterruptedException e) {
                return;
            }
            boolean up = false;
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                if (isPortOpen()) {
                    up = true;
                    break;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
            boolean ready = up;
            handler.post(() -> {
                if (ready) {
                    loading.setVisibility(View.GONE);
                    loadPanel(refreshFrontend);
                } else {
                    loadingText.setText("服务重启超时，请再试一次或重启应用。");
                }
            });
        }, "restart-probe").start();
    }

    private void showLanDialog() {
        final RuntimeEnv env = new RuntimeEnv(this);
        final File config = env.configFile();
        boolean enabled = config.exists() && LocalNetwork.isEnabled(config);
        new AlertDialog.Builder(this)
                .setTitle(R.string.lan_title)
                .setMessage(R.string.lan_message)
                .setPositiveButton(enabled ? R.string.lan_disable : R.string.lan_enable,
                        (d, w) -> toggleLan(env, !enabled))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void toggleLan(RuntimeEnv env, boolean enable) {
        io.execute(() -> {
            try {
                env.ensureReady();
                LocalNetwork.setEnabled(env.configFile(), enable);
                handler.post(() -> {
                    if (enable) {
                        String ip = LocalNetwork.lanIp(this);
                        String msg = ip != null
                                ? getString(R.string.lan_enabled, ip)
                                : getString(R.string.lan_no_ip);
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.lan_title)
                                .setMessage(msg)
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                    } else {
                        Toast.makeText(this, R.string.lan_disabled, Toast.LENGTH_SHORT).show();
                    }
                    restartBackendAndReload();
                });
            } catch (Exception e) {
                Log.e(TAG, "toggle lan failed", e);
                handler.post(() -> Toast.makeText(this,
                        "设置失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void showBatteryDialog() {
        android.os.PowerManager pm =
                (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, R.string.battery_ok, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.battery_title)
                .setMessage(R.string.battery_message)
                .setPositiveButton(R.string.battery_go, (d, w) -> requestBatteryExemption())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @SuppressLint("BatteryLife")
    private void requestBatteryExemption() {
        try {
            Intent i = new Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            // Fall back to the battery optimization settings list.
            try {
                startActivity(new Intent(
                        android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                Toast.makeText(this, R.string.battery_na, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openInInternalBrowser(Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null
                || (!scheme.equals("http") && !scheme.equals("https"))) {
            // Non-web schemes (tel:, mailto:, intent:, ...) go to the system.
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception ignored) {
            }
            return;
        }
        Intent i = new Intent(this, BrowserActivity.class);
        i.putExtra(BrowserActivity.EXTRA_URL, uri.toString());
        startActivity(i);
    }

    private void waitForServerAndLoad() {
        new Thread(() -> {
            boolean up = false;
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                if (isPortOpen()) {
                    up = true;
                    break;
                }
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
            boolean ready = up;
            handler.post(() -> {
                if (ready) {
                    loaded = true;
                    loading.setVisibility(View.GONE);
                    loadPanel(refreshFrontend);
                } else {
                    loadingText.setText("服务启动超时，请查看日志或重启应用。");
                }
            });
        }, "port-probe").start();
    }

    private boolean isPortOpen() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", PORT), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Loads the management panel. When {@code clearCache} is set (after an
     * update, or a manual refresh) the WebView cache is dropped and a fresh
     * cache-busting query is used so the newest frontend is actually fetched.
     */
    private void loadPanel(boolean clearCache) {
        if (clearCache) {
            webView.clearCache(true);
            panelBust = String.valueOf(System.currentTimeMillis());
            refreshFrontend = false;
        }
        webView.loadUrl(BASE_URL + "?v=" + panelBust);
    }

    @Override
    protected void onDestroy() {
        if (stateReceiver != null) {
            unregisterReceiver(stateReceiver);
        }
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            Immersive.apply(this);
        }
    }
}
