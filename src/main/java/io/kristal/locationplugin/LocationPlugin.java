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

import android.location.LocationListener;
import android.os.Bundle;
import fr.cobaltians.cobalt.Cobalt;
import fr.cobaltians.cobalt.fragments.CobaltFragment;
import fr.cobaltians.cobalt.plugin.CobaltAbstractPlugin;
import fr.cobaltians.cobalt.plugin.CobaltPluginWebContainer;

import android.app.Activity;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;


public final class LocationPlugin extends CobaltAbstractPlugin implements LocationListener {

    // TAG
    private static final String TAG = LocationPlugin.class.getSimpleName();

    /**********************************************************
     * MEMBERS
     **********************************************************/

    private static final String LOCATION = "location";
    private static final String ERROR = "error";
    private static final String TEXT = "text";
    private static final String CODE = "code";
    private static final String DISABLED = "disabled";
    private static final String NULL = "null";

    private static final String LONGITUDE = "longitude";
    private static final String LATITUDE = "latitude";
    private static final String ALTITUDE = "altitude";
    private static final String ACCURACY = "accuracy";

    private static final String GET_LOCATION = "getLocation";
    private static final String GET_LONGITUDE = "getLongitude";
    private static final String GET_LATITUDE = "getLatitude";
    private static final String GET_ALTITUDE = "getAltitude";
    private static final String GET_ACCURACY = "getAccuracy";

    private boolean mFoundProvider = true;
    private LocationManager mLocationManager;
    private String mProvider;
    private CobaltFragment mFragment;

    private boolean mLocation = false;
    private boolean mLongitude = false;
    private boolean mLatitude = false;
    private boolean mAltitude = false;
    private boolean mAccuracy = false;

    /*******************************************************************************************************
     * MEMBERS
     *******************************************************************************************************/

    protected static LocationPlugin sInstance;

    /**************************************************************************************
     * CONSTRUCTORS
     **************************************************************************************/

    public static CobaltAbstractPlugin getInstance(CobaltPluginWebContainer webContainer) {
        if (sInstance == null) {
            sInstance = new LocationPlugin();
        }

        sInstance.addWebContainer(webContainer);

        return sInstance;
    }


    /***********************************************************************************************************
     * OVERRIDEN METHODS
     ***********************************************************************************************************/

    @Override
    public void onMessage(CobaltPluginWebContainer webContainer, JSONObject message) {
        mFragment = webContainer.getFragment();
        Location location = getLocation(webContainer);
        try {
            String action = message.getString(Cobalt.kJSAction);
            if (selectionOfAction(action)) {
                if (location != null) sendLocationFound(location);
                else {
                    JSONObject resultLocation = new JSONObject();
                    resultLocation.put(Cobalt.kJSType, Cobalt.JSTypePlugin);
                    resultLocation.put(Cobalt.kJSPluginName, LOCATION);
                    JSONObject data = new JSONObject();
                    data.put(ERROR, true);
                    if (mFoundProvider) {
                        data.put(CODE, NULL);
                        data.put(TEXT, "No location found, please wait");
                        Log.d(TAG, "call requestLocationUpdate");
                        mLocationManager.requestLocationUpdates(mProvider,400, 1, this);
                    }
                    else {
                        data.put(CODE, DISABLED);
                        data.put(TEXT, "Location detection has been disabled by user");
                    }
                    resultLocation.put(Cobalt.kJSData, data);
                    mFragment.sendMessage(resultLocation);
                }
            }
            else
                if (Cobalt.DEBUG) Log.d(TAG, "ERROR - can't found action in message : " + message.toString());
        }
        catch (JSONException exception) {
            if (Cobalt.DEBUG) {
                if (Cobalt.DEBUG) Log.d(TAG, "ERROR - can't find a good key in : " + message.toString());
                exception.printStackTrace();
            }
        }
    }

    private boolean selectionOfAction(String action) {
        if (action.equals(GET_LOCATION)) mLocation = true;
        else if (action.equals(GET_LONGITUDE)) mLongitude = true;
        else if (action.equals(GET_LATITUDE)) mLatitude = true;
        else if (action.equals(GET_ALTITUDE)) mAltitude = true;
        else if (action.equals(GET_ACCURACY)) mAccuracy = true;
        else return false;
        return true;
    }

    private Location getLocation(CobaltPluginWebContainer webContainer) {
        mProvider = getLocationProvider(webContainer);
        if (mProvider == null) {
            mFoundProvider = false;
            return null;
        }
        else {
            Location location = mLocationManager.getLastKnownLocation(mProvider);
            mFoundProvider = true;
            return location;
        }
    }

    private String getLocationProvider(CobaltPluginWebContainer webContainer) {
        Activity activity = webContainer.getActivity();
        mLocationManager = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
        return mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : (mLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER : null);
    }

    private void sendLocationFound (Location location) {
        if (location != null) {
            JSONObject resultLocation = new JSONObject();
            try {
                resultLocation.put(Cobalt.kJSType, Cobalt.JSTypePlugin);
                resultLocation.put(Cobalt.kJSPluginName, LOCATION);
                JSONObject data = new JSONObject();
                data.put(ERROR, false);
                JSONObject value = new JSONObject();
                if (mLocation || mLongitude)
                    value.put(LONGITUDE, location.getLongitude());
                if (mLocation || mLatitude)
                    value.put(LATITUDE, location.getLatitude());
                if (mAltitude)
                    value.put(ALTITUDE, location.getAltitude());
                if (mAccuracy)
                    value.put(ACCURACY, location.getAccuracy());

                data.put(Cobalt.kJSValue, value);
                resultLocation.put(Cobalt.kJSData, data);
                mFragment.sendMessage(resultLocation);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        sendLocationFound(location);
        mLocationManager.removeUpdates(this);
    }

    @Override
    public void onStatusChanged(String s, int i, Bundle bundle) {

    }

    @Override
    public void onProviderEnabled(String s) {

    }

    @Override
    public void onProviderDisabled(String s) {

    }
}
