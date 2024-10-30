package nodomain.freeyourgadget.gadgetbridge.cloud;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Date;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService;

public class HeartRateActivity extends AppCompatActivity {
    private static final Logger LOG = LoggerFactory.getLogger(HeartRateActivity.class);
    private static final String PREFS_NAME = "HeartRatePrefs";
    private static final String KEY_LAST_HEART_RATE = "lastHeartRate";
    private static final String KEY_LAST_TIMESTAMP = "lastTimestamp";

    private TextView lastSampleTextView;
    private TextView heartRateTextView;
    private TextView actionTextView;

    private final BroadcastReceiver heartRateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null) {
                updateTextView(actionTextView, "Received Action: ", action);
                LOG.debug("Received action: {}", action);
            }

            if (DeviceService.ACTION_REALTIME_SAMPLES.equals(action)) {
                LOG.debug("Received realtime sample");
                handleRealtimeSample(intent.getSerializableExtra(DeviceService.EXTRA_REALTIME_SAMPLE));
            } else if (DeviceService.ACTION_HEARTRATE_TEST.equals(action)) {
                String heartRateValue = intent.getStringExtra(DeviceService.EXTRA_HEART_RATE_VALUE);
                if (heartRateValue != null) {
                    updateTextView(heartRateTextView, "Heart Rate Value: ", heartRateValue);
                    LOG.debug("Heart rate value received: {}", heartRateValue);
                } else {
                    LOG.debug("Heart rate value is null");
                }
            } else if (DeviceService.ACTION_ENABLE_HEARTRATE_SLEEP_SUPPORT.equals(action)) {
                boolean enable = intent.getBooleanExtra(DeviceService.EXTRA_BOOLEAN_ENABLE, false);
                LOG.debug("Heart rate sleep support enabled: {}", enable);
                updateTextView(actionTextView, "Sleep Support Enabled: ", String.valueOf(enable));
            } else if (DeviceService.ACTION_SET_HEARTRATE_MEASUREMENT_INTERVAL.equals(action)) {
                int interval = intent.getIntExtra(DeviceService.EXTRA_INTERVAL_SECONDS, 0);
                LOG.debug("Heart rate measurement interval set to: {}", interval);
                updateTextView(actionTextView, "Measurement Interval: ", String.valueOf(interval));
            } else if (DeviceService.ACTION_ENABLE_REALTIME_HEARTRATE_MEASUREMENT.equals(action)) {
                boolean enable = intent.getBooleanExtra(DeviceService.EXTRA_BOOLEAN_ENABLE, false);
                LOG.debug("Realtime heart rate measurement enabled: {}", enable);
                updateTextView(actionTextView, "Realtime Measurement Enabled: ", String.valueOf(enable));
            }
        }
    };


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_heart_rate);

        lastSampleTextView = findViewById(R.id.lastSampleTextView);
        heartRateTextView = findViewById(R.id.heartRateTextView);
        actionTextView = findViewById(R.id.actionTextView);

        // Load last heart rate from SharedPreferences
        loadLastHeartRate();

        // Set up the IntentFilter for all the actions
        IntentFilter filter = new IntentFilter();
        filter.addAction(DeviceService.ACTION_REALTIME_SAMPLES);
        filter.addAction(DeviceService.ACTION_HEARTRATE_TEST);
        filter.addAction(DeviceService.ACTION_ENABLE_REALTIME_HEARTRATE_MEASUREMENT);
        filter.addAction(DeviceService.ACTION_ENABLE_HEARTRATE_SLEEP_SUPPORT); // Added new action
        filter.addAction(DeviceService.ACTION_SET_HEARTRATE_MEASUREMENT_INTERVAL); // Added new action

        LocalBroadcastManager.getInstance(this).registerReceiver(heartRateReceiver, filter);
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

            // Save the latest heart rate and timestamp
            saveLastHeartRate(heartRate, currentTime);
        } else {
            LOG.debug("Received unknown sample data");
        }
    }

    private void saveLastHeartRate(int heartRate, String timestamp) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt(KEY_LAST_HEART_RATE, heartRate);
        editor.putString(KEY_LAST_TIMESTAMP, timestamp);
        editor.apply(); // Save changes
    }

    private void loadLastHeartRate() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int lastHeartRate = prefs.getInt(KEY_LAST_HEART_RATE, -1);
        String lastTimestamp = prefs.getString(KEY_LAST_TIMESTAMP, "N/A");

        if (lastHeartRate != -1) {
            lastSampleTextView.setText("Last Sample: " + lastTimestamp);
            heartRateTextView.setText("Heart Rate: " + lastHeartRate);
            LOG.debug("Loaded last heart rate: {}, Timestamp: {}", lastHeartRate, lastTimestamp);
        } else {
            lastSampleTextView.setText("Last Sample: N/A");
            heartRateTextView.setText("Heart Rate: N/A");
        }
    }

    private void updateTextView(TextView textView, String label, String value) {
        String text = label + value;
        textView.setText(text);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(heartRateReceiver);
    }
}
