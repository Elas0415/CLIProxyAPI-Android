package com.cliproxy.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service that runs the glibc cli-proxy-api backend inside an
 * embedded PRoot + Ubuntu glibc rootfs, so native glibc plugins can be loaded.
 *
 * All lifecycle operations are serialized on a single thread to avoid races
 * during start/restart. Teardown explicitly kills the whole backend process
 * tree so the port is always released.
 */
public class ServerService extends Service {

    static final String TAG = "CLIProxyServer";
    static final int NOTIFICATION_ID = 1001;

    static final String ACTION_START = "com.cliproxy.launcher.START";
    static final String ACTION_RESTART = "com.cliproxy.launcher.RESTART";
    static final String ACTION_STOP = "com.cliproxy.launcher.STOP";

    static final String BROADCAST_STATE = "com.cliproxy.launcher.STATE";
    static final String EXTRA_RUNNING = "running";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Process process;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        startForeground(NOTIFICATION_ID, buildNotification("正在启动..."));

        if (ACTION_STOP.equals(action)) {
            worker.execute(this::stopBackendAndUpdate);
            worker.execute(this::stopSelf);
            return START_NOT_STICKY;
        }

        if (ACTION_RESTART.equals(action)) {
            // Serialize: teardown, then start. Never concurrent with other ops.
            worker.execute(() -> {
                stopBackendAndUpdate();
                sleep(700);
                startBackend();
            });
            return START_STICKY;
        }

        worker.execute(this::startBackend);
        return START_STICKY;
    }

    private synchronized void startBackend() {
        if (running.get() && process != null && process.isAlive()) {
            return;
        }
        try {
            RuntimeEnv env = new RuntimeEnv(this);
            env.ensureReady();

            // Clear any orphaned backend left by a previous app process so the
            // port is free before we bind it again.
            ProcessKiller.killBackendProcesses();
            sleep(400);

            ProcessBuilder pb = new ProcessBuilder(env.prootCommand());
            pb.directory(env.prootDir());
            pb.redirectErrorStream(true);
            pb.environment().putAll(env.prootEnv());

            Process proc = pb.start();
            process = proc;
            running.set(true);
            Log.i(TAG, "backend started via proot");
            updateNotification("本地 API 服务已启动 (端口 8317)");
            notifyState(true);

            // Monitor on a separate thread so this executor stays free to
            // handle stop/restart requests while the backend runs.
            Thread monitor = new Thread(() -> monitorProcess(proc), "cli-proxy-monitor");
            monitor.setDaemon(true);
            monitor.start();
        } catch (Exception e) {
            Log.e(TAG, "failed to launch backend", e);
            running.set(false);
            updateNotification("启动失败: " + e.getMessage());
            notifyState(false);
        }
    }

    private void monitorProcess(Process proc) {
        pipeLogs(proc);
        int code;
        try {
            code = proc.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        Log.i(TAG, "backend exited code=" + code);
        synchronized (this) {
            if (process == proc) {
                process = null;
                running.set(false);
                notifyState(false);
                updateNotification("服务已停止 (code " + code + ")");
            }
        }
    }

    /** Releases the port reliably: graceful destroy, then hard kill by PID scan. */
    private synchronized void stopBackendAndUpdate() {
        Process proc = process;
        if (proc != null) {
            try {
                proc.destroy();
            } catch (Exception ignored) {
            }
            // Wait briefly for a clean exit, but never block forever.
            try {
                proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            process = null;
        }
        // Kill any orphaned proot / cli-proxy-api processes owned by this app.
        ProcessKiller.killBackendProcesses();
        running.set(false);
        notifyState(false);
        updateNotification("服务已停止");
    }

    private void pipeLogs(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                Log.i(TAG, "[backend] " + line);
            }
        } catch (IOException ignored) {
        }
    }

    private void notifyState(boolean isRunning) {
        Intent i = new Intent(BROADCAST_STATE);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_RUNNING, isRunning);
        sendBroadcast(i);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private Notification buildNotification(String text) {
        String channelId = "cli_proxy_status";
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    channelId,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, channelId)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    @Override
    public void onDestroy() {
        stopBackendAndUpdate();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
