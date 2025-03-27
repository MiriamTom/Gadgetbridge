package nodomain.freeyourgadget.gadgetbridge.service;


import android.annotation.SuppressLint;
import android.app.Service;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.cloud.MqttManager;

public class BluetoothLeService extends Service {
    private static final UUID HEART_RATE_SERVICE_UUID = UUID.fromString("0000180D-0000-1000-8000-00805F9B34FB");
    private static final UUID HEART_RATE_MEASUREMENT_UUID = UUID.fromString("00002A37-0000-1000-8000-00805F9B34FB");
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");


    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;
    private boolean isScanning = false;
    private MqttManager mqttManager;

    private  Context context;


    private final ScanCallback scanCallback = new ScanCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device.getName() != null) {
                Log.d("BluetoothScan", "Device found: " + device.getName());
                connectToDevice(device);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e("BluetoothScan", "Scan failed with error: " + errorCode);
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("BluetoothGatt", "Connected, discovering services...");
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("BluetoothGatt", "Disconnected");
                if (bluetoothGatt != null) {
                    bluetoothGatt.close();
                    bluetoothGatt = null;
                }
            }
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService heartRateService = gatt.getService(HEART_RATE_SERVICE_UUID);
                if (heartRateService != null) {
                    BluetoothGattCharacteristic heartRateCharacteristic = heartRateService.getCharacteristic(HEART_RATE_MEASUREMENT_UUID);
                    if (heartRateCharacteristic != null) {
                        gatt.setCharacteristicNotification(heartRateCharacteristic, true);

                        // Set CCCD
                        BluetoothGattDescriptor descriptor = heartRateCharacteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID);
                        if (descriptor != null) {
                            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            gatt.writeDescriptor(descriptor);
                        }
                    }
                }
            }
        }
        @SuppressLint("MissingPermission")
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (HEART_RATE_MEASUREMENT_UUID.equals(characteristic.getUuid())) {
                byte[] data = characteristic.getValue();
                if (data != null && data.length > 0) {
                    int heartRate = parseHeartRate(data);
                    Log.d("HeartRate", "Received heart rate: " + heartRate + " bpm");

                    // Get device info safely
                    String deviceName = "Unknown";
                    try {
                        BluetoothDevice device = gatt.getDevice();
                        deviceName = device.getName() != null ? device.getName() : device.getAddress();
                    } catch (Exception e) {
                        Log.e("BluetoothGatt", "Error getting device info", e);
                    }

                    // Get user info safely
                    String userName = "Unknown";
                    try {
                        userName = GBApplication.getPrefs().getUserName();
                    } catch (Exception e) {
                        Log.e("BluetoothGatt", "Error getting user name", e);
                    }

                    // Create MQTT message
                    String topic = String.format("BP/GB/realtime-hr/%s/%s",
                            sanitizeForTopic(userName),
                            sanitizeForTopic(deviceName));

                    String payload = String.format(Locale.US,
                            "{\"timestamp\": %d, \"heart_rate\": %d}",
                            System.currentTimeMillis(),
                            heartRate);

                    // Send via MQTT Service
                    sendMqttMessage(topic, payload);
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        this.context = this; // Initialize context
        initializeBluetooth();
    }

    private String sanitizeForTopic(String input) {
        return input.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private void sendMqttMessage(String topic, String payload) {
        try {
            Intent mqttIntent = new Intent(context, MqttService.class);
            mqttIntent.setAction("SEND_MQTT_MESSAGE");
            mqttIntent.putExtra("topic", topic);
            mqttIntent.putExtra("payload", payload);
            context.startService(mqttIntent);

           // Log.d("MQTT", "Sent to " + topic + ": " + payload);
        } catch (Exception e) {
            Log.e("MQTT", "Failed to send message", e);
        }
    }
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startScan();
        return START_STICKY;
    }

    @Override
    @SuppressLint("MissingPermission")
    public void onDestroy() {
        super.onDestroy();
        stopScan();
        if (bluetoothGatt != null) {
            bluetoothGatt.close();
            bluetoothGatt = null;
        }

        if (mqttManager != null) {
            mqttManager.disconnect();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void initializeBluetooth() {
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
            }
        }
    }

    private void startScan() {
        if (bluetoothLeScanner == null || isScanning) return;

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(HEART_RATE_SERVICE_UUID)).build());

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bluetoothLeScanner.startScan(filters, settings, scanCallback);
            isScanning = true;
        }
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (bluetoothLeScanner != null && isScanning) {
            bluetoothLeScanner.stopScan(scanCallback);
            isScanning = false;
        }
    }

    private void connectToDevice(BluetoothDevice device) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            bluetoothGatt = device.connectGatt(this, false, gattCallback);
        }
    }

    private int parseHeartRate(byte[] data) {
        if ((data[0] & 0x01) == 0) {
            return data[1] & 0xFF;  // 8-bit value
        } else {
            return ((data[2] & 0xFF) << 8) | (data[1] & 0xFF);  // 16-bit value
        }
    }
}