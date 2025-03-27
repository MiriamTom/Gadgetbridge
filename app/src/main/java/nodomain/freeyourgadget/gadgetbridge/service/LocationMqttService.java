package nodomain.freeyourgadget.gadgetbridge.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;

import org.json.JSONException;
import org.json.JSONObject;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.util.AndroidUtils;

public class LocationMqttService extends Service {
    private static final String TAG = "LocationMqttService";
    private static final long LOCATION_UPDATE_INTERVAL = 10000; // 10 seconds
    private static final float MIN_DISTANCE_CHANGE = 0; // meters

    private Object locationClient; // Can be FusedLocationProviderClient or LocationManager
    private boolean usingAndroidLocationManager = false;
    private LocationListener locationListener;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service started");
        initializeLocationClient();
        startLocationUpdates();
    }

    private void initializeLocationClient() {
        if (AndroidUtils.isHuaweiDevice()) {
            Log.d(TAG, "Huawei device detected - using Android LocationManager");
            usingAndroidLocationManager = true;
            locationClient = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        } else {
            Log.d(TAG, "Using Google Fused Location Provider");
            locationClient = LocationServices.getFusedLocationProviderClient(this);
        }
    }

    private void startLocationUpdates() {
        if (usingAndroidLocationManager) {
            startLocationManagerUpdates();
        } else {
            startFusedLocationUpdates();
        }
    }

    private void startFusedLocationUpdates() {
        try {
            LocationRequest locationRequest = LocationRequest.create()
                    .setInterval(LOCATION_UPDATE_INTERVAL)
                    .setFastestInterval(LOCATION_UPDATE_INTERVAL / 2)
                    .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

            LocationCallback callback = new LocationCallback() {
                @Override
                public void onLocationResult(LocationResult locationResult) {
                    if (locationResult != null) {
                        for (Location location : locationResult.getLocations()) {
                            sendLocationToMqtt(location);
                        }
                    }
                }
            };

            ((FusedLocationProviderClient) locationClient).requestLocationUpdates(
                    locationRequest, callback, Looper.getMainLooper());

        } catch (SecurityException e) {
            Log.e(TAG, "Location permission denied", e);
        }
    }

    private void startLocationManagerUpdates() {
        try {
            locationListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    sendLocationToMqtt(location);
                }

                @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
                @Override public void onProviderEnabled(String provider) {}
                @Override public void onProviderDisabled(String provider) {}
            };

            ((LocationManager) locationClient).requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    LOCATION_UPDATE_INTERVAL,
                    MIN_DISTANCE_CHANGE,
                    locationListener,
                    Looper.getMainLooper());

        } catch (SecurityException e) {
            Log.e(TAG, "Location permission denied", e);
        }
    }

    private void sendLocationToMqtt(Location location) {
        try {
            JSONObject locationJson = new JSONObject();
            locationJson.put("latitude", location.getLatitude());
            locationJson.put("longitude", location.getLongitude());
            locationJson.put("accuracy", location.getAccuracy());
            locationJson.put("timestamp", System.currentTimeMillis());
            locationJson.put("provider", usingAndroidLocationManager ? "LocationManager" : "FusedLocation");

            Intent mqttIntent = new Intent(this, MqttService.class);
            mqttIntent.setAction("SEND_MQTT_MESSAGE");
            String userName = GBApplication.getPrefs().getUserName();
            mqttIntent.putExtra("topic", "BP/GB/location/" + userName);
            mqttIntent.putExtra("payload", locationJson.toString());
            startService(mqttIntent);

            Log.d(TAG, "Location sent to MQTT: " + locationJson.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error sending location to MQTT", e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (usingAndroidLocationManager) {
            if (locationClient != null && locationListener != null) {
                ((LocationManager) locationClient).removeUpdates(locationListener);
            }
        } else {
            if (locationClient instanceof FusedLocationProviderClient) {
                // Handle FusedLocationProviderClient cleanup if needed
            }
        }
        Log.d(TAG, "Service stopped");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}