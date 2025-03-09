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
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;

import com.google.firebase.FirebaseApp;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.gson.Gson;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import de.greenrobot.dao.internal.DaoConfig;
import nodomain.freeyourgadget.gadgetbridge.BuildConfig;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
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
                // TODO
                // Get the DaoSession
                LOG.info("Retrieving DaoSession from DBHandler");
                DaoSession daoSession = dbHandler.getDaoSession();
                if (daoSession == null) {
                    LOG.error("DaoSession is null");
                    broadcastSuccess(false);
                    return;
                }
                // Convert DB data to JSON using DaoSession
                LOG.info("Converting database to JSON");
                String jsonData = convertDbToJson(daoSession);
                if (jsonData == null || jsonData.isEmpty()) {
                    LOG.error("Failed to convert database to JSON");
                    broadcastSuccess(false);
                    return;
                }
                LOG.info("Database converted to JSON successfully");

                // Compress JSON data
                LOG.info("Compressing JSON data");
                byte[] compressedData = compressJsonData(jsonData);
                if (compressedData == null) {
                    LOG.error("Failed to compress JSON data");
                    broadcastSuccess(false);
                    return;
                }
                LOG.info("JSON data compressed successfully");

                // Log sizes for debugging
                LOG.info("Original JSON data size: " + jsonData.getBytes(StandardCharsets.UTF_8).length + " bytes");
                LOG.info("Compressed JSON data size: " + compressedData.length + " bytes");

                // Encode byte[] as Base64 (could be customized to other encodings if needed)
                String compressedDataBase64 = Base64.getEncoder().encodeToString(compressedData);

                // Upload data to Firestore
                uploadDataToFirestore(compressedDataBase64);

                LOG.info("DB export completed");
            } catch (Exception ex) {
                GB.updateExportFailedNotification(localContext.getString(R.string.notif_export_failed_title), localContext);
                LOG.error("Exception while exporting DB: ", ex);
                broadcastSuccess(false);
            }
        }

        private byte[] compressJsonData(String jsonData) {
            try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
                 GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
                gzip.write(jsonData.getBytes(StandardCharsets.UTF_8));
                gzip.finish();
                return bos.toByteArray();
            } catch (IOException e) {
                LOG.error("Failed to compress JSON data", e);
                return null;
            }
        }

        private String convertDbToJson(DaoSession daoSession) {
            List<Map<String, Object>> data = new ArrayList<>();

            // Retrieve all DAOs from the DaoSession
            Collection<AbstractDao<?, ?>> daos = daoSession.getAllDaos();
            if (daos == null || daos.isEmpty()) {
                LOG.error("No DAOs found in DaoSession");
                return null;
            }

            // Iterate over DAOs and extract data
            for (AbstractDao<?, ?> dao : daos) {
                String tableName = dao.getTablename();
                if (tableName.equals("sqlite_sequence") || tableName.equals("android_metadata")) {
                    continue; // Skip system tables
                }

                List<?> entities = dao.loadAll();
                if (entities == null || entities.isEmpty()) {
                    continue;
                }

                Class<?> propertiesClass = getPropertiesClass(dao);
                if (propertiesClass == null) {
                    continue;
                }

                Property[] properties = getProperties(propertiesClass);
                if (properties == null) {
                    continue;
                }
                /**
                 * Getting the last timestamp of the auto-export
                 */
                long lastExportTimestamp = GBApplication.app().getLastAutoExportTimestamp();

                for (Object entity : entities) {
                    Map<String, Object> row = new HashMap<>();
                    boolean hasNonNullValue = false;

                    long recordTimestamp = 0;

                    for (Property property : properties) {
                        try {
                            Field field = entity.getClass().getDeclaredField(property.name);
                            field.setAccessible(true);
                            Object value = field.get(entity);

                            if (value != null) {
                                hasNonNullValue = true;
                                row.put(property.columnName, value);

                                if (property.columnName.equals("timestamp") && value instanceof Number) {
                                    recordTimestamp = ((Number) value).longValue();
                                }
                            }
                        } catch (Exception e) {
                            LOG.error("Failed to access property: " + property.name, e);
                        }
                    }

                    if (hasNonNullValue) {
                        if (recordTimestamp == 0 || recordTimestamp > lastExportTimestamp) {
                            row.put("table_name", tableName);
                            data.add(row);
                        }
                    }
                }
            }

            Gson gson = new Gson();
            return gson.toJson(data);
        }

        private void uploadDataToFirestore(String compressedDataBase64) {
            LOG.info("Uploading data to Firestore");

            FirebaseFirestore db = FirebaseFirestore.getInstance();
            if (db == null) {
                LOG.error("Firestore instance is null. Firebase might not be initialized.");
                broadcastSuccess(false);
                return;
            }

            // Create Firestore data object
            Map<String, Object> dbData = new HashMap<>();
            dbData.put("data", compressedDataBase64); // Store Base64-encoded string
            dbData.put("timestamp", System.currentTimeMillis());

            // Upload to Firestore
            db.collection("databases") // Firestore automatically generates the document ID
                    .add(dbData)
                    .addOnSuccessListener(documentReference -> {
                        LOG.info("DB export completed. Document ID: " + documentReference.getId());
                        broadcastSuccess(true);
                    })
                    .addOnFailureListener(e -> {
                        LOG.error("Exception while uploading DB to Firestore: ", e);
                        broadcastSuccess(false);
                    });
        }
        /**
         * Helper method to get the Properties class for a DAO.
         */
        private Class<?> getPropertiesClass(AbstractDao<?, ?> dao) {
            try {
                String daoClassName = dao.getClass().getName();
                String propertiesClassName = daoClassName.replace("Dao", "Dao$Properties");
                return Class.forName(propertiesClassName);
            } catch (ClassNotFoundException e) {
                LOG.error("Properties class not found for DAO: " + dao.getClass().getSimpleName(), e);
                return null;
            }
        }

        /**
         * Helper method to get the Property objects from a Properties class.
         */
        private Property[] getProperties(Class<?> propertiesClass) {
            try {
                Field[] fields = propertiesClass.getDeclaredFields();
                List<Property> properties = new ArrayList<>();
                for (Field field : fields) {
                    if (field.getType() == Property.class) {
                        field.setAccessible(true);
                        properties.add((Property) field.get(null));
                    }
                }
                return properties.toArray(new Property[0]);
            } catch (Exception e) {
                LOG.error("Failed to get properties from Properties class: " + propertiesClass.getSimpleName(), e);
                return null;
            }
        }

        /* End of integrations with Firestore
         */

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
