/*
 * Copyright (C) 2020 Paranoid Android
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

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.util.Log;
import android.view.View;

import androidx.preference.PreferenceManager;

import co.aospa.hub.controller.LocalUpdateController;
import co.aospa.hub.download.ClientConnector;
import co.aospa.hub.download.DownloadClient;
import co.aospa.hub.misc.Constants;
import co.aospa.hub.misc.Utils;
import co.aospa.hub.model.Configuration;
import co.aospa.hub.model.UpdateInfo;
import co.aospa.hub.model.UpdatePresenter;
import co.aospa.hub.receiver.UpdateCheckReceiver;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

import org.json.JSONException;

public class HubUpdateManager implements ClientConnector.ConnectorListener {

    private static final String TAG = "HubUpdateManager";
    public static final String DEVICE_FILE = "updates/" + SystemProperties.get(Constants.PROP_DEVICE);
    private static final String OTA_CONFIGURATION_FILE = "ota_configuration";

    private final Context mContext;
    private ClientConnector mConnector;
    private final Handler mMainThread = new Handler(Looper.getMainLooper());
    private final Handler mThread = new Handler();
    private final HubActivity mHub;
    private final HubController mController;
    private final RolloutContractor mRolloutContractor;

    private Configuration mConfig;

    private final boolean mEnabled;
    private boolean mIsConfigMatchMaking = false;
    private boolean mUserInitiated;

    public HubUpdateManager(Context context, HubController controller, HubActivity activity) {
        mContext = context;
        mController = controller;
        mHub = activity;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        mEnabled = prefs.getBoolean(Constants.IS_MATCHMAKER_ENABLED, true);
        mRolloutContractor = new RolloutContractor(context);
        mRolloutContractor.setupDevice();
    }

    public void warmUpMatchMaker(boolean userInitiated) {
        if (mEnabled && mController.hasActiveDownloads()) {
            if (mConnector == null) {
                mConnector = new ClientConnector();
                mConnector.addClientStatusListener(this);
            }
            if (userInitiated != mUserInitiated) {
                mUserInitiated = userInitiated;
            }
            File oldJson = Utils.getCachedUpdateList(mContext);
            File newJson = new File(oldJson.getAbsolutePath() + UUID.randomUUID());
            String url = Utils.getServerURL(mContext) + DEVICE_FILE;
            Log.d(TAG, "Updating ota information from " + url);
            mConnector.insert(oldJson, newJson, url);
        } else {
            Log.d(TAG, "Can't get updates because match maker is disabled");
        }
    }

    public void warmUpConfigMatchMaker() {
        if (mEnabled) {
            if (mConnector == null) {
                mConnector = new ClientConnector();
                mConnector.addClientStatusListener(this);
            }
            File oldJson = Utils.getCachedConfiguration(mContext);
            File newJson = new File(oldJson.getAbsolutePath() + UUID.randomUUID());
            String url = Utils.getServerURL(mContext) + OTA_CONFIGURATION_FILE;
            Log.d(TAG, "Updating hub configuration from " + url);
            mConnector.insert(oldJson, newJson, url);
            mIsConfigMatchMaking = true;
            beginMatchMaker();
        } else {
            Log.d(TAG, "Can't get configuration because match maker is disabled");
        }
    }

    public void beginMatchMaker() {
        if (mEnabled && mUserInitiated) {
            if (mHub != null) {
                mMainThread.post(() -> {
                    mHub.getProgressBar().setVisibility(View.VISIBLE);
                    mHub.getProgressBar().setIndeterminate(true);
                });
            }
            if (!Utils.isNetworkAvailable(mContext)){
                mThread.postDelayed(this::cancelUpdate, 2000);
            }
            mThread.postDelayed(() -> mConnector.start(), 5000);
        } else {
            mConnector.start();
        }
    }

    public void beginLocalMatchMaker() {
        if (mHub != null) {
            mMainThread.post(() -> {
                mHub.getProgressBar().setVisibility(View.VISIBLE);
                mHub.getProgressBar().setIndeterminate(true);
            });
        }
        mThread.postDelayed(this::syncLocalUpdate, 5000);
    }

    private void requestUpdate(File oldJson, File newJson) {
        if (mEnabled) {
            if (mConfig != null && mConfig.isOtaEnabledFromServer()) {
                Log.d(TAG, "Requesting update..");
                try {
                    syncUpdate(newJson);
                    if (oldJson.exists() && UpdatePresenter.isNewUpdate(mContext, oldJson, newJson, mRolloutContractor.isReady())) {
                        UpdateCheckReceiver.updateRepeatingUpdatesCheck(mContext);
                    }
                    // In case we set a one-shot check because of a previous failure
                    UpdateCheckReceiver.cancelUpdatesCheck(mContext);
                    newJson.renameTo(oldJson);
                } catch (IOException | JSONException e) {
                    e.printStackTrace();
                }
            } else {
                if (mConfig != null) {
                    Log.d(TAG, "Can't check for new updates, ota enabled from sever? " + mConfig.isOtaEnabledFromServer());
                }
                mController.notifyUpdateStatusChanged(null, HubController.STATE_STATUS_CHANGED);
            }
        }
    }

    private void syncUpdate(File json) throws IOException, JSONException {
        if (mEnabled) {
            Log.d(TAG, "Syncing requested update");
            UpdateInfo update = UpdatePresenter.matchMakeJson(mContext, json);
            if (update != null) {
                boolean mIsUpdateAvailable = mController.isUpdateAvailable(update, mRolloutContractor.isReady(), false);
                if (mUserInitiated && !mIsUpdateAvailable) {
                    if (mHub != null) {
                        mMainThread.post(() -> mHub.reportMessage(R.string.no_updates_found_snack));
                    }
                }
            } else {
                mController.notifyUpdateStatusChanged(null, HubController.STATE_STATUS_CHANGED);
            }
        }
    }

    private void cancelUpdate() {
        Log.d(TAG, "Could not download updates because there is no network available");
        mController.notifyUpdateStatusChanged(null, HubController.STATE_STATUS_CHECK_FAILED);
        if (mHub != null) {
            mMainThread.post(() -> {
                mHub.getProgressBar().setVisibility(View.GONE);
                mHub.getProgressBar().setIndeterminate(false);
            });
        }
    }

    public void syncLocalUpdate() {
        Log.d(TAG, "Syncing requested local update");
        LocalUpdateController controller = 
                new LocalUpdateController(mContext, mController);
        File path = Utils.getExportPath(mContext);
        File update = controller.getLocalFile(path);
        boolean isValidUpdate = update != null;
        if (isValidUpdate) {
            boolean updateAvailable;
            UpdateInfo localUpdate = controller.setUpdate(update);
            Log.d(TAG, "Checking if " + localUpdate.getName() + " is available for local upgrade");
            updateAvailable = mController.isUpdateAvailable(localUpdate, true, true);
            if (!updateAvailable) {
                if (mHub != null) {
                    mMainThread.post(() -> mHub.reportMessage(R.string.no_updates_found_snack));
                }
            } else {
                Log.d(TAG, "Local update: " + localUpdate.getName() + " is available");
            }
        } else {
            Log.d(TAG, "No valid local update found");
            mController.notifyUpdateStatusChanged(null, HubController.STATE_STATUS_CHANGED);
        }
    }

    private void fetchCachedOrNewUpdates() {
        mRolloutContractor.setConfiguration(mConfig);
        if (mEnabled && mController.hasActiveDownloads()) {
            if (mConfig != null && mConfig.isOtaEnabledFromServer()) {
                File cachedUpdate = Utils.getCachedUpdateList(mContext);
                if (cachedUpdate.exists()) {
                    try {
                        syncUpdate(cachedUpdate);
                        Log.d(TAG, "Cached list parsed");
                    } catch (IOException | JSONException e) {
                        Log.e(TAG, "Error while parsing json list", e);
                    }
                } else {
                    warmUpMatchMaker(false);
                    beginMatchMaker();
                }
            } else {
                if (mConfig != null) {
                    Log.d(TAG, "Can't fetch cached updates, ota enabled from sever? " + mConfig.isOtaEnabledFromServer());
                }
                mController.notifyUpdateStatusChanged(null, HubController.STATE_STATUS_CHANGED);
            }
        } else {
            Log.d(TAG, "Can't fetch cached updates because match maker is disabled");
        }
    }

    public Configuration getConfiguration() {
        return mConfig;
    }

    @Override
    public void onClientStatusFailure(boolean cancelled) {
        Log.d(TAG, "Could not download updates");
        mController.notifyUpdateStatusChanged(null, HubController.STATE_STATUS_CHANGED);
        if (mHub != null) {
            mMainThread.post(() -> {
                if (!cancelled) {
                    mHub.reportMessage(R.string.no_updates_found_snack);
                }
                mHub.getProgressBar().setVisibility(View.GONE);
                mHub.getProgressBar().setIndeterminate(false);
            });
        }
    }

    @Override
    public void onClientStatusResponse(int statusCode, String url, DownloadClient.Headers headers) {}

    @Override
    public void onClientStatusSuccess(File oldJson, File newJson) {
        if (mIsConfigMatchMaking) {
            try {
                mConfig = UpdatePresenter.matchMakeConfiguration(oldJson, newJson);
            } catch (IOException | JSONException e) {
                e.printStackTrace();
            }
            mIsConfigMatchMaking = false;
            Log.d(TAG, "Ota configuration Updated!");
            fetchCachedOrNewUpdates();
        }
        requestUpdate(oldJson, newJson);
        if (mHub != null) {
            mMainThread.post(() -> {
                mHub.getProgressBar().setVisibility(View.GONE);
                mHub.getProgressBar().setIndeterminate(false);
            });
        }
    }
}
