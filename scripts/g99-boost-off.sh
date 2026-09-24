#!/system/bin/sh
# Restore default MediaTek Helio G99 clock behavior after g99-boost-on.sh (requires root).
for p in /sys/devices/system/cpu/cpufreq/policy*; do
  echo "$(cat $p/cpuinfo_min_freq)" > $p/scaling_min_freq 2>/dev/null
done
for d in /sys/devices/platform/*dvfsrc*/helio-dvfsrc /sys/devices/platform/soc/*dvfsrc*/helio-dvfsrc /sys/kernel/helio-dvfsrc; do
  echo -1 > $d/dvfsrc_req_ddr_opp 2>/dev/null
  echo -1 > $d/dvfsrc_req_vcore_opp 2>/dev/null
done
echo -1 > /proc/gpufreqv2/fix_target_opp_index 2>/dev/null
echo 0 > /proc/gpufreq/gpufreq_opp_freq 2>/dev/null
echo "boost off $(date)" >> /sdcard/g99-boost.log
