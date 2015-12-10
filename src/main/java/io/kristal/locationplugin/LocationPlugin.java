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

import fr.cobaltians.cobalt.Cobalt;
import fr.cobaltians.cobalt.fragments.CobaltFragment;
import fr.cobaltians.cobalt.plugin.CobaltAbstractPlugin;
import fr.cobaltians.cobalt.plugin.CobaltPluginWebContainer;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Date;

public final class LocationPlugin extends CobaltAbstractPlugin implements LocationListener, ActivityCompat.OnRequestPermissionsResultCallback {

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
    private static final String kJSAccuracy = "accuracy";
    private static final String kJSFrequency = "frequency";
    private static final String kJSMode = "mode";
    private static final String kJSTimeout = "timeout";
    private static final String kJSTimestamp = "timestamp";
    private static final String kJSLongitude = "lng";
    private static final String kJSLatitude = "lat";
    private static final String kJSStatus = "status";

    private static final float ACCURACY_DEFAULT_VALUE = 100;
    private static final long FREQUENCY_DEFAULT_VALUE = 400;
    private static final String MODE_ALL = "all";
    private static final String MODE_FILTER = "filter";
    private static final String STATUS_DISABLED = "disabled";
    private static final String STATUS_REFUSED = "refused";
    private static final String STATUS_TIMEOUT = "timeout";
    private static final long TIMEOUT_DEFAULT_VALUE = 0;
    private static final long TIMESTAMP_DEFAULT_VALUE = 2 * 60 * 1000;

    private float mAccuracy;
    private long mFrequency;
    private String mMode;
    private long mTimeout;
    private long mTimestamp;

    private WeakReference<Activity> mActivity;
    private WeakReference<CobaltFragment> mFragment;
    private LocationManager mLocationManager;
    private ArrayList<String> mProviders;

    protected static LocationPlugin sInstance;

    /***********************************************************************************************
     *
     * CONSTRUCTOR
     *
     **********************************************************************************************/

    public static CobaltAbstractPlugin getInstance(CobaltPluginWebContainer webContainer) {
        if (sInstance == null) {
            sInstance = new LocationPlugin();
        }

        sInstance.addWebContainer(webContainer);

        return sInstance;
    }

    /***********************************************************************************************
     *
     * COBALT
     *
     **********************************************************************************************/

    @Override
    public void onMessage(CobaltPluginWebContainer webContainer, JSONObject message) {
        try {
            String action = message.getString(Cobalt.kJSAction);

            switch(action) {
                case JSActionStartLocation:
                    JSONObject data = message.getJSONObject(Cobalt.kJSData);
                    mMode = data.getString(kJSMode);
                    if (! (MODE_ALL.equals(mMode) || MODE_FILTER.equals(mMode))) {
                        throw new JSONException(TAG + " - onMessage: unknown mode " + mMode);
                    }
                    mAccuracy = (float) data.optDouble(kJSAccuracy, ACCURACY_DEFAULT_VALUE);
                    mFrequency = data.optLong(kJSFrequency, FREQUENCY_DEFAULT_VALUE);
                    mTimeout = data.optLong(kJSTimeout, TIMEOUT_DEFAULT_VALUE);
                    mTimestamp = data.optLong(kJSTimestamp, TIMESTAMP_DEFAULT_VALUE);

                    mActivity = new WeakReference<>(webContainer.getActivity());
                    mFragment = new WeakReference<>(webContainer.getFragment());

                    Activity activity = mActivity.get();
                    if (activity != null) {
                        getActiveProviders(activity);
                        if (mProviders.size() != 0) {
                            requestLocationPermission(activity);
                        }
                        else {
                            sendStatus(STATUS_DISABLED);
                        }
                    }
                    break;
                case JSActionStopLocation:
                    stopLocationUpdates();
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

    /***********************************************************************************************
     *
     * METHODS
     *
     **********************************************************************************************/

    private void getActiveProviders(Context context) {
        mProviders = new ArrayList<>();

        mLocationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (mLocationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
            mProviders.add(LocationManager.PASSIVE_PROVIDER);
        }
        if (mLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            mProviders.add(LocationManager.NETWORK_PROVIDER);
        }
        if (mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            mProviders.add(LocationManager.GPS_PROVIDER);
        }
    }

    private void startLocationUpdates() {
        for (String provider : mProviders) {
            Location location = mLocationManager.getLastKnownLocation(provider);

            if (location != null) {
                if (MODE_ALL.equals(mMode)) {
                    sendLocation(location);
                }
                else if (location.getAccuracy() < mAccuracy
                        && location.getTime() < (new Date().getTime() - mTimestamp)) {
                    sendLocation(location);
                    return;
                }
            }
        }

        for (String provider : mProviders) {
            // TODO: see if another method is more convenient
            mLocationManager.requestLocationUpdates(provider, mFrequency, mAccuracy / 2, this);
        }
    }

    private void stopLocationUpdates() {
        if (mLocationManager != null) {
            mLocationManager.removeUpdates(this);
        }
    }

    private void sendLocation(Location location) {
        CobaltFragment fragment = mFragment.get();
        if (fragment != null) {
            try {
                JSONObject data = new JSONObject();
                data.put(kJSLongitude, location.getLongitude());
                data.put(kJSLatitude, location.getLatitude());
                if (MODE_ALL.equals(mMode)) {
                    data.put(kJSAccuracy, location.getAccuracy());
                    data.put(kJSTimestamp, location.getTime());
                }

                JSONObject message = new JSONObject();
                message.put(Cobalt.kJSType, Cobalt.JSTypePlugin);
                message.put(Cobalt.kJSPluginName, JSPluginName);
                message.put(Cobalt.kJSAction, JSActionOnLocationChanged);
                message.put(Cobalt.kJSData, data);

                fragment.sendMessage(message);
            }
            catch (JSONException exception) {
                exception.printStackTrace();
            }
        }
    }

    private void sendStatus(String status) {
        CobaltFragment fragment = mFragment.get();
        if (fragment != null) {
            try {
                JSONObject data = new JSONObject();
                data.put(kJSStatus, status);

                JSONObject message = new JSONObject();
                message.put(Cobalt.kJSType, Cobalt.JSTypePlugin);
                message.put(Cobalt.kJSPluginName, JSPluginName);
                message.put(Cobalt.kJSAction, JSActionOnStatusChanged);
                message.put(Cobalt.kJSData, data);

                fragment.sendMessage(message);
            }
            catch (JSONException exception) {
                exception.printStackTrace();
            }
        }
    }

    /***********************************************************************************************
     *
     * LOCATION PERMISSION
     *
     **********************************************************************************************/

    private final static int LOCATION_PERMISSION_REQUEST = 0;

    private boolean checkLocationPermission(Context context) {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocationPermission(Activity activity) {
        if (checkLocationPermission(activity)) {
            onRequestLocationPermissionResult(true);
        }
        else if (ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission_group.LOCATION)) {
            ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission_group.LOCATION}, LOCATION_PERMISSION_REQUEST);
        }
        else {
            sendStatus(STATUS_REFUSED);
        }
    }

    private void onRequestLocationPermissionResult(boolean granted) {
        if (granted) {
            startLocationUpdates();
        }
        else {
            sendStatus(STATUS_REFUSED);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case LOCATION_PERMISSION_REQUEST:
                onRequestLocationPermissionResult(grantResults[0] == PackageManager.PERMISSION_GRANTED);
            default:
                break;
        }
    }

    /***********************************************************************************************
     *
     * LOCATION LISTENER
     *
     **********************************************************************************************/

    @Override
    public void onLocationChanged(Location location) {
        if (MODE_ALL.equals(mMode)) {
            sendLocation(location);
        }
        else if (location.getAccuracy() < mAccuracy
                && location.getTime() < (new Date().getTime() - mTimestamp)) {
            sendLocation(location);
            mLocationManager.removeUpdates(this);
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle bundle) {
        switch(status) {
            case LocationProvider.AVAILABLE:
                onProviderEnabled(provider);
                break;
            case LocationProvider.OUT_OF_SERVICE:
                onProviderDisabled(provider);
                break;
            // TODO: what to do in this case?
            //case LocationProvider.TEMPORARILY_UNAVAILABLE:
        }
    }

    @Override
    public void onProviderEnabled(String provider) {
        if (mProviders != null
            && ! mProviders.contains(provider)) {
            mProviders.add(provider);
        }
    }

    @Override
    public void onProviderDisabled(String provider) {
        if (mProviders != null) {
            mProviders.remove(provider);
        }

        if (mProviders.size() == 0) {
            sendStatus(STATUS_DISABLED);
        }
    }
}
