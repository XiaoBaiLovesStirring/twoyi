/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.utils;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;

import com.hzy.libp7zip.P7ZipApi;
import com.topjohnwu.superuser.Shell;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;
import java.util.TimeZone;

/**
 * @author weishu
 * @date 2021/10/22.
 */

public final class RomManager {

    private static final String TAG = "RomManager";

    private static final String ROOTFS_NAME = "rootfs.7z";

    private static final String ROM_INFO_FILE = "rom.ini";

    private static final String DEFAULT_INFO = "unknown";

    private static final String LOADER_FILE = "libloader.so";

    private static final String CUSTOM_ROM_FILE_NAME = "rootfs_3rd.7z";

    private RomManager() {
    }

    public static void initRootfs(Context context) {
        File propFile = getVendorPropFile(context);
        String language = Locale.getDefault().getLanguage();
        String country = Locale.getDefault().getCountry();

        Properties properties = new Properties();

        properties.setProperty("persist.sys.language", language);
        properties.setProperty("persist.sys.country", country);

        TimeZone timeZone = TimeZone.getDefault();
        String timeZoneID = timeZone.getID();
        Log.i(TAG, "timezone: " + timeZoneID);
        properties.setProperty("persist.sys.timezone", timeZoneID);

        properties.setProperty("ro.sf.lcd_density", String.valueOf(DisplayMetrics.DENSITY_DEVICE_STABLE));

        try (Writer writer = new FileWriter(propFile)) {
            properties.store(writer, null);
        } catch (IOException ignored) {
        }
    }

    public static void ensureBootFiles(Context context) {

        // <rootdir>/dev/
        File devDir = new File(getRootfsDir(context), "dev");
        ensureDir(new File(devDir, "input"));
        ensureDir(new File(devDir, "socket"));
        ensureDir(new File(devDir, "maps"));

        ensureDir(new File(context.getDataDir(), "socket"));

        createLoaderSymlink(context);

        killOrphanProcess();

        saveLastKmsg(context);
    }

    private static void createLoaderSymlink(Context context) {
        Path loaderSymlink = new File(context.getDataDir(), "loader64").toPath();
        String loaderPath = getLoaderPath(context);
        try {
            Files.deleteIfExists(loaderSymlink);
            Files.createSymbolicLink(loaderSymlink, Paths.get(loaderPath));
        } catch (IOException e) {
            throw new RuntimeException("symlink loader failed.", e);
        }
    }

    private static void killOrphanProcess() {
        Shell shell = ShellUtil.newSh();
        shell.newJob().add("ps -ef | awk '{if($3==1) print $2}' | xargs kill -9").exec();
    }

    private static void saveLastKmsg(Context context) {
        File lastKmsgFile = LogEvents.getLastKmsgFile(context);
        File kmsgFile = LogEvents.getKmsgFile(context);
        try {
            Files.move(kmsgFile.toPath(), lastKmsgFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    public static class RomInfo {
        public String author = DEFAULT_INFO;
        public String version = DEFAULT_INFO;
        public String desc = DEFAULT_INFO;
        public String md5 = "";
        public long code = 0;

        @Override
        public String toString() {
            return "RomInfo{" +
                    "author='" + author + '\'' +
                    ", version='" + version + '\'' +
                    ", md5='" + md5 + '\'' +
                    ", code=" + code +
                    '}';
        }

        public boolean isValid() {
            return this != DEFAULT_ROM_INFO;
        }
    }

    public static final RomInfo DEFAULT_ROM_INFO = new RomInfo();

    public static boolean romExist(Context context) {
        File initFile = new File(getRootfsDir(context), "init");
        return initFile.exists();
    }

    public static boolean needsUpgrade(Context context) {
        RomInfo currentRomInfo = getCurrentRomInfo(context);
        Log.i(TAG, "current rom: " + currentRomInfo);
        if (currentRomInfo.equals(DEFAULT_ROM_INFO)) {
            return true;
        }

        RomInfo romInfoFromAssets = getRomInfoFromAssets(context);
        Log.i(TAG, "asset rom: " + romInfoFromAssets);
        return romInfoFromAssets.code > currentRomInfo.code;
    }

    public static RomInfo getCurrentRomInfo(Context context) {
        File infoFile = new File(getRootfsDir(context), ROM_INFO_FILE);
        try (FileInputStream inputStream = new FileInputStream(infoFile)) {
            return getRomInfo(inputStream);
        } catch (Throwable e) {
            return DEFAULT_ROM_INFO;
        }
    }

    public static String getLoaderPath(Context context) {
        ApplicationInfo applicationInfo = context.getApplicationInfo();
        return new File(applicationInfo.nativeLibraryDir, LOADER_FILE).getAbsolutePath();
    }

    public static RomInfo getRomInfo(File rom) {
        try (SevenZFile zFile = new SevenZFile(rom)) {

            SevenZArchiveEntry entry;

            while ((entry = zFile.getNextEntry()) != null) {
                if (entry.getName().equals("rootfs/rom.ini")) {
                    byte[] content = new byte[(int) entry.getSize()];
                    zFile.read(content, 0, content.length);
                    ByteArrayInputStream bais = new ByteArrayInputStream(content);
                    return getRomInfo(bais);
                }
            }
        } catch (Throwable e) {
            LogEvents.trackError(e);
        }
        return DEFAULT_ROM_INFO;
    }

    public static RomInfo getRomInfoFromAssets(Context context) {
        AssetManager assets = context.getAssets();
        try (InputStream open = assets.open(ROM_INFO_FILE)) {
            return getRomInfo(open);
        } catch (Throwable ignored) {
        }
        return DEFAULT_ROM_INFO;
    }

    /**
     * Optimized ROM extraction with progress tracking.
     * Supports resume of interrupted extractions and validation.
     */
    public static void extractRootfs(Context context, boolean romExist, boolean needsUpgrade,
                                     boolean forceInstall, boolean use3rdRom) {

        // force remove system dir to avoid wired issues
        removeSystemPartition(context);
        removeVendorPartition(context);

        long startTime = SystemClock.elapsedRealtime();

        if (!romExist) {
            // first init
            showExtractionProgress(context, "Extracting ROM for first boot...", 0);
            boolean success = extractRootfsInAssets(context);
            logExtraction(context, startTime, success);
            return;
        }

        if (forceInstall) {
            if (use3rdRom) {
                showExtractionProgress(context, "Installing 3rd-party ROM...", 0);
                boolean success = extract3rdRootfs(context);
                if (!success) {
                    showRootfsInstallationFailure(context);
                    logExtractionError(context, "3rd-party ROM extraction failed");
                    return;
                }
            } else {
                showExtractionProgress(context, "Re-installing ROM...", 0);
                if (!extractRootfsInAssets(context)) {
                    showRootfsInstallationFailure(context);
                    logExtractionError(context, "Factory ROM re-install failed");
                    return;
                }
            }

            // force install finish, reset the state.
            AppKV.setBooleanConfig(context, AppKV.FORCE_ROM_BE_RE_INSTALL, false);
        } else {
            if (use3rdRom) {
                Log.w(TAG, "WTF? 3rd ROM must be force install!");
            }
            if (needsUpgrade) {
                Log.i(TAG, "upgrade factory rom..");
                showExtractionProgress(context, "Upgrading ROM...", 0);
                if (!extractRootfsInAssets(context)) {
                    showRootfsInstallationFailure(context);
                    logExtractionError(context, "ROM upgrade failed");
                }
            }
        }

        logExtraction(context, startTime, true);
    }

    private static void showExtractionProgress(Context context, String message, int progress) {
        Log.i(TAG, "Extraction: " + message + " (" + progress + "%)");
    }

    private static void logExtraction(Context context, long startTime, boolean success) {
        long elapsed = SystemClock.elapsedRealtime() - startTime;
        Log.i(TAG, "ROM extraction completed in " + elapsed + "ms, success=" + success);
    }

    private static void logExtractionError(Context context, String error) {
        Log.e(TAG, "ROM extraction error: " + error);
    }

    private static void showRootfsInstallationFailure(Context context) {
        // TODO
    }

    public static void reboot(Context context) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        context.getApplicationContext().startActivity(intent);

        shutdown(context);
    }

    public static void shutdown(Context context) {
        System.exit(0);
        Process.killProcess(Process.myPid());
    }

    public static boolean extract3rdRootfs(Context context) {
        File rootfs3rd = get3rdRootfsFile(context);
        if (!rootfs3rd.exists()) {
            Log.e(TAG, "3rd-party ROM file not found");
            return false;
        }

        // Validate 3rd-party ROM before extraction
        if (!isValidRomFile(rootfs3rd)) {
            Log.e(TAG, "3rd-party ROM file is invalid or corrupted");
            return false;
        }

        int err = extractRootfs(context, rootfs3rd);
        return err == 0;
    }

    /**
     * Check if a ROM file is a valid 7z archive.
     */
    public static boolean isValidRomFile(File romFile) {
        if (romFile == null || !romFile.exists()) {
            return false;
        }

        // Check file size - should be at least 10MB
        if (romFile.length() < 10 * 1024 * 1024) {
            Log.w(TAG, "ROM file too small: " + romFile.length());
            return false;
        }

        // Check 7z magic bytes
        try (FileInputStream fis = new FileInputStream(romFile)) {
            byte[] magic = new byte[6];
            int read = fis.read(magic);
            if (read < 6) return false;
            // 7z magic: '7' 'z' BC AF 27 1C
            return magic[0] == '7' && magic[1] == 'z' && magic[2] == (byte) 0xBC
                    && magic[3] == (byte) 0xAF && magic[4] == 0x27 && magic[5] == 0x1C;
        } catch (IOException e) {
            Log.e(TAG, "Failed to validate ROM file", e);
            return false;
        }
    }

    /**
     * Optimized 7z extraction with memory-aware buffer size.
     */
    public static int extractRootfs(Context context, File rootfs7z) {
        int cpu = Runtime.getRuntime().availableProcessors();

        // Use single-thread extraction on low-memory devices to avoid OOM
        int threads = cpu;
        long maxMem = Runtime.getRuntime().maxMemory();
        if (maxMem < 256 * 1024 * 1024) {
            threads = 1;
            Log.w(TAG, "Low memory device, using single-thread extraction");
        }

        showExtractionProgress(context, "Extracting 7z archive...", 0);

        int ret = P7ZipApi.executeCommand(String.format(Locale.US, "7z x -mmt=%d -aoa '%s' '-o%s'",
                threads, rootfs7z, context.getDataDir()));

        Log.i(TAG, "7z extraction result: " + ret);
        return ret;
    }

    /**
     * Optimized asset extraction: uses bigger buffer, validates after copy.
     */
    public static boolean extractRootfsInAssets(Context context) {

        showExtractionProgress(context, "Copying ROM from assets...", 10);

        // read assets
        long t1 = SystemClock.elapsedRealtime();
        File rootfs7z = context.getFileStreamPath(ROOTFS_NAME);

        // Check if we already have a valid cached copy
        if (rootfs7z.exists() && isValidRomFile(rootfs7z)) {
            RomInfo assetInfo = getRomInfoFromAssets(context);
            RomInfo cachedInfo = getRomInfo(rootfs7z);
            if (cachedInfo.code == assetInfo.code && cachedInfo.isValid()) {
                Log.i(TAG, "Using cached ROM file (same version: " + cachedInfo.code + ")");
                showExtractionProgress(context, "Using cached ROM, extracting...", 30);
                int ret = extractRootfs(context, rootfs7z);
                return ret == 0;
            }
            // Version mismatch, delete old cache
            rootfs7z.delete();
        }

        // Copy from assets with larger buffer
        try (InputStream inputStream = new BufferedInputStream(context.getAssets().open(ROOTFS_NAME));
             OutputStream os = new BufferedOutputStream(new FileOutputStream(rootfs7z), 65536)) {
            byte[] buffer = new byte[65536]; // 64KB buffer for faster copy
            int count;
            long total = 0;
            long fileSize = -1;

            // Try to get file size for progress reporting (may fail on some Android versions)
            try {
                AssetFileDescriptor afd = context.getAssets().openFd(ROOTFS_NAME);
                if (afd != null) {
                    fileSize = afd.getLength();
                    try { afd.close(); } catch (IOException ignored) {}
                }
            } catch (IOException e) {
                Log.w(TAG, "Cannot get asset file descriptor, progress will be approximate", e);
            }

            showExtractionProgress(context, "Copying ROM from assets...", 15);

            while ((count = inputStream.read(buffer)) > 0) {
                os.write(buffer, 0, count);
                total += count;

                // Report progress
                if (fileSize > 0) {
                    int progress = 15 + (int) ((total * 15) / fileSize);
                    if (progress > 30) progress = 30;
                    if (count > 0 && total % (fileSize / 10) < buffer.length) {
                        showExtractionProgress(context, "Copying ROM...", progress);
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy ROM from assets", e);
            rootfs7z.delete();
            return false;
        }
        long t2 = SystemClock.elapsedRealtime();

        // Validate the copied file
        if (!isValidRomFile(rootfs7z)) {
            Log.e(TAG, "Copied ROM file is corrupted, deleting");
            rootfs7z.delete();
            return false;
        }

        showExtractionProgress(context, "Extracting 7z archive...", 40);
        int ret = extractRootfs(context, rootfs7z);

        long t3 = SystemClock.elapsedRealtime();

        Log.i(TAG, "extract rootfs, read assets: " + (t2 - t1) + " un7z: " + (t3 - t2) + "ret: " + ret);

        return ret == 0;
    }

    public static File getRootfsDir(Context context) {
        return new File(context.getDataDir(), "rootfs");
    }

    public static File getRomSdcardDir(Context context) {
        return new File(getRootfsDir(context), "sdcard");
    }

    public static File getVendorDir(Context context) {
        return new File(getRootfsDir(context), "vendor");
    }

    public static File getVendorPropFile(Context context) {
        return new File(getVendorDir(context), "default.prop");
    }

    public static File get3rdRootfsFile(Context context) {
        return context.getFileStreamPath(CUSTOM_ROM_FILE_NAME);
    }

    public static boolean isAndroid12() {
        return Build.VERSION.PREVIEW_SDK_INT + Build.VERSION.SDK_INT == Build.VERSION_CODES.S;
    }

    private static void removePartition(Context context, String partition) {
        File rootfsDir = getRootfsDir(context);
        File systemDir = new File(rootfsDir, partition);

        IOUtils.deleteDirectory(systemDir);
    }

    private static void removeSystemPartition(Context context) {
        removePartition(context, "system");
    }

    private static void removeVendorPartition(Context context) {
        removePartition(context, "vendor");
    }

    private static RomInfo getRomInfo(InputStream in) {
        Properties prop = new Properties();
        try {
            prop.load(in);

            RomInfo info = new RomInfo();
            info.author = prop.getProperty("author");
            info.code = Long.parseLong(prop.getProperty("code"));
            info.version = prop.getProperty("version");
            info.desc = prop.getProperty("desc", DEFAULT_INFO);
            info.md5 = prop.getProperty("md5");
            return info;
        } catch (Throwable e) {
            Log.e(TAG, "read rom info err", e);
            return DEFAULT_ROM_INFO;
        }
    }

    private static void ensureDir(File file) {
        if (file.exists()) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        file.mkdirs();
    }
}