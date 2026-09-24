#!/system/bin/sh
# Records CPU/DRAM/GPU clock state to find out what MediaTek touch boost changes (requires root).
# Usage: run it, switch to a running stream within 10s, keep hands off for ~20s,
# then keep a finger on the screen until it finishes (~40s total sampling).
OUT=/data/local/tmp/g99-probe.log
exec > "$OUT" 2>&1

echo "== $(date)"
id
echo "== nodes"
ls -d /proc/perfmgr /proc/ppm /sys/kernel/fpsgo /proc/gpufreq* /sys/kernel/helio-dvfsrc 2>/dev/null
ls -d /sys/devices/platform/*dvfsrc* /sys/devices/platform/soc/*dvfsrc* 2>/dev/null
for d in /sys/class/devfreq/*; do echo "devfreq $(basename $d): $(cat $d/name 2>/dev/null) avail=$(cat $d/available_frequencies 2>/dev/null)"; done
for p in /sys/devices/system/cpu/cpufreq/policy*; do echo "$(basename $p) range=$(cat $p/cpuinfo_min_freq)-$(cat $p/cpuinfo_max_freq) gov=$(cat $p/scaling_governor)"; done
DVFSRC=$(ls -d /sys/devices/platform/*dvfsrc*/helio-dvfsrc /sys/devices/platform/soc/*dvfsrc*/helio-dvfsrc /sys/kernel/helio-dvfsrc 2>/dev/null | head -1)
[ -n "$DVFSRC" ] && echo "dvfsrc dir $DVFSRC: $(ls $DVFSRC | tr '\n' ' ')"

echo "== waiting 10s, switch to the stream now"
sleep 10
START=$(date +%s)
i=0
while [ $i -lt 80 ]; do
  line="t=$(( $(date +%s) - START ))"
  for p in /sys/devices/system/cpu/cpufreq/policy*; do
    line="$line $(basename $p)=$(cat $p/scaling_cur_freq)/min$(cat $p/scaling_min_freq)"
  done
  for d in /sys/class/devfreq/*; do
    line="$line $(basename $d)=$(cat $d/cur_freq 2>/dev/null)"
  done
  g=$(head -3 /proc/gpufreqv2/gpufreq_status 2>/dev/null || head -3 /proc/gpufreq/gpufreq_var_dump 2>/dev/null)
  line="$line gpu=[$(echo $g | tr -s ' ' | cut -c1-120)]"
  [ -n "$DVFSRC" ] && line="$line dvfsrc=[$(cat $DVFSRC/dvfsrc_dump 2>/dev/null | grep -iE 'vcore|ddr|opp' | head -4 | tr -s ' \n' ' ' | cut -c1-160)]"
  echo "$line"
  sleep 0.5
  i=$((i + 1))
done
echo "== done $(date)"
cp "$OUT" /data/media/0/g99-probe.log 2>/dev/null
