package nodomain.freeyourgadget.gadgetbridge.service;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

public class MqttService extends Service {
    private static final String TAG = "MqttService";
    private MqttClient mqttClient;
    private String mqttBrokerUri;
    private String mqttClientId = "AndroidClient";
    private String mqttLogin;
    private String mqttPassword;

    private volatile boolean isConnecting = false;
    private volatile boolean isConnected = false;
    private final Object connectionLock = new Object();

    private final LinkedBlockingQueue<MqttMessage> messageQueue = new LinkedBlockingQueue<>();
    private final ExecutorService messageExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "MqttService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();

            if ("DISCONNECT_MQTT".equals(action)) {
                disconnectFromMqttBroker();
                // Schedule reconnect after disconnect completes
                scheduleDelayedReconnect(1000);
            }
            else if ("SEND_MQTT_MESSAGE".equals(action)) {
                String topic = intent.getStringExtra("topic");
                String payload = intent.getStringExtra("payload");
                sendMessage(topic, payload);
            }
            else if ("GET_MQTT_STATUS".equals(action)) {
                broadcastCurrentStatus();
            }
            else {
                // Handle initial connection or settings update
                String newUri = intent.getStringExtra("mqttBrokerUri");
                String newLogin = intent.getStringExtra("mqttLogin");
                String newPassword = intent.getStringExtra("mqttPassword");

                // Only reconnect if settings actually changed
                if (!newUri.equals(mqttBrokerUri) ||
                        !newLogin.equals(mqttLogin) ||
                        !newPassword.equals(mqttPassword)) {

                    mqttBrokerUri = newUri;
                    mqttLogin = newLogin;
                    mqttPassword = newPassword;

                    disconnectFromMqttBroker();
                    scheduleDelayedReconnect(1000);
                }
            }
        }
        return START_STICKY;
    }

    private void broadcastCurrentStatus() {
        boolean isConnected = (mqttClient != null && mqttClient.isConnected());
        broadcastConnectionStatus(isConnected);
    }

    private void broadcastConnectionStatus(boolean connected) {
        Intent intent = new Intent("MQTT_STATUS_UPDATE");
        intent.putExtra("connected", connected);
        intent.putExtra("serverUri", mqttBrokerUri);
        intent.putExtra("timestamp", System.currentTimeMillis());
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
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
        synchronized (connectionLock) {
            if (isConnecting || isConnected) {
                Log.d(TAG, "Connection already in progress or established");
                return;
            }
            isConnecting = true;
        }

        new Thread(() -> {
            try {
                Log.d(TAG, "Creating new MQTT client instance");
                mqttClient = new MqttClient(mqttBrokerUri,
                        mqttClientId + "_" + System.currentTimeMillis(), // Unique ID
                        new MemoryPersistence());

                MqttConnectOptions options = new MqttConnectOptions();
                options.setAutomaticReconnect(false); // We handle reconnects manually
                options.setCleanSession(true);
                options.setConnectionTimeout(30);
                options.setKeepAliveInterval(60);

                Log.d(TAG, "Attempting connection to: " + mqttBrokerUri);
                mqttClient.connect(options);

                synchronized (connectionLock) {
                    isConnected = true;
                    isConnecting = false;
                }

                Log.d(TAG, "Connection established successfully");
                broadcastConnectionStatus(true);

            } catch (Exception e) {
                synchronized (connectionLock) {
                    isConnecting = false;
                    isConnected = false;
                }

                Log.e(TAG, "Connection failed: " + e.getMessage());
                if (e instanceof MqttException) {
                    Log.e(TAG, "Reason code: " + ((MqttException)e).getReasonCode());
                }

                scheduleDelayedReconnect(5000); // Wait 5 seconds before retry
            }
        }).start();
    }

    private void scheduleDelayedReconnect(long delayMillis) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            synchronized (connectionLock) {
                if (!isConnected && !isConnecting) {
                    connectToMqttBroker();
                }
            }
        }, delayMillis);
    }
    private void scheduleReconnect() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!mqttClient.isConnected()) {
                connectToMqttBroker();
            }
        }, 5000); // Retry every 5 seconds
    }

    private void disconnectFromMqttBroker() {
        synchronized (connectionLock) {
            if (!isConnected && !isConnecting) {
                return;
            }

            Log.d(TAG, "Disconnecting from current broker...");
            try {
                if (mqttClient != null) {
                    if (mqttClient.isConnected()) {
                        mqttClient.disconnect();
                    }
                    mqttClient.close();
                }
            } catch (MqttException e) {
                Log.e(TAG, "Error during disconnect: " + e.getMessage());
            } finally {
                isConnected = false;
                isConnecting = false;
                mqttClient = null;
                broadcastConnectionStatus(false);
            }
        }
    }
    private boolean shouldReconnect(String newUri, String newLogin, String newPassword) {
        if (mqttBrokerUri == null || mqttLogin == null || mqttPassword == null) {
            return true;
        }
        return !newUri.equals(mqttBrokerUri) ||
                !newLogin.equals(mqttLogin) ||
                !newPassword.equals(mqttPassword);
    }

    public void sendMessage(String topic, String payload) {
        messageExecutor.execute(() -> {
            MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
            message.setQos(1);

            try {
                if (!isConnected) {
                    Log.w(TAG, "Queueing message (offline)");
                    messageQueue.put(new MqttMessageWrapper(topic, message));
                    return;
                }

                mqttClient.publish(topic, message);
               // Log.d(TAG, "Published: " + payload);

                // Process queued messages
                while (!messageQueue.isEmpty() && isConnected) {
                    MqttMessageWrapper queued = (MqttMessageWrapper) messageQueue.poll();
                    mqttClient.publish(queued.topic, queued.message);
                }

            } catch (Exception e) {
                Log.e(TAG, "Publish error: " + e.getMessage());
                try {
                    messageQueue.put(new MqttMessageWrapper(topic, message));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    // Helper class for message queue
    private static class MqttMessageWrapper extends MqttMessage {
        final String topic;
        final MqttMessage message;

        MqttMessageWrapper(String topic, MqttMessage message) {
            this.topic = topic;
            this.message = message;
        }

    }
}