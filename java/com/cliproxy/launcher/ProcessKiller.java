package com.cliproxy.launcher;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds and kills backend processes (proot / cli-proxy-api) owned by this app's
 * UID, by scanning /proc. This guarantees the TCP port is released even when a
 * PRoot wrapper is killed abruptly and leaves its child orphaned.
 */
final class ProcessKiller {

    private static final String TAG = "ProcessKiller";

    private ProcessKiller() {
    }

    static void killBackendProcesses() {
        int myUid = android.os.Process.myUid();
        List<Integer> pids = findPids(myUid);
        for (int pid : pids) {
            if (pid == android.os.Process.myPid()) {
                continue;
            }
            try {
                android.os.Process.sendSignal(pid, android.os.Process.SIGNAL_KILL);
                Log.i(TAG, "killed pid " + pid);
            } catch (Exception e) {
                Log.w(TAG, "kill pid " + pid + " failed: " + e.getMessage());
            }
        }
    }

    private static List<Integer> findPids(int uid) {
        List<Integer> out = new ArrayList<>();
        File proc = new File("/proc");
        File[] entries = proc.listFiles();
        if (entries == null) {
            return out;
        }
        for (File entry : entries) {
            if (!entry.isDirectory()) {
                continue;
            }
            String name = entry.getName();
            if (name.isEmpty() || !Character.isDigit(name.charAt(0))) {
                continue;
            }
            int pid;
            try {
                pid = Integer.parseInt(name);
            } catch (NumberFormatException e) {
                continue;
            }
            try {
                String status = new String(
                        Files.readAllBytes(new File(entry, "status").toPath()),
                        StandardCharsets.UTF_8);
                if (!status.contains("Uid:\t" + uid) && !status.contains("Uid:\t" + uid + "\t")) {
                    continue;
                }
                String cmdline = new String(
                        Files.readAllBytes(new File(entry, "cmdline").toPath()),
                        StandardCharsets.UTF_8);
                if (cmdline.contains("cli-proxy-api") || cmdline.contains("libproot")) {
                    out.add(pid);
                }
            } catch (IOException ignored) {
                // Process may have exited while we were reading.
            }
        }
        return out;
    }
}
