#!/system/bin/sh
# twoyi-magisk: Magisk pre-init script
# This script is called before the container's Android system starts
# to prepare Magisk integration.

MAGISK_DIR="/data/adb"
MAGISK_MODULES_DIR="${MAGISK_DIR}/modules"
MAGISK_WORK_DIR="${MAGISK_DIR}/magisk"

# Magisk module loading script
# Each module in /data/adb/modules/ that has a post-fs-data.sh or service.sh
# will be executed accordingly.

mount_magisk_overlay() {
    # Check if overlay filesystem is available
    if [ ! -e /proc/filesystems ] || ! grep -q "overlay" /proc/filesystems; then
        echo "twoyi-magisk: Overlay filesystem not available, skipping overlay mount"
        return 1
    fi

    local module_id="$1"
    local module_dir="${MAGISK_MODULES_DIR}/${module_id}"
    
    if [ ! -d "${module_dir}" ] || [ -f "${module_dir}/disable" ]; then
        return 1
    fi

    echo "twoyi-magisk: Mounting overlay for module: ${module_id}"

    # Remove file means module should be removed
    if [ -f "${module_dir}/remove" ]; then
        echo "twoyi-magisk: Module ${module_id} marked for removal"
        rm -rf "${module_dir}"
        return 1
    fi

    local module_overlay="${module_dir}/system"
    if [ -d "${module_overlay}" ]; then
        # Mount module overlay using magic mount (bind mounts)
        find "${module_overlay}" -type f -o -type l | while read -r file; do
            local target="${file#${module_overlay}}"
            local target_path="/system/${target}"
            if [ -e "${target_path}" ]; then
                local backup_path="${MAGISK_WORK_DIR}/backup/${target}"
                mkdir -p "$(dirname "${backup_path}")" 2>/dev/null
                cp -a "${target_path}" "${backup_path}" 2>/dev/null
                chmod 644 "${backup_path}" 2>/dev/null
                mount -o bind "${file}" "${target_path}" 2>/dev/null
                echo "twoyi-magisk: Mounted ${file} -> ${target_path}"
            fi
        done
    fi
}

load_modules() {
    echo "twoyi-magisk: Loading Magisk modules..."
    
    if [ ! -d "${MAGISK_MODULES_DIR}" ]; then
        echo "twoyi-magisk: No modules directory found"
        return
    fi

    for module_dir in "${MAGISK_MODULES_DIR}"/*/; do
        [ -d "${module_dir}" ] || continue
        
        local module_id
        module_id=$(basename "${module_dir}")
        
        # Skip disabled modules
        [ -f "${module_dir}/disable" ] && continue
        
        # Mount overlay if module has system directory
        mount_magisk_overlay "${module_id}"
        
        # Execute post-fs-data.sh if exists
        local post_fs_data="${module_dir}/post-fs-data.sh"
        if [ -f "${post_fs_data}" ]; then
            echo "twoyi-magisk: Running post-fs-data.sh for ${module_id}"
            sh "${post_fs_data}" 2>&1 | sed 's/^/twoyi-magisk: /'
        fi
    done
}

load_services() {
    echo "twoyi-magisk: Starting Magisk module services..."
    
    if [ ! -d "${MAGISK_MODULES_DIR}" ]; then
        return
    fi

    for module_dir in "${MAGISK_MODULES_DIR}"/*/; do
        [ -d "${module_dir}" ] || continue
        
        local module_id
        module_id=$(basename "${module_dir}")
        
        # Skip disabled modules
        [ -f "${module_dir}/disable" ] && continue
        
        # Execute service.sh if exists
        local service_script="${module_dir}/service.sh"
        if [ -f "${service_script}" ]; then
            echo "twoyi-magisk: Starting service.sh for ${module_id}"
            (nohup sh "${service_script}" &) 2>&1 | sed 's/^/twoyi-magisk: /' &
        fi
    done
}

case "$1" in
    "mount")
        mount_magisk_overlay "$2"
        ;;
    "load_modules")
        load_modules
        ;;
    "load_services")
        load_services
        ;;
    "all")
        load_modules
        load_services
        echo "twoyi-magisk: All modules loaded"
        ;;
    *)
        echo "Usage: $0 {all|mount|load_modules|load_services}"
        exit 1
        ;;
esac