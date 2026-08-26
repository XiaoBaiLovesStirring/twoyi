/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.ui;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.util.Pair;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import io.twoyi.R;
import io.twoyi.utils.AppKV;
import io.twoyi.utils.RomManager;
import io.twoyi.utils.UIHelper;

/**
 * @author twoyi-magisk
 * Dedicated ROM import activity.
 * Lets users select a 7z ROM file from device storage,
 * validates it, shows ROM info, and applies it.
 */
public class RomImportActivity extends AppCompatActivity {

    private static final String TAG = "RomImportActivity";
    private static final int REQUEST_PICK_ROM = 1001;

    private Uri mSelectedUri;
    private RomManager.RomInfo mRomInfo;
    private File mCopiedFile;

    private TextView mStatusText;
    private LinearLayout mRomInfoCard;
    private TextView mRomAuthor;
    private TextView mRomVersion;
    private TextView mRomDesc;
    private Button mPickButton;
    private Button mApplyButton;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rom_import);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.rom_import_title);
        }

        initViews();
    }

    private void initViews() {
        mStatusText = findViewById(R.id.rom_import_status);
        mRomInfoCard = findViewById(R.id.rom_info_card);
        mRomAuthor = findViewById(R.id.rom_info_author);
        mRomVersion = findViewById(R.id.rom_info_version);
        mRomDesc = findViewById(R.id.rom_info_desc);
        mPickButton = findViewById(R.id.rom_import_pick_btn);
        mApplyButton = findViewById(R.id.rom_import_apply_btn);

        mPickButton.setOnClickListener(v -> pickRomFile());
        mApplyButton.setOnClickListener(v -> applyRom());
    }

    private void pickRomFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, REQUEST_PICK_ROM);
        } catch (Throwable e) {
            Toast.makeText(this, R.string.importer_pick_error, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQUEST_PICK_ROM || resultCode != Activity.RESULT_OK || data == null) {
            return;
        }

        mSelectedUri = data.getData();
        if (mSelectedUri == null) {
            return;
        }

        mStatusText.setText(R.string.rom_import_copying);
        mApplyButton.setEnabled(false);
        mRomInfoCard.setVisibility(View.GONE);

        ProgressDialog dialog = UIHelper.getProgressDialog(this);
        dialog.setCancelable(false);
        dialog.setMessage(getString(R.string.rom_import_copying));
        dialog.show();

        UIHelper.defer().when(() -> {
            // Copy selected file to internal storage
            File rootfs3rd = RomManager.get3rdRootfsFile(RomImportActivity.this);
            ContentResolver contentResolver = getContentResolver();

            try (InputStream inputStream = contentResolver.openInputStream(mSelectedUri);
                 FileOutputStream fos = new FileOutputStream(rootfs3rd)) {
                byte[] buffer = new byte[65536];
                int count;
                long total = 0;
                while ((count = inputStream.read(buffer)) > 0) {
                    fos.write(buffer, 0, count);
                    total += count;
                }

                Log.i(TAG, "Copied " + total + " bytes to " + rootfs3rd);
            } catch (IOException e) {
                Log.e(TAG, "Failed to copy ROM file", e);
                rootfs3rd.delete();
                throw new RuntimeException("Failed to copy ROM file", e);
            }

            // Validate the ROM file
            if (!RomManager.isValidRomFile(rootfs3rd)) {
                rootfs3rd.delete();
                throw new RuntimeException("Invalid ROM file: not a valid 7z archive");
            }

            // Read ROM info
            RomManager.RomInfo info = RomManager.getRomInfo(rootfs3rd);
            return Pair.create(rootfs3rd, info);
        }).done(result -> {
            UIHelper.dismiss(dialog);

            mCopiedFile = result.first;
            mRomInfo = result.second;

            if (mRomInfo.isValid()) {
                // Check for official authors
                String author = mRomInfo.author;
                if ("weishu".equalsIgnoreCase(author) || "twoyi".equalsIgnoreCase(author)) {
                    Toast.makeText(this, R.string.rom_import_unknown_author, Toast.LENGTH_SHORT).show();
                    mCopiedFile.delete();
                    mCopiedFile = null;
                    mRomInfo = null;
                    mStatusText.setText(R.string.rom_import_no_file);
                    return;
                }

                // Show ROM info
                mRomAuthor.setText(mRomInfo.author);
                mRomVersion.setText(mRomInfo.version);
                mRomDesc.setText(mRomInfo.desc != null && !mRomInfo.desc.isEmpty()
                        ? mRomInfo.desc : "N/A");
                mRomInfoCard.setVisibility(View.VISIBLE);
                mStatusText.setText(R.string.rom_import_validating);
                mApplyButton.setEnabled(true);
            } else {
                mCopiedFile.delete();
                mCopiedFile = null;
                Toast.makeText(this, R.string.rom_import_invalid, Toast.LENGTH_SHORT).show();
                mStatusText.setText(R.string.rom_import_no_file);
            }
        }).fail(err -> {
            UIHelper.dismiss(dialog);
            if (mCopiedFile != null) {
                mCopiedFile.delete();
                mCopiedFile = null;
            }
            mRomInfo = null;
            String msg = err.getMessage();
            if (msg != null && msg.contains("7z")) {
                Toast.makeText(this, R.string.rom_import_invalid, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, getString(R.string.rom_import_error, msg), Toast.LENGTH_SHORT).show();
            }
            mStatusText.setText(R.string.rom_import_no_file);
        });
    }

    private void applyRom() {
        if (mCopiedFile == null || mRomInfo == null || !mRomInfo.isValid()) {
            Toast.makeText(this, R.string.rom_import_invalid, Toast.LENGTH_SHORT).show();
            return;
        }

        // Set flags to use 3rd-party ROM on next boot
        AppKV.setBooleanConfig(this, AppKV.SHOULD_USE_THIRD_PARTY_ROM, true);
        AppKV.setBooleanConfig(this, AppKV.FORCE_ROM_BE_RE_INSTALL, true);

        Toast.makeText(this, R.string.rom_import_success, Toast.LENGTH_SHORT).show();

        // Reboot to apply
        RomManager.reboot(this);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}