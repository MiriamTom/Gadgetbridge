package nodomain.freeyourgadget.gadgetbridge.cloud;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

public class HeartRateService extends Service {
    private HeartRateMonitor heartRateMonitor = new HeartRateMonitor();
    private Handler handler;
    private Runnable heartRateRunnable;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Start the handler and runnable for periodic monitoring
        Log.d("HeartRateService", "Service started for heart rate monitoring");
        handler = new Handler(Looper.getMainLooper());
        heartRateRunnable = new Runnable() {
            @Override
            public void run() {
                heartRateMonitor.startMonitoring(); // Start monitoring
                handler.postDelayed(this, 40000); // Repeat every 1 minute (60000 ms)
            }
        };
        handler.post(heartRateRunnable);

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        heartRateMonitor.stopMonitoring(); // Stop monitoring on service destroy
        if (handler != null) {
            handler.removeCallbacks(heartRateRunnable); // Stop the periodic runnable
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}