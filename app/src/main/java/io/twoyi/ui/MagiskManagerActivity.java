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
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.topjohnwu.superuser.Shell;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import io.twoyi.R;
import io.twoyi.utils.AppKV;
import io.twoyi.utils.MagiskUtils;
import io.twoyi.utils.RomManager;
import io.twoyi.utils.ShellUtil;
import io.twoyi.utils.UIHelper;

/**
 * @author twoyi-magisk
 * Magisk module manager for twoyi.
 * Manages Magisk modules inside the container, with install/enable/disable/remove.
 */
public class MagiskManagerActivity extends AppCompatActivity {

    private static final int REQUEST_SELECT_MODULE = 1001;

    private ListView mModuleList;
    private TextView mEmptyView;
    private TextView mStatusView;
    private SwitchCompat mAutoSwitch;
    private TextView mAutoStatus;
    private ModuleAdapter mAdapter;
    private final List<MagiskUtils.MagiskModule> mModules = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_magisk_manager);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.magisk_title);
        }

        mModuleList = findViewById(R.id.magisk_module_list);
        mEmptyView = findViewById(R.id.magisk_empty_view);
        mStatusView = findViewById(R.id.magisk_status);
        mAutoSwitch = findViewById(R.id.magisk_auto_switch);
        mAutoStatus = findViewById(R.id.magisk_auto_status);

        mAdapter = new ModuleAdapter();
        mModuleList.setAdapter(mAdapter);
        mModuleList.setEmptyView(mEmptyView);

        findViewById(R.id.magisk_install_fab).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
            intent.setType("application/zip");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                startActivityForResult(intent, REQUEST_SELECT_MODULE);
            } catch (Throwable ignored) {
                Toast.makeText(this, R.string.magisk_no_file_manager, Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.magisk_refresh).setOnClickListener(v -> refreshModules());
        findViewById(R.id.magisk_inject_btn).setOnClickListener(v -> injectMagisk());

        // Auto-download toggle
        boolean autoEnabled = AppKV.getBooleanConfig(this, AppKV.MAGISK_AUTO_DOWNLOAD, false);
        mAutoSwitch.setChecked(autoEnabled);
        updateAutoStatus(autoEnabled);
        mAutoSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AppKV.setBooleanConfig(MagiskManagerActivity.this, AppKV.MAGISK_AUTO_DOWNLOAD, isChecked);
            updateAutoStatus(isChecked);
            if (isChecked && isContainerRunning()) {
                downloadAndConfigureMagisk();
            }
        });

        refreshStatus();
        refreshModules();
    }

    private void refreshStatus() {
        boolean magiskInstalled = MagiskUtils.isMagiskInstalled(this);
        boolean containerRunning = isContainerRunning();

        if (magiskInstalled) {
            mStatusView.setText(R.string.magisk_status_installed);
            mStatusView.setBackgroundResource(R.color.magiskStatusInstalled);
        } else if (containerRunning) {
            mStatusView.setText(R.string.magisk_status_not_installed);
            mStatusView.setBackgroundResource(R.color.magiskStatusNotInstalled);
        } else {
            mStatusView.setText(R.string.magisk_status_container_offline);
            mStatusView.setBackgroundResource(R.color.magiskStatusOffline);
        }
    }

    private boolean isContainerRunning() {
        return RomManager.romExist(this);
    }

    private void refreshModules() {
        mModules.clear();
        mModules.addAll(MagiskUtils.listModules(this));
        mAdapter.notifyDataSetChanged();
    }

    private void injectMagisk() {
        if (!isContainerRunning()) {
            Toast.makeText(this, R.string.magisk_container_required, Toast.LENGTH_SHORT).show();
            return;
        }

        ProgressDialog dialog = UIHelper.getProgressDialog(this);
        dialog.setCancelable(false);
        dialog.setMessage(getString(R.string.magisk_injecting));
        dialog.show();

        UIHelper.defer().when(() -> {
            // Create Magisk overlay structure
            boolean overlay = MagiskUtils.createMagiskOverlay(this);

            // Attempt to inject Magisk binaries
            boolean injected = MagiskUtils.injectMagisk(this);

            SystemClock.sleep(1000);

            return overlay || injected;
        }).done(result -> {
            UIHelper.dismiss(dialog);
            if (result) {
                Toast.makeText(this, R.string.magisk_inject_success, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.magisk_inject_partial, Toast.LENGTH_SHORT).show();
            }
            refreshStatus();
            refreshModules();
        }).fail(err -> {
            UIHelper.dismiss(dialog);
            Toast.makeText(this, R.string.magisk_inject_failed, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_SELECT_MODULE && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                installModuleFromUri(uri);
            }
        }
    }

    private void installModuleFromUri(Uri uri) {
        if (!isContainerRunning()) {
            Toast.makeText(this, R.string.magisk_container_required, Toast.LENGTH_SHORT).show();
            return;
        }

        ProgressDialog dialog = UIHelper.getProgressDialog(this);
        dialog.setCancelable(false);
        dialog.setMessage(getString(R.string.magisk_installing));
        dialog.show();

        UIHelper.defer().when(() -> {
            ContentResolver contentResolver = getContentResolver();
            File tempFile = new File(getCacheDir(), "magisk_module_" + System.currentTimeMillis() + ".zip");

            try (InputStream inputStream = contentResolver.openInputStream(uri);
                 FileOutputStream fos = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = inputStream.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to copy module file", e);
            }

            boolean success = MagiskUtils.installModule(this, tempFile);
            tempFile.delete();
            return success;
        }).done(success -> {
            UIHelper.dismiss(dialog);
            if (success) {
                Toast.makeText(this, R.string.magisk_install_success, Toast.LENGTH_SHORT).show();
                refreshModules();
            } else {
                Toast.makeText(this, R.string.magisk_install_failed, Toast.LENGTH_SHORT).show();
            }
        }).fail(err -> {
            UIHelper.dismiss(dialog);
            Toast.makeText(this, R.string.magisk_install_failed, Toast.LENGTH_SHORT).show();
        });
    }

    private void updateAutoStatus(boolean enabled) {
        if (mAutoStatus != null) {
            mAutoStatus.setText(enabled ? R.string.magisk_auto_download_on : R.string.magisk_auto_download_off);
        }
    }

    private void downloadAndConfigureMagisk() {
        // Auto-download and configure Magisk using the bundled binaries from assets.
        // This method injects Magisk into the container using the native libraries
        // bundled in the APK assets (Plan B: Full Magisk Injection).
        if (!isContainerRunning()) {
            runOnUiThread(() ->
                    Toast.makeText(this, R.string.magisk_container_required, Toast.LENGTH_SHORT).show());
            return;
        }

        ProgressDialog dialog = UIHelper.getProgressDialog(this);
        dialog.setCancelable(false);
        dialog.setMessage(getString(R.string.magisk_injecting));
        dialog.show();

        UIHelper.defer().when(() -> {
            // Step 1: Create Magisk overlay structure
            MagiskUtils.createMagiskOverlay(this);

            // Step 2: Inject Magisk binaries from bundled assets
            boolean injected = MagiskUtils.injectMagisk(this);

            SystemClock.sleep(1000);

            // Step 3: Start magiskd daemon
            boolean daemonStarted = false;
            if (injected) {
                daemonStarted = MagiskUtils.startMagiskDaemon(this);
            }

            return injected && daemonStarted;
        }).done(result -> {
            UIHelper.dismiss(dialog);
            if (result) {
                runOnUiThread(() -> {
                    Toast.makeText(this, R.string.magisk_configured, Toast.LENGTH_SHORT).show();
                    refreshStatus();
                });
            } else {
                runOnUiThread(() -> {
                    Toast.makeText(this, R.string.magisk_inject_partial, Toast.LENGTH_SHORT).show();
                    refreshStatus();
                });
            }
        }).fail(err -> {
            UIHelper.dismiss(dialog);
            runOnUiThread(() ->
                    Toast.makeText(this, R.string.magisk_inject_failed, Toast.LENGTH_SHORT).show());
        });
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private class ModuleAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return mModules.size();
        }

        @Override
        public MagiskUtils.MagiskModule getItem(int position) {
            return mModules.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(MagiskManagerActivity.this)
                        .inflate(R.layout.item_magisk_module, parent, false);
                holder = new ViewHolder(convertView);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            MagiskUtils.MagiskModule module = getItem(position);

            holder.name.setText(module.name);
            holder.version.setText("v" + module.version);
            holder.author.setText(module.author);
            holder.description.setText(module.description);

            if (module.enabled) {
                holder.statusIcon.setImageResource(android.R.drawable.presence_online);
                holder.statusText.setText(R.string.magisk_module_enabled);
                holder.statusText.setTextColor(0xFF4CAF50);
            } else {
                holder.statusIcon.setImageResource(android.R.drawable.presence_offline);
                holder.statusText.setText(R.string.magisk_module_disabled);
                holder.statusText.setTextColor(0xFF9E9E9E);
            }

            convertView.setOnClickListener(v -> {
                // Toggle module
                boolean newState = !module.enabled;
                boolean success = MagiskUtils.setModuleEnabled(MagiskManagerActivity.this,
                        module.id, newState);
                if (success) {
                    module.enabled = newState;
                    notifyDataSetChanged();
                    Toast.makeText(MagiskManagerActivity.this,
                            newState ? R.string.magisk_module_enabled_toast : R.string.magisk_module_disabled_toast,
                            Toast.LENGTH_SHORT).show();
                }
            });

            convertView.setOnLongClickListener(v -> {
                UIHelper.getDialogBuilder(MagiskManagerActivity.this)
                        .setTitle(module.name)
                        .setItems(new CharSequence[]{
                                getString(R.string.magisk_module_remove),
                                getString(R.string.magisk_module_details)
                        }, (dialog, which) -> {
                            if (which == 0) {
                                // Remove module
                                boolean removed = MagiskUtils.removeModule(
                                        MagiskManagerActivity.this, module.id);
                                if (removed) {
                                    Toast.makeText(MagiskManagerActivity.this,
                                            R.string.magisk_module_removed, Toast.LENGTH_SHORT).show();
                                    refreshModules();
                                }
                            } else {
                                // Show details
                                String details = String.format(
                                        "ID: %s\nVersion: %s (%d)\nAuthor: %s\n\n%s",
                                        module.id, module.version, module.versionCode,
                                        module.author, module.description);
                                UIHelper.getDialogBuilder(MagiskManagerActivity.this)
                                        .setTitle(module.name)
                                        .setMessage(details)
                                        .setPositiveButton(android.R.string.ok, null)
                                        .show();
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return true;
            });

            return convertView;
        }
    }

    static class ViewHolder {
        TextView name;
        TextView version;
        TextView author;
        TextView description;
        ImageView statusIcon;
        TextView statusText;

        ViewHolder(View root) {
            name = root.findViewById(R.id.magisk_module_name);
            version = root.findViewById(R.id.magisk_module_version);
            author = root.findViewById(R.id.magisk_module_author);
            description = root.findViewById(R.id.magisk_module_desc);
            statusIcon = root.findViewById(R.id.magisk_module_status_icon);
            statusText = root.findViewById(R.id.magisk_module_status_text);
        }
    }
}