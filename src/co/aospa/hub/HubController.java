/*
 * Copyright (C) 2017 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package co.aospa.hub;

import static co.aospa.hub.model.Version.TYPE_RELEASE;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.preference.PreferenceManager;

import co.aospa.hub.controller.ABUpdateController;
import co.aospa.hub.controller.LocalUpdateController;
import co.aospa.hub.controller.UpdateController;
import co.aospa.hub.download.DownloadClient;
import co.aospa.hub.misc.Constants;
import co.aospa.hub.misc.Utils;
import co.aospa.hub.model.Update;
import co.aospa.hub.model.UpdateInfo;
import co.aospa.hub.model.UpdateStatus;
import co.aospa.hub.model.Version;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class HubController {

    private final String TAG = "HubController";

    public static final int STATE_STATUS_CHANGED = 0;
    public static final int STATE_DOWNLOAD_PROGRESS = 1;
    public static final int STATE_INSTALL_PROGRESS = 2;
    public static final int STATE_UPDATE_DELETE = 3;
    public static final int STATE_STATUS_CHECK_FAILED = 4;
    public static final String EXTRA_DOWNLOAD_ID = "extra_download_id";

    private static HubController sController;

    private static final int MAX_REPORT_INTERVAL_MS = 1000;

    private final Context mContext;
    private final Handler mUiThread;
    private final Handler mBgThread = new Handler();

    private final PowerManager.WakeLock mWakeLock;

    private final File mDownloadRoot;

    private int mActiveDownloads = 0;
    private final List<StatusListener> mListeners = new ArrayList<>();
    private final Map<String, DownloadEntry> mDownloads = new HashMap<>();
    private final Set<String> mVerifyingUpdates = new HashSet<>();
    private final SharedPreferences mPrefs;

    public interface StatusListener {
        void onUpdateStatusChanged(Update update, int state);
    }

    public static synchronized HubController getInstance(Context context) {
        if (sController == null) {
            sController = new HubController(context);
        }
        return sController;
    }

    private HubController(Context context) {
        mUiThread = new Handler(context.getMainLooper());
        mDownloadRoot = Utils.getDownloadPath(context);
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hub:wakelock");
        mWakeLock.setReferenceCounted(false);
        mContext = context.getApplicationContext();
        mPrefs = PreferenceManager.getDefaultSharedPreferences(context);

        Utils.cleanupDownloadsDir(context);
    }

    private static class DownloadEntry {
        final Update mUpdate;
        DownloadClient mDownloadClient;
        private DownloadEntry(Update update) {
            mUpdate = update;
        }
    }

    public void notifyUpdateStatusChanged(Update update, int state) {
        mUiThread.post(() -> {
            for (StatusListener listener : mListeners) {
                    listener.onUpdateStatusChanged(update, state);
                }
            });
    }

    private void tryReleaseWakelock() {
        if (hasActiveDownloads()) {
            mWakeLock.release();
        }
    }

    public void addUpdateStatusListener(StatusListener listener) {
        mListeners.add(listener);
    }

    public void removeUpdateStatusListener(StatusListener listener) {
        mListeners.remove(listener);
    }

    private DownloadClient.DownloadCallback getDownloadCallback(final String downloadId) {
        return new DownloadClient.DownloadCallback() {

            @Override
            public void onResponse(int statusCode, String url, DownloadClient.Headers headers) {
                final Update update = Objects.requireNonNull(mDownloads.get(downloadId)).mUpdate;
                String contentLength = headers.get("Content-Length");
                if (contentLength != null) {
                    try {
                        long size = Long.parseLong(contentLength);
                        if (update.getFileSize() < size) {
                            update.setFileSize(size);
                        }
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "Could not get content-length");
                    }
                }
                update.setStatus(UpdateStatus.DOWNLOADING, mContext);
                update.setPersistentStatus(UpdateStatus.Persistent.INCOMPLETE);
                notifyUpdateStatusChanged(update, STATE_STATUS_CHANGED);
            }

            @Override
            public void onSuccess(File destination) {
                Log.d(TAG, "Download complete");
                Update update = Objects.requireNonNull(mDownloads.get(downloadId)).mUpdate;
                update.setStatus(UpdateStatus.DOWNLOADED, mContext);
                removeDownloadClient(Objects.requireNonNull(mDownloads.get(downloadId)));
                if (Version.isBuild(TYPE_RELEASE)) verifyUpdateAsync(update, downloadId, false);
                notifyUpdateStatusChanged(update, STATE_STATUS_CHANGED);
                tryReleaseWakelock();
            }

            @Override
            public void onFailure(boolean cancelled) {
                Update update = Objects.requireNonNull(mDownloads.get(downloadId)).mUpdate;
                if (cancelled) {
                    Log.d(TAG, "Download cancelled");
                    // Already notified
                } else {
                    Log.e(TAG, "Download failed");
                    removeDownloadClient(Objects.requireNonNull(mDownloads.get(downloadId)));
                    update.setStatus(UpdateStatus.DOWNLOAD_FAILED, mContext);
                    notifyUpdateStatusChanged(update, STATE_STATUS_CHANGED);
                }
                tryReleaseWakelock();
            }
        };
    }

    private DownloadClient.ProgressListener getProgressListener(final String downloadId) {
        return new DownloadClient.ProgressListener() {
            private long mLastUpdate = 0;
            private int mProgress = 0;

            @Override
            public void update(long bytesRead, long contentLength, long speed, long eta,
                    boolean done) {
                Update update = Objects.requireNonNull(mDownloads.get(downloadId)).mUpdate;
                if (contentLength <= 0) {
                    if (update.getFileSize() <= 0) {
                        return;
                    } else {
                        contentLength = update.getFileSize();
                    }
                }
                if (contentLength <= 0) {
                    return;
                }
                final long now = SystemClock.elapsedRealtime();
                int progress = Math.round(bytesRead * 100 / contentLength);
                if (progress != mProgress || mLastUpdate - now > MAX_REPORT_INTERVAL_MS) {
                    mProgress = progress;
                    mLastUpdate = now;
                    update.setProgress(progress);
                    update.setEta(eta);
                    update.setSpeed(speed);
                    notifyUpdateStatusChanged(update, STATE_DOWNLOAD_PROGRESS);
                }
            }
        };
    }

    public void verifyUpdateAsync(Update update, final String downloadId, boolean isLocalUpdate) {
        update.setStatus(UpdateStatus.VERIFYING, mContext);
        notifyUpdateStatusChanged(update, STATE_STATUS_CHANGED);
        mVerifyingUpdates.add(downloadId);
        new Thread(() -> {
            File file = update.getFile();
            if (file.exists() && verifyPackage(file)) {
                file.setReadable(true, false);
                update.setPersistentStatus(UpdateStatus.Persistent.VERIFIED);
                update.setStatus(isLocalUpdate ? UpdateStatus.LOCAL_UPDATE : UpdateStatus.VERIFIED, mContext);
            } else {
                update.setPersistentStatus(UpdateStatus.Persistent.UNKNOWN);
                update.setProgress(0);
                update.setStatus(isLocalUpdate ? UpdateStatus.LOCAL_UPDATE_FAILED : UpdateStatus.VERIFICATION_FAILED, mContext);
            }
            mVerifyingUpdates.remove(downloadId);
            notifyUpdateStatusChanged(update, STATE_STATUS_CHANGED);
        }).start();
    }

    private boolean verifyPackage(File file) {
        try {
            android.os.RecoverySystem.verifyPackage(file, null, null);
            Log.e(TAG, "Verification successful");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Verification failed", e);
            if (file.exists()) {
                file.delete();
            } else {
                // The download was probably stopped. Exit silently
                Log.e(TAG, "Error while verifying the file", e);
            }
            return false;
        }
    }

    private boolean fixUpdateStatus(Update update) {
        switch (update.getPersistentStatus()) {
            case UpdateStatus.Persistent.VERIFIED:
            case UpdateStatus.Persistent.INCOMPLETE:
                if (update.getFile() == null || !update.getFile().exists()) {
                    update.setStatus(UpdateStatus.UNKNOWN, mContext);
                    return false;
                } else if (update.getFileSize() > 0) {
                    update.setStatus(UpdateStatus.PAUSED, mContext);
                    int progress = Math.round(
                            update.getFile().length() * 100 / update.getFileSize());
                    update.setProgress(progress);
                }
                break;
        }
        return true;
    }

    public boolean isUpdateAvailable(UpdateInfo info, boolean isReadyForRollout, boolean isLocalUpdate) {
        Update update = new Update(info);
        boolean needsReboot = mPrefs.getBoolean(Constants.NEEDS_REBOOT_AFTER_UPDATE, false);
        int status = mPrefs.getInt(Constants.UPDATE_STATUS, -1);
        if (needsReboot) {
            update.setStatus(UpdateStatus.INSTALLED, mContext);
            notifyUpdateStatusChanged(update, STATE_STATUS_CHANGED);
            return false;
        }

        if (status == UpdateStatus.INSTALLED) {
            update.setStatus(UpdateStatus.INSTALLED, mContext);
            notifyUpdateStatusChanged(update, STATE_STATUS_CHANGED);
            return false;
        } else if (status == UpdateStatus.INSTALLING) {
            update.setStatus(UpdateStatus.INSTALLING, mContext);
            notifyUpdateStatusChanged(update, STATE_INSTALL_PROGRESS);
            return false;
        } else if (status == UpdateStatus.DOWNLOADING) {
            update.setStatus(UpdateStatus.DOWNLOADING, mContext);
            notifyUpdateStatusChanged(update, STATE_DOWNLOAD_PROGRESS);
            return false;
        } else if (status == UpdateStatus.DOWNLOADED) {
            update.setStatus(UpdateStatus.DOWNLOADED, mContext);
            notifyUpdateStatusChanged(update, STATE_STATUS_CHANGED);
            return false;
        }

        Version version = new Version(mContext, update);
        if (!version.isUpdateAvailable()) {
            Log.d(TAG, update.getName() + " already installed, up to date");
            update.setStatus(UpdateStatus.UNAVAILABLE, mContext);
            notifyUpdateStatusChanged(update, STATE_STATUS_CHANGED);
            return false;
        }

        if (!fixUpdateStatus(update) && !update.getAvailableOnline()) {
            update.setPersistentStatus(UpdateStatus.Persistent.UNKNOWN);
            deleteUpdateAsync(update);
            Log.d(TAG, info.getDownloadId() + " had an invalid status and is not online");
            return false;
        }

        if (!mDownloads.containsKey(info.getDownloadId())) {
            if (!isReadyForRollout) {
                Log.d(TAG, "Update " + info.getDownloadId() + " is available but not ready for rollout on this device");
                update.setStatus(UpdateStatus.UNAVAILABLE, mContext);
                notifyUpdateStatusChanged(update, STATE_STATUS_CHANGED);
                return false;
            }
            Log.d(TAG, "Adding update entry: " + info.getDownloadId());
            mDownloads.put(info.getDownloadId(), new DownloadEntry(update));
            if (isLocalUpdate) {
                if (Version.isBuild(TYPE_RELEASE)) {
                    verifyUpdateAsync(update, info.getDownloadId(), true);
                } else {
                    Log.d(TAG, "Setting update status for local update");
                    update.setStatus(UpdateStatus.LOCAL_UPDATE, mContext);
                }
            } else {
                Log.d(TAG, "Setting update status for update");
                update.setStatus(UpdateStatus.AVAILABLE, mContext);
            }
            update.setAvailableOnline(true);
            notifyUpdateStatusChanged(update, STATE_STATUS_CHANGED);
            return true;
        }
        update.setStatus(status, mContext);
        notifyUpdateStatusChanged(update, STATE_STATUS_CHANGED);
        return false;
    }

    public void startLocalUpdate(String downloadId) {
        Log.d(TAG, "Starting local update for " + downloadId);
        if (!mDownloads.containsKey(downloadId)) {
            Log.d(TAG, "Local update not registered");
            return;
        }

        UpdateInfo update = getUpdate(downloadId);
        LocalUpdateController controller = LocalUpdateController.getInstance(mContext, this);
        controller.copyUpdateToDir(update);
    }

    public void startDownload(String downloadId) {
        Log.d(TAG, "Starting " + downloadId);
        if (!mDownloads.containsKey(downloadId) || isDownloading(downloadId)) {
            return;
        }
        Update update = Objects.requireNonNull(mDownloads.get(downloadId)).mUpdate;
        File destination = new File(mDownloadRoot, update.getName());
        /*if (destination.exists()) {
            destination = Utils.appendSequentialNumber(destination);
            Log.d(TAG, "Changing name with " + destination.getName());
        }*/
        update.setFile(destination);
        DownloadClient downloadClient;
        try {
            downloadClient = new DownloadClient.Builder()
                    .setUrl(update.getDownloadUrl())
                    .setDestination(update.getFile())
                    .setDownloadCallback(getDownloadCallback(downloadId))
                    .setProgressListener(getProgressListener(downloadId))
                    .setUseDuplicateLinks(true)
                    .build();
        } catch (IOException exception) {
            Log.e(TAG, "Could not build download client");
            update.setStatus(UpdateStatus.DOWNLOAD_FAILED, mContext);
            notifyUpdateStatusChanged(update, STATE_STATUS_CHANGED);
            return;
        }
        addDownloadClient(Objects.requireNonNull(mDownloads.get(downloadId)), downloadClient);
        update.setStatus(UpdateStatus.STARTING, mContext);
        notifyUpdateStatusChanged(update, STATE_STATUS_CHANGED);
        downloadClient.start();
        mWakeLock.acquire(10*60*1000L /*10 minutes*/);
    }

    public void resumeDownload(String downloadId) {
        Log.d(TAG, "Resuming " + downloadId);
        if (!mDownloads.containsKey(downloadId) || isDownloading(downloadId)) {
            return;
        }
        Update update = Objects.requireNonNull(mDownloads.get(downloadId)).mUpdate;
        File file = update.getFile();
        if (file == null || !file.exists()) {
            Log.e(TAG, "The destination file of " + downloadId + " doesn't exist, can't resume");
            update.setStatus(UpdateStatus.PAUSED_ERROR, mContext);
            notifyUpdateStatusChanged(update, STATE_STATUS_CHANGED);
            return;
        }
        if (file.exists() && update.getFileSize() > 0 && file.length() >= update.getFileSize() && Version.isBuild(TYPE_RELEASE)) {
            Log.d(TAG, "File already downloaded, starting verification");
            if (Version.isBuild(TYPE_RELEASE)) verifyUpdateAsync(update, downloadId, false);
            notifyUpdateStatusChanged(update, STATE_STATUS_CHANGED);
        } else {
            DownloadClient downloadClient;
            try {
                downloadClient = new DownloadClient.Builder()
                        .setUrl(update.getDownloadUrl())
                        .setDestination(update.getFile())
                        .setDownloadCallback(getDownloadCallback(downloadId))
                        .setProgressListener(getProgressListener(downloadId))
                        .setUseDuplicateLinks(true)
                        .build();
            } catch (IOException exception) {
                Log.e(TAG, "Could not build download client");
                update.setStatus(UpdateStatus.PAUSED_ERROR, mContext);
                notifyUpdateStatusChanged(update, STATE_STATUS_CHANGED);
                return;
            }
            addDownloadClient(Objects.requireNonNull(mDownloads.get(downloadId)), downloadClient);
            update.setStatus(UpdateStatus.STARTING, mContext);
            notifyUpdateStatusChanged(update, STATE_STATUS_CHANGED);
            downloadClient.resume();
            mWakeLock.acquire(10*60*1000L /*10 minutes*/);
        }
    }

    public void pauseDownload(String downloadId) {
        if (!isDownloading(downloadId)) {
            Log.d(TAG, "Couldn't pausing, nothing downloading");
            return;
        }
        DownloadEntry entry = mDownloads.get(downloadId);
        assert entry != null;
        if (entry.mDownloadClient == null) {
            Log.d(TAG, "Download id is unavailable");
			return;
		}
        Log.d(TAG, "Pausing " + downloadId);
        entry.mDownloadClient.cancel();
        removeDownloadClient(Objects.requireNonNull(mDownloads.get(downloadId)));
        entry.mUpdate.setStatus(UpdateStatus.PAUSED, mContext);
        entry.mUpdate.setEta(0);
        entry.mUpdate.setSpeed(0);
        notifyUpdateStatusChanged(entry.mUpdate, STATE_STATUS_CHANGED);
    }

    private void deleteUpdateAsync(final Update update) {
        new Thread(() -> {
            File file = update.getFile();
            if (file.exists() && !file.delete()) {
                Log.e(TAG, "Could not delete " + file.getAbsolutePath());
            }
        }).start();
    }

    public void deleteUpdate(String downloadId) {
        Log.d(TAG, "Cancelling " + downloadId);
        if (!mDownloads.containsKey(downloadId) || isDownloading(downloadId)) {
            return;
        }
        Update update = Objects.requireNonNull(mDownloads.get(downloadId)).mUpdate;
        update.setStatus(UpdateStatus.DELETED, mContext);
        update.setProgress(0);
        update.setPersistentStatus(UpdateStatus.Persistent.UNKNOWN);
        deleteUpdateAsync(update);

        if (!update.getAvailableOnline()) {
            Log.d(TAG, "Download no longer available online, removing");
            mDownloads.remove(downloadId);
            notifyUpdateStatusChanged(update, STATE_UPDATE_DELETE);
        } else {
            notifyUpdateStatusChanged(update, STATE_STATUS_CHANGED);
        }

    }

    public List<UpdateInfo> getUpdates() {
        List<UpdateInfo> updates = new ArrayList<>();
        for (DownloadEntry entry : mDownloads.values()) {
            updates.add(entry.mUpdate);
        }
        return updates;
    }

    public UpdateInfo getUpdate(String downloadId) {
        Log.d(TAG, "Getting update for: " + downloadId);
        DownloadEntry entry = mDownloads.get(downloadId);
        return entry != null ? entry.mUpdate : null;
    }

    public Update getActualUpdate(String downloadId) {
        Log.d(TAG, "Getting update for: " + downloadId);
        DownloadEntry entry = mDownloads.get(downloadId);
        return entry != null ? entry.mUpdate : null;
    }

    public int getUpdateStatus() {
        return mPrefs.getInt(Constants.UPDATE_STATUS, -1);
    }

    public void setDownloadEntry(Update update) {
        if (!mDownloads.containsKey(update.getDownloadId())) {
            mDownloads.put(update.getDownloadId(), new DownloadEntry(update));
        }
    }

    public boolean isDownloading(String downloadId) {
        return mDownloads.containsKey(downloadId) &&
                Objects.requireNonNull(mDownloads.get(downloadId)).mDownloadClient != null;
    }

    public boolean hasActiveDownloads() {
        return mActiveDownloads <= 0;
    }

    public boolean isInstalling(Context context, boolean abUpdate) {
        if (abUpdate) {
            return ABUpdateController.isInstalling(context);
        }
        return UpdateController.isInstalling();
    }

    public boolean isInstallSuspended(Context context) {
        return ABUpdateController.isInstallSuspended(context);
    }

    private void addDownloadClient(DownloadEntry entry, DownloadClient downloadClient) {
        if (entry.mDownloadClient != null) {
            return;
        }
        entry.mDownloadClient = downloadClient;
        mActiveDownloads++;
    }

    private void removeDownloadClient(DownloadEntry entry) {
        if (entry.mDownloadClient == null) {
            return;
        }
        entry.mDownloadClient = null;
        mActiveDownloads--;
    }

    public void resetHub() {
        ((ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE))
                .clearApplicationUserData();
    }
}
