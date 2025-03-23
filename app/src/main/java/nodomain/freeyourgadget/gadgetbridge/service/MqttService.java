package nodomain.freeyourgadget.gadgetbridge.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class MqttService extends Service {
    private static final String TAG = "MqttService";
    private MqttClient mqttClient;
    private String mqttBrokerUri;
    private String mqttClientId = "AndroidClient";
    private String mqttLogin;
    private String mqttPassword;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "MqttService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("SEND_MQTT_MESSAGE".equals(action)) {
                String topic = intent.getStringExtra("topic");
                String payload = intent.getStringExtra("payload");
                sendMessage(topic, payload);
            } else {
                // Retrieve MQTT settings from the intent
                mqttBrokerUri = intent.getStringExtra("mqttBrokerUri");
                mqttLogin = intent.getStringExtra("mqttLogin");
                mqttPassword = intent.getStringExtra("mqttPassword");

                // Connect to the MQTT broker
                connectToMqttBroker();
            }
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "MqttService destroyed");

        // Disconnect from the MQTT broker
        disconnectFromMqttBroker();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // This is a started service, not a bound service
    }

    private void connectToMqttBroker() {
        try {
            if (mqttBrokerUri == null || mqttBrokerUri.isEmpty()) {
                Log.e(TAG, "MQTT broker URI is not set.");
                return;
            }

            mqttClient = new MqttClient(mqttBrokerUri, mqttClientId, new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setConnectionTimeout(60); // Increase timeout to 60 seconds

            if (mqttLogin != null && !mqttLogin.isEmpty() && mqttPassword != null) {
                options.setUserName(mqttLogin);
                options.setPassword(mqttPassword.toCharArray());
            }

            Log.d(TAG, "Connecting to broker: " + mqttBrokerUri);
            mqttClient.connect(options);
            Log.d(TAG, "Connected to MQTT broker at: " + mqttBrokerUri);
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
                Log.e(TAG, "Error disconnecting from MQTT broker", e);
            }
        }
    }

    public void sendMessage(String topic, String payload) {
        if (mqttClient != null && mqttClient.isConnected()) {
            try {
                MqttMessage message = new MqttMessage(payload.getBytes());
                message.setQos(0);
                mqttClient.publish(topic, message);
                Log.d(TAG, "Published message to MQTT topic '" + topic + "': " + payload);
            } catch (MqttException e) {
                Log.e(TAG, "Error publishing message to MQTT broker", e);
            }
        } else {
            Log.e(TAG, "MQTT client is not connected!");
        }
    }
}