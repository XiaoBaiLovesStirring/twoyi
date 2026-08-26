#!/system/bin/sh
# twoyi-magisk: Magisk full injection script
# This script runs inside the container to set up the Magisk environment.
# It extracts binaries from the Magisk APK, sets up the directory structure,
# and starts the magiskd daemon for proper root permission management.

MAGISK_VER="27000"
MAGISK_VER_STR="2c6adbc6"
TAG="twoyi-magisk"

log() {
    echo "$TAG: $1"
    log -p i -t "$TAG" "$1" 2>/dev/null || true
}

error() {
    echo "$TAG: ERROR: $1"
    log -p e -t "$TAG" "$1" 2>/dev/null || true
}

# Check if Magisk is already installed
if [ -f /sbin/magisk ] && [ -f /sbin/su ]; then
    # Check if magiskd is already running
    if pidof magiskd >/dev/null 2>&1 || [ -f /data/adb/magisk/magiskd.pid ]; then
        log "Magisk is already installed and running"
        exit 0
    fi
    log "Magisk binaries found but daemon not running, restarting..."
fi

# Step 1: Locate the Magisk binaries
# They should be pre-extracted by the Android app into /data/local/tmp/magisk/
MAGISK_SRC="/data/local/tmp/magisk"
MAGISK_BIN="/sbin"

log "Step 1: Checking Magisk binaries at $MAGISK_SRC..."

if [ ! -d "$MAGISK_SRC" ]; then
    error "Magisk source directory not found at $MAGISK_SRC"
    error "Has the APK been extracted?"
    exit 1
fi

# Step 2: Copy Magisk binaries to system binaries directory
log "Step 2: Installing Magisk binaries to $MAGISK_BIN..."

# Mount /sbin as rw if needed
if [ ! -w "$MAGISK_BIN" ]; then
    mount -o remount,rw / 2>/dev/null || true
    mount -o remount,rw /system 2>/dev/null || true
fi

copy_binary() {
    local src="$1"
    local dst="$2"
    if [ -f "$src" ]; then
        cp -f "$src" "$dst"
        chmod 755 "$dst"
        chown 0:0 "$dst"
        log "  Installed: $dst"
    else
        error "  Missing: $src"
        return 1
    fi
}

# Determine architecture and copy appropriate binaries
ARCH=$(uname -m)
if [ "$ARCH" = "aarch64" ]; then
    MAGISK_MAIN="magisk64"
elif [ "$ARCH" = "armv7l" ] || [ "$ARCH" = "armv8l" ]; then
    MAGISK_MAIN="magisk32"
elif [ "$ARCH" = "x86_64" ]; then
    MAGISK_MAIN="magisk64"
elif [ "$ARCH" = "x86" ] || [ "$ARCH" = "i686" ]; then
    MAGISK_MAIN="magisk32"
else
    MAGISK_MAIN="magisk64"
    log "Unknown architecture: $ARCH, defaulting to 64-bit"
fi

copy_binary "$MAGISK_SRC/$MAGISK_MAIN" "$MAGISK_BIN/magisk" || exit 1
copy_binary "$MAGISK_SRC/magiskinit" "$MAGISK_BIN/magiskinit" || true
copy_binary "$MAGISK_SRC/magiskpolicy" "$MAGISK_BIN/magiskpolicy" || exit 1
copy_binary "$MAGISK_SRC/busybox" "$MAGISK_BIN/busybox" || true

# Step 3: Create su symlink
log "Step 3: Creating su symlink..."
if [ -L "$MAGISK_BIN/su" ] || [ -f "$MAGISK_BIN/su" ]; then
    rm -f "$MAGISK_BIN/su"
fi
ln -sf "$MAGISK_BIN/magisk" "$MAGISK_BIN/su"
chmod 755 "$MAGISK_BIN/su"
log "  Symlink: $MAGISK_BIN/su -> $MAGISK_BIN/magisk"

# Also create /system/bin/su and /system/xbin/su for compatibility
for dir in /system/bin /system/xbin /vendor/bin; do
    if [ -d "$dir" ]; then
        ln -sf "$MAGISK_BIN/su" "$dir/su" 2>/dev/null || true
    fi
done

# Step 4: Set up Magisk data directory structure
log "Step 4: Setting up Magisk data directories..."

# Create /data/adb directories
mkdir -p /data/adb/magisk
mkdir -p /data/adb/modules
mkdir -p /data/adb/modules_update
mkdir -p /data/adb/post-fs-data.d
mkdir -p /data/adb/service.d

chmod 755 /data/adb
chmod 755 /data/adb/magisk
chmod 755 /data/adb/modules
chmod 755 /data/adb/modules_update
chmod 755 /data/adb/post-fs-data.d
chmod 755 /data/adb/service.d

# Create Magisk database with default permissions
# This allows all apps to request root (query mode)
if [ ! -f /data/adb/magisk.db ]; then
    log "  Creating default Magisk database..."
    # Create a minimal SQLite database with default policies
    # Using a simple approach: create the file and let magiskd initialize it
    touch /data/adb/magisk.db
    chmod 755 /data/adb/magisk.db
    chcon u:object_r:magisk_file:s0 /data/adb/magisk.db 2>/dev/null || true
fi

# Create magisk configuration
if [ ! -f /data/adb/magisk/config ]; then
    cat > /data/adb/magisk/config <<'EOF'
# Magisk configuration
KEEPVERITY=true
KEEPFORCEENCRYPT=true
RECOVERYMODE=false
EOF
    chmod 644 /data/adb/magisk/config
fi

# Step 5: Set up SELinux context if available
log "Step 5: Setting up SELinux..."

# Try to set Magisk SELinux context
if command -v magiskpolicy >/dev/null 2>&1; then
    log "  Applying SELinux policy..."
    # Load the Magisk SELinux policy
    magiskpolicy --live 2>/dev/null || true
    # Allow su to work in permissive mode
    magiskpolicy --live "allow * magisk_file * *" 2>/dev/null || true
    magiskpolicy --live "allow * su * *" 2>/dev/null || true
fi

# If SELinux is enforcing, set to permissive for Magisk compatibility
SELINUX_STATUS=$(getenforce 2>/dev/null || echo "Disabled")
if [ "$SELINUX_STATUS" = "Enforcing" ]; then
    log "  SELinux is enforcing, setting to permissive..."
    setenforce 0 2>/dev/null || true
fi

# Step 6: Start magiskd daemon
log "Step 6: Starting magiskd daemon..."

# Kill any existing magiskd
if pidof magiskd >/dev/null 2>&1; then
    kill -9 $(pidof magiskd) 2>/dev/null || true
    sleep 1
fi

# Start magiskd in the background
# The magisk binary acts as a daemon when invoked as 'magiskd'
log "  Launching magiskd..."
$MAGISK_BIN/magisk --daemon 2>/dev/null &
MAGISKD_PID=$!
echo $MAGISKD_PID > /data/adb/magisk/magiskd.pid

# Wait for daemon to start
sleep 1

# Verify daemon is running
if kill -0 $MAGISKD_PID 2>/dev/null; then
    log "  magiskd started successfully (PID: $MAGISKD_PID)"
else
    # Try alternative method: run magiskd directly
    log "  Trying alternative method..."
    $MAGISK_BIN/magisk magiskd 2>/dev/null &
    MAGISKD_PID=$!
    echo $MAGISKD_PID > /data/adb/magisk/magiskd.pid
    sleep 1
    if kill -0 $MAGISKD_PID 2>/dev/null; then
        log "  magiskd started via alternative method (PID: $MAGISKD_PID)"
    else
        error "  Failed to start magiskd!"
        # Fallback: create a simple su wrapper
        log "  Creating fallback su wrapper..."
        create_fallback_su
    fi
fi

# Step 7: Verify installation
log "Step 7: Verifying Magisk installation..."

if [ -f "$MAGISK_BIN/su" ] && [ -f "$MAGISK_BIN/magisk" ]; then
    log "  Magisk binaries: OK"
fi
if pidof magiskd >/dev/null 2>&1; then
    log "  magiskd daemon: RUNNING"
else
    log "  magiskd daemon: NOT RUNNING (using fallback)"
fi

# Test su binary
if command -v su >/dev/null 2>&1; then
    log "  su command: AVAILABLE"
    su -c "echo 'Root access test: OK'" 2>/dev/null && \
        log "  Root access: WORKING" || \
        log "  Root access: NEEDS SETUP"
fi

log "Magisk injection complete!"
exit 0

# Fallback function: create a simple su wrapper if magiskd fails
create_fallback_su() {
    cat > "$MAGISK_BIN/su" <<'SUEOF'
#!/system/bin/sh
# Fallback su: directly executes commands as root
# Since the container already runs as root, this is a simple wrapper
if [ $# -eq 0 ]; then
    # Interactive shell
    exec /system/bin/sh
else
    # Execute command
    exec "$@"
fi
SUEOF
    chmod 755 "$MAGISK_BIN/su"
    chown 0:0 "$MAGISK_BIN/su"
    log "  Fallback su created at $MAGISK_BIN/su"
}