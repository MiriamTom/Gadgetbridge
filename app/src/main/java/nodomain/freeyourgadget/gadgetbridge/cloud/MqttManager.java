package nodomain.freeyourgadget.gadgetbridge.cloud;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MqttManager {
    private static final String MQTT_BROKER_URI = "tcp://192.168.0.174:1883"; // Zmeň na svoju IP adresu
    private static final String MQTT_CLIENT_ID = "AndroidClient";
    private static MqttManager instance;
    private MqttClient mqttClient;
    private static final Logger LOG = LoggerFactory.getLogger(MqttManager.class);

    private MqttManager() {
        connect();
    }

    public static synchronized MqttManager getInstance() {
        if (instance == null) {
            instance = new MqttManager();
        }
        return instance;
    }

    private void connect() {
        try {
            mqttClient = new MqttClient(MQTT_BROKER_URI, MQTT_CLIENT_ID, new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);

            mqttClient.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    LOG.debug("Connection to MQTT broker lost!");
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    LOG.debug("MQTT message received: " + new String(message.getPayload()));
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    LOG.debug("MQTT message delivery complete!");
                }
            });

            mqttClient.connect(options);
            LOG.debug("Connected to MQTT broker at: " + MQTT_BROKER_URI);
        } catch (MqttException e) {
            LOG.error("Failed to connect to MQTT broker", e);
        }
    }

    public void sendMessage(String topic, String payload) {
        if (mqttClient != null && mqttClient.isConnected()) {
            try {
                MqttMessage message = new MqttMessage(payload.getBytes());
                message.setQos(0); // QoS level 0 (at most once)
                mqttClient.publish(topic, message);
                LOG.debug("Published message to MQTT topic '{}': {}", topic, payload);
            } catch (MqttException e) {
                LOG.error("Error publishing message to MQTT broker", e);
            }
        } else {
            LOG.error("MQTT client is not connected!");
        }
    }
    public void sendHeartRate(int heartRate) {
        String topic = "heartRate";
        String payload = String.valueOf(heartRate);
        LOG.debug("Preparing to send heart rate: {}", heartRate);
        sendMessage(topic, payload);
    }
}
