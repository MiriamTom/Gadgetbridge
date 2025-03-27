package nodomain.freeyourgadget.gadgetbridge.cloud;

import static nodomain.freeyourgadget.gadgetbridge.util.StringUtils.bytesToHex;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.firebase.firestore.FirebaseFirestore;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.huami.HuamiService;
import nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandService;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService;
import nodomain.freeyourgadget.gadgetbridge.service.MqttService;
import nodomain.freeyourgadget.gadgetbridge.service.BluetoothLeService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattCharacteristic;

public class HeartRateActivity extends AppCompatActivity {
    private static final Logger LOG = LoggerFactory.getLogger(HeartRateActivity.class);
    private static final String PREFS_NAME = "HeartRatePrefs";
    private static final String KEY_LAST_HEART_RATE = "lastHeartRate";
    private static final String KEY_LAST_TIMESTAMP = "lastTimestamp";

    // Add this constant to your activity
    private static final String ACTION_CHARACTERISTIC_CHANGED =
            "nodomain.freeyourgadget.gadgetbridge.service.btle.ACTION_CHARACTERISTIC_CHANGED";
    private static final String EXTRA_CHARACTERISTIC_UUID = "characteristic_uuid";
    private static final String EXTRA_CHARACTERISTIC_VALUE = "characteristic_value";
    private TextView lastSampleTextView;
    private TextView heartRateTextView;
    private TextView actionTextView;

    private EditText mqttBrokerAddressEditText;
    private EditText mqttBrokerPortEditText;
    private EditText mqttLoginEditText;
    private EditText mqttPasswordEditText;
    private Button saveMqttSettingsButton;
    private TextView connectionStatusTextView;

    private String mqttBrokerAddress;
    private int mqttBrokerPort;
    private String mqttLogin;
    private String mqttPassword;

    // Declare TextViews here but do not initialize them yet
    private TextView lastExportedTimestampTextView;
    private TextView cloudNameTextView;

    private LocationManager locationManager;
    private static final long MIN_TIME_BW_UPDATES = 1000 ; // 1 minute
    private static final float MIN_DISTANCE_CHANGE_FOR_UPDATES = 0; // 10 meters

    private static final String CLOUD_NAME = "Firebase Firestore";
    private final BroadcastReceiver mqttStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean connected = intent.getBooleanExtra("connected", false);
            String serverUri = intent.getStringExtra("serverUri");
            int port = intent.getIntExtra("port", 1883);
            updateConnectionStatus(connected, serverUri, port);
        }
    };
    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            double latitude = location.getLatitude();
            double longitude = location.getLongitude();
            float accuracy = location.getAccuracy();
            long time = location.getTime();

            // Log the GPS location
            LOG.debug("GPS Location Update - Lat: {}, Long: {}, Accuracy: {}, Time: {}",
                    latitude, longitude, accuracy, time);


            // You can also send this via MQTT if needed
            sendLocationViaMqtt(latitude, longitude, accuracy, time);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            LOG.debug("GPS Status Changed: {}", status);
        }

        @Override
        public void onProviderEnabled(String provider) {
            LOG.debug("GPS Provider Enabled: {}", provider);
        }

        @Override
        public void onProviderDisabled(String provider) {
            LOG.debug("GPS Provider Disabled: {}", provider);
        }
    };

    private void sendLocationViaMqtt(double latitude, double longitude, float accuracy, long timestamp) {
        JSONObject locationJson = new JSONObject();
        try {
            locationJson.put("latitude", latitude);
            locationJson.put("longitude", longitude);
            locationJson.put("accuracy", accuracy);
            locationJson.put("timestamp", timestamp);

            Intent intent = new Intent(this, MqttService.class);
            intent.setAction("SEND_MQTT_MESSAGE");
            intent.putExtra("topic", "BP/GB/location");
            intent.putExtra("payload", locationJson.toString());
            startService(intent);
        } catch (JSONException e) {
            LOG.error("Error creating GPS JSON", e);
        }
    }

    private final BroadcastReceiver heartRateReceiver   = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null) {
                LOG.debug("Received action: {}", action);
            }
            if (ACTION_CHARACTERISTIC_CHANGED.equals(action)) {
                // Handle raw BLE characteristic changes
                String uuid = intent.getStringExtra(EXTRA_CHARACTERISTIC_UUID);
                byte[] value = intent.getByteArrayExtra(EXTRA_CHARACTERISTIC_VALUE);

                if (uuid != null && GattCharacteristic.UUID_CHARACTERISTIC_HEART_RATE_MEASUREMENT.equals(uuid)) {
                    int heartRate = value.length > 1 ? value[1] & 0xFF : -1;
                    if (heartRate > 0) {
                        LOG.debug("Raw BLE Heart Rate: {}", heartRate);
                        sendToMqtt("heartrate/raw", String.valueOf(heartRate));
                    }
                }
            } else if (DeviceService.ACTION_REALTIME_SAMPLES.equals(action)) {
                // Handle parsed activity samples
                Serializable sample = intent.getSerializableExtra(DeviceService.EXTRA_REALTIME_SAMPLE);
                if (sample instanceof ActivitySample) {
                    ActivitySample activitySample = (ActivitySample) sample;
                    int heartRate = activitySample.getHeartRate();

                    LOG.debug("Parsed Heart Rate: {}", heartRate);
                    sendToMqtt("heartrate/parsed", String.valueOf(heartRate));

                }
            }


            if (intent.hasExtra("characteristic_uuid")) {
                String uuid = intent.getStringExtra("characteristic_uuid");
                byte[] value = intent.getByteArrayExtra("value");
                String valueStr = bytesToHex(value);

                // Handle specific UUIDs
                if (GattCharacteristic.UUID_CHARACTERISTIC_HEART_RATE_MEASUREMENT.equals(uuid)) {
                    handleHeartRateMeasurement(value);
                    sendToMqtt("heartrate/measurement", valueStr);
                }
                else if (HuamiService.UUID_CHARACTERISTIC_6_BATTERY_INFO.equals(uuid)) {
                    handleBatteryInfo(value);
                    sendToMqtt("battery/info", valueStr);
                }
                else if (MiBandService.UUID_CHARACTERISTIC_REALTIME_STEPS.equals(uuid)) {
                    handleRealtimeSteps(value);
                    sendToMqtt("activity/steps", valueStr);
                }
                else if (HuamiService.UUID_CHARACTERISTIC_DEVICEEVENT.equals(uuid)) {
                    handleDeviceEvent(value);
                    sendToMqtt("device/event", valueStr);
                }
                // Add more UUIDs as needed
                else {
                    LOG.debug("Unhandled characteristic: {}", uuid);
                    sendToMqtt("unknown/" + uuid, valueStr);
                }
            }
            // Existing action handlers
            else if (DeviceService.ACTION_REALTIME_SAMPLES.equals(action)) {
                LOG.debug("Received realtime sample");
                handleRealtimeSample(intent.getSerializableExtra(DeviceService.EXTRA_REALTIME_SAMPLE));
            } else if (DeviceService.ACTION_HEARTRATE_TEST.equals(action)) {
                String heartRateValue = intent.getStringExtra(DeviceService.EXTRA_HEART_RATE_VALUE);
                if (heartRateValue != null) {
                    LOG.debug("Heart rate value received: {}", heartRateValue);
                } else {
                    LOG.debug("Heart rate value is null");
                }
            } else if (DeviceService.ACTION_ENABLE_HEARTRATE_SLEEP_SUPPORT.equals(action)) {
                boolean enable = intent.getBooleanExtra(DeviceService.EXTRA_BOOLEAN_ENABLE, false);
                LOG.debug("Heart rate sleep support enabled: {}", enable);
            } else if (DeviceService.ACTION_SET_HEARTRATE_MEASUREMENT_INTERVAL.equals(action)) {
                int interval = intent.getIntExtra(DeviceService.EXTRA_INTERVAL_SECONDS, 0);
                LOG.debug("Heart rate measurement interval set to: {}", interval);
            } else if (DeviceService.ACTION_ENABLE_REALTIME_HEARTRATE_MEASUREMENT.equals(action)) {
                boolean enable = intent.getBooleanExtra(DeviceService.EXTRA_BOOLEAN_ENABLE, false);
                LOG.debug("Realtime heart rate measurement enabled: {}", enable);
            }
        }
    };
    private void handleHeartRateMeasurement(byte[] value) {
        // Parse heart rate measurement
        int heartRate = value.length > 1 ? value[1] & 0xFF : -1;
        LOG.debug("Heart rate measurement: {}", heartRate);
    }

    private void handleBatteryInfo(byte[] value) {
        // Parse battery info (implementation depends on your device's protocol)
        int batteryLevel = value.length > 0 ? value[0] & 0xFF : -1;
        LOG.debug("Battery info: {}", batteryLevel);
    }

    private void handleRealtimeSteps(byte[] value) {
        // Parse steps (implementation depends on your device's protocol)
        int steps = value.length >= 4 ?
                (value[3] & 0xFF) << 24 | (value[2] & 0xFF) << 16 |
                        (value[1] & 0xFF) << 8 | (value[0] & 0xFF) : -1;
        LOG.debug("Realtime steps: {}", steps);
    }

    private void handleDeviceEvent(byte[] value) {
        // Parse device event
        String event = "Event: " + bytesToHex(value);

        LOG.debug("Device event: {}", event);
    }
    private void initializeMqttService() {
        if (!mqttBrokerAddress.isEmpty()) {
            Intent intent = new Intent(this, MqttService.class);
            intent.putExtra("mqttBrokerUri", "tcp://" + mqttBrokerAddress + ":" + mqttBrokerPort);
            intent.putExtra("mqttLogin", mqttLogin);
            intent.putExtra("mqttPassword", mqttPassword);
            startService(intent);
        }
    }
    private void sendToMqtt(String topic, String value) {
        try {
            JSONObject json = new JSONObject();
            json.put("value", value);
            json.put("timestamp", System.currentTimeMillis());

            Intent mqttIntent = new Intent(this, MqttService.class);
            mqttIntent.setAction("SEND_MQTT_MESSAGE");
            mqttIntent.putExtra("topic", "BP/GB/" + topic);
            mqttIntent.putExtra("payload", json.toString());
            startService(mqttIntent);
        } catch (JSONException e) {
            LOG.error("Error creating MQTT message", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_heart_rate);

        // Initialize UI elements after setContentView()
        lastExportedTimestampTextView = findViewById(R.id.lastExportedTimestampTextView);
        cloudNameTextView = findViewById(R.id.cloudNameTextView);
        mqttBrokerAddressEditText = findViewById(R.id.mqttBrokerAddressEditText);
        mqttBrokerPortEditText = findViewById(R.id.mqttBrokerPortEditText);
        mqttLoginEditText = findViewById(R.id.mqttLoginEditText);
        mqttPasswordEditText = findViewById(R.id.mqttPasswordEditText);
        saveMqttSettingsButton = findViewById(R.id.saveMqttSettingsButton);
        connectionStatusTextView = findViewById(R.id.connectionStatusTextView);

        // Load saved MQTT settings and connect to the broker
        loadLastMqttSettings();
        initializeMqttService();

        // Load last export details
        loadLastExportDetails();

        // Set up button click listener
        saveMqttSettingsButton.setOnClickListener(v -> saveMqttSettings());


        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        // Check and request location permissions if needed
        if (checkLocationPermission()) {
            startLocationUpdates();
        }

    }
    private boolean checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    1);
            return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates();
            }
        }
    }

    private void startLocationUpdates() {
        try {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    MIN_TIME_BW_UPDATES,
                    MIN_DISTANCE_CHANGE_FOR_UPDATES,
                    locationListener);
        } catch (SecurityException e) {
            LOG.error("Location permission not granted", e);
        }
    }

    private void loadLastExportDetails() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String lastCloudName = "BachelorThesis";
        long lastExportTimestamp = GBApplication.app().getLastAutoExportTimestamp();

        // Format the timestamp
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        String lastExportedTimestamp = lastExportTimestamp != -1
                ? sdf.format(new Date(lastExportTimestamp))
                : "N/A";

        // Update the UI
        lastExportedTimestampTextView.setText("Timestamp: " + lastExportedTimestamp);
        cloudNameTextView.setText("Cloud: " + lastCloudName);
    }

    private void handleRealtimeSample(Serializable extra) {
        if (extra instanceof ActivitySample) {
            ActivitySample sample = (ActivitySample) extra;
            int heartRate = sample.getHeartRate();
            String currentTime = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date());

            // Update UI
            lastSampleTextView.setText("Last Sample: " + currentTime);
            heartRateTextView.setText("Heart Rate: " + heartRate);
            LOG.debug("Heart rate sample received: {}", heartRate);

            saveLastHeartRate(heartRate, currentTime);

            // Send via MQTT Service instead of Manager
            Intent intent = new Intent(this, MqttService.class);
            intent.setAction("SEND_MQTT_MESSAGE");
            intent.putExtra("topic", "heartrate/data");
            intent.putExtra("payload", String.valueOf(heartRate));
            startService(intent);
        }
    }

    private void updateConnectionStatus(boolean connected, String serverUri, int port) {
        runOnUiThread(() -> {
            String status = connected ? "✅ " : "❌ ";
            String displayText = String.format("Status: %s\nServer: %s\nPort: %d",
                    status,
                    serverUri != null ? serverUri : "N/A",
                    port);
            connectionStatusTextView.setText(displayText);
        });
    }
    @Override
    protected void onStart() {
        super.onStart();
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(mqttStatusReceiver, new IntentFilter("MQTT_STATUS_UPDATE"));
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(heartRateReceiver, new IntentFilter(DeviceService.ACTION_REALTIME_SAMPLES));
    IntentFilter filter = new IntentFilter();
        // For raw BLE notifications
        filter.addAction(ACTION_CHARACTERISTIC_CHANGED);
        // For parsed samples
        filter.addAction(DeviceService.ACTION_REALTIME_SAMPLES);

        LocalBroadcastManager.getInstance(this).registerReceiver(heartRateReceiver, filter);
    }
    @Override
    protected void onStop() {
        super.onStop();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mqttStatusReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(heartRateReceiver);
    }

    private void saveMqttSettings() {
        // Retrieve user input for MQTT settings
        mqttBrokerAddress = mqttBrokerAddressEditText.getText().toString();
        String portString = mqttBrokerPortEditText.getText().toString();
        mqttLogin = mqttLoginEditText.getText().toString();
        mqttPassword = mqttPasswordEditText.getText().toString();

        // Check if port is provided, otherwise use default
        if (portString.isEmpty()) {
            mqttBrokerPort = 1883; // Default port
        } else {
            mqttBrokerPort = Integer.parseInt(portString);
        }

        // Save these settings for future use
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("mqttBrokerAddress", mqttBrokerAddress);
        editor.putInt("mqttBrokerPort", mqttBrokerPort);
        editor.putString("mqttLogin", mqttLogin);
        editor.putString("mqttPassword", mqttPassword);
        editor.apply();

        // Disconnect first, then connect to new broker
        Intent disconnectIntent = new Intent(this, MqttService.class);
        disconnectIntent.setAction("DISCONNECT_MQTT");
        startService(disconnectIntent);

        // The connection will be established automatically after a short delay
    }

    private void saveLastHeartRate(int heartRate, String timestamp) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt(KEY_LAST_HEART_RATE, heartRate);
        editor.putString(KEY_LAST_TIMESTAMP, timestamp);
        editor.apply(); // Save changes
    }




    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(heartRateReceiver  );
        if (locationManager != null) {
            locationManager.removeUpdates(locationListener);
        }
    }

    private void loadLastMqttSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        mqttBrokerAddress = prefs.getString("mqttBrokerAddress", "");
        mqttBrokerPort = prefs.getInt("mqttBrokerPort", 1883);
        mqttLogin = prefs.getString("mqttLogin", "");
        mqttPassword = prefs.getString("mqttPassword", "");

        mqttBrokerAddressEditText.setText(mqttBrokerAddress);
        mqttBrokerPortEditText.setText(String.valueOf(mqttBrokerPort));
        mqttLoginEditText.setText(mqttLogin);
        mqttPasswordEditText.setText(mqttPassword);
    }

    private void displayConnectionStatus() {
        MqttManager.getInstance().setConnectionListener(this::onConnectionStatusChanged);
    }

    public void onConnectionStatusChanged(boolean connected, String serverUri, int port) {
        runOnUiThread(() -> {
            String status = connected ? "Connected" : "Disconnected";
            String displayText = String.format("Status: %s\nServer: %s\nPort: %d", status, serverUri, port);
            connectionStatusTextView.setText(displayText);
        });
    }
}