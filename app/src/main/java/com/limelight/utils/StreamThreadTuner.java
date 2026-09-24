package com.limelight.utils;

import android.os.Process;

import com.limelight.LimeLog;
import com.limelight.nvstream.jni.MoonBridge;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

// Raises scheduling priority (and optionally CPU affinity) of latency-critical streaming threads.
// Native moonlight-common-c threads are located by name via /proc/self/task.
public class StreamThreadTuner {
    public static final int PRIORITY = Process.THREAD_PRIORITY_URGENT_DISPLAY;

    public static void tuneCurrentThread(String label, boolean preferBigCores) {
        tuneThread(Process.myTid(), label, preferBigCores);
    }

    public static void tuneThread(int tid, String label, boolean preferBigCores) {
        try {
            Process.setThreadPriority(tid, PRIORITY);
        } catch (Exception e) {
            LimeLog.warning("Unable to raise priority of " + label + ": " + e.getMessage());
        }

        boolean pinned = false;
        if (preferBigCores) {
            try {
                pinned = MoonBridge.setThreadAffinityToBigCluster(tid);
            } catch (UnsatisfiedLinkError e) {
                // Native library not loaded
            }
        }

        LimeLog.info("Tuned thread " + label + " (tid " + tid + "), big cores: " + pinned);
    }

    // Tunes all threads of this process whose name starts with one of the given prefixes
    public static void tuneNamedThreads(boolean preferBigCores, String... namePrefixes) {
        File[] tasks = new File("/proc/self/task").listFiles();
        if (tasks == null) {
            return;
        }

        for (File task : tasks) {
            String name = readComm(task);
            if (name == null) {
                continue;
            }

            for (String prefix : namePrefixes) {
                if (name.startsWith(prefix)) {
                    try {
                        tuneThread(Integer.parseInt(task.getName()), name, preferBigCores);
                    } catch (NumberFormatException ignored) {}
                    break;
                }
            }
        }
    }

    private static String readComm(File task) {
        try (BufferedReader reader = new BufferedReader(new FileReader(new File(task, "comm")))) {
            String line = reader.readLine();
            return line != null ? line.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
