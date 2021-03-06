package com.tune;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Patterns;
import android.widget.Toast;

import com.tune.http.TuneUrlRequester;
import com.tune.http.UrlRequester;
import com.tune.location.TuneLocationListener;
import com.tune.ma.TuneManager;
import com.tune.ma.analytics.model.TuneAnalyticsVariable;
import com.tune.ma.analytics.model.constants.TuneVariableType;
import com.tune.ma.configuration.TuneConfiguration;
import com.tune.ma.eventbus.TuneEventBus;
import com.tune.ma.eventbus.event.TuneEventOccurred;
import com.tune.ma.eventbus.event.userprofile.TuneUpdateUserProfile;
import com.tune.ma.experiments.model.TuneInAppMessageExperimentDetails;
import com.tune.ma.experiments.model.TunePowerHookExperimentDetails;
import com.tune.ma.inapp.model.TuneInAppMessage;
import com.tune.ma.model.TuneCallback;
import com.tune.ma.model.TuneDeepActionCallback;
import com.tune.ma.push.TunePushInfo;
import com.tune.ma.push.settings.TuneNotificationBuilder;
import com.tune.ma.push.settings.TunePushListener;
import com.tune.ma.utils.TuneOptional;
import com.tune.smartwhere.TuneSmartWhere;
import com.tune.smartwhere.TuneSmartWhereConfiguration;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * @author tony@hasoffers.com
 * @author john.gu@hasoffers.com
 */
public class Tune {
    private final String IV = "heF9BATUfWuISyO8";

    /* Protected fields needed for unit tests */
    // Connectivity receiver
    protected BroadcastReceiver networkStateReceiver;
    // The context passed into the constructor
    protected Context mContext;
    // Thread pool for public method execution
    private ExecutorService pubQueue = null;
    // Queue interface object for storing events that were not fired
    protected TuneEventQueue eventQueue;
    // Location listener
    protected TuneLocationListener locationListener;
    // Parameters container
    protected TuneParameters params = new TuneParameters();
    // Interface for testing URL requests
    protected TuneTestRequest tuneRequest; // note: this has no setter - must subclass to set

    // Whether connectivity receiver is registered or not
    protected boolean isRegistered;
    // Whether to collect location or not
    protected boolean collectLocation;

    // Deferred deeplink helper class
    private TuneDeeplinker dplinkr;

    // Preloaded apps data values to send
    private TunePreloadData mPreloadData;

    // Interface for making url requests
    private UrlRequester urlRequester;
    // Encryptor for url
    private TuneEncryption encryption;
    // Interface for reading platform response to tracking calls
    private TuneListener tuneListener;

    // Whether to show debug output
    private boolean debugMode;

    // TODO: REFACTOR into FirstRun Logic
    // If this is the first session of the app lifecycle, wait for the advertising ID and referrer
    private boolean firstSession;

    // TODO: REFACTOR into FirstRun Logic
    // Is this the first install with the Tune SDK. This will be true for the entire first session.
    protected boolean isFirstInstall;

    // TODO: REFACTOR into FirstRun Logic
    // Time that SDK was initialized
    private long initTime;

    // TODO: REFACTOR into FirstRun Logic
    // Time that SDK received referrer
    private long referrerTime;

    // TODO: REFACTOR into FirstRun Logic
    // Time SDK last measuredSession
    protected long timeLastMeasuredSession;

    // Whether we're invoking FB event logging
    private boolean fbLogging;

    // Thread pool for running the request Runnables
    private final ExecutorService pool;

    private static volatile Tune sTuneInstance = null;

    // Container for FirstRun Logic
    TuneFirstRunLogic firstRunLogic;

    protected Tune() {
        pool = Executors.newSingleThreadExecutor();
        firstRunLogic = new TuneFirstRunLogic();
    }

    /**
     * Get existing TUNE singleton object.
     * @return Tune instance
     */
    public static synchronized Tune getInstance() {
        if (sTuneInstance == null) {
            TuneDebugLog.d("Tune Instance is null", new Exception("Null Singleton"));
        }
        return sTuneInstance;
    }

    /**
     * Initializes the TUNE SDK with TMA off by default.
     * @param context Application context
     * @param advertiserId TUNE advertiser ID
     * @param conversionKey TUNE conversion key
     * @return Tune instance with initialized values
     */
    public static synchronized Tune init(Context context,
                                         String advertiserId, String conversionKey) {
        return init(context, advertiserId, conversionKey, false);
    }

    /**
     * Initializes the TUNE SDK.
     * @param context Application context
     * @param advertiserId TUNE advertiser ID
     * @param conversionKey TUNE conversion key
     * @param turnOnIAM Whether to enable Tune In-App Marketing or not
     * @return Tune instance with initialized values
     */
    public static synchronized Tune init(Context context,
                                         String advertiserId, String conversionKey,
                                         boolean turnOnIAM) {
        return init(context, advertiserId, conversionKey, turnOnIAM, new TuneConfiguration());
    }

    /**
     * Initializes the TUNE SDK.
     * @param context Application context
     * @param advertiserId TUNE advertiser ID
     * @param conversionKey TUNE conversion key
     * @param turnOnIAM Whether to enable Tune In-App Marketing or not
     * @param configuration custom SDK configuration
     * @return Tune instance with initialized values
     */
    public static synchronized Tune init(Context context,
                                         String advertiserId, String conversionKey,
                                         boolean turnOnIAM, TuneConfiguration configuration) {
        // SDK version declaration format for Google. See SDK-579
        String sdkVersionString = BuildConfig.SDK_VERSION_STRING;

        if (sTuneInstance == null) {
            initAll(new Tune(), context, advertiserId, conversionKey, turnOnIAM, configuration);
        }

        return sTuneInstance;
    }

    /**
     * Wait for Initialization to complete.
     * @param milliseconds Number of milliseconds to wait for initialization to complete.
     * @return true if initialization completed in the time frame expected.
     */
    boolean waitForInit(long milliseconds) {
        return params.waitForInitComplete(milliseconds);
    }

    // Package Private
    void shutDown() {
        TuneDebugLog.d("Tune shutDown()");

        if (sTuneInstance != null) {
            firstRunLogic.cancel();

            synchronized (pool) {
                pool.notifyAll();
                pool.shutdown();
            }

            try {
                pool.awaitTermination(1, TimeUnit.SECONDS);

                // The executor is done, however it may have posted events.  It is difficult to know
                // when those are done, but without a sleep() here, they generally are *not* done.
                Thread.sleep(100);
            } catch (InterruptedException e) {
                TuneDebugLog.e("Error waiting for Pool Shutdown", e);
            }

            pubQueue.shutdownNow();
            TuneManager.destroy();
        } else {
            TuneDebugLog.d("Tune already shut down");
        }

        TuneEventBus.clearFlags();
        TuneEventBus.disable();
        TuneEventBus.clearCaches();
        TuneManager.destroy();
        params.destroy();

        sTuneInstance = null;
        TuneDebugLog.d("Tune shutDown() complete");
    }

    protected static synchronized Tune initAll(Tune tune, Context context,
                                               String advertiserId, String conversionKey,
                                               boolean turnOnIAM, TuneConfiguration configuration) {
        if (sTuneInstance == null) {
            sTuneInstance = tune;

            sTuneInstance.mContext = context.getApplicationContext();
            sTuneInstance.pubQueue = Executors.newSingleThreadExecutor();

            if (turnOnIAM && TuneUtils.hasPermission(context, Manifest.permission.INTERNET)) {
                // Enable the event bus
                TuneEventBus.enable();
                // Init TuneManager
                TuneManager.init(context.getApplicationContext(), configuration);
            } else {
                // Disable the event bus if TMA is not on
                TuneEventBus.disable();
            }

            sTuneInstance.initLocal(advertiserId, conversionKey);

            // Location listener init
            sTuneInstance.locationListener = new TuneLocationListener(context);
            // Check configuration for whether to collect location or not
            if (configuration != null) {
                sTuneInstance.collectLocation = configuration.shouldAutoCollectDeviceLocation();
                if (sTuneInstance.collectLocation) {
                    // Get initial location
                    sTuneInstance.locationListener.startListening();
                }
            }

            TuneDebugLog.alwaysLog("Initializing Tune Version " + getSDKVersion());
        } else {
            TuneDebugLog.e("Tune Already Initialized");
        }
        return sTuneInstance;
    }

    private void initLocal(String advertiserId, String conversionKey) {
        // Dplinkr init
        dplinkr = new TuneDeeplinker(advertiserId, conversionKey, mContext.getPackageName());

        params.init(this, mContext, advertiserId, conversionKey);

        initLocalVariables(conversionKey);

        eventQueue = new TuneEventQueue(mContext, this);

        // Set up connectivity listener so we dump the queue when re-connected to Internet
        networkStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (isRegistered) {
                    dumpQueue();
                }
            }
        };

        if (isRegistered) {
            // Unregister receiver in case one is still previously registered
            try {
                mContext.unregisterReceiver(networkStateReceiver);
            } catch (java.lang.IllegalArgumentException e) {
                TuneDebugLog.d("Invalid state.", e);
            }
            isRegistered = false;
        }

        IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        mContext.registerReceiver(networkStateReceiver, filter);
        isRegistered = true;

        if (!params.hasInstallFlagBeenSet()) {
            isFirstInstall = true;
            params.setInstallFlag();
        }
    }

    /**
     * Initialize class variables.
     * @param key the conversion key
     */
    private void initLocalVariables(String key) {
        urlRequester = new TuneUrlRequester();
        encryption = new TuneEncryption(key.trim(), IV);

        initTime = System.currentTimeMillis();
        firstSession = true;
        isRegistered = false;
        debugMode = false;
        fbLogging = false;
        collectLocation = true;

        if (params.getInstallReferrer() != null) {
            firstRunLogic.receivedInstallReferrer();
        }
    }

    /**
     * Returns true if an Internet connection is detected.
     * @param context the app context to check connectivity from
     * @return whether Internet connection exists
     */
    public synchronized boolean isOnline(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    protected ExecutorService getPubQueue() {
        return pubQueue;
    }

    protected synchronized void addEventToQueue(String link, String data, JSONObject postBody, boolean firstSession) {
        synchronized (pool) {
            if (pool.isShutdown()) {
                return;
            }

            pool.execute(eventQueue.new Add(link, data, postBody, firstSession));
        }
    }

    protected synchronized void dumpQueue() {
        if (!isOnline(mContext)) {
            return;
        }

        synchronized (pool) {
            if (pool.isShutdown()) {
                return;
            }

            pool.execute(eventQueue.new Dump());
        }
    }

    /**
     * Measure new session.
     * Tune Android SDK plugins may use this method to trigger session measurement events.
     * This should be called in the equivalent of onResume().
     */
    public void measureSessionInternal() {
        timeLastMeasuredSession = System.currentTimeMillis();
        measureEvent(new TuneEvent(TuneEvent.NAME_SESSION));
        if (debugMode) {
            Handler handler = new Handler(Looper.getMainLooper());
            handler.post(new Runnable() {
                public void run() {
                    Toast.makeText(mContext, "TUNE measureSession called", Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    /**
     * Get the time the Tune SDK last measured a new session. This value may not update each time the app is foregrounded.
     * @return time of last session measurement in milliseconds (System time).
     */
    public long getTimeLastMeasuredSession() {
        return timeLastMeasuredSession;
    }

    /**
     * Event measurement function that measures an event for the given eventName.
     * @param eventName event name in TUNE system.  The eventName parameter cannot be null or empty
     */
    public void measureEvent(String eventName) {
        try {
            measureEvent(new TuneEvent(eventName));
        } catch (IllegalArgumentException e) {
            TuneDebugLog.e("measureEvent() " + e.getMessage());
            if (debugMode) {
                throw e;
            }
        }
    }

    /**
     * Event measurement function that measures an event based on TuneEvent values.
     * Create a TuneEvent to pass in with:<br>
     * <pre>new TuneEvent(eventName)</pre>
     * @param eventData custom data to associate with the event
     */
    public void measureEvent(final TuneEvent eventData) {
        updateLocation();

        measure(eventData);
    }

    private void runQueue(String tag, Runnable runnable) {
        if (pubQueue != null) {
            TuneDebugLog.d("Run Queue: " + tag);
            synchronized (pubQueue) {
                pubQueue.execute(runnable);
            }
        } else {
            TuneDebugLog.e("Run Queue NULL: " + tag);
        }

    }

    private void measureTuneLinkClick(final String clickedTuneLinkUrl) {
        // Go Asynchronous
        runQueue("measureTuneLinkClick", new Runnable() {
            public void run() {
                String link = TuneUrlBuilder.appendTuneLinkParameters(params, clickedTuneLinkUrl);
                String data = "";
                JSONObject postBody = new JSONObject();

                if (tuneRequest != null) {
                    tuneRequest.constructedRequest(link, data, postBody);
                }

                // Send the Tune Link click request immediately
                makeRequest(link, data, postBody);

                if (tuneListener != null) {
                    tuneListener.enqueuedActionWithRefId(null);
                }
            }
        });
    }

    private synchronized void measure(final TuneEvent eventData) {
        // Go Asynchronous
        runQueue("measure", new Runnable() {
            public void run() {
                if (sTuneInstance == null) {
                    TuneDebugLog.e("TUNE is not initialized");
                    return;
                }

                // Post event to TuneEventBus for IAM
                TuneEventBus.post(new TuneEventOccurred(eventData));

                dumpQueue();

                params.setAction(TuneParameters.ACTION_CONVERSION); // Default to conversion
                if (eventData.getEventName() != null) {
                    String eventName = eventData.getEventName();
                    if (fbLogging) {
                        TuneFBBridge.logEvent(params, eventData);
                    }
                    if (TuneEvent.NAME_CLOSE.equals(eventName)) {
                        return; // Don't send close events
                    } else if (TuneEvent.NAME_OPEN.equals(eventName)
                            || TuneEvent.NAME_INSTALL.equals(eventName)
                            || TuneEvent.NAME_UPDATE.equals(eventName)
                            || TuneEvent.NAME_SESSION.equals(eventName)) {
                        params.setAction(TuneParameters.ACTION_SESSION);
                    }
                }

                if (eventData.getRevenue() > 0) {
                    params.setIsPayingUser("1");
                }

                String link = TuneUrlBuilder.buildLink(params, eventData, mPreloadData, debugMode);
                String data = TuneUrlBuilder.buildDataUnencrypted(params, eventData);
                JSONArray eventItemsJson = new JSONArray();
                if (eventData.getEventItems() != null) {
                    for (int i = 0; i < eventData.getEventItems().size(); i++) {
                        eventItemsJson.put(eventData.getEventItems().get(i).toJson());
                    }
                }
                JSONObject postBody =
                        TuneUrlBuilder.buildBody(eventItemsJson, eventData.getReceiptData(), eventData.getReceiptSignature(), params.getUserEmails());

                if (tuneRequest != null) {
                    tuneRequest.constructedRequest(link, data, postBody);
                }

                addEventToQueue(link, data, postBody, firstSession);
                // Mark firstSession false
                firstSession = false;
                dumpQueue();

                if (tuneListener != null) {
                    tuneListener.enqueuedActionWithRefId(eventData.getRefId());
                }

                if (TuneSmartWhere.getInstance().getConfiguration().isPermissionGranted(TuneSmartWhereConfiguration.GRANT_SMARTWHERE_TUNE_EVENTS)) {
                    TuneSmartWhere.getInstance().processMappedEvent(mContext, eventData);
                }
            }
        });
    }

    /**
     * Helper function for making single request and displaying response.
     * @param link Url address
     * @param data Url link data
     * @param postBody Url post body
     * @return true if request was sent successfully and should be removed from queue
     */
    protected boolean makeRequest(String link, String data, JSONObject postBody) {
        TuneDebugLog.d("Sending event to server...");

        final boolean removeRequestFromQueue = true;
        final boolean retryRequestInQueue = false;

        if (link == null) { // This is an internal method and link should always be set, but for customer stability we will prevent NPEs
            TuneDebugLog.e("CRITICAL internal Tune request link is null");
            safeReportFailureToTuneListener("Internal Tune request link is null");
            return removeRequestFromQueue;
        }

        updateLocation(); // If location not set before sending, try to get location again

        String encData = TuneUrlBuilder.updateAndEncryptData(params, data, encryption);
        String fullLink = link + "&data=" + encData;

        if (tuneListener != null) {
            tuneListener.enqueuedRequest(fullLink, postBody);
        }

        JSONObject response = urlRequester.requestUrl(fullLink, postBody, debugMode);

        if (response == null) { // The only way we get null from TuneUrlRequester is if *our server* returned HTTP 400. Do not retry.
            safeReportFailureToTuneListener("Error 400 response from Tune");
            return removeRequestFromQueue;
        }

        if (!response.has("success")) { // if response is empty, it should be requeued
            TuneDebugLog.e("Request failed, event will remain in queue");
            safeReportFailureToTuneListener(response);
            return retryRequestInQueue;
        }

        checkForExpandedTuneLinks(link, response);

        // notify tuneListener of success or failure
        boolean success = false;
        try {
            success = response.getString("success").equals("true");
        } catch (JSONException e) {
            TuneDebugLog.e("Error parsing response " + response + " to check for success", e);
            safeReportFailureToTuneListener(response);
            return retryRequestInQueue;
        }

        safeReportSuccessOrFailureToTuneListener(response, success);
        saveOpenLogId(response);

        return removeRequestFromQueue;
    }

    private void safeReportSuccessOrFailureToTuneListener(JSONObject response, boolean success) {
        if (success) {
            safeReportSuccessToTuneListener(response);
        } else {
            safeReportFailureToTuneListener(response);
        }
    }

    private void safeReportSuccessToTuneListener(JSONObject response) {
        if (tuneListener != null) {
            tuneListener.didSucceedWithData(response);
        }
    }

    private void safeReportFailureToTuneListener(JSONObject response) {
        if (tuneListener != null) {
            tuneListener.didFailWithError(response);
        }
    }

    private void safeReportFailureToTuneListener(String errorMessage) {
        Map<String, String> errors = new HashMap<>();
        errors.put("error", errorMessage);
        safeReportFailureToTuneListener(new JSONObject(errors));
    }

    private void saveOpenLogId(JSONObject response) {
        try {
            String eventType = response.optString("site_event_type");
            if ("open".equals(eventType)) {
                String logId = response.getString("log_id");
                if ("".equals(getOpenLogId())) {
                    params.setOpenLogId(logId);
                }
                params.setLastOpenLogId(logId);
            }
        } catch (JSONException e) {
            TuneDebugLog.e("Error parsing response " + response + " to save open log id", e);
        }
    }

    private void checkForExpandedTuneLinks(String link, JSONObject response) {
        try {
            if (isTuneLinkMeasurementRequest(link) && !isInvokeUrlParameterInReferralUrl()) {
                if (response.has(TuneConstants.KEY_INVOKE_URL)) {
                    dplinkr.handleExpandedTuneLink(response.getString(TuneConstants.KEY_INVOKE_URL));
                } else {
                    dplinkr.handleFailedExpandedTuneLink("There is no invoke url for this Tune Link");
                }
            }
        } catch (JSONException e) {
            TuneDebugLog.e("Error parsing response " + response + " to check for invoke url", e);
        }
    }

    private boolean isInvokeUrlParameterInReferralUrl() {
        return invokeUrlFromReferralUrl(params.getReferralUrl()).isPresent();
    }

    private boolean isTuneLinkMeasurementRequest(String link) {
        return link.contains(TuneUrlKeys.ACTION + "=" + TuneParameters.ACTION_CLICK);
    }

    protected TuneOptional<String> invokeUrlFromReferralUrl(String referralUrl) {
        String invokeUrl = null;
        try {
            Uri clickedLink = Uri.parse(referralUrl);
            invokeUrl = clickedLink.getQueryParameter(TuneConstants.KEY_INVOKE_URL);
        } catch (Exception e) {
            TuneDebugLog.e("Error looking for invoke_url in referral url: " + referralUrl, e);
        }
        return TuneOptional.ofNullable(invokeUrl);
    }

    /**
     * Update Location if autocollect is enabled.
     * If location autocollect is enabled, tries to get latest location from TuneLocationListener,
     * triggering an update if needed
     */
    protected void updateLocation() {
        if (collectLocation) {
            if (params.getLocation() == null && locationListener != null) {
                Location lastLocation = locationListener.getLastLocation();
                if (lastLocation != null) {
                    params.setLocation(new TuneLocation(lastLocation));
                }
            }
        }
    }

    /******************
     * Public Getters *
     ******************/

    /**
     * Gets the action of the event.
     * @return install/update/conversion
     */
    public String getAction() {
        return params.getAction();
    }

    /**
     * Gets the TUNE advertiser ID.
     * @return TUNE advertiser ID
     */
    public String getAdvertiserId() {
        return params.getAdvertiserId();
    }

    /**
     * Gets the user age.
     * NOTE: this value must be set with {@link Tune#setAge(int)} otherwise this method will return 0.
     *
     * @return age, if set. If no value is set this method returns 0.
     */
    public int getAge() {
        return params.getAgeNumeric();
    }

    /**
     * Gets the device altitude.
     * NOTE: this value must be set with {@link Tune#setAltitude(double)} otherwise this method will return 0.
     *
     * @return device altitude, if set. If no value is set returns 0.0
     */
    public double getAltitude() {
        String altitudeString = params.getAltitude();
        double altitude = 0.0d;
        if (altitudeString != null) {
            try {
                altitude = Double.parseDouble(altitudeString);
            } catch (NumberFormatException e) {
                TuneDebugLog.e("Error parsing altitude value " + altitudeString, e);
            }
        }
        return altitude;
    }

    /**
     * Gets the ANDROID_ID of the device that was set with {@link Tune#setAndroidId(String)}.
     * @return ANDROID_ID
     */
    public String getAndroidId() {
        return params.getAndroidId();
    }

    /**
     * Get whether the user has app-level ad tracking enabled or not.
     * Note that COPPA rules apply.
     * @return app-level ad tracking enabled or not
     */
    public boolean getAppAdTrackingEnabled() {
        return params.getAppAdTrackingEnabled();
    }

    /**
     * Gets the app name.
     * @return app name
     */
    public String getAppName() {
        return params.getAppName();
    }

    /**
     * Gets the app version.
     * @return app version
     */
    public int getAppVersion() {
        String appVersionString = params.getAppVersion();
        int appVersion = 0;
        if (appVersionString != null) {
            try {
                appVersion = Integer.parseInt(appVersionString);
            } catch (NumberFormatException e) {
                TuneDebugLog.e("Error parsing appVersion value " + appVersionString, e);
            }

        }
        return appVersion;
    }

    /**
     * Gets the connection type (mobile or WIFI).
     * @return whether device is connected by WIFI or mobile data connection
     */
    public String getConnectionType() {
        return params.getConnectionType();
    }

    /**
     * Gets the ISO 639-1 country code.
     * @return ISO 639-1 country code
     */
    public String getCountryCode() {
        return params.getCountryCode();
    }

    /**
     * Gets the ISO 4217 currency code.
     * @return ISO 4217 currency code
     */
    public String getCurrencyCode() {
        return params.getCurrencyCode();
    }

    /**
     * Gets the device brand/manufacturer (HTC, Apple, etc).
     * @return device brand/manufacturer name
     */
    public String getDeviceBrand() {
        return params.getDeviceBrand();
    }

    /**
     * Gets the device build.
     * @return device build name
     */
    public String getDeviceBuild() {
        return params.getDeviceBuild();
    }

    /**
     * Gets the device carrier if any.
     * @return mobile device carrier/service provider name
     */
    public String getDeviceCarrier() {
        return params.getDeviceCarrier();
    }

    /**
     * Gets the Device ID, also known as IMEI/MEID, if any.
     * @return device IMEI/MEID
     */
    public String getDeviceId() {
        return params.getDeviceId();
    }

    /**
     * Gets the device model name.
     * @return device model name
     */
    public String getDeviceModel() {
        return params.getDeviceModel();
    }

    /**
     * Gets value previously set of existing user or not.
     * @return whether user existed prior to install
     */
    public boolean getExistingUser() {
        int intExisting = Integer.parseInt(params.getExistingUser());
        return (intExisting == 1);
    }

    /**
     * Gets the Facebook user ID previously set.
     * @return Facebook user ID
     */
    public String getFacebookUserId() {
        return params.getFacebookUserId();
    }

    /**
     * Gets the Fire Advertising ID.
     * @return Fire advertising ID
     * @deprecated As of SDK v5.1.0.   Use {@link Tune#getPlatformAdvertisingId()}
     */
    @Deprecated
    public String getFireAdvertisingId() {
        return params.getFireAdvertisingId();
    }

    /**
     * Gets whether use of the Fire Advertising ID is limited by user request.
     * Note that COPPA rules apply.
     * @return whether tracking is limited
     * @deprecated As of SDK v5.1.0.   Use {@link Tune#getPlatformAdTrackingLimited()}
     */
    @Deprecated
    public boolean getFireAdTrackingLimited() {
        return params.getPlatformAdTrackingLimited();
    }

    /**
     * Gets the user gender set with {@link Tune#setGender(TuneGender)}.
     * @return gender
     */
    public TuneGender getGender() {
        String gender = params.getGender();
        if ("0".equals(gender)) {
            return TuneGender.MALE;
        } else if ("1".equals(gender)) {
            return TuneGender.FEMALE;
        } else {
            return TuneGender.UNKNOWN;
        }
    }

    /**
     * Gets the Google Play Services Advertising ID.
     * @return Google advertising ID
     * @deprecated As of SDK v5.1.0.   Use {@link Tune#getPlatformAdvertisingId()}
     */
    @Deprecated
    public String getGoogleAdvertisingId() {
        return params.getGoogleAdvertisingId();
    }

    /**
     * Gets whether use of the Google Play Services Advertising ID is limited by user request.
     * Note that COPPA rules apply.
     * @return whether tracking is limited
     * @deprecated As of SDK v5.1.0.   Use {@link Tune#getPlatformAdTrackingLimited()}
     */
    @Deprecated
    public boolean getGoogleAdTrackingLimited() {
        return params.getPlatformAdTrackingLimited();
    }

    /**
     * Gets the Google user ID previously set.
     * @return Google user ID
     */
    public String getGoogleUserId() {
        return params.getGoogleUserId();
    }

    /**
     * Gets the date of app install.
     * @return date that app was installed, epoch seconds
     */
    public long getInstallDate() {
        String installDateString = params.getInstallDate();
        long installDate = 0L;
        if (installDateString != null) {
            try {
                installDate = Long.parseLong(installDateString);
            } catch (NumberFormatException e) {
                TuneDebugLog.e("Error parsing installDate value " + installDateString, e);
            }
        }
        return installDate;
    }

    /**
     * Gets the Google Play INSTALL_REFERRER.
     * @return Play INSTALL_REFERRER
     */
    public String getInstallReferrer() {
        return params.getInstallReferrer();
    }

    /**
     * Gets whether the user is revenue-generating or not.
     * @return true if the user has produced revenue, false if not
     */
    public boolean getIsPayingUser() {
        String isPayingUser = params.getIsPayingUser();
        return "1".equals(isPayingUser);
    }

    /**
     * Returns whether this device profile is flagged as privacy protected.
     * This will be true if either the age is set to less than 13 or if {@link #setPrivacyProtectedDueToAge(boolean)} is set to true.
     * @return true if the age has been set to less than 13 OR this profile has been set explicitly as privacy protected.
     */
    public boolean isPrivacyProtectedDueToAge() {
        return params.isPrivacyProtectedDueToAge();
    }

    /**
     * Gets the language of the device.
     * @return device language
     */
    public String getLanguage() {
        return params.getLanguage();
    }

    /**
     * Gets the last TUNE open log ID.
     * @return most recent TUNE open log ID
     */
    public String getLastOpenLogId() {
        return params.getLastOpenLogId();
    }

    /**
     * Gets the device latitude.
     * NOTE: Must be set by {@link Tune#setLatitude(double)}. This value is not automatically retrieved.
     * @return device latitude
     */
    public double getLatitude() {
        String latitudeString = params.getLatitude();
        double latitude = 0d;
        if (latitudeString != null) {
            try {
                latitude = Double.parseDouble(latitudeString);
            } catch (NumberFormatException e) {
                TuneDebugLog.e("Error parsing latitude value " + latitudeString, e);
            }
        }

        return latitude;
    }

    /**
     * Gets the device locale.
     * @return device locale
     */
    public String getLocale() {
        return params.getLocale();
    }

    /**
     * Gets the device longitude.
     * NOTE: This value must be set by {@link Tune#setLongitude(double)}. This value is not automatically retrieved.
     * @return device longitude
     */
    public double getLongitude() {
        String longitudeString = params.getLongitude();
        double longitude = 0d;
        if (longitudeString != null) {
            try {
                longitude = Double.parseDouble(longitudeString);
            } catch (NumberFormatException e) {
                TuneDebugLog.e("Error parsing longitude value " + longitudeString, e);
            }
        }

        return longitude;
    }

    /**
     * Gets the MAC address of device.
     * @return device MAC address
     */
    public String getMacAddress() {
        return params.getMacAddress();
    }

    /**
     * Gets the MAT ID generated on install.
     * @return MAT ID
     */
    public String getMatId() {
        return params.getMatId();
    }

    /**
     * Gets the mobile country code.
     * @return mobile country code associated with the carrier
     */
    public String getMCC() {
        return params.getMCC();
    }

    /**
     * Gets the mobile network code.
     * @return mobile network code associated with the carrier
     */
    public String getMNC() {
        return params.getMNC();
    }

    /**
     * Gets the first TUNE open log ID.
     * @return first TUNE open log ID
     */
    public String getOpenLogId() {
        return params.getOpenLogId();
    }

    /**
     * Gets the Android OS version.
     * @return Android OS version
     */
    public String getOsVersion() {
        return params.getOsVersion();
    }


    /**
     * Gets the app package name.
     * @return package name of app
     */
    public String getPackageName() {
        return params.getPackageName();
    }

    /**
     * Gets whether use of the Platform Advertising ID is limited by user request.
     * Note that COPPA rules apply.
     * @return whether tracking is limited
     */
    public boolean getPlatformAdTrackingLimited() {
        return params.getPlatformAdTrackingLimited();
    }

    /**
     * Gets the Platform Advertising ID.
     * @return Platform advertising ID
     */
    public String getPlatformAdvertisingId() {
        return params.getPlatformAdvertisingId();
    }

    /**
     * Get SDK plugin name used.
     * @return name of TUNE plugin
     */
    public String getPluginName() {
        return params.getPluginName();
    }

    /**
     * Gets the package name of the app that started this Activity, if any.
     * @return source package name that caused open via StartActivityForResult
     */
    public String getReferralSource() {
        return params.getReferralSource();
    }

    /**
     * Gets the url scheme that started this Activity, if any.
     * @return full url of app scheme that caused open
     */
    public String getReferralUrl() {
        return params.getReferralUrl();
    }

    /**
     * Gets the screen density of the device.
     * @return 0.75/1.0/1.5/2.0/3.0/4.0 for ldpi/mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi
     */
    public String getScreenDensity() {
        return params.getScreenDensity();
    }

    /**
     * Gets the screen height of the device in pixels.
     * @return height
     */
    public String getScreenHeight() {
        return params.getScreenHeight();
    }

    /**
     * Gets the screen width of the device in pixels.
     * @return width
     */
    public String getScreenWidth() {
        return params.getScreenWidth();
    }

    /**
     * Gets the TUNE Android SDK version.
     * @return TUNE Android SDK version
     */
    public static String getSDKVersion() {
        return BuildConfig.VERSION_NAME;
    }

    /**
     * Gets the TRUSTe ID set.
     * @return TRUSTe ID
     */
    public String getTRUSTeId() {
        return params.getTRUSTeId();
    }

    /**
     * Gets the Twitter user ID previously set.
     * @return Twitter user ID
     */
    public String getTwitterUserId() {
        return params.getTwitterUserId();
    }

    /**
     * Gets the device browser user agent.
     * @return device user agent
     */
    public String getUserAgent() {
        return params.getUserAgent();
    }

    /**
     * Gets the custom user email.
     * @return custom user email
     */
    public String getUserEmail() {
        return params.getUserEmail();
    }

    /**
     * Gets the custom user ID.
     * @return custom user id
     */
    public String getUserId() {
        return params.getUserId();
    }

    /**
     * Gets the custom user name.
     * @return custom user name
     */
    public String getUserName() {
        return params.getUserName();
    }

    /******************
     * Public Setters *
     ******************/

    /**
     * Sets the TUNE advertiser ID.
     * @param advertiserId TUNE advertiser ID
     */
    public void setAdvertiserId(final String advertiserId) {
        params.setAdvertiserId(advertiserId);
    }

    /**
     * Sets the user's age.
     * When age is set to a value less than 13 this device profile will be marked as privacy protected
     * for the purposes of the protection of children from ad targeting and
     * personal data collection. In the US this is part of the COPPA law.
     * This method is related to {@link #setPrivacyProtectedDueToAge(boolean)}
     * See https://developers.tune.com/sdk/settings-for-user-characteristics/ for more information
     * @param age User age
     */
    public void setAge(final int age) {
        params.setAge(Integer.toString(age));
    }

    /**
     * Sets the device altitude.
     * @param altitude device altitude
     */
    public void setAltitude(final double altitude) {
        params.setAltitude(Double.toString(altitude));
    }

    /**
     * Sets the ANDROID ID.
     * @param androidId ANDROID_ID
     */
    public void setAndroidId(final String androidId) {
        // Params sometimes not initialized by the time GetAdvertisingId thread finishes
        if (params != null) {
            params.setAndroidId(androidId);

            if (dplinkr != null) {
                dplinkr.setAndroidId(androidId);
                requestDeferredDeeplink();
            }
        }
    }

    /**
     * Sets the ANDROID ID MD5 hash.
     * @param androidIdMd5 ANDROID_ID MD5 hash
     */
    public void setAndroidIdMd5(final String androidIdMd5) {
        params.setAndroidIdMd5(androidIdMd5);
    }

    /**
     * Sets the ANDROID ID SHA-1 hash.
     * @param androidIdSha1 ANDROID_ID SHA-1 hash
     */
    public void setAndroidIdSha1(final String androidIdSha1) {
        params.setAndroidIdSha1(androidIdSha1);
    }

    /**
     * Sets the ANDROID ID SHA-256 hash.
     * @param androidIdSha256 ANDROID_ID SHA-256 hash
     */
    public void setAndroidIdSha256(final String androidIdSha256) {
        params.setAndroidIdSha256(androidIdSha256);
    }

    /**
     * Sets whether app-level ad tracking is enabled.
     * @param adTrackingEnabled false if user has opted out of ad tracking at the app-level, true if the user has opted in
     */
    public void setAppAdTrackingEnabled(final boolean adTrackingEnabled) {
        if (adTrackingEnabled) {
            params.setAppAdTrackingEnabled(TuneConstants.PREF_SET);
        } else {
            params.setAppAdTrackingEnabled(TuneConstants.PREF_UNSET);
        }
    }

    /**
     * Sets the conversion key for the SDK.
     * @param conversionKey TUNE conversion key
     */
    public void setConversionKey(final String conversionKey) {
        params.setConversionKey(conversionKey);
    }

    /**
     * Sets the ISO 4217 currency code.
     * @param currencyCode the currency code
     */
    public void setCurrencyCode(final String currencyCode) {
        if (currencyCode == null || currencyCode.equals("")) {
            params.setCurrencyCode(TuneConstants.DEFAULT_CURRENCY_CODE);
        } else {
            params.setCurrencyCode(currencyCode);
        }
    }

    /**
     * Sets the device brand, or manufacturer.
     * @param deviceBrand device brand
     */
    public void setDeviceBrand(final String deviceBrand) {
        params.setDeviceBrand(deviceBrand);
    }

    /**
     * Sets the device build.
     * @param deviceBuild device build
     */
    public void setDeviceBuild(final String deviceBuild) {
        params.setDeviceBuild(deviceBuild);
    }

    /**
     * Sets the device IMEI/MEID.
     * @param deviceId device IMEI/MEID
     */
    public void setDeviceId(final String deviceId) {
        params.setDeviceId(deviceId);
    }

    /**
     * Sets the device model.
     * @param deviceModel device model
     */
    public void setDeviceModel(final String deviceModel) {
        params.setDeviceModel(deviceModel);
    }

    /**
     * Sets whether app was previously installed prior to version with TUNE SDK. This should be called BEFORE your first activity resumes.
     * @param existing true if this user already had the app installed prior to updating to TUNE version
     */
    public void setExistingUser(final boolean existing) {
        if (existing) {
            params.setExistingUser(TuneConstants.PREF_SET);
        } else {
            params.setExistingUser(TuneConstants.PREF_UNSET);
        }
    }

    /**
     * Sets the user ID to associate with Facebook.
     * @param userId the Facebook user id
     */
    public void setFacebookUserId(final String userId) {
        params.setFacebookUserId(userId);
    }

    /**
     * Sets the Amazon Fire Advertising ID.
     * Note that this is auto-collected at initialization if running on a Fire device
     * @param adId Amazon Fire advertising ID
     * @param isLATEnabled whether user has enabled limit ad tracking
     */
    public void setFireAdvertisingId(final String adId, boolean isLATEnabled) {
        final int intLimit = isLATEnabled ? 1 : 0;

        if (params != null) {
            params.setFireAdvertisingId(adId);
            params.setFireAdTrackingLimited(Integer.toString(intLimit));
        }
        setPlatformAdvertisingId(adId, isLATEnabled);
    }

    /**
     * Sets the user gender.
     * @param gender use TuneGender.MALE, TuneGender.FEMALE
     */
    public void setGender(final TuneGender gender) {
        params.setGender(gender);
    }

    /**
     * Sets the Google Play Services Advertising ID.
     * Note that this is auto-collected at initialization if Google Play Services is available
     * @param adId Google Play advertising ID
     * @param isLATEnabled whether user has enabled limit ad tracking
     */
    public void setGoogleAdvertisingId(final String adId, boolean isLATEnabled) {
        final int intLimit = isLATEnabled ? 1 : 0;

        if (params != null) {
            params.setGoogleAdvertisingId(adId);
            params.setGoogleAdTrackingLimited(Integer.toString(intLimit));
        }
        setPlatformAdvertisingId(adId, isLATEnabled);
    }

    /**
     * Sets the user ID to associate with Google.
     * @param userId the Google user id
     */
    public void setGoogleUserId(final String userId) {
        params.setGoogleUserId(userId);
    }

    /**
     * Overrides the Google Play INSTALL_REFERRER received.
     * @param referrer Your custom referrer value
     */
    public void setInstallReferrer(final String referrer) {
        // Record when referrer was received
        referrerTime = System.currentTimeMillis();
        if (params != null) {
            params.setReferrerDelay(referrerTime - initTime);
        }
        params.setInstallReferrer(referrer);
        firstRunLogic.receivedInstallReferrer();
    }

    // This method will wait for the FirstRun startup sequence to complete.
    // Called exclusively by TuneEventQueue.
    void waitForFirstRunData(int timeToWait) {
        firstRunLogic.waitForFirstRunData(mContext, timeToWait);
    }

    /**
     * Sets whether the user is revenue-generating or not.
     * @param isPayingUser true if the user has produced revenue, false if not
     */
    public void setIsPayingUser(final boolean isPayingUser) {
        if (isPayingUser) {
            params.setIsPayingUser("1");
        } else {
            params.setIsPayingUser("0");
        }
    }

    /**
     * Sets the device locale.
     * @param locale the device locale
     */
    public void setLocale(final String locale) {
        params.setLocale(locale);
    }

    /**
     * Sets the device location.
     * @param location the device location
     */
    public void setLocation(final Location location) {
        TuneLocation loc = null == location ? null : new TuneLocation(location);
        setLocation(loc);
    }

    /**
     * Sets the device location.
     * Manually setting the location through this method disables geo-location auto-collection.
     * @param location the device location as a TuneLocation
     */
    public void setLocation(final TuneLocation location) {
        if (location == null) {
            TuneDebugLog.e("Location may not be null");
            return;
        }

        setShouldAutoCollectDeviceLocation(false);
        params.setLocation(location);
    }

    /**
     * Sets the device latitude.
     * Manually setting the latitude through this method disables geo-location auto-collection.
     * @param latitude the device latitude
     */
    public void setLatitude(final double latitude) {
        setShouldAutoCollectDeviceLocation(false);
        params.setLatitude(Double.toString(latitude));
    }

    /**
     * Sets the device longitude.
     * Manually setting the longitude through this method disables geo-location auto-collection.
     * @param longitude the device longitude
     */
    public void setLongitude(final double longitude) {
        setShouldAutoCollectDeviceLocation(false);
        params.setLongitude(Double.toString(longitude));
    }

    /**
     * Register a TuneListener interface to receive server response callback.
     * @param listener a TuneListener object that will be called when server request is complete
     */
    public void setListener(TuneListener listener) {
        tuneListener = listener;
    }

    /**
     * Sets the device MAC address.
     * @param macAddress device MAC address
     */
    public void setMacAddress(final String macAddress) {
        params.setMacAddress(macAddress);
    }

    /**
     * Sets the device OS version.
     * @param osVersion device OS version
     */
    public void setOsVersion(final String osVersion) {
        params.setOsVersion(osVersion);
    }

    /**
     * Sets the app package name.
     * @param packageName App package name
     */
    public void setPackageName(final String packageName) {
        dplinkr.setPackageName(packageName);
        String pkg = TextUtils.isEmpty(packageName) ? mContext.getPackageName() : packageName;
        params.setPackageName(pkg);

        if (TuneSmartWhere.isSmartWhereAvailable()) {
            TuneSmartWhere.getInstance().setPackageName(mContext, pkg);
        }
    }

    /**
     * Sets the device phone number.
     * @param phoneNumber Phone number
     */
    public void setPhoneNumber(final String phoneNumber) {
        if (TextUtils.isEmpty(phoneNumber)) {
            params.setPhoneNumber(phoneNumber);
        } else {
            // Regex remove all non-digits from phoneNumber
            String phoneNumberDigits = phoneNumber.replaceAll("\\D+", "");
            // Convert to digits from foreign characters if needed
            StringBuilder digitsBuilder = new StringBuilder();
            for (int i = 0; i < phoneNumberDigits.length(); i++) {
                int numberParsed = Integer.parseInt(String.valueOf(phoneNumberDigits.charAt(i)));
                digitsBuilder.append(numberParsed);
            }
            params.setPhoneNumber(digitsBuilder.toString());
        }
    }

    /**
     * Sets the Platform Advertising ID.
     * @param adId Advertising ID
     * @param isLATEnabled whether user has enabled limit ad tracking
     */
    void setPlatformAdvertisingId(final String adId, boolean isLATEnabled) {
        final int intLimit = isLATEnabled ? 1 : 0;

        if (params != null) {
            params.setPlatformAdvertisingId(adId);
            params.setPlatformAdTrackingLimited(Integer.toString(intLimit));

            if (dplinkr != null) {
                dplinkr.setPlatformAdvertisingId(adId, intLimit);
                requestDeferredDeeplink();
            }
        }
        firstRunLogic.receivedAdvertisingId();
    }

    /**
     * Sets publisher information for device preloaded apps.
     * @param preloadData Preload app attribution data
     */
    public void setPreloadedApp(TunePreloadData preloadData) {
        mPreloadData = preloadData;
        // Only do this if TMA is not disabled
        TuneEventBus.post(new TuneUpdateUserProfile(new TuneAnalyticsVariable(TuneUrlKeys.PUBLISHER_ID, preloadData.publisherId)));
        TuneEventBus.post(new TuneUpdateUserProfile(new TuneAnalyticsVariable(TuneUrlKeys.OFFER_ID, preloadData.offerId)));
        TuneEventBus.post(new TuneUpdateUserProfile(new TuneAnalyticsVariable(TuneUrlKeys.AGENCY_ID, preloadData.agencyId)));
        TuneEventBus.post(new TuneUpdateUserProfile(new TuneAnalyticsVariable(TuneUrlKeys.PUBLISHER_REF_ID, preloadData.publisherReferenceId)));
        TuneEventBus.post(new TuneUpdateUserProfile(new TuneAnalyticsVariable(TuneUrlKeys.PUBLISHER_SUB_PUBLISHER, preloadData.publisherSubPublisher)));
        TuneEventBus.post(new TuneUpdateUserProfile(new TuneAnalyticsVariable(TuneUrlKeys.PUBLISHER_SUB_SITE, preloadData.publisherSubSite)));
        TuneEventBus.post(new TuneUpdateUserProfile(new TuneAnalyticsVariable(TuneUrlKeys.PUBLISHER_SUB_CAMPAIGN, preloadData.publisherSubCampaign)));
        TuneEventBus.post(new TuneUpdateUserProfile(new TuneAnalyticsVariable(TuneUrlKeys.PUBLISHER_SUB_ADGROUP, preloadData.publisherSubAdgroup)));
        TuneEventBus.post(new TuneUpdateUserProfile(new TuneAnalyticsVariable(TuneUrlKeys.PUBLISHER_SUB_AD, preloadData.publisherSubAd)));
        TuneEventBus.post(new TuneUpdateUserProfile(new TuneAnalyticsVariable(TuneUrlKeys.PUBLISHER_SUB_KEYWORD, preloadData.publisherSubKeyword)));
        TuneEventBus.post(new TuneUpdateUserProfile(new TuneAnalyticsVariable(TuneUrlKeys.PUBLISHER_SUB1, preloadData.publisherSub1)));
        TuneEventBus.post(new TuneUpdateUserProfile(new TuneAnalyticsVariable(TuneUrlKeys.PUBLISHER_SUB2, preloadData.publisherSub2)));
        TuneEventBus.post(new TuneUpdateUserProfile(new TuneAnalyticsVariable(TuneUrlKeys.PUBLISHER_SUB3, preloadData.publisherSub3)));
        TuneEventBus.post(new TuneUpdateUserProfile(new TuneAnalyticsVariable(TuneUrlKeys.PUBLISHER_SUB4, preloadData.publisherSub4)));
        TuneEventBus.post(new TuneUpdateUserProfile(new TuneAnalyticsVariable(TuneUrlKeys.PUBLISHER_SUB5, preloadData.publisherSub5)));
        TuneEventBus.post(new TuneUpdateUserProfile(new TuneAnalyticsVariable(TuneUrlKeys.ADVERTISER_SUB_PUBLISHER, preloadData.advertiserSubPublisher)));
        TuneEventBus.post(new TuneUpdateUserProfile(new TuneAnalyticsVariable(TuneUrlKeys.ADVERTISER_SUB_SITE, preloadData.advertiserSubSite)));
        TuneEventBus.post(new TuneUpdateUserProfile(new TuneAnalyticsVariable(TuneUrlKeys.ADVERTISER_SUB_CAMPAIGN, preloadData.advertiserSubCampaign)));
        TuneEventBus.post(new TuneUpdateUserProfile(new TuneAnalyticsVariable(TuneUrlKeys.ADVERTISER_SUB_ADGROUP, preloadData.advertiserSubAdgroup)));
        TuneEventBus.post(new TuneUpdateUserProfile(new TuneAnalyticsVariable(TuneUrlKeys.ADVERTISER_SUB_AD, preloadData.advertiserSubAd)));
        TuneEventBus.post(new TuneUpdateUserProfile(new TuneAnalyticsVariable(TuneUrlKeys.ADVERTISER_SUB_KEYWORD, preloadData.advertiserSubKeyword)));
    }

    /**
     * Set privacy as protected.
     * Set this device profile as privacy protected for the purposes of the protection of children
     * from ad targeting and personal data collection. In the US this is part of the COPPA law.
     * This method is related to {@link #setAge(int)}.
     * @param isPrivacyProtected True if privacy should be protected for this user.
     * @return true if age requirements are met.  For example, you cannot turn privacy protection "off" for children who meet the COPPA standard.
     */
    public boolean setPrivacyProtectedDueToAge(final boolean isPrivacyProtected) {
        int currentAge = getAge();

        // You cannot turn "off" privacy protection if age is within the COPPA boundary
        if (currentAge > 0 && currentAge < TuneConstants.COPPA_MINIMUM_AGE) {
            if (!isPrivacyProtected) {
                return false;
            }
        }

        // You can, however, turn "on" privacy protection regardless of age.
        params.setPrivacyExplicitlySetAsProtected(isPrivacyProtected);
        return true;
    }

    /**
     * Set the package that invoked the activity.
     * Typically this value is from {@link Activity#getCallingPackage} and may be null.
     *
     * @param referralCallingPackage The name of the callling package
     */
    public void setReferralCallingPackage(@Nullable final String referralCallingPackage) {
        params.setReferralSource(referralCallingPackage);
    }

    /**
     * Set referral url (deeplink).
     * You usually do not need to call this directly. If called, this method should be called BEFORE {@link #measureSessionInternal()}
     * @param url deeplink with which app was invoked
     */
    public void setReferralUrl(final String url) {
        params.setReferralUrl(url);

        if (url != null && isTuneLink(url)) {
            try {
                // In case the tune link already contains an invoke_url, short circuit and call the deeplink listener
                TuneOptional<String> invokeUrl = invokeUrlFromReferralUrl(url);
                if (invokeUrl.isPresent()) {
                    dplinkr.handleExpandedTuneLink(invokeUrl.get());
                }
            } catch (Exception e) {
                dplinkr.handleFailedExpandedTuneLink("Error accessing invoke_url from clicked Tune Link");
            } finally {
                measureTuneLinkClick(url);
            }
        }
    }

    /**
     * Sets the TRUSTe ID, should generate via their SDK.
     * @param tpid TRUSTe ID
     */
    public void setTRUSTeId(final String tpid) {
        params.setTRUSTeId(tpid);
    }

    /**
     * Sets the user ID to associate with Twitter.
     * @param userId the Twitter user id
     */
    public void setTwitterUserId(final String userId) {
        params.setTwitterUserId(userId);
    }

    /**
     * Sets the custom user email.
     * @param userEmail the user email
     */
    public void setUserEmail(final String userEmail) {
        params.setUserEmail(userEmail);
    }

    /**
     * Sets the custom user ID.
     * @param userId the user id
     */
    public void setUserId(final String userId) {
        params.setUserId(userId);
    }

    /**
     * Sets the custom user name.
     * @param userName the username
     */
    public void setUserName(final String userName) {
        params.setUserName(userName);
    }

    /**
     * Set the name of plugin used, if any.
     * @param pluginName the name of the plugin used
     */
    public void setPluginName(final String pluginName) {
        // Validate plugin name
        if (Arrays.asList(TuneConstants.PLUGIN_NAMES).contains(pluginName)) {
            params.setPluginName(pluginName);
        } else {
            if (debugMode) {
                throw new IllegalArgumentException("Plugin name not acceptable");
            }
        }
    }

    /**
     * Turns debug mode on or off, under tag "TUNE".
     * Additionally, setting this to 'true' will cause two exceptions to be thrown to aid in debugging the IAM configuration.
     * Normally IAM will log an error to the console when you misconfigure or misuse a method, but this way an exception is thrown to
     * quickly and explicitly find what is misconfigured.
     * <br>
     *  - TuneIAMNotEnabledException: This will be thrown if you use a IAM method without IAM enabled.
     *  - TuneIAMConfigurationException: This will be thrown if the arguments passed to an IAM method are invalid. The exception message will have more details.
     *
     * @param debug whether to enable debug output
     */
    public void setDebugMode(final boolean debug) {
        debugMode = debug;
        params.setDebugMode(debug);
        if (TuneSmartWhere.isSmartWhereAvailable()) {
            TuneSmartWhere.getInstance().setDebugMode(mContext, debug);
        }

        if (debug) {
            TuneDebugLog.enableLog();
            TuneDebugLog.setLogLevel(TuneDebugLog.Level.DEBUG);
            Handler handler = new Handler(Looper.getMainLooper());
            handler.post(new Runnable() {
                public void run() {
                    Toast.makeText(mContext, "TUNE Debug Mode Enabled, do not release with this enabled!!", Toast.LENGTH_LONG).show();
                }
            });
        } else {
            TuneDebugLog.disableLog();
            TuneDebugLog.setLogLevel(TuneDebugLog.Level.ERROR);
        }
    }


    /**
     * Checks the current status of debug mode.
     * @return Whether debug mode is on or off.
     */
    public boolean isInDebugMode() {
        return debugMode;
    }

    /**
     * Enables or disables primary Gmail address collection.
     * Requires GET_ACCOUNTS permission
     * @param collectEmail whether to collect device email address
     */
    public void setEmailCollection(final boolean collectEmail) {
        boolean accountPermission = TuneUtils.hasPermission(mContext, Manifest.permission.GET_ACCOUNTS);
        if (collectEmail && accountPermission) {
            // Set primary Gmail address as user email
            Account[] accounts = AccountManager.get(mContext).getAccountsByType("com.google");
            if (accounts.length > 0) {
                params.setUserEmail(accounts[0].name);
            }

            // Store the rest of email addresses
            HashMap<String, String> emailMap = new HashMap<String, String>();
            accounts = AccountManager.get(mContext).getAccounts();
            for (Account account : accounts) {
                if (Patterns.EMAIL_ADDRESS.matcher(account.name).matches()) {
                    emailMap.put(account.name, account.type);
                }
                Set<String> emailKeys = emailMap.keySet();
                String[] emailArr = emailKeys.toArray(new String[emailKeys.size()]);
                params.setUserEmails(emailArr);
            }
        }
    }

    /**
     * Whether to log TUNE events in the FB SDK as well.
     * @param logging Whether to send TUNE events to FB as well
     * @param context Activity context
     * @param limitEventAndDataUsage Whether user opted out of ads targeting
     */
    public void setFacebookEventLogging(boolean logging, Context context, boolean limitEventAndDataUsage) {
        fbLogging = logging;
        if (logging && (context != null)) {
            TuneFBBridge.startLogger(context, limitEventAndDataUsage);
        }
    }

    /**
     * Whether to autocollect device location if location is enabled.
     * @param autoCollect Automatically collect device location, default is true
     */
    public void setShouldAutoCollectDeviceLocation(boolean autoCollect) {
        collectLocation = autoCollect;
        if (collectLocation) {
            locationListener.startListening();
        } else {
            locationListener.stopListening();
        }
    }

    //********************
    //** Power Hook API *
    //******************/

    /**
     * Registers a Power Hook for use with TUNE.
     * Use this method to declare the existence of a Power Hook you would like to pass in from TUNE.
     * This declaration should occur in {@link android.app.Application#onCreate()}.
     *
     * @param hookId The name of the configuration setting to register. Name must be unique for this app and cannot be empty.
     * @param friendlyName The name for this hook that will be displayed in TMC. This value cannot be empty.
     * @param defaultValue The default value for this hook.  This value will be used if no value is passed in from TMC for this app. This value cannot be nil.
     *
     */
    public void registerPowerHook(String hookId, String friendlyName, String defaultValue) {
        if (TuneManager.getPowerHookManagerForUser("registerPowerHook") == null) {
            return;
        }

        TuneManager.getInstance().getPowerHookManager().registerPowerHook(hookId, friendlyName, defaultValue, null, null);
    }

    /**
     * Registers a single-value (non-code-block) Power Hook for use with TUNE.
     * Use this method to declare the existence of a Power Hook you would like to pass in from TUNE.
     * This declaration should occur in the {@link android.app.Application#onCreate()} method of your Application.
     *
     * @param hookId The name of the configuration setting to register. Name must be unique for this app and cannot be empty.
     * @param friendlyName The name for this hook that will be displayed in TMC. This value cannot be empty.
     * @param defaultValue The default value for this hook.  This value will be used if no value is passed in from TMC for this app. This value cannot be nil.
     * @param description The description for this Power Hook. This will be shown on the web to help identify this Power Hook if many are registered.
     * @param approvedValues The values that are allowed for this Power Hook.
     *                       Any values entered on the web that don't fit within this array of values will not propagate to the app.
     *
     */
    // NOTE: Private til we release this API.
    private void registerPowerHook(String hookId, String friendlyName, String defaultValue, String description, List<String> approvedValues) {
        if (TuneManager.getPowerHookManagerForUser("registerPowerHook") == null) {
            return;
        }

        TuneManager.getInstance().getPowerHookManager().registerPowerHook(hookId, friendlyName, defaultValue, description, approvedValues);
    }

    /**
     * Gets the value of a Power Hook.
     * Use this method to get the value of a Power Hook from TUNE.
     * This will return the value specified in IAM web console, or the default value if none has been specified.
     * <br>
     * NOTE: If no hook was registered for the given ID, this method will return null.
     *
     * @param hookId The name of the Power Hook you wish to retrieve. Will return nil if the Power Hook has not been registered.
     * @return the value of the Power Hook
     */
    public String getValueForHookById(String hookId) {
        if (TuneManager.getPowerHookManagerForUser("getValueForHookById") == null) {
            return null;
        }

        return TuneManager.getInstance().getPowerHookManager().getValueForHookById(hookId);
    }

    /**
     * Sets the value of a Power Hook.
     * Use this method to set the value of a Power Hook from TUNE.
     * <br>
     * NOTE: ** This is for QA purposes only, you should not use this method in Production as it will override the value sent from the web platform. **
     *
     * @param hookId The name of the Power Hook you wish to set the value for.
     * @param value The new value you would like to test.
     */
    public void setValueForHookById(String hookId, String value) {
        if (TuneManager.getPowerHookManagerForUser("setValueForHookById") == null) {
            return;
        }

        TuneManager.getInstance().getPowerHookManager().setValueForHookById(hookId, value);
    }

    /**
     * Register a callback when any Power Hooks have changed due to a new value from the Server.
     * NOTE: Only the latest callback registered will be executed.
     * <br>
     * NOTE: ** The thread calling the block of code is not guaranteed to be on the main thread.
     * If the code inside of the block requires executing on the main thread you will need to implement this logic. **
     *
     * @param callback The block of code to be executed.
     *
     */
    public void onPowerHooksChanged(TuneCallback callback) {
        if (TuneManager.getPowerHookManagerForUser("onPowerHooksChanged") == null) {
            return;
        }

        TuneManager.getInstance().getPowerHookManager().onPowerHooksChanged(callback);
    }

    /********************
     ** Deep Action API *
     ******************/

    /**
     * Registers a code-block Deep Action for use with TUNE.
     * Use this method to declare the existence of a Power Hook you would like to pass in from TUNE.
     *
     * @param actionId The name of the deep action to register. Name must be unique for this app and cannot be null or empty.
     * @param friendlyName The friendly name for this action that will be displayed in TMC. This value cannot be null.
     * @param defaultData The default values for this deep action.
     *                    These values will be used if no value is passed in from TMC for this app. This cannot be null.
     * @param action The code block that implements Runnable to execute when this Deep Action fires. This cannot be null
     */
    public void registerDeepAction(String actionId, String friendlyName, Map<String, String> defaultData, TuneDeepActionCallback action) {
        if (TuneManager.getDeepActionManagerForUser("registerDeepAction") == null) {
            return;
        }

        TuneManager.getInstance().getDeepActionManager().registerDeepAction(actionId, friendlyName, null, defaultData, null, action);
    }

    /**
     * Registers a code-block Deep Action for use with TUNE.
     * Use this method to declare the existence of a Power Hook you would like to pass in from TUNE.
     *
     * @param actionId The name of the deep action to register. Name must be unique for this app and cannot be null or empty.
     * @param friendlyName The friendly name for this action that will be displayed in TMC. This value cannot be null.
     * @param description An optional description ofr this Deep Action.
     * @param defaultData The default values for this deep action.
     *                    These values will be used if no value is passed in from TMC for this app. This cannot be null.
     * @param action The code block that implements Runnable to execute when this Deep Action fires. This cannot be null
     */
    // NOTE: Private til we release this API.
    private void registerDeepAction(String actionId, String friendlyName, String description, Map<String, String> defaultData, TuneDeepActionCallback action) {
        if (TuneManager.getDeepActionManagerForUser("registerDeepAction") == null) {
            return;
        }

        TuneManager.getInstance().getDeepActionManager().registerDeepAction(actionId, friendlyName, description, defaultData, null, action);
    }

    /**
     * Registers a code-block Deep Action for use with TUNE.
     * Use this method to declare the existence of a Power Hook you would like to pass in from TUNE.
     *
     * @param actionId The name of the deep action to register. Name must be unique for this app and cannot be null or empty.
     * @param friendlyName The friendly name for this action that will be displayed in TMC. This value cannot be null.
     * @param description An optional description ofr this Deep Action.
     * @param defaultData The default values for this deep action.
     *                    These values will be used if no value is passed in from TMC for this app. This cannot be null.
     * @param approvedValues An optional Map of key to list of approved values.
     * @param action The code block that implements Runnable to execute when this Deep Action fires. This cannot be null
     */
    // NOTE: Private til we release this API.
    private void registerDeepAction(String actionId, String friendlyName, String description,
                                    Map<String, String> defaultData, Map<String, List<String>> approvedValues, TuneDeepActionCallback action) {
        if (TuneManager.getDeepActionManagerForUser("registerDeepAction") == null) {
            return;
        }

        TuneManager.getInstance().getDeepActionManager().registerDeepAction(actionId, friendlyName, description, defaultData, approvedValues, action);
    }

    /**
     * Executes a previously registered Deep Action code-block.
     * The data to be used by the current execution of the deep action code-block is derived by merging the Map provided here with the default Map provided
     * during deep action registration. Also, the new values take preference over the default values when the keys match.
     *
     * @param activity Activity object to be made available to the deep action code-block. This object may be null depending on its usage in the code-block.
     * @param actionId Non-empty non-null name of a previously registered deep action code-block.
     * @param data Values to be used with the deep action. This Map may be null or empty or contain string keys and values.
     */
    public void executeDeepAction(Activity activity, String actionId, Map<String, String> data) {
        if (TuneManager.getDeepActionManagerForUser("executeDeepAction") == null) {
            return;
        }

        TuneManager.getInstance().getDeepActionManager().executeDeepAction(activity, actionId, data);
    }

    /****************************
     ** Experiment Details API *
     **************************/

    /**
     * Get details for all currently running Power Hook Variable experiments.
     * Details include the hook id for the Power Hook, experiment and variation ids and start
     * and end date of the experiment.
     *
     * @return a `Map` of experiment details for all running Power Hook variable experiments,
     *     where the keys are the `String` Power Hook IDs of the Power Hooks, and the values
     *     are `TunePowerHookExperimentDetails` objects.
     */
    public Map<String, TunePowerHookExperimentDetails> getPowerHookExperimentDetails() {
        if (TuneManager.getExperimentManagerForUser("getPowerHookExperimentDetails") == null) {
            return null;
        }

        return TuneManager.getInstance().getExperimentManager().getPhookExperimentDetails();
    }


    /**
     * Get details for all currently running In App Message experiments.
     * Details include the experiment and variation ids and start and end date of the experiment.
     *
     * @return a `HashMap` of experiment details for all running In App Message experiments,
     *     where the keys are the `String` campaign ids of the In App Messages, and the values are
     *     `TuneInAppMessageExperimentDetails` objects.
     */
    public Map<String, TuneInAppMessageExperimentDetails> getInAppMessageExperimentDetails() {
        if (TuneManager.getExperimentManagerForUser("getInAppMessageExperimentDetails") == null) {
            return null;
        }

        return TuneManager.getInstance().getExperimentManager().getInAppExperimentDetails();
    }

    /**************************************
     ** On First Playlist Downloaded API *
     ************************************/

    /**
     * Register callback when the first playlist is downloaded for App's lifetime.
     * Use this method to register a callback the first time a playlist is downloaded.
     * This call is non-blocking so code execution will continue immediately to the next line of code.
     * <br>
     * <b>IMPORTANT:</b> The thread executing the callback is not going to be on the main thread.
     * You will need to implement custom logic if you want to ensure that the block of code always executes on the main thread.
     * <br>
     * If the first playlist has already been downloaded when this call is made the callback is executed immediately on a background thread.
     * <br>
     * Otherwise the callback will fire after {@link TuneConstants#DEFAULT_FIRST_PLAYLIST_DOWNLOADED_TIMEOUT} milliseconds or when the first playlist is
     * downloaded, whichever comes first.
     * <br>
     * NOTE: This callback will fire upon first playlist download from the application start and upon each callback registration call.
     * If registered more than once, the latest callback will always fire, regardless of whether a previously registered callback already executed.
     * We do not recommend registering more than once but if you do so, please make sure that executing the callback more than once will not cause any issues in
     * your app.
     * <br>
     * NOTE: Only one callback can be registered at a time. Each time a callback is registered it will fire.
     * <br>
     * NOTE: Pending callbacks will be canceled upon app background and resumed upon app foreground.
     * <br>
     * WARNING: If TMA is not enabled then this callback will never fire.
     *
     * @param callback A TuneCallback object that is to be executed.
     */
    public void onFirstPlaylistDownloaded(final TuneCallback callback) {
        if (TuneManager.getPlaylistManagerForUser("onFirstPlaylistDownloaded") == null) {
            return;
        }

        TuneManager.getInstance().getPlaylistManager().onFirstPlaylistDownloaded(callback, TuneConstants.DEFAULT_FIRST_PLAYLIST_DOWNLOADED_TIMEOUT);
    }


    /**
     * Register callback when the first playlist is downloaded for the App's lifetime.
     * Use this method to register a callback for the first time a playlist is downloaded.
     * This call is non-blocking so code execution will continue immediately to the next line of code.
     * <br>
     * <b>IMPORTANT:</b> The thread executing the callback is not going to be on the main thread.
     * You will need to implement custom logic if you want to ensure that the block of code always executes on the main thread.
     * <br>
     * If the first playlist has already been downloaded when this call is made the callback is executed immediately on a background thread.
     * <br>
     * Otherwise the callback will fire after the given timeout (in milliseconds) or when the first playlist is downloaded, whichever comes first.
     * <br>
     * If the timeout is greater than zero, the callback will fire when the timeout expires or the first playlist is downloaded, whichever comes first.
     * <br>
     * NOTE: This callback will fire upon first playlist download from the application start and upon each callback registration call.
     * If registered more than once, the latest callback will always fire, regardless of whether a previously registered callback already executed.
     * We do not recommend registering more than once but if you do so, please make sure that executing the callback more than once will not cause any issues in
     * your app.
     * <br>
     * NOTE: Only one callback can be registered at a time. Each time a callback is registered it will fire.
     * <br>
     * NOTE: Pending callbacks will be canceled upon app background and resumed upon app foreground.
     * <br>
     * WARNING: If TMA is not enabled then this callback will never fire.
     *
     * @param callback A TuneCallback object that is to be executed.
     * @param timeout The number of miliseconds to wait until executing the callback regardless
     *                of Playlist download.
     */
    public void onFirstPlaylistDownloaded(TuneCallback callback, long timeout) {
        if (TuneManager.getPlaylistManagerForUser("onFirstPlaylistDownloaded") == null) {
            return;
        }

        TuneManager.getInstance().getPlaylistManager().onFirstPlaylistDownloaded(callback, timeout);
    }

    //***********************
    //** In-App Message API *
    //***********************/

    /**
     * Get in-app messages for this device that are triggered by custom event.
     * @param eventName Event name of custom {@link TuneEvent} that would trigger these messages
     * @return List of messages that are triggered from the given event
     */
    public List<TuneInAppMessage> getInAppMessagesForCustomEvent(String eventName) {
        if (TuneManager.getInAppMessageManagerForUser("getInAppMessagesForCustomEvent") == null) {
            return null;
        }

        return TuneManager.getInstance().getInAppMessageManager().getMessagesForCustomEvent(eventName);
    }

    /**
     * Get in-app messages for this device that are triggered by a push opened event for a specific TUNE push message.
     * @param pushId Push id (message variation id) of the push notification.
     * @return List of messages that are triggered by Push Opened event
     */
    public List<TuneInAppMessage> getInAppMessagesForPushOpened(String pushId) {
        if (TuneManager.getInAppMessageManagerForUser("getInAppMessagesForPushOpened") == null) {
            return null;
        }

        return TuneManager.getInstance().getInAppMessageManager().getMessagesForPushOpened(pushId);
    }

    /**
     * Get in-app messages for this device that are triggered by a Push Enabled/Disabled event.
     * Push Enabled or Disabled events are sent when a user enables/disables push at a system level
     * or app level {@link Tune#setOptedOutOfPush(boolean)}
     * or the user profile's age does not meet COPPA (automatically disabled)
     * @param pushEnabled Whether Push Enabled or Push Disabled is the event to use
     * @return List of messages that are triggered by Push Enabled/Disabled event
     */
    public List<TuneInAppMessage> getInAppMessagesForPushEnabled(boolean pushEnabled) {
        if (TuneManager.getInAppMessageManagerForUser("getInAppMessagesForPushEnabled") == null) {
            return null;
        }

        return TuneManager.getInstance().getInAppMessageManager().getMessagesForPushEnabled(pushEnabled);
    }

    /**
     * Get in-app messages for this device that are triggered by a "Starts App" event.
     * "Starts App" events are actually triggered on the first IAM playlist downloaded after the app
     * launches and not immediately on application foreground.
     * <br>
     * We do this so that all IAM messages, power hooks, deep actions, etc. are populated with the
     * latest values by the time the message should be shown.
     * @return List of messages that are triggered by "Starts App" event
     */
    public List<TuneInAppMessage> getInAppMessagesForStartsApp() {
        if (TuneManager.getInAppMessageManagerForUser("getInAppMessagesForStartsApp") == null) {
            return null;
        }

        return TuneManager.getInstance().getInAppMessageManager().getMessagesForStartsApp();
    }

    /**
     * Get in-app messages for this device that are triggered by a Screen Viewed event.
     * @param activityName Activity name for the Viewed Screen event
     * @return List of messages that are triggered by Viewed Screen event
     */
    public List<TuneInAppMessage> getInAppMessagesForScreenViewed(String activityName) {
        if (TuneManager.getInAppMessageManagerForUser("getInAppMessagesForScreenViewed") == null) {
            return null;
        }

        return TuneManager.getInstance().getInAppMessageManager().getMessagesForScreenViewed(activityName);
    }

    /**
     * Get all in-app messages that can be shown for this device, sorted by message variation id.
     * Messages can be modified before displaying, or manually triggered to display.
     *
     * @return `Map` of in-app messages for this device, where the keys are the `String` message
     *     variation ids of the In-App Message, and the values are {@link TuneInAppMessage} objects.
     */
    public Map<String, TuneInAppMessage> getInAppMessagesByIds() {
        if (TuneManager.getInAppMessageManagerForUser("getInAppMessagesByIds") == null) {
            return null;
        }

        return TuneManager.getInstance().getInAppMessageManager().getMessagesByIds();
    }

    /**
     * Get all in-app messages that can be shown for this device, sorted by trigger event.
     * Messages can be modified for displaying, or manually triggered to display.
     *
     * @return `Map` of in-app messages for this device, where the keys are the `String` trigger
     *     event (event hash) of the In-App Message, and the values are a list of {@link TuneInAppMessage} objects with that trigger.
     */
    public Map<String, List<TuneInAppMessage>> getInAppMessagesByTriggerEvents() {
        if (TuneManager.getInAppMessageManagerForUser("getInAppMessagesByTriggerEvents") == null) {
            return null;
        }

        return TuneManager.getInstance().getInAppMessageManager().getMessagesByTriggerEvents();
    }

    /**
     * Preload all messages in a given Activity, so they're ready to be shown quickly.
     * @param activity Activity where messages would be shown when triggered
     */
    public void preloadMessages(Activity activity) {
        if (TuneManager.getInAppMessageManagerForUser("preloadMessages") == null) {
            return;
        }

        TuneManager.getInstance().getInAppMessageManager().preloadMessages(activity);
    }

    /**
     * Preload all messages that are triggered by given custom event.
     * @param activity Activity where messages would be shown when triggered
     * @param eventName Trigger event name for the messages
     */
    public void preloadMessagesForCustomEvent(Activity activity, String eventName) {
        if (TuneManager.getInAppMessageManagerForUser("preloadMessagesForCustomEvent") == null) {
            return;
        }

        TuneManager.getInstance().getInAppMessageManager().preloadMessagesForCustomEvent(activity, eventName);
    }

    /**
     * Preload a single message with given message ID.
     * @param activity Activity where message would be shown when triggered
     * @param messageId Message ID
     */
    public void preloadMessageWithId(Activity activity, String messageId) {
        if (TuneManager.getInAppMessageManagerForUser("preloadMessageWithId") == null) {
            return;
        }

        TuneManager.getInstance().getInAppMessageManager().preloadMessageWithId(activity, messageId);
    }

    /**
     * Sets a layout to use as the loading screen before a full screen in-app message is shown, in place of the system default ProgressDialog.
     * @param layoutId layout id of the custom load screen to use
     */
    public void setFullScreenLoadingScreen(int layoutId) {
        if (TuneManager.getInAppMessageManagerForUser("setFullScreenLoadingScreen") == null) {
            return;
        }

        TuneManager.getInstance().getInAppMessageManager().setFullScreenLoadingScreen(layoutId);
    }


    //*************
    //** Push API *
    //*************/

    /**
     * Provide Tune with your Push Sender ID. This is your Google API Project Number.
     * We will handle getting the GCM registration Id for each device.
     * <br>
     * You get your project number when you set up a project at https://console.developers.google.com/project
     * <br>
     * By setting a push sender Id you are implicitly enabling Tune Push Messaging.
     * <br>
     * IMPORTANT: If you use this method you should not use {@link Tune#setPushNotificationRegistrationId(String)}
     *
     * @param pushSenderId Your Push Sender Id.
     */
    public void setPushNotificationSenderId(String pushSenderId) {
        if (TuneManager.getPushManagerForUser("setPushNotificationSenderId") == null) {
            return;
        }

        TuneManager.getInstance().getPushManager().setPushNotificationSenderId(pushSenderId);
    }

    /**
     * Provide Tune with your GCM registration id for this device.
     * By using this method you are implicitly enabling Tune Push Messaging.
     * <br>
     * IMPORTANT: If you use this method you should not use {@link Tune#setPushNotificationSenderId(String)}
     *
     * @param registrationId The device token you want to use.
     */
    public void setPushNotificationRegistrationId(String registrationId) {
        if (TuneManager.getPushManagerForUser("setPushNotificationRegistrationId") == null) {
            return;
        }

        TuneManager.getInstance().getPushManager().setPushNotificationRegistrationId(registrationId);
    }

    /**
     * Provide Tune with a notification builder to provide defaults for your app's notifications.
     * If you do not use {@link #setPushNotificationSenderId(String)} this doesn't do anything.
     * <br>
     * Important: If you do not provide a small icon for your notifications via the builder we will
     * default to using your app icon. This may look odd if your app is targeting API 21+ because
     * the OS will take only the alpha of the icon and display that on a neutral background.
     * If your app is set to target API 21+ we strongly recommend that you take advantage of the
     * {@link TuneNotificationBuilder} API.
     *
     * @param builder by providing a {@link TuneNotificationBuilder} you can provide defaults for
     *                your app's notifications for Tune Push Messages, like the small icon
     */
    public void setPushNotificationBuilder(TuneNotificationBuilder builder) {
        if (TuneManager.getPushManagerForUser("setPushNotificationBuilder") == null) {
            return;
        }

        TuneManager.getInstance().getPushManager().setTuneNotificationBuilder(builder);
    }

    /**
     * Provide Tune with a push listener to access extraPushPayload and decide if a notification should be displayed.
     *
     * @param listener by providing a {@link TunePushListener} you can access the extraPushPayload and decide if the notification should be displayed
     */
    public void setPushListener(TunePushListener listener) {
        if (TuneManager.getPushManagerForUser("setPushListener") == null) {
            return;
        }

        TuneManager.getInstance().getPushManager().setTunePushListener(listener);
    }

    /**
     * Specify whether the current user has opted out of push messaging.
     * This information is added to the personalization profile of the current user for segmentation,
     * targeting, and reporting purposes.
     * <br>
     * Also, if you are using Tune Push, then by default Tune will assume push messaging is enabled
     * as long as the user has Google Play Services installed on their device and we can successfully
     * get a device token for their device.
     * <br>
     * If you have a custom setting that gives your end users the ability to turn off notifications
     * for your app, you can use this method to pass that setting on to Tune.
     * Tune will not send notifications to devices where this setting is turned off.
     * <br>
     * This can be called from anywhere in your app.
     *
     * @param optedOutOfPush Whether the user opted out of push messaging.
     */
    public void setOptedOutOfPush(boolean optedOutOfPush) {
        if (TuneManager.getPushManagerForUser("setOptedOutOfPush") == null) {
            return;
        }

        TuneManager.getInstance().getPushManager().setOptedOutOfPush(optedOutOfPush);
    }

    /**
     * Returns the currently registered device token for push.
     * @return The currently registered device token for push, or null if we aren't registered.
     */
    public String getDeviceToken() {
        if (TuneManager.getPushManagerForUser("getDeviceToken") == null) {
            return null;
        }

        return TuneManager.getInstance().getPushManager().getDeviceToken();
    }

    /**
     * Returns if the user manually disabled push on the Application Setting page.
     * If this returns true then nothing will be allowed to be posted to the tray, not just push notifications
     *
     * @return Whether the user manually disabled push from the Application Settings screen if API Level &gt;= 19, otherwise false.
     */
    public boolean didUserManuallyDisablePush() {
        if (TuneManager.getPushManagerForUser("didUserManuallyDisablePush") == null) {
            return false;
        }

        return TuneManager.getInstance().getPushManager().didUserManuallyDisablePush();
    }

    /**
     * Returns if the current session is because the user opened a push notification.
     * This status is reset to false when the application becomes backgrounded.
     * *NOTE:* If you are implementing {@link com.tune.ma.application.TuneActivity} manually then
     * this should be called after `super.onStart();` in the activity.
     *
     * @return true if this session was started because the user opened a push message, otherwise false.
     */
    public boolean didSessionStartFromTunePush() {
        if (TuneManager.getPushManagerForUser("didSessionStartFromTunePush") == null) {
            return false;
        }

        return TuneManager.getInstance().getPushManager().didOpenFromTunePushThisSession();
    }

    /**
     * Returns a POJO containing information about the push message that started the current session.
     * This is reset to null when the application becomes backgrounded.
     * *NOTE:* If you are implementing {@link com.tune.ma.application.TuneActivity} manually then
     * this should be called after `super.onStart();` in the activity.
     *
     * @return Information about the last opened push if {@link Tune#didSessionStartFromTunePush()} is true, otherwise null.
     */
    public TunePushInfo getTunePushInfoForSession() {
        if (TuneManager.getPushManagerForUser("getTunePushInfoForSession") == null) {
            return null;
        }

        return TuneManager.getInstance().getPushManager().getLastOpenedPushInfo();
    }

    //****************
    //** Segment API *
    //****************/

    /**
     * Returns whether the user belongs to the given segment.
     * @param segmentId Segment ID to check for a match
     * @return whether the user belongs to the given segment
     */
    public boolean isUserInSegmentId(String segmentId) {
        if (TuneManager.getPlaylistManagerForUser("isUserInSegmentId") == null) {
            return false;
        }

        return TuneManager.getInstance().getPlaylistManager().isUserInSegmentId(segmentId);
    }

    /**
     * Returns whether the user belongs to any of the given segments.
     * @param segmentIds Segment IDs to check for a match
     * @return whether the user belongs to any of the given segments
     */
    public boolean isUserInAnySegmentIds(List<String> segmentIds) {
        if (TuneManager.getPlaylistManagerForUser("isUserInAnySegmentIds") == null) {
            return false;
        }

        return TuneManager.getInstance().getPlaylistManager().isUserInAnySegmentIds(segmentIds);
    }

    //*****************
    //** Deeplink API *
    //*****************/

    /**
     * Remove the deeplink listener previously set with {@link #registerDeeplinkListener(TuneDeeplinkListener)}.
     */
    public void unregisterDeeplinkListener() {
        dplinkr.setListener(null);
    }

    /**
     * Set the deeplink listener that will be called when either a deferred deeplink is found for a fresh install or for handling an opened Tune Link.
     * Registering a deeplink listener will trigger an asynchronous call to check for deferred deeplinks
     * during the first session after installing of the app with the Tune SDK.
     * <br>
     * The {@code TuneDeeplinkListener#didFailWithError} callback will be called if there is no
     * deferred deeplink from Tune for this user or in the event of an error from the server
     * (possibly due to misconfiguration).
     * <br>
     * The {@code TuneDeeplinkListener#didReceiveDeeplink} callback will be called when there is a
     * deep link from Tune that you should route the user to. The string should be a fully qualified deep link url string.
     *
     * @param listener will be called with deferred deeplinks after install or expanded Tune links. May be null.
     *                 Passing null will clear the previously set listener, although you may use {@link #unregisterDeeplinkListener()} instead.
     */
    public void registerDeeplinkListener(@Nullable TuneDeeplinkListener listener) {
        dplinkr.setListener(listener);
        requestDeferredDeeplink();
    }

    /**
     * Request a deferred deep link if this is the first install of the app with the Tune SDK.
     */
    private void requestDeferredDeeplink() {
        final boolean shouldRequestDeferredDeeplink = isFirstInstall && dplinkr != null && params != null;

        if (shouldRequestDeferredDeeplink) {
            dplinkr.requestDeferredDeeplink(params.getUserAgent(), urlRequester);
        }
    }

    /**
     * If you have set up a custom domain for use with Tune Links (cname to a *.tlnk.io domain), then register it with this method.
     * Tune Links are Tune-hosted App Links. Tune Links are often shared as short-urls, and the Tune SDK
     * will handle expanding the url and returning the in-app destination url to
     * {@link TuneDeeplinkListener#didReceiveDeeplink(String)} registered via
     * {@link #registerDeeplinkListener(TuneDeeplinkListener)}
     * This method will test if any clicked links match the given suffix. Do not include a * for
     * wildcard subdomains, instead pass the suffix that you would like to match against the url.
     * <br>
     * So, ".customize.it" will match "1235.customize.it" and "56789.customize.it" but not "customize.it"
     * And, "customize.it" will match "1235.customize.it" and "56789.customize.it", "customize.it", and "1235.tocustomize.it"
     * You can register as many custom subdomains as you like.
     * @param domainSuffix domain which you are using for Tune Links. Must not be null.
     */
    public void registerCustomTuneLinkDomain(@NonNull String domainSuffix) {
        dplinkr.registerCustomTuneLinkDomain(domainSuffix);
    }

    /**
     * Test if your custom Tune Link domain is registered with Tune.
     * Tune Links are Tune-hosted App Links. Tune Links are often shared as short-urls, and the Tune SDK
     * will handle expanding the url and returning the in-app destination url to {@link TuneDeeplinkListener#didReceiveDeeplink(String)}
     * registered via {@link #registerDeeplinkListener(TuneDeeplinkListener)}
     * @param appLinkUrl url to test if it is a Tune Link. Must not be null.
     * @return true if this link is a Tune Link that will be measured by Tune and routed into the {@link TuneDeeplinkListener}.
     */
    public boolean isTuneLink(@NonNull String appLinkUrl) {
        return dplinkr.isTuneLink(appLinkUrl);
    }

    /**
     * Set the Url Requester.
     * @param urlRequester UrlRequester
     */
    protected void setUrlRequester(UrlRequester urlRequester) {
        this.urlRequester = urlRequester;
    }


    //**********************
    //* MA Profile Methods *
    //**********************/

    // Register

    /**
     * Register a custom profile variable for this user.
     * This custom variable will be included in this user's personalization profile, and can be used for segmentation, targeting, and reporting purposes.<br>
     * <br>
     * Once registered, the value for this variable can be set using
     *  {@link Tune#setCustomProfileStringValue(String, String)}. The default value is nil. <br>
     * <br>
     * This method should be called in your Application class {@link android.app.Application#onCreate()} method.<br>
     * @param variableName Name of the variable to register for the current user.
     *                     Valid characters for this name include [0-9],[a-z],[A-Z], -, and _.
     *                     Any other characters will automatically be stripped out.
     */
    public void registerCustomProfileString(String variableName) {
        if (TuneManager.getProfileForUser("registerCustomProfileString") == null) {
            return;
        }

        TuneManager.getInstance().getProfileManager().registerCustomProfileVariable(
                TuneAnalyticsVariable.Builder(variableName)
                        .withType(TuneVariableType.STRING)
                        .build());
    }

    /**
     * Register a custom profile variable for this user.
     * This custom variable will be included in this user's personalization profile,
     * and can be used for segmentation, targeting, and reporting purposes.
     * <br>
     * Once registered, the value for this variable can be set using
     *  {@link Tune#setCustomProfileStringValue(String, String)}. The default value is nil.
     * <br>
     * This method should be called in your Application class {@link android.app.Application#onCreate()} method.
     * <br>
     * @param variableName Name of the variable to register for the current user.
     *                     Valid characters for this name include [0-9],[a-z],[A-Z], -, and _.
     *                     Any other characters will automatically be stripped out.
     * @param defaultValue Initial value for the variable.
     */
    public void registerCustomProfileString(String variableName, String defaultValue) {
        if (TuneManager.getProfileForUser("registerCustomProfileString") == null) {
            return;
        }

        TuneManager.getInstance().getProfileManager().registerCustomProfileVariable(new TuneAnalyticsVariable(variableName, defaultValue));
    }

    /**
     * Register a custom profile variable for this user.
     * This custom variable will be included in this user's personalization profile, and can be used for segmentation, targeting, and reporting purposes.<br>
     * <br>
     * Once registered, the value for this variable can be set using {@link Tune#setCustomProfileDate(String, Date)}. The default value is nil. <br>
     * <br>
     * This method should be called in your Application class {@link android.app.Application#onCreate()} method.<br>
     * @param variableName Name of the variable to register for the current user.
     *                     Valid characters for this name include [0-9],[a-z],[A-Z], -, and _.
     *                     Any other characters will automatically be stripped out.
     */
    public void registerCustomProfileDate(String variableName) {
        if (TuneManager.getProfileForUser("registerCustomProfileDate") == null) {
            return;
        }

        TuneManager.getInstance().getProfileManager().registerCustomProfileVariable(
                TuneAnalyticsVariable.Builder(variableName)
                        .withType(TuneVariableType.DATETIME)
                        .build());
    }

    /**
     * Register a custom profile variable for this user.
     * This custom variable will be included in this user's personalization profile,
     * and can be used for segmentation, targeting, and reporting purposes.
     * <br>
     * Once registered, the value for this variable can be set using
     *  {@link Tune#setCustomProfileDate(String, Date)}. The default value is nil.
     * <br>
     * This method should be called in your Application class {@link android.app.Application#onCreate()} method.
     * @param variableName Name of the variable to register for the current user.
     *                     Valid characters for this name include [0-9],[a-z],[A-Z], -, and _.
     *                     Any other characters will automatically be stripped out.
     * @param defaultValue Initial value for the variable.
     */
    public void registerCustomProfileDate(String variableName, Date defaultValue) {
        if (TuneManager.getProfileForUser("registerCustomProfileDate") == null) {
            return;
        }

        TuneManager.getInstance().getProfileManager().registerCustomProfileVariable(new TuneAnalyticsVariable(variableName, defaultValue));
    }

    /**
     * Register a custom profile variable for this user.
     * This custom variable will be included in this user's personalization profile,
     * and can be used for segmentation, targeting, and reporting purposes.
     * <br>
     * Once registered, the value for this variable can be set using
     *  {@link Tune#setCustomProfileGeolocation(String, TuneLocation)}. The default value is nil.
     * <br>
     * This method should be called in your Application class {@link android.app.Application#onCreate()} method.
     * @param variableName Name of the variable to register for the current user.
     *                     Valid characters for this name include [0-9],[a-z],[A-Z], -, and _.
     *                     Any other characters will automatically be stripped out.
     */
    public void registerCustomProfileGeolocation(String variableName) {
        if (TuneManager.getProfileForUser("registerCustomProfileGeolocation") == null) {
            return;
        }

        TuneManager.getInstance().getProfileManager().registerCustomProfileVariable(
                TuneAnalyticsVariable.Builder(variableName)
                        .withType(TuneVariableType.GEOLOCATION)
                        .build());
    }

    /**
     * Register a custom profile variable for this user.
     * This custom variable will be included in this user's personalization profile,
     * and can be used for segmentation, targeting, and reporting purposes.
     * <br>
     * Once registered, the value for this variable can be set using
     *  {@link Tune#setCustomProfileGeolocation(String, TuneLocation)}. The default value is nil.
     * <br>
     * This method should be called in your Application class {@link android.app.Application#onCreate()} method.
     * @param variableName Name of the variable to register for the current user.
     *                     Valid characters for this name include [0-9],[a-z],[A-Z], -, and _.
     *                     Any other characters will automatically be stripped out.
     * @param defaultValue Initial value for the variable.
     */
    public void registerCustomProfileGeolocation(String variableName, TuneLocation defaultValue) {
        if (TuneManager.getProfileForUser("registerCustomProfileGeolocation") == null) {
            return;
        }

        TuneManager.getInstance().getProfileManager().registerCustomProfileVariable(new TuneAnalyticsVariable(variableName, defaultValue));
    }

    /**
     * Register a custom profile variable for this user.
     * This custom variable will be included in this user's personalization profile,
     * and can be used for segmentation, targeting, and reporting purposes.
     * <br>
     * Once registered, the value for this variable can be set using
     *  {@link Tune#setCustomProfileNumber(String, double)},
     *  {@link Tune#setCustomProfileNumber(String, float)}, or
     *  {@link Tune#setCustomProfileNumber(String, int)}.
     * You may use these setters interchangeably. The default value is nil.
     * <br>
     * This method should be called in your Application class {@link android.app.Application#onCreate()} method.
     * @param variableName Name of the variable to register for the current user.
     *                     Valid characters for this name include [0-9],[a-z],[A-Z], -, and _.
     *                     Any other characters will automatically be stripped out.
     */
    public void registerCustomProfileNumber(String variableName) {
        if (TuneManager.getProfileForUser("registerCustomProfileNumber") == null) {
            return;
        }

        TuneManager.getInstance().getProfileManager().registerCustomProfileVariable(
                TuneAnalyticsVariable.Builder(variableName)
                        .withType(TuneVariableType.FLOAT)
                        .build());
    }

    /**
     * Register a custom profile variable for this user.
     * This custom variable will be included in this user's personalization profile,
     * and can be used for segmentation, targeting, and reporting purposes.
     * <br>
     * Once registered, the value for this variable can be set using
     *  {@link Tune#setCustomProfileNumber(String, double)},
     *  {@link Tune#setCustomProfileNumber(String, float)}, or
     *  {@link Tune#setCustomProfileNumber(String, int)}.
     * You may use these setters interchangeably. The default value is nil.
     * <br>
     * This method should be called in your Application class {@link android.app.Application#onCreate()} method.
     * @param variableName Name of the variable to register for the current user.
     *                     Valid characters for this name include [0-9],[a-z],[A-Z], -, and _.
     *                     Any other characters will automatically be stripped out.
     * @param defaultValue Initial value for the variable.
     */
    public void registerCustomProfileNumber(String variableName, int defaultValue) {
        if (TuneManager.getProfileForUser("registerCustomProfileNumber") == null) {
            return;
        }

        TuneManager.getInstance().getProfileManager().registerCustomProfileVariable(new TuneAnalyticsVariable(variableName, defaultValue));
    }

    /**
     * Register a custom profile variable for this user.
     * This custom variable will be included in this user's personalization profile,
     * and can be used for segmentation, targeting, and reporting purposes.
     * <br>
     * Once registered, the value for this variable can be set using
     *  {@link Tune#setCustomProfileNumber(String, double)},
     *  {@link Tune#setCustomProfileNumber(String, float)}, or
     *  {@link Tune#setCustomProfileNumber(String, int)}.
     * You may use these setters interchangeably. The default value is nil.
     * <br>
     * This method should be called in your Application class {@link android.app.Application#onCreate()} method.<br>
     * @param variableName Name of the variable to register for the current user.
     *                     Valid characters for this name include [0-9],[a-z],[A-Z], -, and _.
     *                     Any other characters will automatically be stripped out.
     * @param defaultValue Initial value for the variable.
     */
    public void registerCustomProfileNumber(String variableName, double defaultValue) {
        if (TuneManager.getProfileForUser("registerCustomProfileNumber") == null) {
            return;
        }

        TuneManager.getInstance().getProfileManager().registerCustomProfileVariable(new TuneAnalyticsVariable(variableName, defaultValue));
    }

    /**
     * Register a custom profile variable for this user.
     * This custom variable will be included in this user's personalization profile,
     * and can be used for segmentation, targeting, and reporting purposes.
     * <br>
     * Once registered, the value for this variable can be set using
     *  {@link Tune#setCustomProfileNumber(String, double)},
     *  {@link Tune#setCustomProfileNumber(String, float)}, or
     *  {@link Tune#setCustomProfileNumber(String, int)}.
     * You may use these setters interchangeably. The default value is nil.
     * <br>
     * This method should be called in your Application class {@link android.app.Application#onCreate()} method.
     * @param variableName Name of the variable to register for the current user.
     *                     Valid characters for this name include [0-9],[a-z],[A-Z], -, and _.
     *                     Any other characters will automatically be stripped out.
     * @param defaultValue Initial value for the variable.
     */
    public void registerCustomProfileNumber(String variableName, float defaultValue) {
        if (TuneManager.getProfileForUser("registerCustomProfileNumber") == null) {
            return;
        }

        TuneManager.getInstance().getProfileManager().registerCustomProfileVariable(new TuneAnalyticsVariable(variableName, defaultValue));
    }

    /**
     * Set or update the value associated with a custom string profile variable.
     * This new value will be used as part of this user's personalization profile,
     * and will be used from this point forward for segmentation, targeting, and reporting purposes.
     * <br>
     * This can be called from anywhere in your app after the appropriate register call in {@link android.app.Application#onCreate()}.
     * @param value Value to use for the given variable.
     * @param variableName Variable to which this value should be assigned.
     *                     Passing null or an empty string value will have the same effect as calling
     *                     {@link #clearCustomProfileVariable(String)}.
     */
    public void setCustomProfileStringValue(String variableName, String value) {
        if (TuneManager.getProfileForUser("setCustomProfileStringValue") == null) {
            return;
        }

        if (TextUtils.isEmpty(value)) {
            clearCustomProfileVariable(variableName);
        } else {
            TuneManager.getInstance().getProfileManager().setCustomProfileVariable(new TuneAnalyticsVariable(variableName, value));
        }
    }

    /**
     * Set or update the value associated with a custom date profile variable.
     * This new value will be used as part of this user's personalization profile,
     * and will be used from this point forward for segmentation, targeting, and reporting purposes.
     * <br>
     * This can be called from anywhere in your app after the appropriate register call in {@link android.app.Application#onCreate()}.
     * @param value Value to use for the given variable.
     * @param variableName Variable to which this value should be assigned.
     *                     Passing a null value will have the same effect as calling
     *                     {@link #clearCustomProfileVariable(String)}.
     */
    public void setCustomProfileDate(String variableName, Date value) {
        if (TuneManager.getProfileForUser("setCustomProfileDate") == null) {
            return;
        }

        if (value == null) {
            clearCustomProfileVariable(variableName);
        } else {
            TuneManager.getInstance().getProfileManager().setCustomProfileVariable(new TuneAnalyticsVariable(variableName, value));
        }
    }

    /**
     * Set or update the value associated with a custom location profile variable.
     * This new value will be used as part of this user's personalization profile,
     * and will be used from this point forward for segmentation, targeting, and reporting purposes.
     * <br>
     * This can be called from anywhere in your app after the appropriate register call in {@link android.app.Application#onCreate()}.
     * @param value Value to use for the given variable.
     * @param variableName Variable to which this value should be assigned.
     *                     Passing a null value will have the same effect as calling
     *                     {@link #clearCustomProfileVariable(String)}.
     */
    public void setCustomProfileGeolocation(String variableName, TuneLocation value) {
        if (TuneManager.getProfileForUser("setCustomProfileGeolocation") == null) {
            return;
        }

        if (value == null) {
            clearCustomProfileVariable(variableName);
        } else {
            TuneManager.getInstance().getProfileManager().setCustomProfileVariable(new TuneAnalyticsVariable(variableName, value));
        }
    }

    /**
     * Set or update the value associated with a custom number profile variable.
     * This new value will be used as part of this user's personalization profile,
     * and will be used from this point forward for segmentation, targeting, and reporting purposes.
     * <br>
     * This can be called from anywhere in your app after the appropriate register call in {@link android.app.Application#onCreate()}.
     * @param value Value to use for the given variable.
     * @param variableName Variable to which this value should be assigned.
     */
    public void setCustomProfileNumber(String variableName, int value) {
        if (TuneManager.getProfileForUser("setCustomProfileNumber") == null) {
            return;
        }

        TuneManager.getInstance().getProfileManager().setCustomProfileVariable(new TuneAnalyticsVariable(variableName, value));
    }

    /**
     * Set or update the value associated with a custom number profile variable.
     * This new value will be used as part of this user's personalization profile,
     * and will be used from this point forward for segmentation, targeting, and reporting purposes.
     * <br>
     * This can be called from anywhere in your app after the appropriate register call in {@link android.app.Application#onCreate()}.
     * @param value Value to use for the given variable.
     * @param variableName Variable to which this value should be assigned.
     */
    public void setCustomProfileNumber(String variableName, double value) {
        if (TuneManager.getProfileForUser("setCustomProfileNumber") == null) {
            return;
        }

        TuneManager.getInstance().getProfileManager().setCustomProfileVariable(new TuneAnalyticsVariable(variableName, value));
    }

    /**
     * Set or update the value associated with a custom number profile variable.
     * This new value will be used as part of this user's personalization profile,
     * and will be used from this point forward for segmentation, targeting, and reporting purposes.
     * <br>
     * This can be called from anywhere in your app after the appropriate register call in {@link android.app.Application#onCreate()}.
     * @param value Value to use for the given variable.
     * @param variableName Variable to which this value should be assigned.
     */
    public void setCustomProfileNumber(String variableName, float value) {
        if (TuneManager.getProfileForUser("setCustomProfileNumber") == null) {
            return;
        }

        TuneManager.getInstance().getProfileManager().setCustomProfileVariable(new TuneAnalyticsVariable(variableName, value));
    }

    // Get

    /**
     * Get the value associated with a custom string profile variable.
     * Return the value stored for the custom profile variable.
     * Must be called after the appropriate register call in {@link android.app.Application#onCreate()}.
     * <br>
     * This will return null if the variable was registered without a default and has never been set,
     * or if has been explicitly set as null.
     * @param variableName Name of the custom profile variable.
     * @return Value stored for the variable. It may be null.
     */
    public String getCustomProfileString(String variableName) {
        if (TuneManager.getProfileForUser("getCustomProfileString") == null) {
            return null;
        }

        TuneAnalyticsVariable var = TuneManager.getInstance().getProfileManager().getCustomProfileVariable(variableName);
        if (var == null) {
            return null;
        } else {
            return var.getValue();
        }
    }

    /**
     * Get the value associated with a custom date profile variable.
     * Return the value stored for the custom profile variable.
     * Must be called after the appropriate register call in {@link android.app.Application#onCreate()}.
     * <br>
     * This will return null if the variable was registered without a default and has never been set,
     * or if has been explicitly set as null.
     * @param variableName Name of the custom profile variable.
     * @return Value stored for the variable. It may be null.
     */
    public Date getCustomProfileDate(String variableName) {
        if (TuneManager.getProfileForUser("getCustomProfileDate") == null) {
            return null;
        }

        TuneAnalyticsVariable var = TuneManager.getInstance().getProfileManager().getCustomProfileVariable(variableName);
        if (var == null) {
            return null;
        } else {
            return TuneAnalyticsVariable.stringToDate(var.getValue());
        }
    }

    /**
     * Get the value associated with a custom location profile variable.
     * Return the value stored for the custom profile variable.
     * Must be called after the appropriate register call in {@link android.app.Application#onCreate()}.
     * <br>
     * This will return null if the variable was registered without a default and has never been set, or if has been explicitly set as null.
     * @param variableName Name of the custom profile variable.
     * @return Value stored for the variable. It may be null.
     */
    public TuneLocation getCustomProfileGeolocation(String variableName) {
        if (TuneManager.getProfileForUser("getCustomProfileGeolocation") == null) {
            return null;
        }

        TuneAnalyticsVariable var = TuneManager.getInstance().getProfileManager().getCustomProfileVariable(variableName);
        if (var == null) {
            return null;
        } else {
            return TuneAnalyticsVariable.stringToGeolocation(var.getValue());
        }
    }

    /**
     * Get the value associated with a custom number profile variable.
     * Return the value stored for the custom profile variable.
     * Must be called after the appropriate register call in {@link android.app.Application#onCreate()}.
     * <br>
     * This will return null if the variable was registered without a default and has never been set, or if has been explicitly set as null.
     * @param variableName Name of the custom profile variable.
     * @return Value stored for the variable. It may be null.
     */
    public Number getCustomProfileNumber(String variableName) {
        if (TuneManager.getProfileForUser("getCustomProfileNumber") == null) {
            return null;
        }

        TuneAnalyticsVariable var = TuneManager.getInstance().getProfileManager().getCustomProfileVariable(variableName);
        if (var == null || var.getValue() == null) {
            return null;
        } else {
            return new BigDecimal(var.getValue());
        }
    }

    /**
     * Returns In-App Marketing's generated app ID value.
     * @return App ID in In-App Marketing, or null if IAM was not enabled
     * @deprecated use {@link #getIAMAppId()} instead
     */
    @Deprecated
    public String getAppId() {
        return getIAMAppId();
    }

    /**
     * Returns In-App Marketing's generated application identifier.
     * This value changes if the package name or advertiser id set in the Tune SDK initialization changes.
     *
     * @return App ID for In-App Marketing, or null if IAM was not enabled
     */
    public String getIAMAppId() {
        if (TuneManager.getProfileForUser("getIAMAppId") == null) {
            return null;
        }

        return TuneManager.getInstance().getProfileManager().getAppId();
    }

    /**
     * Returns the identifier for this device's profile for In-App Marketing.
     * NOTE: the device's advertising identifiers, including the Google Advertising Identifier
     * are fetched asynchronously after the Tune SDK is initialized and may change this value.
     * We recommend fetching this value some time after the Tune SDK is initialized.
     *
     * @return Device profile identifier for In-App Marketing, or null if IAM was not enabled
     */
    public String getIAMDeviceId() {
        if (TuneManager.getProfileForUser("getIAMDeviceId") == null) {
            return null;
        }

        return TuneManager.getInstance().getProfileManager().getDeviceId();
    }

    // Clear

    /**
     * Unset the value for a user profile variable.
     * Use this method to clear out the value for any custom user profile variable.
     * <br>
     * This must be called after the associated register call.
     * <br>
     * NOTE: This will not stop the variable from being registered again on the next {@link android.app.Application#onCreate()}.
     * @param variableName Name of the custom profile variable to clear.  The profile variable cannot be null or an empty string.
     */
    public void clearCustomProfileVariable(String variableName) {
        if (TuneManager.getProfileForUser("clearCustomProfileVariable") == null) {
            return;
        }

        if (TextUtils.isEmpty(variableName)) {
            TuneDebugLog.e("Invalid custom profile variable");
            if (debugMode) {
                throw new IllegalArgumentException("Invalid custom profile variable");
            }
        }

        TuneManager.getInstance().getProfileManager().clearCertainCustomProfileVariable(variableName);
    }

    /**
     * Clear out all previously specified profile information.
     * Use this method to clear out all custom profile variables.
     * <br>
     * This will only clear out all profile variables that have been registered before this call.
     * <br>
     * NOTE: This will not stop the variables from being registered again on the next {@link android.app.Application#onCreate()}.
     */
    public void clearAllCustomProfileVariables() {
        if (TuneManager.getProfileForUser("clearAllCustomProfileVariables") == null) {
            return;
        }

        TuneManager.getInstance().getProfileManager().clearAllCustomProfileVariables();
    }

    /**
     * Get the current state of the Tune Parameters.
     * @return the {@link TuneParameters} used to initialize Tune.
     */
    public final TuneParameters getTuneParams() {
        return params;
    }

    //**********************
    //* Smartwhere Methods *
    //**********************

    /**
     * Opt-In Smartwhere Integration with the Tune Marketing Console.
     * @throws TuneConfigurationException if Smartwhere is not available.
     */
    public void enableSmartwhere() throws TuneConfigurationException {
        if (!TuneSmartWhere.isSmartWhereAvailable()) {
            throw new TuneConfigurationException("Smartwhere is not available. Please ensure that the Smartwhere library dependency is included.");
        }

        TuneSmartWhere.getInstance().enable(mContext);
    }

    /**
     * Disable Smartwhere Integration.
     * @throws TuneConfigurationException if Smartwhere is not available.
     */
    public void disableSmartwhere() throws TuneConfigurationException {
        if (!TuneSmartWhere.isSmartWhereAvailable()) {
            throw new TuneConfigurationException("Smartwhere is not available. Please ensure that the Smartwhere library dependency is included.");
        }

        TuneSmartWhere.getInstance().disable(mContext);
    }

    /**
     * Configure Smartwhere using a configuration.
     * @param config {@link TuneSmartWhereConfiguration} Configuration
     * @throws TuneConfigurationException if Smartwhere is not available.
     */
    public void configureSmartwhere(TuneSmartWhereConfiguration config) throws TuneConfigurationException {
        if (!TuneSmartWhere.isSmartWhereAvailable()) {
            throw new TuneConfigurationException("Smartwhere is not available. Please ensure that the Smartwhere library dependency is included.");
        }

        TuneSmartWhere.getInstance().configure(config);
    }
}
