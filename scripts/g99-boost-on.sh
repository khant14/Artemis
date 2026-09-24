#!/system/bin/sh
# Pin MediaTek Helio G99 CPU/DRAM/GPU clocks high while streaming (requires root).
# Emulates the touch boost that lowers decode latency. Undo with g99-boost-off.sh.
LOG=/sdcard/g99-boost.log
echo "boost on $(date)" > $LOG
w() { [ -e "$1" ] && echo "$2" > "$1" 2>/dev/null && echo "set $1=$2" >> $LOG; }

# CPU: floor every cluster at its max frequency
for p in /sys/devices/system/cpu/cpufreq/policy*; do
  w $p/scaling_min_freq "$(cat $p/cpuinfo_max_freq)"
done

# DRAM / vcore (video decoder clock follows these): request highest OPP
for d in /sys/devices/platform/*dvfsrc*/helio-dvfsrc /sys/devices/platform/soc/*dvfsrc*/helio-dvfsrc /sys/kernel/helio-dvfsrc; do
  w $d/dvfsrc_req_ddr_opp 0
  w $d/dvfsrc_req_vcore_opp 0
done

# GPU: fix to top OPP
w /proc/gpufreqv2/fix_target_opp_index 0
w /proc/gpufreq/gpufreq_opp_freq "$(head -1 /proc/gpufreq/gpufreq_opp_dump 2>/dev/null | sed -n 's/.*freq = \([0-9]*\).*/\1/p')"

cat $LOG
