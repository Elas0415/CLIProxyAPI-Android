package com.cliproxy.launcher;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Prepares and describes the embedded PRoot + glibc runtime.
 *
 * Layout under filesDir/:
 *   proot                 - Android arm64 PRoot binary
 *   loader                - PRoot loader
 *   lib/libtalloc.so.2
 *   lib/libandroid-shmem.so
 *   tmp/                  - PRoot temp dir
 *   rootfs/               - glibc rootfs (Ubuntu base, minimal)
 *     opt/cpa/
 *       cli-proxy-api     - glibc backend
 *       config.yaml
 *       static/
 *       plugins/          - user-imported glibc plugins
 *       .cli-proxy-api/   - auth data
 */
final class RuntimeEnv {

    private static final String TAG = "RuntimeEnv";

    static final String ROOT_MARKER = ".extracted-v1";

    /** Set once the runtime is known-good in this process, to skip re-checks. */
    private static volatile boolean prepared;

    private final Context ctx;
    private final File base;
    private final File rootfs;
    private final File cpaDir;

    RuntimeEnv(Context ctx) {
        this.ctx = ctx;
        this.base = new File(ctx.getFilesDir(), "runtime");
        this.rootfs = new File(base, "rootfs");
        this.cpaDir = new File(rootfs, "opt/cpa");
    }

    File cpaDir() {
        return cpaDir;
    }

    File pluginsDir() {
        return new File(cpaDir, "plugins");
    }

    File configFile() {
        return new File(cpaDir, "config.yaml");
    }

    File prootDir() {
        return base;
    }

    private File versionFile() {
        return new File(cpaDir, "cpa-version.txt");
    }

    /** Installed backend version, e.g. "7.3.8". */
    String currentVersion() {
        File f = versionFile();
        if (f.exists()) {
            try {
                String v = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
                if (!v.isEmpty()) {
                    return v;
                }
            } catch (IOException ignored) {
            }
        }
        return readBundledVersion();
    }

    void setCurrentVersion(String version) {
        try {
            if (!cpaDir.exists() && !cpaDir.mkdirs()) {
                return;
            }
            Files.write(versionFile().toPath(),
                    version.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "setCurrentVersion failed", e);
        }
    }

    private String readBundledVersion() {
        try (InputStream in = ctx.getAssets().open("cpa-version.txt")) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[256];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "0.0.0";
        }
    }

    /** Extracts everything and builds the rootfs on first run. */
    void ensureReady() throws IOException {
        File marker = new File(base, ROOT_MARKER);
        File backend = new File(cpaDir, "cli-proxy-api");

        // Fast path: everything is already in place for this APK version.
        // Extraction is idempotent but never free, so avoid walking the asset
        // tree again on every entry into the app.
        if (prepared && marker.exists() && backend.exists()
                && new File(cpaDir, "backend-sync-" + readBundledVersion()).exists()
                && new File(cpaDir, "panel-sync-" + readBundledPanelVersion()).exists()) {
            return;
        }
        long started = System.nanoTime();

        File libDir = new File(base, "lib");
        File tmpDir = new File(base, "tmp");

        if (!base.exists() && !base.mkdirs()) {
            throw new IOException("cannot create " + base);
        }
        if (!tmpDir.exists() && !tmpDir.mkdirs()) {
            throw new IOException("cannot create tmp dir");
        }
        if (!libDir.exists() && !libDir.mkdirs()) {
            throw new IOException("cannot create lib dir");
        }

        AssetManager am = ctx.getAssets();

        boolean fresh = !marker.exists();
        if (fresh) {
            Log.i(TAG, "first run: extracting runtime");
            copyAsset(am, "libtalloc.so.2", new File(libDir, "libtalloc.so.2"));
            copyAsset(am, "libandroid-shmem.so", new File(libDir, "libandroid-shmem.so"));

            // Extract rootfs FIRST (this wipes/recreates the rootfs dir).
            File tarGz = new File(base, "rootfs.bin");
            copyAsset(am, "rootfs.bin", tarGz);
            if (rootfs.exists()) {
                deleteRecursive(rootfs);
            }
            if (!rootfs.mkdirs()) {
                throw new IOException("cannot create rootfs dir");
            }
            extractTar(tarGz, rootfs);
            tarGz.delete();

            // glibc needs these shared objects to be loadable (executable).
            makeLibsExecutable(new File(rootfs, "lib"));

            // Now place the backend + config + panel into the rootfs.
            new File(cpaDir, "plugins").mkdirs();
            new File(cpaDir, "static").mkdirs();
            new File(cpaDir, ".cli-proxy-api").mkdirs();
            copyAsset(am, "backend/cli-proxy-api", new File(cpaDir, "cli-proxy-api"));
            copyAsset(am, "backend/config.yaml", configFile());
            copyAsset(am, "backend/static", new File(cpaDir, "static"));

            // PRoot requires the emulated binary to carry the exec bit.
            new File(cpaDir, "cli-proxy-api").setExecutable(true, false);

            setCurrentVersion(readBundledVersion());

            if (!marker.createNewFile()) {
                Log.w(TAG, "could not create marker");
            }
        }

        // Ensure required dirs exist even if marker present.
        pluginsDir().mkdirs();
        new File(cpaDir, ".cli-proxy-api").mkdirs();
        new File(cpaDir, "static").mkdirs();
        new File(cpaDir, "cli-proxy-api").setExecutable(true, false);

        writeResolvConf();

        // Install the backend bundled with this APK version if we have not yet.
        syncBackend();

        // Install the frontend bundled with this APK version if we have not yet.
        syncPanel();

        prepared = true;
        Log.i(TAG, "ensureReady took " + ((System.nanoTime() - started) / 1_000_000)
                + "ms (extracted=" + fresh + ")");
    }

    /**
     * Lays down the backend binary bundled with this APK, keyed on its version.
     *
     * <p>{@link #ensureReady()} only copies assets on the very first run, so
     * without this an APK upgrade would keep executing the backend extracted by
     * the initial install forever: the panel would move forward while the
     * backend stayed behind, which breaks the UI. Keying on the bundled version
     * means a newer APK replaces the backend once, while a backend later
     * updated at runtime (to a newer version) is never downgraded.
     */
    private void syncBackend() throws IOException {
        String bundled = readBundledVersion();
        if (bundled.isEmpty() || "0.0.0".equals(bundled)) {
            return;
        }
        File target = new File(cpaDir, "cli-proxy-api");
        File marker = new File(cpaDir, "backend-sync-" + bundled);

        if (marker.exists() && target.exists()) {
            return;
        }

        // Trust the version file only when it exists; if it is missing we cannot
        // assume the extracted binary is current, so fall through and copy.
        String installed = versionFile().exists() ? currentVersion() : "";
        if (target.exists() && !installed.isEmpty()
                && !Updater.isNewer(installed, bundled)) {
            createMarker(marker);
            return;
        }

        File tmp = new File(cpaDir, "cli-proxy-api.sync");
        try {
            copyAsset(ctx.getAssets(), "backend/cli-proxy-api", tmp);
            tmp.setExecutable(true, false);
            if (target.exists() && !target.delete()) {
                throw new IOException("cannot remove old backend");
            }
            if (!tmp.renameTo(target)) {
                throw new IOException("cannot install bundled backend");
            }
            target.setExecutable(true, false);
            setCurrentVersion(bundled);
            createMarker(marker);
            Log.i(TAG, "bundled backend installed: " + bundled);
        } finally {
            if (tmp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    private static void createMarker(File marker) {
        try {
            if (!marker.exists() && !marker.createNewFile()) {
                Log.w(TAG, "could not create marker " + marker.getName());
            }
        } catch (IOException e) {
            Log.w(TAG, "could not create marker " + marker.getName(), e);
        }
    }

    private File panelVersionFile() {
        return new File(cpaDir, "panel-version.txt");
    }

    /** Installed management panel version, e.g. "1.26.6". */
    String currentPanelVersion() {
        File f = panelVersionFile();
        if (f.exists()) {
            try {
                String v = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
                if (!v.isEmpty()) {
                    return v;
                }
            } catch (IOException ignored) {
            }
        }
        return readBundledPanelVersion();
    }

    void setCurrentPanelVersion(String version) {
        try {
            if (!cpaDir.exists() && !cpaDir.mkdirs()) {
                return;
            }
            Files.write(panelVersionFile().toPath(),
                    version.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "setCurrentPanelVersion failed", e);
        }
    }

    private String readBundledPanelVersion() {
        try (InputStream in = ctx.getAssets().open("panel-version.txt")) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[64];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Lays down the management panel bundled with this APK, once per bundled
     * panel version.
     *
     * <p>{@link #ensureReady()} only copies assets on the very first run, so
     * without this an app upgrade would keep serving the old, already-extracted
     * frontend. Keying on the bundled panel version means a newer APK installs
     * its newer panel, while a panel that was later updated at runtime is left
     * untouched (the marker for that version already exists).
     */
    private void syncPanel() throws IOException {
        String version = readBundledPanelVersion();
        if (version.isEmpty()) {
            return;
        }
        File marker = new File(cpaDir, "panel-sync-" + version);
        if (marker.exists()) {
            return;
        }
        copyAsset(ctx.getAssets(), "backend/static", new File(cpaDir, "static"));
        setCurrentPanelVersion(version);
        if (!marker.createNewFile()) {
            Log.w(TAG, "could not create panel sync marker");
        }
        Log.i(TAG, "bundled panel installed: " + version);
    }

    /**
     * The minimal rootfs has no /etc/resolv.conf, which makes glibc fall back to
     * 127.0.0.1:53 and fail all DNS lookups. Write a usable one, preferring the
     * DNS servers reported by the active networks.
     */
    private void writeResolvConf() throws IOException {
        List<String> servers = collectDnsServers();
        if (servers.isEmpty()) {
            servers.add("1.1.1.1");
            servers.add("8.8.8.8");
            servers.add("114.114.114.114");
        }
        StringBuilder sb = new StringBuilder();
        for (String s : servers) {
            sb.append("nameserver ").append(s).append('\n');
        }
        sb.append("options timeout:3 attempts:2\n");

        File etc = new File(rootfs, "etc");
        if (!etc.exists() && !etc.mkdirs()) {
            throw new IOException("cannot create etc dir");
        }
        Files.write(new File(etc, "resolv.conf").toPath(),
                sb.toString().getBytes(StandardCharsets.UTF_8));
        Log.i(TAG, "resolv.conf -> " + servers);
    }

    /** Best-effort discovery of system DNS servers (API 26+ via ConnectivityManager). */
    private List<String> collectDnsServers() {
        List<String> out = new ArrayList<>();
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return out;
            }
            for (android.net.Network net : cm.getAllNetworks()) {
                android.net.LinkProperties lp = cm.getLinkProperties(net);
                if (lp == null) {
                    continue;
                }
                for (java.net.InetAddress addr : lp.getDnsServers()) {
                    if (addr instanceof java.net.Inet6Address) {
                        // PRoot/glibc has no IPv6 route in this environment.
                        continue;
                    }
                    String ip = addr.getHostAddress();
                    if (ip != null && !out.contains(ip)) {
                        out.add(ip);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "collectDnsServers failed", e);
        }
        return out;
    }

    File prootBinary() {
        return new File(ctx.getApplicationInfo().nativeLibraryDir, "libproot.so");
    }
    File loaderBinary() {
        return new File(ctx.getApplicationInfo().nativeLibraryDir, "libproot_loader.so");
    }

    String[] prootCommand() {
        return new String[]{
                prootBinary().getAbsolutePath(),
                "-0",
                "-r", rootfs.getAbsolutePath(),
                "-w", "/opt/cpa",
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "/opt/cpa/cli-proxy-api"
        };
    }

    Map<String, String> prootEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("LD_LIBRARY_PATH", new File(base, "lib").getAbsolutePath());
        env.put("PROOT_LOADER", loaderBinary().getAbsolutePath());
        env.put("PROOT_TMP_DIR", new File(base, "tmp").getAbsolutePath());
        env.put("PROOT_NO_SECCOMP", "1");
        env.put("HOME", "/opt/cpa");
        env.put("TMPDIR", "/tmp");
        env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        return env;
    }

    private void extractTar(File tarGz, File dest) throws IOException {
        String tar = findTar();
        if (tar == null) {
            throw new IOException("no tar available on device");
        }
        ProcessBuilder pb = new ProcessBuilder(tar, "-xzf", tarGz.getAbsolutePath());
        pb.directory(dest);
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    Log.d(TAG, "[tar] " + line);
                }
            }
            int code = p.waitFor();
            if (code != 0) {
                throw new IOException("tar exited " + code);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("tar interrupted");
        }
    }

    private String findTar() {
        for (String cand : new String[]{"/system/bin/tar", "/system/xbin/tar", "tar"}) {
            if (cand.equals("tar") || new File(cand).canExecute()) {
                return cand;
            }
        }
        return null;
    }

    private void copyAsset(AssetManager am, String assetPath, File dest) throws IOException {
        String[] children = null;
        try {
            children = am.list(assetPath);
        } catch (IOException ignored) {
        }
        if (children != null && children.length > 0) {
            if (!dest.exists() && !dest.mkdirs()) {
                throw new IOException("cannot create dir " + dest);
            }
            for (String child : children) {
                copyAsset(am, assetPath + "/" + child, new File(dest, child));
            }
        } else {
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("cannot create dir " + parent);
            }
            try (InputStream in = am.open(assetPath);
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
        }
    }

    /** Ensures every shared object under dir is loadable (needs the exec bit). */
    private static void makeLibsExecutable(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                makeLibsExecutable(f);
            } else if (f.getName().contains(".so")) {
                f.setExecutable(true, false);
            }
        }
    }

    static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursive(c);
                }
            }
        }
        f.delete();
    }
}
