/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.ui;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import com.topjohnwu.superuser.Shell;

import java.io.File;
import java.util.Locale;

import io.twoyi.R;
import io.twoyi.Render2Activity;
import io.twoyi.TwoyiStatusManager;
import io.twoyi.utils.RomManager;
import io.twoyi.utils.ShellUtil;
import io.twoyi.utils.UIHelper;

/**
 * @author twoyi-magisk
 * Home launcher for twoyi with quick actions, status monitoring, and navigation.
 */
public class HomeActivity extends AppCompatActivity {

    private TextView mStatusText;
    private TextView mStatusDetail;
    private TextView mContainerVersion;
    private TextView mMemoryUsage;
    private TextView mUptimeText;
    private CardView mStartCard;
    private CardView mStopCard;
    private CardView mRebootCard;
    private CardView mImportCard;
    private CardView mMagiskCard;
    private CardView mSettingsCard;
    private CardView mAboutCard;
    private ImageView mStatusIcon;

    private boolean mIsRunning = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // NavUtils.hideNavigation(getWindow());
        setContentView(R.layout.activity_home);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(false);
            actionBar.setTitle(R.string.app_name);
        }

        initViews();
        refreshStatus();
        updateMemoryInfo();
        updateVersionInfo();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        updateMemoryInfo();
    }

    private void initViews() {
        mStatusIcon = findViewById(R.id.home_status_icon);
        mStatusText = findViewById(R.id.home_status_text);
        mStatusDetail = findViewById(R.id.home_status_detail);
        mContainerVersion = findViewById(R.id.home_container_version);
        mMemoryUsage = findViewById(R.id.home_memory_usage);
        mUptimeText = findViewById(R.id.home_uptime);

        mStartCard = findViewById(R.id.card_start);
        mStopCard = findViewById(R.id.card_stop);
        mRebootCard = findViewById(R.id.card_reboot);
        mImportCard = findViewById(R.id.card_import);
        mMagiskCard = findViewById(R.id.card_magisk);
        mSettingsCard = findViewById(R.id.card_settings);
        mAboutCard = findViewById(R.id.card_about);

        mStartCard.setOnClickListener(v -> launchContainer());
        mStopCard.setOnClickListener(v -> shutdownContainer());
        mRebootCard.setOnClickListener(v -> rebootContainer());
        mImportCard.setOnClickListener(v -> {
            UIHelper.startActivity(this, EnhancedPackageImporterActivity.class);
        });
        mMagiskCard.setOnClickListener(v -> {
            UIHelper.startActivity(this, MagiskManagerActivity.class);
        });
        mSettingsCard.setOnClickListener(v -> {
            UIHelper.startActivity(this, SettingsActivity.class);
        });
        mAboutCard.setOnClickListener(v -> {
            UIHelper.startActivity(this, AboutActivity.class);
        });
    }

    private void refreshStatus() {
        mIsRunning = TwoyiStatusManager.getInstance().isStarted();

        if (mIsRunning) {
            mStatusIcon.setImageResource(android.R.drawable.presence_online);
            mStatusText.setText(R.string.home_status_running);
            mStatusDetail.setText(R.string.home_status_running_detail);
            mStartCard.setVisibility(View.GONE);
            mStopCard.setVisibility(View.VISIBLE);
            mRebootCard.setVisibility(View.VISIBLE);
            updateUptime();
        } else {
            mStatusIcon.setImageResource(android.R.drawable.presence_offline);
            mStatusText.setText(R.string.home_status_stopped);
            mStatusDetail.setText(R.string.home_status_stopped_detail);
            mStartCard.setVisibility(View.VISIBLE);
            mStopCard.setVisibility(View.GONE);
            mRebootCard.setVisibility(View.GONE);
            mUptimeText.setText(R.string.home_uptime_na);
        }
    }

    private void updateUptime() {
        if (!mIsRunning) {
            return;
        }
        // Read container uptime from /proc/uptime or container process
        try {
            Shell shell = ShellUtil.newSh();
            Shell.Result result = shell.newJob()
                    .add("cat /proc/uptime 2>/dev/null | awk '{print $1}'")
                    .exec();
            if (result.isSuccess() && result.getOut().size() > 0) {
                String uptimeStr = result.getOut().get(0).trim();
                double uptimeSeconds = Double.parseDouble(uptimeStr);
                long hours = (long) (uptimeSeconds / 3600);
                long minutes = (long) ((uptimeSeconds % 3600) / 60);
                mUptimeText.setText(String.format(Locale.getDefault(),
                        getString(R.string.home_uptime_format), hours, minutes));
            }
        } catch (Exception ignored) {
            mUptimeText.setText(R.string.home_uptime_na);
        }
    }

    private void updateMemoryInfo() {
        ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) return;

        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memInfo);

        long totalMem = memInfo.totalMem;
        long availMem = memInfo.availMem;
        long usedMem = totalMem - availMem;

        String memStr = String.format(Locale.getDefault(), "%.1f / %.1f GB",
                (double) usedMem / (1024 * 1024 * 1024),
                (double) totalMem / (1024 * 1024 * 1024));
        mMemoryUsage.setText(memStr);
    }

    private void updateVersionInfo() {
        String versionName = "unknown";
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            versionName = packageInfo.versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        RomManager.RomInfo info = RomManager.getCurrentRomInfo(this);
        String containerVer = String.format(Locale.getDefault(), "%s (ROM code: %d)",
                versionName, info.code);
        mContainerVersion.setText(containerVer);
    }

    private void launchContainer() {
        if (mIsRunning) {
            Toast.makeText(this, R.string.home_already_running, Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(this, Render2Activity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    private void shutdownContainer() {
        if (!mIsRunning) {
            Toast.makeText(this, R.string.home_not_running, Toast.LENGTH_SHORT).show();
            return;
        }

        RomManager.shutdown(this);
        Toast.makeText(this, R.string.home_shutdown_ok, Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private void rebootContainer() {
        if (!mIsRunning) {
            launchContainer();
            return;
        }

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