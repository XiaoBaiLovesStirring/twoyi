/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.utils;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.topjohnwu.superuser.Shell;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * @author twoyi-magisk
 * Utility class for Magisk integration, full injection, and module management.
 */
public final class MagiskUtils {

    private static final String TAG = "MagiskUtils";

    private static final String MAGISK_ASSET_DIR = "magisk";
    private static final String MODULE_PROP = "module.prop";

    // Magisk binaries to inject into container
    private static final String[] MAGISK_BINARIES = {
            "magisk64",      // Main Magisk binary (64-bit)
            "magiskinit",    // Init replacement
            "magiskpolicy",  // SELinux policy tool
            "busybox"        // Busybox utilities
    };

    private static final String[] MAGISK_BIN_NAMES = {
            "magisk",
            "magiskinit",
            "magiskpolicy",
            "busybox"
    };

    private MagiskUtils() {
    }

    // ============================================================
    //  Magisk Injection (Plan B: Full Magisk Injection)
    // ============================================================

    /**
     * Inject Magisk into the container rootfs.
     * This implements Plan B: Full Magisk Injection.
     *
     * Steps:
     * 1. Extract Magisk native libraries from assets
     * 2. Copy binaries to container's /sbin directory
     * 3. Create su symlink
     * 4. Set up /data/adb directory structure
     * 5. Run magisk_inject.sh inside the container
     * 6. Start magiskd daemon
     *
     * @return true if injection was successful
     */
    public static boolean injectMagisk(Context context) {
        Log.i(TAG, "Starting full Magisk injection (Plan B)...");

        // Step 1: Extract Magisk binaries from assets to container's tmp dir
        File rootfs = RomManager.getRootfsDir(context);
        if (!rootfs.exists()) {
            Log.e(TAG, "Container rootfs does not exist at " + rootfs);
            return false;
        }

        // Target directories inside container
        File containerTmp = new File(rootfs, "data/local/tmp/magisk");
        File containerSbin = new File(rootfs, "sbin");

        // Ensure tmp directory exists
        if (!containerTmp.exists()) {
            containerTmp.mkdirs();
        }

        // Step 2: Extract binaries from assets
        Log.i(TAG, "Extracting Magisk binaries from assets...");
        try {
            if (!extractMagiskBinaries(context, containerTmp)) {
                Log.e(TAG, "Failed to extract Magisk binaries from assets");
                return false;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error extracting Magisk binaries", e);
            return false;
        }

        // Step 3: Copy binaries to /sbin
        Log.i(TAG, "Copying binaries to " + containerSbin);
        for (int i = 0; i < MAGISK_BINARIES.length; i++) {
            File src = new File(containerTmp, MAGISK_BINARIES[i]);
            File dst = new File(containerSbin, MAGISK_BIN_NAMES[i]);
            if (src.exists()) {
                try {
                    copyFile(src, dst);
                    dst.setExecutable(true);
                    Log.d(TAG, "  Copied " + MAGISK_BINARIES[i] + " -> " + dst);
                } catch (IOException e) {
                    Log.w(TAG, "  Failed to copy " + MAGISK_BINARIES[i], e);
                }
            }
        }

        // Step 4: Create su symlink
        File suLink = new File(containerSbin, "su");
        File magiskTarget = new File(containerSbin, "magisk");
        try {
            if (suLink.exists()) {
                suLink.delete();
            }
            java.nio.file.Files.createSymbolicLink(
                    suLink.toPath(), magiskTarget.toPath());
            Log.i(TAG, "Created su symlink: " + suLink + " -> " + magiskTarget);
        } catch (IOException e) {
            Log.w(TAG, "Failed to create su symlink, copying binary instead", e);
            try {
                copyFile(magiskTarget, suLink);
            } catch (IOException e2) {
                Log.e(TAG, "Failed to create su", e2);
            }
        }

        // Also create /system/bin/su and /system/xbin/su for compatibility
        for (String dir : new String[]{"system/bin", "system/xbin", "vendor/bin"}) {
            File compatDir = new File(rootfs, dir);
            if (compatDir.exists()) {
                File compatSu = new File(compatDir, "su");
                try {
                    if (compatSu.exists()) compatSu.delete();
                    java.nio.file.Files.createSymbolicLink(
                            compatSu.toPath(), suLink.toPath());
                } catch (IOException ignored) {
                }
            }
        }

        // Step 5: Set up /data/adb directory structure
        Log.i(TAG, "Setting up /data/adb directories...");
        File adbDir = new File(rootfs, "data/adb");
        createDirIfNeeded(new File(adbDir, "magisk"));
        createDirIfNeeded(new File(adbDir, "modules"));
        createDirIfNeeded(new File(adbDir, "modules_update"));
        createDirIfNeeded(new File(adbDir, "post-fs-data.d"));
        createDirIfNeeded(new File(adbDir, "service.d"));

        // Create Magisk config file
        File configFile = new File(adbDir, "magisk/config");
        if (!configFile.getParentFile().exists()) {
            configFile.getParentFile().mkdirs();
        }
        if (!configFile.exists()) {
            try {
                configFile.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(configFile)) {
                    String config = "KEEPVERITY=true\nKEEPFORCEENCRYPT=true\nRECOVERYMODE=false\n";
                    fos.write(config.getBytes("UTF-8"));
                }
            } catch (IOException e) {
                Log.w(TAG, "Failed to create Magisk config", e);
            }
        }

        // Create empty Magisk database
        File dbFile = new File(adbDir, "magisk.db");
        if (!dbFile.exists()) {
            try {
                dbFile.createNewFile();
                dbFile.setExecutable(true);
            } catch (IOException e) {
                Log.w(TAG, "Failed to create magisk.db", e);
            }
        }

        // Step 6: Run the injection script inside the container
        Log.i(TAG, "Running magisk_inject.sh inside container...");
        boolean scriptResult = runMagiskInjectScript(context);

        // Step 7: Verify installation
        boolean magiskExists = new File(containerSbin, "magisk").exists();
        boolean suExists = new File(containerSbin, "su").exists();
        boolean magiskPolicyExists = new File(containerSbin, "magiskpolicy").exists();

        if (magiskExists && suExists) {
            Log.i(TAG, "Magisk injection completed: binaries=" + magiskExists
                    + " su=" + suExists + " policy=" + magiskPolicyExists
                    + " daemon=" + scriptResult);
            return true;
        } else {
            Log.e(TAG, "Magisk injection incomplete: binaries=" + magiskExists
                    + " su=" + suExists + " policy=" + magiskPolicyExists);
            return false;
        }
    }

    /**
     * Extract Magisk native libraries from the app's assets into the target directory.
     */
    private static boolean extractMagiskBinaries(Context context, File targetDir) throws IOException {
        AssetManager assets = context.getAssets();
        String magiskAssetPath = MAGISK_ASSET_DIR;

        // Check if the magisk asset directory exists
        String[] assetFiles;
        try {
            assetFiles = assets.list(magiskAssetPath);
        } catch (IOException e) {
            Log.e(TAG, "Magisk assets not found at " + magiskAssetPath, e);
            return false;
        }

        if (assetFiles == null || assetFiles.length == 0) {
            Log.e(TAG, "No files in magisk asset directory");
            return false;
        }

        boolean anyExtracted = false;
        for (String assetFile : MAGISK_BINARIES) {
            File targetFile = new File(targetDir, assetFile);
            try (InputStream in = assets.open(magiskAssetPath + "/" + assetFile);
                 FileOutputStream out = new FileOutputStream(targetFile)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = in.read(buffer)) > 0) {
                    out.write(buffer, 0, count);
                }
                targetFile.setExecutable(true);
                Log.d(TAG, "  Extracted: " + assetFile + " (" + targetFile.length() + " bytes)");
                anyExtracted = true;
            } catch (IOException e) {
                Log.w(TAG, "  Failed to extract " + assetFile + ": " + e.getMessage());
            }
        }

        return anyExtracted;
    }

    /**
     * Run the magisk_inject.sh script inside the container.
     * This script is copied to the container's /data/local/tmp/ and executed.
     */
    private static boolean runMagiskInjectScript(Context context) {
        File rootfs = RomManager.getRootfsDir(context);
        File scriptInContainer = new File(rootfs, "data/local/tmp/magisk_inject.sh");

        // Copy the injection script to the container
        try (InputStream in = context.getAssets().open("scripts/magisk_inject.sh");
             FileOutputStream out = new FileOutputStream(scriptInContainer)) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) > 0) {
                out.write(buffer, 0, count);
            }
            scriptInContainer.setExecutable(true);
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy magisk_inject.sh to container", e);
            return false;
        }

        // Execute the script inside the container using shell
        try {
            Shell shell = ShellUtil.newSh();
            List<String> result = shell.newJob()
                    .add("sh " + scriptInContainer.getAbsolutePath())
                    .exec()
                    .getOut();

            for (String line : result) {
                Log.d(TAG, "  [container] " + line);
            }

            // Check exit code
            int exitCode = shell.newJob()
                    .add("echo $?")
                    .exec()
                    .getOut()
                    .stream()
                    .findFirst()
                    .map(s -> {
                        try {
                            return Integer.parseInt(s.trim());
                        } catch (NumberFormatException e) {
                            return -1;
                        }
                    })
                    .orElse(-1);

            return exitCode == 0;
        } catch (Exception e) {
            Log.e(TAG, "Failed to run injection script in container", e);
            return false;
        }
    }

    /**
     * Check if the Magisk daemon (magiskd) is running inside the container.
     */
    public static boolean isMagiskDaemonRunning(Context context) {
        try {
            Shell shell = ShellUtil.newSh();
            List<String> out = shell.newJob()
                    .add("pidof magiskd 2>/dev/null || ps -ef | grep magiskd | grep -v grep | awk '{print $2}' | head -1")
                    .exec()
                    .getOut();
            return out != null && !out.isEmpty() && !out.get(0).trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Start the magiskd daemon inside the container.
     */
    public static boolean startMagiskDaemon(Context context) {
        if (isMagiskDaemonRunning(context)) {
            Log.i(TAG, "magiskd is already running");
            return true;
        }

        Log.i(TAG, "Starting magiskd daemon...");
        try {
            Shell shell = ShellUtil.newSh();
            List<String> out = shell.newJob()
                    .add("nohup /sbin/magisk --daemon 2>/dev/null &")
                    .add("sleep 2")
                    .add("pidof magiskd")
                    .exec()
                    .getOut();

            boolean started = out != null && !out.isEmpty() && !out.get(0).trim().isEmpty();
            if (started) {
                Log.i(TAG, "magiskd started successfully");
            } else {
                Log.w(TAG, "magiskd may not have started");
            }
            return started;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start magiskd", e);
            return false;
        }
    }

    /**
     * Get the installed Magisk version from the container.
     */
    public static String getMagiskVersion(Context context) {
        try {
            Shell shell = ShellUtil.newSh();
            List<String> out = shell.newJob()
                    .add("/sbin/magisk -v 2>/dev/null || echo 'unknown'")
                    .exec()
                    .getOut();
            if (out != null && !out.isEmpty()) {
                return out.get(0).trim();
            }
        } catch (Exception ignored) {
        }
        return "unknown";
    }

    // ============================================================
    //  Existing Magisk Module Management
    // ============================================================

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
        File modulesDir = new File(rootfs, "data/adb/modules");
        if (!modulesDir.exists()) {
            // Fallback to older path
            modulesDir = new File(rootfs, "sbin/.magisk/modules");
        }
        if (!modulesDir.exists()) {
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

    // ============================================================
    //  File utility methods
    // ============================================================

    private static void extractZip(File zipFile, File destDir) throws IOException {
        byte[] buffer = new byte[8192];
        try (java.util.zip.ZipInputStream zis =
                     new java.util.zip.ZipInputStream(new FileInputStream(zipFile))) {
            java.util.zip.ZipEntry entry;
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
                copyFile(file, destFile);
            }
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
        }
        dst.setExecutable(src.canExecute());
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

    private static void createDirIfNeeded(File dir) {
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    // ============================================================
    //  MagiskModule model class
    // ============================================================

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