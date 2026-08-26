/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.utils;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * @author twoyi-magisk
 * Utility for handling various Android package formats.
 * Supports: APK, XAPK, APKM, APKS, APK Bundle (split APKs), ZIP with APK
 */
public final class PackageFormatUtils {

    private static final String TAG = "PackageFormatUtils";

    // Extension constants
    public static final String EXT_APK = ".apk";
    public static final String EXT_XAPK = ".xapk";
    public static final String EXT_APKM = ".apkm";
    public static final String EXT_APKS = ".apks";
    public static final String EXT_APK_BUNDLE = ".apk.bundle";
    public static final String EXT_ZIP = ".zip";

    private PackageFormatUtils() {
    }

    /**
     * Detect the package format from a file path.
     */
    public static PackageFormat detectFormat(String filePath) {
        String lower = filePath.toLowerCase();
        if (lower.endsWith(EXT_XAPK)) return PackageFormat.XAPK;
        if (lower.endsWith(EXT_APKM)) return PackageFormat.APKM;
        if (lower.endsWith(EXT_APKS)) return PackageFormat.APKS;
        if (lower.endsWith(EXT_APK)) return PackageFormat.APK;
        if (lower.endsWith(EXT_ZIP)) return PackageFormat.ZIP;
        if (lower.endsWith(EXT_APK_BUNDLE)) return PackageFormat.APK_BUNDLE;
        return PackageFormat.UNKNOWN;
    }

    /**
     * Extract APK files from a package file (supports all formats).
     * Returns a list of actual .apk files ready for installation.
     */
    public static List<File> extractApks(Context context, File packageFile) throws IOException {
        PackageFormat format = detectFormat(packageFile.getName());
        File outputDir = new File(context.getCacheDir(), "extracted_" + System.currentTimeMillis());
        outputDir.mkdirs();

        List<File> apkFiles = new ArrayList<>();

        switch (format) {
            case APK:
                // Single APK - just return it
                apkFiles.add(packageFile);
                break;

            case XAPK:
                // XAPK is a ZIP containing APK + OBB + manifest
                extractXapk(packageFile, outputDir, apkFiles);
                break;

            case APKM:
            case APKS:
            case APK_BUNDLE:
                // These are ZIP archives containing split APKs
                extractBundleApks(packageFile, outputDir, apkFiles);
                break;

            case ZIP:
                // Generic ZIP - try to find APK files inside
                extractZipApks(packageFile, outputDir, apkFiles);
                break;

            case UNKNOWN:
                // Try extensionless - maybe an APK
                if (isValidApk(packageFile)) {
                    apkFiles.add(packageFile);
                }
                break;
        }

        return apkFiles;
    }

    /**
     * Extract APK files from an XAPK package (APKPure format).
     * XAPK = ZIP containing: APK + Android/obb/<pkg>/main.<version>.<pkg>.obb
     */
    private static void extractXapk(File xapkFile, File outputDir, List<File> apkFiles) throws IOException {
        Log.i(TAG, "Extracting XAPK: " + xapkFile.getName());

        try (ZipFile zipFile = new ZipFile(xapkFile)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().toLowerCase();

                // Skip OBB files, manifest files, and directories
                if (entry.isDirectory() || name.contains("obb/") || name.contains("manifest")) {
                    continue;
                }

                if (name.endsWith(EXT_APK)) {
                    File extractedApk = new File(outputDir, new File(entry.getName()).getName());
                    extractZipEntry(zipFile, entry, extractedApk);
                    apkFiles.add(extractedApk);
                    Log.i(TAG, "  Extracted APK: " + extractedApk.getName());
                }
            }
        }
    }

    /**
     * Extract APK files from APKM/APKS bundle format (APKMirror).
     * These are ZIP archives containing multiple split APKs.
     */
    private static void extractBundleApks(File bundleFile, File outputDir, List<File> apkFiles) throws IOException {
        Log.i(TAG, "Extracting bundle: " + bundleFile.getName());

        try (ZipFile zipFile = new ZipFile(bundleFile)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().toLowerCase();

                if (entry.isDirectory() || name.contains("__MACOSX")) {
                    continue;
                }

                if (name.endsWith(EXT_APK)) {
                    File extractedApk = new File(outputDir, new File(entry.getName()).getName());
                    extractZipEntry(zipFile, entry, extractedApk);
                    apkFiles.add(extractedApk);
                    Log.i(TAG, "  Extracted APK: " + extractedApk.getName());
                }
            }
        }
    }

    /**
     * Extract APK files from a generic ZIP archive.
     */
    private static void extractZipApks(File zipFile, File outputDir, List<File> apkFiles) throws IOException {
        Log.i(TAG, "Extracting ZIP: " + zipFile.getName());

        try (ZipFile zf = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().toLowerCase();

                if (entry.isDirectory() || name.contains("__MACOSX")) {
                    continue;
                }

                if (name.endsWith(EXT_APK)) {
                    File extractedApk = new File(outputDir, new File(entry.getName()).getName());
                    extractZipEntry(zf, entry, extractedApk);
                    apkFiles.add(extractedApk);
                    Log.i(TAG, "  Extracted APK: " + extractedApk.getName());
                }
            }
        }
    }

    /**
     * Check if a file is a valid APK (has ZIP magic + AndroidManifest.xml).
     */
    public static boolean isValidApk(File file) {
        if (file == null || !file.exists()) return false;

        // Check for ZIP magic bytes
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] magic = new byte[4];
            raf.readFully(magic);
            // PK\x03\x04 or PK\x05\x06 or PK\x07\x08
            boolean isZip = magic[0] == 0x50 && magic[1] == 0x4B;

            if (!isZip) return false;

            // Check if it contains AndroidManifest.xml
            try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(file)))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if ("AndroidManifest.xml".equals(entry.getName())) {
                        return true;
                    }
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Get a human-readable description of the package format.
     */
    public static String getFormatDescription(PackageFormat format) {
        switch (format) {
            case APK: return "Android APK";
            case XAPK: return "APKPure XAPK (APK + OBB)";
            case APKM: return "APKMirror APKM Bundle";
            case APKS: return "APKS Bundle (Split APKs)";
            case APK_BUNDLE: return "APK Bundle";
            case ZIP: return "ZIP Archive";
            default: return "Unknown Format";
        }
    }

    /**
     * Get file extension filter for file picker.
     */
    public static String[] getSupportedMimeTypes() {
        return new String[]{
                "application/vnd.android.package-archive",
                "application/zip",
                "application/x-zip-compressed",
                "*/*"
        };
    }

    private static void extractZipEntry(ZipFile zipFile, ZipEntry entry, File outputFile) throws IOException {
        outputFile.getParentFile().mkdirs();
        try (InputStream is = zipFile.getInputStream(entry);
             FileOutputStream fos = new FileOutputStream(outputFile)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
        }
    }

    /**
     * Supported package formats.
     */
    public enum PackageFormat {
        APK,
        XAPK,
        APKM,
        APKS,
        APK_BUNDLE,
        ZIP,
        UNKNOWN
    }
}