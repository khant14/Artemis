package com.limelight.utils;

import com.limelight.LimeLog;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;

// Experimental: uses root (su) to pin CPU, devfreq (DRAM/bus/GPU) and MediaTek DVFSRC/GPU clocks
// at their maximum while streaming, emulating the SoC touch boost. Original minimums are restored
// on stop. Live clock readings are exposed via status for the performance overlay.
public class RootClockBoost {
    public static volatile String status = null;

    private static final String SAVE_DIR = "/data/local/tmp/artemis_boost";
    private static final String END = "__ARTEMIS_END__";

    private static final String APPLY =
            "mkdir -p " + SAVE_DIR + "\n" +
            "for p in /sys/devices/system/cpu/cpufreq/policy*; do " +
            "  n=$(basename $p); cat $p/scaling_min_freq > " + SAVE_DIR + "/$n 2>/dev/null; " +
            "  cat $p/cpuinfo_max_freq > $p/scaling_min_freq 2>/dev/null; done\n" +
            "for d in /sys/class/devfreq/*; do " +
            "  n=df_$(basename $d); cat $d/min_freq > " + SAVE_DIR + "/$n 2>/dev/null; " +
            "  cat $d/max_freq > $d/min_freq 2>/dev/null; done\n" +
            "for d in /sys/devices/platform/*dvfsrc*/helio-dvfsrc /sys/devices/platform/soc/*dvfsrc*/helio-dvfsrc /sys/kernel/helio-dvfsrc; do " +
            "  [ -e $d/dvfsrc_req_ddr_opp ] && echo 0 > $d/dvfsrc_req_ddr_opp; " +
            "  [ -e $d/dvfsrc_req_vcore_opp ] && echo 0 > $d/dvfsrc_req_vcore_opp; done 2>/dev/null\n" +
            "[ -e /proc/gpufreqv2/fix_target_opp_index ] && echo 0 > /proc/gpufreqv2/fix_target_opp_index 2>/dev/null\n";

    private static final String REVERT =
            "for p in /sys/devices/system/cpu/cpufreq/policy*; do " +
            "  n=$(basename $p); [ -f " + SAVE_DIR + "/$n ] && cat " + SAVE_DIR + "/$n > $p/scaling_min_freq 2>/dev/null; done\n" +
            "for d in /sys/class/devfreq/*; do " +
            "  n=df_$(basename $d); [ -f " + SAVE_DIR + "/$n ] && cat " + SAVE_DIR + "/$n > $d/min_freq 2>/dev/null; done\n" +
            "for d in /sys/devices/platform/*dvfsrc*/helio-dvfsrc /sys/devices/platform/soc/*dvfsrc*/helio-dvfsrc /sys/kernel/helio-dvfsrc; do " +
            "  [ -e $d/dvfsrc_req_ddr_opp ] && echo -1 > $d/dvfsrc_req_ddr_opp; " +
            "  [ -e $d/dvfsrc_req_vcore_opp ] && echo -1 > $d/dvfsrc_req_vcore_opp; done 2>/dev/null\n" +
            "[ -e /proc/gpufreqv2/fix_target_opp_index ] && echo -1 > /proc/gpufreqv2/fix_target_opp_index 2>/dev/null\n";

    // Raw clocks: "c<policy> <cur kHz> <min kHz>" per CPU policy and "d<name> <cur Hz>" per devfreq
    private static final String READ =
            "for p in /sys/devices/system/cpu/cpufreq/policy*; do " +
            "  echo \"c${p##*policy} $(cat $p/scaling_cur_freq) $(cat $p/scaling_min_freq)\"; done; " +
            "for d in /sys/class/devfreq/*; do echo \"d$(basename $d) $(cat $d/cur_freq)\"; done; " +
            "echo " + END + "\n";

    private static final String DISCOVER =
            "id; ls /sys/class/devfreq; " +
            "for d in /sys/class/devfreq/*; do echo \"$(basename $d) min=$(cat $d/min_freq) max=$(cat $d/max_freq) gov=$(cat $d/governor)\"; done; " +
            "ls -d /proc/gpufreq* /sys/kernel/helio-dvfsrc /sys/devices/platform/*dvfsrc* /proc/perfmgr 2>/dev/null; echo " + END + "\n";

    private Process su;
    private Writer stdin;
    private BufferedReader stdout;
    private Thread thread;
    private volatile boolean running;

    public synchronized void start() {
        if (thread != null) {
            return;
        }
        running = true;
        status = "root boost: requesting su...";
        thread = new Thread(this::run, "RootClockBoost");
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    private void run() {
        try {
            su = Runtime.getRuntime().exec("su");
            stdin = new OutputStreamWriter(su.getOutputStream());
            stdout = new BufferedReader(new InputStreamReader(su.getInputStream()));
            // Never let stderr fill up and block the shell
            write("exec 2>/dev/null\n");

            String discovery = exec(DISCOVER);
            LimeLog.info("RootClockBoost discovery:\n" + discovery);
            if (!discovery.contains("uid=0")) {
                status = "root boost: su denied (" + discovery.trim().replace('\n', ' ') + ")";
                running = false;
                return;
            }

            write(APPLY);
            LimeLog.info("RootClockBoost applied");

            while (running) {
                status = formatClocks(exec(READ));
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        } catch (Exception e) {
            LimeLog.warning("RootClockBoost failed: " + e);
            status = "root boost: su unavailable (" + e.getMessage() + ")";
            running = false;
        } finally {
            try {
                if (stdin != null) {
                    write(REVERT);
                    write("exit\n");
                    su.waitFor();
                    LimeLog.info("RootClockBoost reverted");
                }
            } catch (Exception ignored) {
            } finally {
                if (su != null) {
                    su.destroy();
                }
                // Keep error messages visible in the overlay; clear only on a normal stop
                if (status == null || !status.startsWith("root boost: su")) {
                    status = null;
                }
            }
        }
    }

    private static String formatClocks(String raw) {
        StringBuilder sb = new StringBuilder("root boost (MHz):");
        for (String line : raw.split("\n")) {
            String[] f = line.trim().split(" ");
            try {
                if (f[0].startsWith("c") && f.length >= 3) {
                    sb.append(' ').append(f[0]).append('=')
                            .append(Long.parseLong(f[1]) / 1000).append('/').append(Long.parseLong(f[2]) / 1000);
                }
                else if (f[0].startsWith("d") && f.length >= 2) {
                    String name = f[0].substring(1);
                    sb.append(' ').append(name.length() > 14 ? name.substring(0, 14) : name).append('=')
                            .append(Long.parseLong(f[1]) / 1000000);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return sb.toString();
    }

    private void write(String cmd) throws Exception {
        stdin.write(cmd);
        stdin.flush();
    }

    private String exec(String cmd) throws Exception {
        write(cmd);
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = stdout.readLine()) != null && !line.equals(END)) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
