package com.cliproxy.launcher;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

/**
 * Checks GitHub for a newer CLIProxyAPI release and installs the
 * linux_aarch64 (plugin-enabled) build over the rootfs backend.
 */
final class Updater {

    private static final String TAG = "Updater";

    private static final String RELEASES_LATEST =
            "https://api.github.com/repos/router-for-me/CLIProxyAPI/releases/latest";
    private static final String RELEASES_LATEST_REDIRECT =
            "https://github.com/router-for-me/CLIProxyAPI/releases/latest";
    private static final String DOWNLOAD_BASE =
            "https://github.com/router-for-me/CLIProxyAPI/releases/download/";
    private static final String ASSET_SUFFIX = "_linux_aarch64.tar.gz";

    /**
     * The management control panel (the WebUI / "frontend") is published as its
     * own release asset by a separate repository, independent of the backend
     * binary. Updating only the binary would leave the panel stale, so we pull
     * it from here as well.
     */
    private static final String PANEL_RELEASES_LATEST =
            "https://api.github.com/repos/router-for-me/Cli-Proxy-API-Management-Center/releases/latest";
    private static final String PANEL_ASSET_NAME = "management.html";

    private Updater() {
    }

    static final class Release {
        final String version;      // e.g. 7.3.9
        final String assetName;    // CLIProxyAPI_7.3.9_linux_aarch64.tar.gz
        final String downloadUrl;
        final long size;

        Release(String version, String assetName, String downloadUrl, long size) {
            this.version = version;
            this.assetName = assetName;
            this.downloadUrl = downloadUrl;
            this.size = size;
        }
    }

    /**
     * Resolves the latest release. Uses the unauthenticated releases/latest
     * redirect first (no API rate limit); falls back to the GitHub API.
     */
    static Release fetchLatest() throws IOException {
        Release viaRedirect = fetchViaRedirect();
        if (viaRedirect != null) {
            return viaRedirect;
        }
        return fetchViaApi();
    }

    private static Release fetchViaRedirect() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(RELEASES_LATEST_REDIRECT);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", "CLIProxyLauncher");
            int code = conn.getResponseCode();
            if (code < 300 || code >= 400) {
                return null;
            }
            String location = conn.getHeaderField("Location");
            if (location == null) {
                return null;
            }
            int idx = location.lastIndexOf('/');
            if (idx < 0) {
                return null;
            }
            String tag = location.substring(idx + 1);
            String version = tag.startsWith("v") ? tag.substring(1) : tag;
            if (version.isEmpty()) {
                return null;
            }
            String asset = "CLIProxyAPI_" + version + ASSET_SUFFIX;
            return new Release(version, asset, DOWNLOAD_BASE + tag + "/" + asset, 0);
        } catch (Exception e) {
            Log.w(TAG, "fetchViaRedirect failed: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** Returns the latest release info, or null if none found. */
    static Release fetchViaApi() throws IOException {
        HttpURLConnection conn = open(RELEASES_LATEST);
        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IOException("GitHub HTTP " + code);
        }
        String body = readAll(conn.getInputStream());
        conn.disconnect();
        try {
            JSONObject json = new JSONObject(body);
            String tag = json.optString("tag_name", "");
            String version = tag.startsWith("v") ? tag.substring(1) : tag;
            JSONArray assets = json.optJSONArray("assets");
            if (assets == null) {
                return null;
            }
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String name = a.optString("name", "");
                if (name.endsWith(ASSET_SUFFIX)) {
                    return new Release(
                            version,
                            name,
                            a.optString("browser_download_url", ""),
                            a.optLong("size", 0));
                }
            }
        } catch (Exception e) {
            throw new IOException("parse release: " + e.getMessage());
        }
        return null;
    }

    /** Downloads the release tarball and extracts the backend binary in place. */
    static void downloadAndInstall(Release release, RuntimeEnv env, Progress cb) throws IOException {
        File tmpTar = new File(env.prootDir(), "update.tar.gz");
        File tmpBin = new File(env.prootDir(), "cli-proxy-api.new");

        try {
            download(release.downloadUrl, tmpTar, cb);
            extractBackendBinary(tmpTar, tmpBin);
            if (!tmpBin.exists() || tmpBin.length() < 1024 * 1024) {
                throw new IOException("下载的包中未找到有效的后端二进制");
            }
            // Overwrite the backend inside the rootfs.
            File target = new File(env.cpaDir(), "cli-proxy-api");
            copy(tmpBin, target);
            target.setExecutable(true, false);

            // The WebUI panel is a separate release asset; refresh it too so the
            // "frontend" actually changes after an in-app update.
            try {
                updatePanel(env);
            } catch (Exception e) {
                Log.w(TAG, "panel refresh after backend update failed: " + e.getMessage());
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmpTar.delete();
            //noinspection ResultOfMethodCallIgnored
            tmpBin.delete();
        }
    }

    /** Result of a panel check: {@code updated} is true only when it really changed. */
    static final class PanelResult {
        final String version;   // remote panel version, or null when unknown
        final boolean updated;

        PanelResult(String version, boolean updated) {
            this.version = version;
            this.updated = updated;
        }
    }

    /**
     * Installs the management control panel when the published one is newer than
     * the installed one.
     *
     * <p>The panel is distributed separately from the backend binary (its own
     * repository / release), so this is what actually makes the WebUI "frontend"
     * reflect an update. When the installed panel is already current nothing is
     * downloaded and {@code updated} stays false — the caller must not restart
     * the backend in that case. On failure the existing panel is kept.
     */
    static PanelResult updatePanel(RuntimeEnv env) {
        HttpURLConnection conn = null;
        File tmp = new File(env.cpaDir(), "management.html.new");
        String version = null;
        try {
            File staticDir = new File(env.cpaDir(), "static");
            if (!staticDir.exists() && !staticDir.mkdirs()) {
                Log.w(TAG, "panel: cannot create static dir");
                return new PanelResult(null, false);
            }

            conn = open(PANEL_RELEASES_LATEST);
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "panel release HTTP " + code);
                return new PanelResult(null, false);
            }
            String body = readAll(conn.getInputStream());
            JSONObject json = new JSONObject(body);
            String tag = json.optString("tag_name", "");
            version = tag.startsWith("v") ? tag.substring(1) : tag;

            String installed = env.currentPanelVersion();
            if (!installed.isEmpty() && !isNewer(installed, version)) {
                Log.i(TAG, "panel already current: " + installed);
                return new PanelResult(version, false);
            }

            String url = null;
            JSONArray assets = json.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    String name = a.optString("name", "");
                    if (PANEL_ASSET_NAME.equals(name) || name.endsWith(".html")) {
                        url = a.optString("browser_download_url", "");
                        break;
                    }
                }
            }
            if (url == null || url.isEmpty()) {
                Log.w(TAG, "panel asset not found in release " + version);
                return new PanelResult(null, false);
            }

            download(url, tmp, null);
            if (!tmp.exists() || tmp.length() < 100 * 1024) {
                Log.w(TAG, "panel download too small: " + (tmp != null ? tmp.length() : 0));
                return new PanelResult(null, false);
            }

            File target = new File(staticDir, PANEL_ASSET_NAME);
            copy(tmp, target);
            env.setCurrentPanelVersion(version);
            Log.i(TAG, "panel updated to " + version);
            return new PanelResult(version, true);
        } catch (Exception e) {
            Log.w(TAG, "updatePanel failed: " + e.getMessage());
            return new PanelResult(version, false);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    interface Progress {
        void onProgress(long downloaded, long total);
    }

    private static void download(String url, File dest, Progress cb) throws IOException {
        HttpURLConnection conn = open(url);
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("下载失败 HTTP " + code);
            }
            long total = conn.getContentLengthLong();
            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[128 * 1024];
                long done = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (cb != null) {
                        cb.onProgress(done, total);
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Extracts the first entry ending in /cli-proxy-api (or named cli-proxy-api) from the tar.gz. */
    private static void extractBackendBinary(File tarGz, File dest) throws IOException {
        try (InputStream fin = new java.io.FileInputStream(tarGz);
             GZIPInputStream gz = new GZIPInputStream(new BufferedInputStream(fin))) {
            byte[] header = new byte[512];
            while (true) {
                if (!readFully(gz, header, 512)) {
                    break;
                }
                if (isZeroBlock(header)) {
                    break;
                }
                String name = readName(header);
                long size = readOctal(header, 124, 12);

                String base = name;
                int slash = base.lastIndexOf('/');
                if (slash >= 0) {
                    base = base.substring(slash + 1);
                }
                boolean isTarget = base.equals("cli-proxy-api") && !name.endsWith("/");

                long remaining = size;
                if (isTarget) {
                    try (FileOutputStream out = new FileOutputStream(dest)) {
                        copyExactly(gz, out, remaining, null);
                    }
                } else {
                    copyExactly(gz, null, remaining, null);
                }
                // Skip padding to 512-byte boundary.
                long pad = (512 - (size % 512)) % 512;
                if (pad > 0) {
                    copyExactly(gz, null, pad, null);
                }
                if (isTarget) {
                    Log.i(TAG, "extracted backend binary (" + size + " bytes)");
                    return;
                }
            }
        }
        throw new IOException("tar 中未找到 cli-proxy-api");
    }

    private static void copyExactly(InputStream in, java.io.OutputStream out,
                                    long count, byte[] unused) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long left = count;
        while (left > 0) {
            int want = (int) Math.min(buf.length, left);
            int n = in.read(buf, 0, want);
            if (n < 0) {
                throw new IOException("tar 意外结束");
            }
            if (out != null) {
                out.write(buf, 0, n);
            }
            left -= n;
        }
    }

    private static boolean readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) {
                return off > 0;
            }
            off += n;
        }
        return true;
    }

    private static boolean isZeroBlock(byte[] b) {
        for (byte value : b) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static String readName(byte[] header) {
        return readString(header, 0, 100);
    }

    private static String readString(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) {
            end++;
        }
        return new String(b, off, end - off);
    }

    private static long readOctal(byte[] b, int off, int len) {
        String s = readString(b, off, len).trim();
        if (s.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(s, 8);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void copy(File src, File dest) throws IOException {
        File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
        try (InputStream in = new java.io.FileInputStream(src);
             FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        if (dest.exists() && !dest.delete()) {
            throw new IOException("无法替换旧后端");
        }
        if (!tmp.renameTo(dest)) {
            // Fallback: copy over.
            try (InputStream in = new java.io.FileInputStream(tmp);
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[128 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "CLIProxyLauncher");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        return conn;
    }

    private static String readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        try {
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return new String(bos.toByteArray(), "UTF-8");
    }

    /** Compares dotted numeric versions; returns true if candidate &gt; current. */
    static boolean isNewer(String current, String candidate) {
        int[] a = parse(current);
        int[] b = parse(candidate);
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (y > x) return true;
            if (y < x) return false;
        }
        return false;
    }

    private static int[] parse(String v) {
        if (v == null) {
            return new int[0];
        }
        String clean = v.trim();
        if (clean.startsWith("v") || clean.startsWith("V")) {
            clean = clean.substring(1);
        }
        String[] parts = clean.split("[.\\-+]");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].replaceAll("\\D", ""));
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }
}
