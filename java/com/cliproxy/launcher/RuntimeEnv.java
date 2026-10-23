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
