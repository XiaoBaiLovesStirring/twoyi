#!/system/bin/sh
# twoyi-magisk: Magisk module loader
# This script runs inside the container to load Magisk modules
# and ensure the Magisk daemon (magiskd) is running.

TAG="twoyi-magisk"

log() {
    echo "$TAG: $1"
    log -p i -t "$TAG" "$1" 2>/dev/null || true
}

error() {
    echo "$TAG: ERROR: $1"
    log -p e -t "$TAG" "$1" 2>/dev/null || true
}

log "Starting Magisk loader..."

# ============================================================
# Step 1: Ensure magiskd daemon is running
# ============================================================
MAGISKD_RUNNING=false

if pidof magiskd >/dev/null 2>&1; then
    MAGISKD_RUNNING=true
    log "magiskd is already running (PID: $(pidof magiskd))"
else
    log "magiskd is not running, attempting to start..."

    # Check if magisk binary exists
    if [ -f /sbin/magisk ]; then
        # Try to start magiskd
        /sbin/magisk --daemon 2>/dev/null &
        MAGISKD_PID=$!
        sleep 2

        if kill -0 $MAGISKD_PID 2>/dev/null; then
            MAGISKD_RUNNING=true
            echo $MAGISKD_PID > /data/adb/magisk/magiskd.pid
            log "magiskd started successfully (PID: $MAGISKD_PID)"
        else
            error "Failed to start magiskd!"
        fi
    else
        error "Magisk binary not found at /sbin/magisk"
        # Check if injection script is available
        if [ -f /data/local/tmp/magisk_inject.sh ]; then
            log "Running magisk injection script..."
            sh /data/local/tmp/magisk_inject.sh
        fi
    fi
fi

# ============================================================
# Step 2: Set up Magisk environment
# ============================================================

# Ensure /data/adb directories exist
for dir in /data/adb/magisk /data/adb/modules /data/adb/modules_update; do
    if [ ! -d "$dir" ]; then
        mkdir -p "$dir"
        chmod 755 "$dir"
    fi
done

# Set up PATH to include Magisk binaries
if echo "$PATH" | grep -q "/sbin"; then
    : # /sbin already in PATH
else
    export PATH=/sbin:/system/bin:/system/xbin:$PATH
fi

# ============================================================
# Step 3: Load Magisk modules
# ============================================================

log "Loading Magisk modules..."

# Magisk modules are stored in /data/adb/modules/
# Each module directory contains:
#   - module.prop (metadata)
#   - system/ (overlay files)
#   - post-fs-data.sh (script)
#   - service.sh (script)
#   - sepolicy.rule (SELinux policy)
#   - disable (if module is disabled)
#   - remove (if module should be removed)
#   - update (if module has an update)

MODULES_DIR="/data/adb/modules"
if [ -d "$MODULES_DIR" ]; then
    for module_dir in "$MODULES_DIR"/*/; do
        [ -d "$module_dir" ] || continue

        MODULE_ID=$(basename "$module_dir")

        # Skip if module has 'remove' flag
        if [ -f "$module_dir/remove" ]; then
            log "  Removing module: $MODULE_ID"
            rm -rf "$module_dir"
            continue
        fi

        # Skip if module is disabled
        if [ -f "$module_dir/disable" ]; then
            log "  Skipping disabled module: $MODULE_ID"
            continue
        fi

        # Skip if module has 'update' flag (will be updated)
        if [ -f "$module_dir/update" ]; then
            log "  Module has update: $MODULE_ID"
        fi

        log "  Loading module: $MODULE_ID"

        # Run post-fs-data.sh if present
        if [ -f "$module_dir/post-fs-data.sh" ]; then
            log "  Running post-fs-data.sh for $MODULE_ID"
            sh "$module_dir/post-fs-data.sh" 2>/dev/null || true
        fi

        # Apply sepolicy.rule if present
        if [ -f "$module_dir/sepolicy.rule" ]; then
            log "  Applying sepolicy rule for $MODULE_ID"
            if command -v magiskpolicy >/dev/null 2>&1; then
                magiskpolicy --live < "$module_dir/sepolicy.rule" 2>/dev/null || true
            fi
        fi

        # Mount module overlay if system directory exists
        if [ -d "$module_dir/system" ]; then
            log "  Mounting system overlay for $MODULE_ID"
            # Use bind mounts to overlay module files
            find "$module_dir/system" -type f 2>/dev/null | while read -r file; do
                target="${file#$module_dir/system}"
                target="/$target"
                if [ -f "$target" ]; then
                    mount --bind "$file" "$target" 2>/dev/null || true
                fi
            done
        fi
    done
fi

# ============================================================
# Step 4: Run service.sh scripts (post-boot)
# ============================================================

log "Running service scripts..."

# Run service.d scripts
if [ -d /data/adb/service.d ]; then
    for script in /data/adb/service.d/*.sh; do
        [ -f "$script" ] || continue
        log "  Running service script: $script"
        sh "$script" 2>/dev/null || true
    done
fi

# Run module service.sh scripts
if [ -d "$MODULES_DIR" ]; then
    for module_dir in "$MODULES_DIR"/*/; do
        [ -d "$module_dir" ] || continue
        MODULE_ID=$(basename "$module_dir")
        [ -f "$module_dir/disable" ] && continue

        if [ -f "$module_dir/service.sh" ]; then
            log "  Running service.sh for $MODULE_ID"
            sh "$module_dir/service.sh" 2>/dev/null || true
        fi
    done
fi

# ============================================================
# Step 5: Verify Magisk status
# ============================================================

log "Verifying Magisk status..."
if command -v su >/dev/null 2>&1; then
    log "  su command: AVAILABLE"
    su -c "echo 'Root access test: OK'" 2>/dev/null && \
        log "  Root access: WORKING" || \
        log "  Root access: NOT WORKING"
else
    log "  su command: NOT FOUND"
fi

if [ -f /sbin/magisk ]; then
    MAGISK_VER=$(/sbin/magisk -v 2>/dev/null || echo "unknown")
    log "  Magisk version: $MAGISK_VER"
fi

log "Magisk loader complete!"
exit 0