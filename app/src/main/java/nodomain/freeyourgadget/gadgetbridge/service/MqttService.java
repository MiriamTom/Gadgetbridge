package nodomain.freeyourgadget.gadgetbridge.service;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class MqttService extends Service implements MqttCallback {
    private static final String TAG = "MqttService";
    private static final String BROKER_URL = "tcp://192.168.0.174:1883"; // Replace with your broker URL
    private static final String CLIENT_ID = "GadgetBridgeClient"; // Unique client ID
    private static final String TOPIC = "gadgetbridge/heartrate"; // Topic to publish heart rate data

    private MqttClient mqttClient;

    private final BroadcastReceiver heartRateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("HEART_RATE_DATA".equals(intent.getAction())) {
                int heartRate = intent.getIntExtra("HEART_RATE", -1);
                if (heartRate != -1) {
                    publishMessage(TOPIC, String.valueOf(heartRate));
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        connectToMqttBroker();

        // Register to receive heart rate data
        LocalBroadcastManager.getInstance(this).registerReceiver(
                heartRateReceiver,
                new IntentFilter("HEART_RATE_DATA")
        );
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        disconnectFromMqttBroker();

        // Unregister the receiver
        LocalBroadcastManager.getInstance(this).unregisterReceiver(heartRateReceiver);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void connectToMqttBroker() {
        try {
            mqttClient = new MqttClient(BROKER_URL, CLIENT_ID, new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setAutomaticReconnect(true); // Automatically reconnect if disconnected

            mqttClient.connect(options);
            mqttClient.setCallback(this);
            Log.d(TAG, "Connected to MQTT broker");
        } catch (MqttException e) {
            Log.e(TAG, "Failed to connect to MQTT broker", e);
        }
    }

    private void disconnectFromMqttBroker() {
        if (mqttClient != null && mqttClient.isConnected()) {
            try {
                mqttClient.disconnect();
                Log.d(TAG, "Disconnected from MQTT broker");
            } catch (MqttException e) {
                Log.e(TAG, "Failed to disconnect from MQTT broker", e);
            }
        }
    }

    public void publishMessage(String topic, String message) {
        if (mqttClient != null && mqttClient.isConnected()) {
            try {
                MqttMessage mqttMessage = new MqttMessage(message.getBytes());
                mqttClient.publish(topic, mqttMessage);
                Log.d(TAG, "Published message to topic: " + topic);
            } catch (MqttException e) {
                Log.e(TAG, "Failed to publish message", e);
            }
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        Log.e(TAG, "Connection to MQTT broker lost", cause);
        // Attempt to reconnect
        connectToMqttBroker();
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        String payload = new String(message.getPayload());
        Log.d(TAG, "Message arrived on topic " + topic + ": " + payload);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        Log.d(TAG, "Message delivery complete");
    }
}