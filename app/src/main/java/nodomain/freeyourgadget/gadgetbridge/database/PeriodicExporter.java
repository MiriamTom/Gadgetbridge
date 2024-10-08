/*  Copyright (C) 2018-2024 Carsten Pfeiffer, Felix Konstantin Maurer,
    Ganblejs, José Rebelo, Petr Vaněk

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.database;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;

import nodomain.freeyourgadget.gadgetbridge.BuildConfig;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs;
import nodomain.freeyourgadget.gadgetbridge.util.PendingIntentUtils;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

/**
 * Created by maufl on 1/4/18.
 */

public class PeriodicExporter extends BroadcastReceiver {
    private static final Logger LOG = LoggerFactory.getLogger(PeriodicExporter.class);

    public static final String ACTION_DATABASE_EXPORT_SUCCESS = "nodomain.freeyourgadget.gadgetbridge.action.DATABASE_EXPORT_SUCCESS";
    public static final String ACTION_DATABASE_EXPORT_FAIL = "nodomain.freeyourgadget.gadgetbridge.action.DATABASE_EXPORT_FAIL";

    public static void enablePeriodicExport(Context context) {
        Prefs prefs = GBApplication.getPrefs();
        GBApplication gbApp = GBApplication.app();
        long autoExportScheduled = gbApp.getAutoExportScheduledTimestamp();
        boolean autoExportEnabled = prefs.getBoolean(GBPrefs.AUTO_EXPORT_ENABLED, false);
        Integer autoExportInterval = prefs.getInt(GBPrefs.AUTO_EXPORT_INTERVAL, 0);
        scheduleAlarm(context, autoExportInterval, autoExportEnabled && autoExportScheduled == 0);
    }

    public static void scheduleAlarm(Context context, Integer autoExportInterval, boolean autoExportEnabled) {
        Intent i = new Intent(context, PeriodicExporter.class);
        i.setPackage(BuildConfig.APPLICATION_ID);
        PendingIntent pi = PendingIntentUtils.getBroadcast(context, 0, i, 0, false);
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pi);
        if (!autoExportEnabled) {
            LOG.info("Not scheduling periodic export, either already scheduled or not enabled");
            return;
        }
        int exportPeriod = autoExportInterval * 60 * 60 * 1000;
        if (exportPeriod == 0) {
            LOG.info("Not scheduling periodic export, interval set to 0");
            return;
        }
        LOG.info("Scheduling periodic export");
        GBApplication gbApp = GBApplication.app();
        gbApp.setAutoExportScheduledTimestamp(System.currentTimeMillis() + exportPeriod);
        am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + exportPeriod,
                exportPeriod,
                pi
        );
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        LOG.info("Received command to export DB");
        createRefreshTask("Export database", context).execute();
    }

    protected RefreshTask createRefreshTask(String task, Context context) {
        return new RefreshTask(task, context);
    }

    public class RefreshTask extends DBAccess {
        Context localContext;

        public RefreshTask(String task, Context context) {
            super(task, context);
            localContext = context;
        }

        @Override
        protected void doInBackground(DBHandler handler) {
            LOG.info("Exporting DB in a background thread");
            try (DBHandler dbHandler = GBApplication.acquireDB()) {
                DBHelper helper = new DBHelper(localContext);
                String dst = GBApplication.getPrefs().getString(GBPrefs.AUTO_EXPORT_LOCATION, null);
                if (dst == null) {
                    LOG.warn("Unable to export DB, export location not set");
                    broadcastSuccess(false);
                    return;
                }
                Uri dstUri = Uri.parse(dst);
                try (OutputStream out = localContext.getContentResolver().openOutputStream(dstUri)) {
                    helper.exportDB(dbHandler, out);
                    GBApplication gbApp = GBApplication.app();
                    gbApp.setLastAutoExportTimestamp(System.currentTimeMillis());
                }

                broadcastSuccess(true);

                LOG.info("DB export completed");
            } catch (Exception ex) {
                GB.updateExportFailedNotification(localContext.getString(R.string.notif_export_failed_title), localContext);
                LOG.info("Exception while exporting DB: ", ex);
                broadcastSuccess(false);
            }
        }

        private void broadcastSuccess(final boolean success) {
            if (!GBApplication.getPrefs().getBoolean("intent_api_broadcast_export", false)) {
                return;
            }

            LOG.info("Broadcasting database export success={}", success);

            final String action = success ? ACTION_DATABASE_EXPORT_SUCCESS : ACTION_DATABASE_EXPORT_FAIL;
            final Intent exportedNotifyIntent = new Intent(action);
            localContext.sendBroadcast(exportedNotifyIntent);
        }

        @Override
        protected void onPostExecute(Object o) {
        }
    }
}
