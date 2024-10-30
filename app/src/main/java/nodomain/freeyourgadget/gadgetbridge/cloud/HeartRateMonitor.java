package nodomain.freeyourgadget.gadgetbridge.cloud;

import android.os.Handler;
import android.os.Looper;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;

public class HeartRateMonitor {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable heartRateTask = new Runnable() {
        @Override
        public void run() {
            GBApplication.deviceService().onHeartRateTest();
            handler.postDelayed(this, 40000); // Schedule every 60 seconds
        }
    };

    public void startMonitoring() {
        handler.post(heartRateTask);
    }

    public void stopMonitoring() {
        handler.removeCallbacks(heartRateTask);
    }
}
