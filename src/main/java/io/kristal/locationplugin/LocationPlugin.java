/**
 *
 * LocationPlugin
 * Location
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2014 Kristal
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 */

package io.kristal.locationplugin;

import android.os.Bundle;
import org.cobaltians.cobalt.Cobalt;
import org.cobaltians.cobalt.fragments.CobaltFragment;
import org.cobaltians.cobalt.plugin.CobaltAbstractPlugin;
import org.cobaltians.cobalt.plugin.CobaltPluginWebContainer;


import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public final class LocationPlugin extends CobaltAbstractPlugin {

    // TAG
    private static final String TAG = LocationPlugin.class.getSimpleName();

    /***********************************************************************************************
     *
     * MEMBERS
     *
     **********************************************************************************************/

    private static final String JSPluginName = "location";

    private static final String JSActionStartLocation = "startLocation";
    private static final String JSActionStopLocation = "stopLocation";
    private static final String JSActionOnLocationChanged = "onLocationChanged";
    private static final String JSActionOnStatusChanged = "onStatusChanged";

    private static final String kJSLocation = "location";
    private static final String kJSAccuracy = "accuracy";
    private static final String kJSInterval = "interval";
    private static final String kJSMode = "mode";
    private static final String kJSTimeout = "timeout";
    private static final String kJSMaxAge = "age";
    private static final String kJSLatitude = "latitude";
    private static final String kJSLongitude = "longitude";
    private static final String kJSTimestamp = "timestamp";
    private static final String kJSStatus = "status";

    private static final String MODE_ALL = "all";
    private static final String MODE_FILTER = "filter";
    private static final String STATUS_DISABLED = "disabled";
    private static final String STATUS_REFUSED = "refused";
    private static final String STATUS_TIMEOUT = "timeout";

    private static final String MODE_DEFAULT_VALUE = MODE_FILTER;
    private static final float ACCURACY_DEFAULT_VALUE = 100;
    private static final long INTERVAL_DEFAULT_VALUE = 500;
    private static final long TIMEOUT_DEFAULT_VALUE = 0;
    private static final long TIMESTAMP_DEFAULT_VALUE = 2 * 60 * 1000;

    /***********************************************************************************************
     *
     * SINGLETON
     *
     **********************************************************************************************/

    protected static LocationPlugin sInstance;

    public static CobaltAbstractPlugin getInstance(CobaltPluginWebContainer webContainer) {
        if (sInstance == null) {
            sInstance = new LocationPlugin();
        }

        sInstance.addWebContainer(webContainer);

        return sInstance;
    }

    private LocationPlugin() {
        listeners = new ArrayList<>();
    }

    /***********************************************************************************************
     *
     * METHODS
     *
     **********************************************************************************************/

    private List<LocationListener> listeners;

    @Override
    public void onMessage(CobaltPluginWebContainer webContainer, JSONObject message) {
        try {
            String action = message.getString(Cobalt.kJSAction);

            switch(action) {
                case JSActionStartLocation:
                    // Remove any previously registered listener for this fragment
                    removeListeningFragment(webContainer.getFragment());

                    JSONObject data = message.getJSONObject(Cobalt.kJSData);
                    String mode = data.optString(kJSMode, MODE_DEFAULT_VALUE);
                    float accuracy = (float) data.optDouble(kJSAccuracy, ACCURACY_DEFAULT_VALUE);
                    long interval = data.optLong(kJSInterval, INTERVAL_DEFAULT_VALUE);
                    long maxAge = data.optLong(kJSMaxAge, TIMESTAMP_DEFAULT_VALUE);
                    long timeout = data.optLong(kJSTimeout, TIMEOUT_DEFAULT_VALUE);

                    CobaltFragment fragment = webContainer.getFragment();
                    LocationListener listener = new LocationListener(fragment, mode, interval, accuracy, maxAge, timeout);
                    addListeningFragment(listener);

                    break;

                case JSActionStopLocation:
                    this.removeListeningFragment(webContainer.getFragment());
                    break;

                default:
                    if (Cobalt.DEBUG) {
                        Log.d(TAG, "onMessage: unknown action " + action);
                    }
                    break;
            }
        }
        catch (JSONException exception) {
            if (Cobalt.DEBUG) {
                Log.d(TAG, "onMessage: action field missing or is not a string or data field is missing or is not an object. " + message.toString());
            }
            exception.printStackTrace();
        }
    }

    private void addListeningFragment(LocationListener locationListener) {
        if (!this.listeners.contains(locationListener)) {
            this.listeners.add(locationListener);
        }
    }

    private void removeListeningFragment(CobaltFragment fragment) {
        for (LocationListener locationListener : listeners) {
            if (locationListener.getFragment() == null || locationListener.getFragment().equals(fragment)) {
                locationListener.stop();
            }
        }
    }

    private void removeListeningFragment(LocationListener locationListener) {
        listeners.remove(locationListener);
    }

    /***********************************************************************************************
     *
     * LOCATION LISTENER
     *
     **********************************************************************************************/

    private class LocationListener implements android.location.LocationListener, ActivityCompat.OnRequestPermissionsResultCallback {
        private WeakReference<CobaltFragment> fragmentReference;

        private final boolean sendAllUpdates;
        private final long interval;
        private final float accuracy;
        private final long maxAge;
        private Timer timer;

        private Context applicationContext;
        private LocationManager locationManager;
        private List<String> providers;
        private Location bestLocation;
        private long lastSentLocationTimestamp = 0;

        private boolean listening = false;

        /*******************************************************************************************
         *
         * METHODS
         *
         ******************************************************************************************/

        public LocationListener(CobaltFragment fragment, String mode, long interval, float accuracy, long maxAge, long timer) {
            fragmentReference = new WeakReference<>(fragment);

            sendAllUpdates = mode.equals(MODE_ALL);
            this.interval = interval;
            this.accuracy = accuracy;
            this.maxAge = maxAge;

            applicationContext = fragment.getActivity().getApplicationContext();
            locationManager = (LocationManager) fragment.getActivity().getSystemService(Context.LOCATION_SERVICE);

            if (timer > 0) {
                this.timer = new Timer();
                this.timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        sendStatus(STATUS_TIMEOUT, bestLocation);
                        stop();
                    }
                }, timer);
            }

            start();
        }

        public void start() {
            if (checkLocationPermission(applicationContext)) {
                providers = new ArrayList<>();

                if (locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
                    providers.add(LocationManager.PASSIVE_PROVIDER);
                }
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    providers.add(LocationManager.NETWORK_PROVIDER);
                }
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    providers.add(LocationManager.GPS_PROVIDER);
                }

                for (String provider : providers) {
                    locationManager.requestLocationUpdates(provider, sendAllUpdates ? interval : INTERVAL_DEFAULT_VALUE, 0, this);

                    Location location = locationManager.getLastKnownLocation(provider);
                    if (location != null) {
                        if (isBetterLocation(location, bestLocation)) {
                            bestLocation = location;
                        }
                    }
                }

                listening = true;

                // sendStatus("started", null);

                sendLocation(bestLocation);
            }
            else {
                requestLocationPermission(getFragment().getActivity());
            }
        }

        public void stop() {
            if (listening) {
                clearTimeout();
                locationManager.removeUpdates(this);

                // sendStatus("stopped", null);

                listening = false;
            }

            LocationPlugin.this.removeListeningFragment(this);
        }

        private void clearTimeout() {
            if (timer != null) {
                timer.cancel();
                timer = null;
            }
        }

        private boolean locationFulfillsRequirements(Location location) {
            return location != null
                    && location.getAccuracy() < accuracy
                    && location.getTime() >= (new Date().getTime() - maxAge);
        }

        public void sendLocation(Location location) {
            if (location != null) {
                CobaltFragment fragment = getFragment();

                if (fragment != null) {
                    boolean sendLocation = false;
                    boolean stopUpdates = false;

                    if (isBetterLocation(location, bestLocation)) {
                        bestLocation = location;
                    }

                    if (locationFulfillsRequirements(location)) {
                        sendLocation = true;
                        stopUpdates = true;
                    }
                    else if (sendAllUpdates && System.currentTimeMillis() >= (lastSentLocationTimestamp + interval)) {
                        lastSentLocationTimestamp = System.currentTimeMillis();

                        sendLocation = true;
                    }

                    if (sendLocation) {
                        fragment.sendMessage(buildLocationMessage(location));
                    }

                    if (stopUpdates) {
                        stop();
                    }
                }
                else {
                    stop();
                }
            }
        }

        public void sendStatus(String status, Location bestLocation) {
            CobaltFragment fragment = getFragment();

            if (fragment != null) {
                fragment.sendMessage(buildStatusMessage(status, bestLocation));
            }
            else {
                stop();
            }
        }

        /*******************************************************************************************
         *
         * CALLBACKS
         *
         ******************************************************************************************/

        @Override
        public void onLocationChanged(Location location) {
            if (listening) {
                this.sendLocation(location);
            }
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle bundle) {
            switch (status) {
                case LocationProvider.AVAILABLE:
                    onProviderEnabled(provider);
                    break;

                case LocationProvider.OUT_OF_SERVICE:
                    onProviderDisabled(provider);
                    break;

                // TODO: How to handle this case?
                // case LocationProvider.TEMPORARILY_UNAVAILABLE:
            }
        }

        @Override
        public void onProviderEnabled(String provider) {
            if (!providers.contains(provider)) {
                providers.add(provider);
            }
        }

        @Override
        public void onProviderDisabled(String provider) {
            providers.remove(provider);

            if (providers.size() <= 0) {
                sendStatus(STATUS_DISABLED, null);
            }
        }

        /*******************************************************************************************
         *
         * LOCATION PERMISSION
         *
         ******************************************************************************************/

        private final int LOCATION_PERMISSION_REQUEST = this.hashCode();

        private boolean checkLocationPermission(Context context) {
            return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }

        private void requestLocationPermission(Activity activity) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission_group.LOCATION)) {
                ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission_group.LOCATION}, LOCATION_PERMISSION_REQUEST);
            }
            else {
                sendStatus(STATUS_REFUSED, null);
                stop();
            }
        }

        @Override
        public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
            if (requestCode == LOCATION_PERMISSION_REQUEST) {
                onRequestLocationPermissionResult(grantResults[0] == PackageManager.PERMISSION_GRANTED);
            }
        }

        private void onRequestLocationPermissionResult(boolean granted) {
            if (granted) {
                start();
            }
            else {
                sendStatus(STATUS_REFUSED, null);
                stop();
            }
        }

        /*******************************************************************************************
         *
         * HELPERS
         *
         ******************************************************************************************/

        public CobaltFragment getFragment() {
            return fragmentReference.get();
        }

        private JSONObject buildLocationMessage(Location location) {
            try {
                JSONObject data = new JSONObject();
                data.put(kJSLatitude, location.getLatitude());
                data.put(kJSLongitude, location.getLongitude());
                data.put(kJSAccuracy, location.getAccuracy());
                data.put(kJSTimestamp, location.getTime());

                JSONObject message = new JSONObject();
                message.put(Cobalt.kJSType, Cobalt.JSTypePlugin);
                message.put(Cobalt.kJSPluginName, JSPluginName);
                message.put(Cobalt.kJSAction, JSActionOnLocationChanged);
                message.put(Cobalt.kJSData, data);

                return message;
            }
            catch (JSONException e) {
                e.printStackTrace();

                return null;
            }
        }

        private JSONObject buildStatusMessage(String status, Location bestLocation) {
            try {
                JSONObject data = new JSONObject();
                data.put(kJSStatus, status);

                if (bestLocation != null) {
                    JSONObject location = new JSONObject();
                    location.put(kJSLatitude, bestLocation.getLatitude());
                    location.put(kJSLongitude, bestLocation.getLongitude());
                    location.put(kJSAccuracy, bestLocation.getAccuracy());
                    location.put(kJSTimestamp, bestLocation.getTime());
                    data.put(kJSLocation, location);
                }

                JSONObject message = new JSONObject();
                message.put(Cobalt.kJSType, Cobalt.JSTypePlugin);
                message.put(Cobalt.kJSPluginName, JSPluginName);
                message.put(Cobalt.kJSAction, JSActionOnStatusChanged);
                message.put(Cobalt.kJSData, data);

                return message;
            }
            catch (JSONException e) {
                e.printStackTrace();

                return null;
            }
        }

        @Override
        public boolean equals(Object o) {
            return (o instanceof LocationListener)
                    && ((LocationListener) o).getFragment() != null
                    && ((LocationListener) o).getFragment() == getFragment();
        }

        /** Determines whether one Location reading is better than the current Location fix
         * @param location  The new Location that you want to evaluate
         * @param currentBestLocation  The current Location fix, to which you want to compare the new one
         */
        private boolean isBetterLocation(Location location, Location currentBestLocation) {
            if (currentBestLocation == null) {
                // A new location is always better than no location
                return true;
            }

            // Check whether the new location fix is newer or older
            long timeDelta = location.getTime() - currentBestLocation.getTime();
            boolean isSignificantlyNewer = timeDelta > 120000;
            boolean isSignificantlyOlder = timeDelta < -120000;
            boolean isNewer = timeDelta > 0;

            // If it's been more than two minutes since the current location, use the new location
            // because the user has likely moved
            if (isSignificantlyNewer) {
                return true;
                // If the new location is more than two minutes older, it must be worse
            } else if (isSignificantlyOlder) {
                return false;
            }

            // Check whether the new location fix is more or less accurate
            int accuracyDelta = (int) (location.getAccuracy() - currentBestLocation.getAccuracy());
            boolean isLessAccurate = accuracyDelta > 0;
            boolean isMoreAccurate = accuracyDelta < 0;
            boolean isSignificantlyLessAccurate = accuracyDelta > 200;

            // Check if the old and new location are from the same provider
            boolean isFromSameProvider = isSameProvider(location.getProvider(),
                    currentBestLocation.getProvider());

            // Determine location quality using a combination of timeliness and accuracy
            if (isMoreAccurate) {
                return true;
            } else if (isNewer && !isLessAccurate) {
                return true;
            } else if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider) {
                return true;
            }

            return false;
        }

        /**
         * Checks whether two providers are the same
         * */
        private boolean isSameProvider(String provider1, String provider2) {
            if (provider1 == null) {
                return provider2 == null;
            }

            return provider1.equals(provider2);
        }
    }
}
