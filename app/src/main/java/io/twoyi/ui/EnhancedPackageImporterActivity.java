/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.ui;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import io.twoyi.R;
import io.twoyi.utils.Installer;
import io.twoyi.utils.IOUtils;
import io.twoyi.utils.PackageFormatUtils;
import io.twoyi.utils.UIHelper;

/**
 * @author twoyi-magisk
 * Enhanced package importer with multi-format support.
 * Supports APK, XAPK, APKM, APKS, and batch installation.
 */
public class EnhancedPackageImporterActivity extends AppCompatActivity {

    private static final String TAG = "EnhancedPkgImporter";
    private static final int REQUEST_PICK_FILES = 2001;

    private ListView mFileListView;
    private TextView mEmptyView;
    private TextView mFormatInfo;
    private Spinner mFormatSpinner;
    private CheckBox mBatchInstallCheck;
    private Button mInstallButton;
    private Button mPickFilesButton;

    private final List<Uri> mSelectedUris = new ArrayList<>();
    private final List<File> mCopiedFiles = new ArrayList<>();
    private FileListAdapter mAdapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_enhanced_importer);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.importer_title);
        }

        initViews();
    }

    private void initViews() {
        mFileListView = findViewById(R.id.importer_file_list);
        mEmptyView = findViewById(R.id.importer_empty_view);
        mFormatInfo = findViewById(R.id.importer_format_info);
        mFormatSpinner = findViewById(R.id.importer_format_spinner);
        mBatchInstallCheck = findViewById(R.id.importer_batch_install);
        mInstallButton = findViewById(R.id.importer_install_btn);
        mPickFilesButton = findViewById(R.id.importer_pick_files);

        mAdapter = new FileListAdapter();
        mFileListView.setAdapter(mAdapter);
        mFileListView.setEmptyView(mEmptyView);

        // Format spinner
        ArrayAdapter<CharSequence> spinnerAdapter = ArrayAdapter.createFromResource(this,
                R.array.importer_format_options, android.R.layout.simple_spinner_item);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mFormatSpinner.setAdapter(spinnerAdapter);
        mFormatSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateFormatInfo();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // Batch install checkbox
        mBatchInstallCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            updateInstallButtonState();
        });

        mPickFilesButton.setOnClickListener(v -> pickFiles());
        mInstallButton.setOnClickListener(v -> startInstall());

        updateInstallButtonState();
    }

    private void pickFiles() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

        // Determine MIME type based on spinner selection
        int position = mFormatSpinner.getSelectedItemPosition();
        switch (position) {
            case 0: // Auto / All
                intent.setType("*/*");
                break;
            case 1: // APK only
                intent.setType("application/vnd.android.package-archive");
                break;
            case 2: // Archives
                intent.setType("application/zip");
                break;
            default:
                intent.setType("*/*");
        }

        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, REQUEST_PICK_FILES);
        } catch (Throwable e) {
            Toast.makeText(this, R.string.importer_pick_error, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQUEST_PICK_FILES || resultCode != Activity.RESULT_OK || data == null) {
            return;
        }

        mSelectedUris.clear();

        ClipData clipData = data.getClipData();
        if (clipData == null) {
            // Single file
            mSelectedUris.add(data.getData());
        } else {
            // Multiple files
            for (int i = 0; i < clipData.getItemCount(); i++) {
                ClipData.Item item = clipData.getItemAt(i);
                mSelectedUris.add(item.getUri());
            }
        }

        updateFormatInfo();
        mAdapter.notifyDataSetChanged();
        updateInstallButtonState();
    }

    private void updateFormatInfo() {
        if (mSelectedUris.isEmpty()) {
            mFormatInfo.setText(R.string.importer_no_files);
            return;
        }

        StringBuilder info = new StringBuilder();
        info.append(getString(R.string.importer_files_selected, mSelectedUris.size()));

        // Detect format from URI names
        String lastPath = null;
        for (Uri uri : mSelectedUris) {
            lastPath = uri.getLastPathSegment();
            if (lastPath != null) break;
        }

        if (lastPath != null) {
            PackageFormatUtils.PackageFormat format = PackageFormatUtils.detectFormat(lastPath);
            String formatDesc = PackageFormatUtils.getFormatDescription(format);
            info.append(" | ").append(getString(R.string.importer_format, formatDesc));
        }

        mFormatInfo.setText(info.toString());
    }

    private void updateInstallButtonState() {
        boolean hasFiles = !mSelectedUris.isEmpty();
        boolean batchMode = mBatchInstallCheck.isChecked();

        if (hasFiles) {
            mInstallButton.setText(batchMode
                    ? R.string.importer_install_batch
                    : R.string.importer_install_single);
            mInstallButton.setEnabled(true);
        } else {
            mInstallButton.setText(R.string.importer_install_disabled);
            mInstallButton.setEnabled(false);
        }
    }

    private void startInstall() {
        if (mSelectedUris.isEmpty()) {
            Toast.makeText(this, R.string.importer_no_files, Toast.LENGTH_SHORT).show();
            return;
        }

        boolean batchMode = mBatchInstallCheck.isChecked();

        if (!batchMode && mSelectedUris.size() > 1) {
            // Single mode with multiple files - install them as a bundle
            Toast.makeText(this, R.string.importer_bundle_install, Toast.LENGTH_SHORT).show();
        }

        ProgressDialog dialog = UIHelper.getProgressDialog(this);
        dialog.setCancelable(false);
        dialog.setMessage(getString(R.string.importer_processing));
        dialog.show();

        UIHelper.defer().when(() -> {
            // Copy and extract files
            mCopiedFiles.clear();
            for (Uri uri : mSelectedUris) {
                List<File> extracted = copyAndExtract(uri);
                mCopiedFiles.addAll(extracted);
            }
            return mCopiedFiles;
        }).done(files -> {
            if (files.isEmpty()) {
                UIHelper.dismiss(dialog);
                Toast.makeText(this, R.string.importer_no_apk_found, Toast.LENGTH_SHORT).show();
                return;
            }

            // Check if all files are valid APKs
            boolean allValid = true;
            for (File file : files) {
                if (!Installer.checkFile(this, file.getAbsolutePath())) {
                    allValid = false;
                    break;
                }
            }

            if (!allValid) {
                UIHelper.dismiss(dialog);
                IOUtils.deleteAll(files);
                Toast.makeText(this, R.string.importer_invalid_apk, Toast.LENGTH_SHORT).show();
                return;
            }

            // Install
            Installer.installAsync(this, files, new Installer.InstallResult() {
                @Override
                public void onSuccess(List<File> installedFiles) {
                    runOnUiThread(() -> {
                        UIHelper.dismiss(dialog);
                        Toast.makeText(EnhancedPackageImporterActivity.this,
                                R.string.install_success, Toast.LENGTH_SHORT).show();
                        finish();
                    });
                }

                @Override
                public void onFail(List<File> failedFiles, String msg) {
                    runOnUiThread(() -> {
                        UIHelper.dismiss(dialog);
                        Toast.makeText(EnhancedPackageImporterActivity.this,
                                getString(R.string.install_failed_reason, msg),
                                Toast.LENGTH_SHORT).show();
                    });
                }
            });
        }).fail(err -> {
            UIHelper.dismiss(dialog);
            Toast.makeText(this, R.string.importer_error, Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Install failed", err);
        });
    }

    private List<File> copyAndExtract(Uri uri) {
        List<File> result = new ArrayList<>();
        try {
            ContentResolver contentResolver = getContentResolver();
            String fileName = getFileName(uri);
            File tempFile = new File(getCacheDir(), "import_" + System.currentTimeMillis() + "_" + fileName);

            // Copy to temp
            try (InputStream inputStream = contentResolver.openInputStream(uri);
                 FileOutputStream fos = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = inputStream.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }
            }

            // Extract APKs if needed
            PackageFormatUtils.PackageFormat format = PackageFormatUtils.detectFormat(tempFile.getName());
            if (format == PackageFormatUtils.PackageFormat.APK) {
                result.add(tempFile);
            } else {
                List<File> extracted = PackageFormatUtils.extractApks(this, tempFile);
                result.addAll(extracted);
                // Clean up the original archive
                tempFile.delete();
            }

        } catch (IOException e) {
            Log.e(TAG, "Failed to copy/extract file", e);
        }
        return result;
    }

    private String getFileName(Uri uri) {
        String path = uri.getLastPathSegment();
        if (path == null) return "unknown.apk";
        // Remove any leading path segments
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0) path = path.substring(lastSlash + 1);
        return path;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private class FileListAdapter extends android.widget.BaseAdapter {

        @Override
        public int getCount() {
            return mSelectedUris.size();
        }

        @Override
        public Uri getItem(int position) {
            return mSelectedUris.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(
                        android.R.layout.simple_list_item_2, parent, false);
            }

            TextView text1 = convertView.findViewById(android.R.id.text1);
            TextView text2 = convertView.findViewById(android.R.id.text2);

            Uri uri = getItem(position);
            String fileName = getFileName(uri);
            PackageFormatUtils.PackageFormat fmt = PackageFormatUtils.detectFormat(
                    fileName != null ? fileName : "");
            String formatDesc = PackageFormatUtils.getFormatDescription(fmt);

            text1.setText(fileName != null ? fileName : "Unknown");
            text2.setText(formatDesc);

            return convertView;
        }
    }
}