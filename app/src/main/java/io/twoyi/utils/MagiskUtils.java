/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.utils;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * @author twoyi-magisk
 * Utility class for Magisk integration and module management.
 */
public final class MagiskUtils {

    private static final String TAG = "MagiskUtils";

    private static final String MAGISK_DIR = ".magisk";
    private static final String MODULES_DIR = "modules";
    private static final String MODULE_PROP = "module.prop";

    private MagiskUtils() {
    }

    /**
     * Check if Magisk is installed in the container rootfs.
     */
    public static boolean isMagiskInstalled(Context context) {
        File rootfs = RomManager.getRootfsDir(context);
        File magiskBinary = new File(rootfs, "sbin/magisk");
        File magiskPolicy = new File(rootfs, "sbin/magiskpolicy");
        return magiskBinary.exists() && magiskPolicy.exists();
    }

    /**
     * Get the Magisk modules directory inside the container.
     */
    public static File getModulesDir(Context context) {
        File rootfs = RomManager.getRootfsDir(context);
        // Magisk modules are stored in /data/adb/modules on modern Magisk
        File modulesDir = new File(rootfs, "sbin/.magisk/modules");
        if (!modulesDir.exists()) {
            // Fallback to older path
            modulesDir = new File(rootfs, "magisk/modules");
        }
        return modulesDir;
    }

    /**
     * List all installed Magisk modules.
     */
    public static List<MagiskModule> listModules(Context context) {
        List<MagiskModule> modules = new ArrayList<>();
        File modulesDir = getModulesDir(context);

        if (!modulesDir.exists() || !modulesDir.isDirectory()) {
            return modules;
        }

        File[] moduleDirs = modulesDir.listFiles(File::isDirectory);
        if (moduleDirs == null) {
            return modules;
        }

        for (File moduleDir : moduleDirs) {
            MagiskModule module = readModuleProp(moduleDir);
            if (module != null) {
                modules.add(module);
            }
        }

        return modules;
    }

    /**
     * Read module.prop from a Magisk module directory.
     */
    private static MagiskModule readModuleProp(File moduleDir) {
        File propFile = new File(moduleDir, MODULE_PROP);
        if (!propFile.exists()) {
            return null;
        }

        MagiskModule module = new MagiskModule();
        module.id = moduleDir.getName();

        try (FileInputStream fis = new FileInputStream(propFile)) {
            byte[] data = new byte[(int) propFile.length()];
            int read = fis.read(data);
            if (read <= 0) return null;

            String content = new String(data, "UTF-8");
            String[] lines = content.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("id=")) {
                    module.id = line.substring(3).trim();
                } else if (line.startsWith("name=")) {
                    module.name = line.substring(5).trim();
                } else if (line.startsWith("version=")) {
                    module.version = line.substring(8).trim();
                } else if (line.startsWith("versionCode=")) {
                    try {
                        module.versionCode = Integer.parseInt(line.substring(12).trim());
                    } catch (NumberFormatException ignored) {
                    }
                } else if (line.startsWith("author=")) {
                    module.author = line.substring(7).trim();
                } else if (line.startsWith("description=")) {
                    module.description = line.substring(12).trim();
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to read module.prop for " + moduleDir.getName(), e);
            return null;
        }

        // Check if module is disabled (has 'disable' file)
        module.enabled = !new File(moduleDir, "disable").exists();

        // Remove file
        module.hasRemove = new File(moduleDir, "remove").exists();

        // Update file
        module.hasUpdate = new File(moduleDir, "update").exists();

        return module;
    }

    /**
     * Install a Magisk module from a ZIP file.
     * @return true if module was installed successfully
     */
    public static boolean installModule(Context context, File zipFile) {
        File rootfs = RomManager.getRootfsDir(context);
        File modulesDir = new File(rootfs, "data/adb/modules");

        if (!modulesDir.exists()) {
            modulesDir.mkdirs();
        }

        // Extract module to a temp directory
        File tempDir = new File(context.getCacheDir(), "magisk_install_" + System.currentTimeMillis());
        tempDir.mkdirs();

        try {
            extractZip(zipFile, tempDir);

            // Find module.prop to determine module ID
            File moduleProp = new File(tempDir, MODULE_PROP);
            if (!moduleProp.exists()) {
                // Try common.zip structure
                File[] files = tempDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.isDirectory() && new File(f, MODULE_PROP).exists()) {
                            moduleProp = new File(f, MODULE_PROP);
                            break;
                        }
                    }
                }
            }

            if (!moduleProp.exists()) {
                Log.e(TAG, "No module.prop found in zip");
                return false;
            }

            // Read module ID
            String moduleId = null;
            try (FileInputStream fis = new FileInputStream(moduleProp)) {
                byte[] data = new byte[(int) moduleProp.length()];
                fis.read(data);
                String content = new String(data, "UTF-8");
                for (String line : content.split("\n")) {
                    line = line.trim();
                    if (line.startsWith("id=")) {
                        moduleId = line.substring(3).trim();
                        break;
                    }
                }
            }

            if (moduleId == null || moduleId.isEmpty()) {
                Log.e(TAG, "No module ID found");
                return false;
            }

            // Copy module to modules directory
            File targetDir = new File(modulesDir, moduleId);
            if (targetDir.exists()) {
                deleteDirectory(targetDir);
            }
            targetDir.mkdirs();

            copyDirectory(tempDir, targetDir);

            Log.i(TAG, "Module " + moduleId + " installed successfully");
            return true;

        } catch (IOException e) {
            Log.e(TAG, "Failed to install module", e);
            return false;
        } finally {
            deleteDirectory(tempDir);
        }
    }

    /**
     * Enable or disable a Magisk module.
     */
    public static boolean setModuleEnabled(Context context, String moduleId, boolean enabled) {
        File modulesDir = getModulesDir(context);
        File moduleDir = new File(modulesDir, moduleId);
        if (!moduleDir.exists()) {
            return false;
        }

        File disableFile = new File(moduleDir, "disable");
        if (enabled) {
            return disableFile.delete();
        } else {
            try {
                return disableFile.createNewFile();
            } catch (IOException e) {
                Log.e(TAG, "Failed to disable module", e);
                return false;
            }
        }
    }

    /**
     * Remove a Magisk module.
     */
    public static boolean removeModule(Context context, String moduleId) {
        File modulesDir = getModulesDir(context);
        File moduleDir = new File(modulesDir, moduleId);
        if (!moduleDir.exists()) {
            return false;
        }

        return deleteDirectory(moduleDir);
    }

    /**
     * Inject Magisk into the container rootfs.
     * This is a placeholder - actual Magisk injection requires a Magisk binary
     * that is compatible with the container's architecture.
     */
    public static boolean injectMagisk(Context context) {
        // TODO: Implement actual Magisk injection
        // This requires:
        // 1. Downloading/storing Magisk zip
        // 2. Extracting magiskinit, magisk, magiskpolicy binaries
        // 3. Patching the container's init to load Magisk
        // 4. Setting up the Magisk overlay filesystem
        Log.w(TAG, "Magisk injection not yet implemented");
        return false;
    }

    /**
     * Create the Magisk overlay structure for module support.
     */
    public static boolean createMagiskOverlay(Context context) {
        File rootfs = RomManager.getRootfsDir(context);
        File magiskDir = new File(rootfs, "data/adb");
        if (!magiskDir.exists()) {
            magiskDir.mkdirs();
        }

        File modulesDir = new File(magiskDir, "modules");
        if (!modulesDir.exists()) {
            modulesDir.mkdirs();
        }

        return true;
    }

    // -- File utility methods --

    private static void extractZip(File zipFile, File destDir) throws IOException {
        byte[] buffer = new byte[8192];
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File targetFile = new File(destDir, entry.getName());

                // Prevent zip slip
                if (!targetFile.getCanonicalPath().startsWith(destDir.getCanonicalPath())) {
                    throw new IOException("Bad zip entry: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    targetFile.mkdirs();
                } else {
                    targetFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private static void copyDirectory(File sourceDir, File destDir) throws IOException {
        if (!destDir.exists()) {
            destDir.mkdirs();
        }

        File[] files = sourceDir.listFiles();
        if (files == null) return;

        for (File file : files) {
            File destFile = new File(destDir, file.getName());
            if (file.isDirectory()) {
                copyDirectory(file, destFile);
            } else {
                try (FileInputStream fis = new FileInputStream(file);
                     FileOutputStream fos = new FileOutputStream(destFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = fis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                }
            }
        }
    }

    private static boolean deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) return true;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        return dir.delete();
    }

    /**
     * Represents a Magisk module with its metadata.
     */
    public static class MagiskModule {
        public String id;
        public String name;
        public String version;
        public int versionCode;
        public String author;
        public String description;
        public boolean enabled;
        public boolean hasRemove;
        public boolean hasUpdate;

        public MagiskModule() {
            this.id = "";
            this.name = "Unknown";
            this.version = "1.0";
            this.versionCode = 1;
            this.author = "Unknown";
            this.description = "";
            this.enabled = true;
        }

        @Override
        public String toString() {
            return name + " v" + version + " (" + (enabled ? "enabled" : "disabled") + ")";
        }
    }
}