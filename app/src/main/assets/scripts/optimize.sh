#!/system/bin/sh
# twoyi-magisk: Boot optimization script
# This script runs inside the container to optimize performance
# and prepare the environment.

# Optimize VM settings
echo "twoyi-opt: Applying VM optimizations..."
echo 10 > /proc/sys/vm/swappiness
echo 50 > /proc/sys/vm/vfs_cache_pressure
echo 1000 > /proc/sys/vm/dirty_writeback_centisecs
echo 500 > /proc/sys/vm/dirty_expire_centisecs

# Optimize I/O scheduler
echo "twoyi-opt: Optimizing I/O scheduler..."
if [ -e /sys/block/mmcblk0/queue/scheduler ]; then
    echo "cfq" > /sys/block/mmcblk0/queue/scheduler 2>/dev/null || true
fi

# Set performance governor
echo "twoyi-opt: Setting CPU governor..."
for cpu in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do
    echo "interactive" > "$cpu" 2>/dev/null || true
    echo "performance" > "$cpu" 2>/dev/null || true
done

# Reduce log spam
echo "twoyi-opt: Reducing log verbosity..."
setprop log.tag.art VERBOSE 2>/dev/null || true
setprop persist.log.tag.SU WARNING 2>/dev/null || true

# Network optimization
echo "twoyi-opt: Applying network optimizations..."
echo 0 > /proc/sys/net/ipv4/tcp_slow_start_after_idle 2>/dev/null || true

# Create Magisk module directory if it doesn't exist
if [ ! -d /data/adb/modules ]; then
    mkdir -p /data/adb/modules
    chmod 755 /data/adb/modules
fi

# Create Magisk work directory
if [ ! -d /data/adb/magisk ]; then
    mkdir -p /data/adb/magisk
    chmod 755 /data/adb/magisk
fi

# Clean up old temp files
echo "twoyi-opt: Cleaning up temp files..."
rm -rf /data/local/tmp/* 2>/dev/null || true
rm -rf /cache/* 2>/dev/null || true

echo "twoyi-opt: Optimization complete!"