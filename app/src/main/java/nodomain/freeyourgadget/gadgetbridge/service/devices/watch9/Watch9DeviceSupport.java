/*  Copyright (C) 2018-2024 Andreas Böhler, Andreas Shimokawa, Arjan
    Schrijver, Carsten Pfeiffer, Damien Gaignon, Daniele Gobbetti, maxirnilian,
    Sebastian Kranz

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.watch9;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.UUID;

import androidx.annotation.IntRange;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.watch9.Watch9Constants;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser;
import nodomain.freeyourgadget.gadgetbridge.model.Alarm;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.model.CallSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLEDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.BLETypeConversions;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.actions.SetDeviceStateAction;
import nodomain.freeyourgadget.gadgetbridge.service.devices.watch9.operations.InitOperation;
import nodomain.freeyourgadget.gadgetbridge.util.AlarmUtils;
import nodomain.freeyourgadget.gadgetbridge.util.ArrayUtils;
import nodomain.freeyourgadget.gadgetbridge.util.BcdUtil;

public class Watch9DeviceSupport extends AbstractBTLEDeviceSupport {

    private boolean needsAuth;
    private int sequenceNumber = 0;
    private boolean isCalibrationActive = false;

    private byte ACK_CALIBRATION = 0;

    private final GBDeviceEventVersionInfo versionInfo = new GBDeviceEventVersionInfo();
    private final GBDeviceEventBatteryInfo batteryInfo = new GBDeviceEventBatteryInfo();

    private static final Logger LOG = LoggerFactory.getLogger(Watch9DeviceSupport.class);

    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String broadcastAction = intent.getAction();
            switch (broadcastAction) {
                case Watch9Constants.ACTION_CALIBRATION:
                    enableCalibration(intent.getBooleanExtra(Watch9Constants.ACTION_ENABLE, false));
                    break;
                case Watch9Constants.ACTION_CALIBRATION_SEND:
                    int hour = intent.getIntExtra(Watch9Constants.VALUE_CALIBRATION_HOUR, -1);
                    int minute = intent.getIntExtra(Watch9Constants.VALUE_CALIBRATION_MINUTE, -1);
                    int second = intent.getIntExtra(Watch9Constants.VALUE_CALIBRATION_SECOND, -1);
                    if (hour != -1 && minute != -1 && second != -1) {
                        sendCalibrationData(hour, minute, second);
                    }
                    break;
                case Watch9Constants.ACTION_CALIBRATION_HOLD:
                    holdCalibration();
                    break;
            }
        }
    };

    public Watch9DeviceSupport() {
        super(LOG);
        addSupportedService(GattService.UUID_SERVICE_GENERIC_ACCESS);
        addSupportedService(GattService.UUID_SERVICE_GENERIC_ATTRIBUTE);
        addSupportedService(Watch9Constants.UUID_SERVICE_WATCH9);

        LocalBroadcastManager broadcastManager = LocalBroadcastManager.getInstance(getContext());
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Watch9Constants.ACTION_CALIBRATION);
        intentFilter.addAction(Watch9Constants.ACTION_CALIBRATION_SEND);
        intentFilter.addAction(Watch9Constants.ACTION_CALIBRATION_HOLD);
        broadcastManager.registerReceiver(broadcastReceiver, intentFilter);
    }

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        try {
            boolean auth = needsAuth;
            needsAuth = false;
            new InitOperation(auth, this, builder).perform();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return builder;
    }

    @Override
    public boolean connectFirstTime() {
        needsAuth = true;
        return super.connect();
    }

    @Override
    public boolean useAutoConnect() {
        return true;
    }

    @Override
    public void onNotification(NotificationSpec notificationSpec) {
        sendNotification(Watch9Constants.NOTIFICATION_CHANNEL_DEFAULT, false);
    }

    private void sendNotification(int notificationChannel, boolean isStopNotification) {
        try {
            TransactionBuilder builder = performInitialized("showNotification");
            byte[] command = Watch9Constants.CMD_NOTIFICATION_TASK;
            command[1] = (byte) (isStopNotification ? 0x04 : 0x01);
            builder.write(getCharacteristic(Watch9Constants.UUID_CHARACTERISTIC_WRITE),
                    buildCommand(command,
                            Watch9Constants.TASK,
                            Conversion.toByteArr32(notificationChannel)));
            performImmediately(builder);
        } catch (IOException e) {
            LOG.warn("Unable to send notification", e);
        }
    }

    private Watch9DeviceSupport enableNotificationChannels(TransactionBuilder builder) {
        builder.write(getCharacteristic(Watch9Constants.UUID_CHARACTERISTIC_WRITE),
                buildCommand(Watch9Constants.CMD_NOTIFICATION_SETTINGS,
                        Watch9Constants.WRITE_VALUE,
                        new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}));

        return this;
    }

    public Watch9DeviceSupport authorizationRequest(TransactionBuilder builder, boolean firstConnect) {
        builder.write(getCharacteristic(Watch9Constants.UUID_CHARACTERISTIC_WRITE),
                buildCommand(Watch9Constants.CMD_AUTHORIZATION_TASK,
                        Watch9Constants.TASK,
                        new byte[]{(byte) (firstConnect ? 0x00 : 0x01)})); //possibly not the correct meaning

        return this;
    }

    private Watch9DeviceSupport enableDoNotDisturb(TransactionBuilder builder, boolean active) {
        builder.write(getCharacteristic(Watch9Constants.UUID_CHARACTERISTIC_WRITE),
                buildCommand(Watch9Constants.CMD_DO_NOT_DISTURB_SETTINGS,
                        Watch9Constants.WRITE_VALUE,
                        new byte[]{(byte) (active ? 0x01 : 0x00)}));

        return this;
    }

    private void enableCalibration(boolean enable) {
        try {
            TransactionBuilder builder = performInitialized("enableCalibration");
            builder.write(getCharacteristic(Watch9Constants.UUID_CHARACTERISTIC_WRITE),
                    buildCommand(Watch9Constants.CMD_CALIBRATION_INIT_TASK,
                            Watch9Constants.TASK,
                            new byte[]{(byte) (enable ? 0x01 : 0x00)}));
            performImmediately(builder);
        } catch (IOException e) {
            LOG.warn("Unable to start/stop calibration mode", e);
        }
    }

    private void holdCalibration() {
        try {
            TransactionBuilder builder = performInitialized("holdCalibration");
            builder.write(getCharacteristic(Watch9Constants.UUID_CHARACTERISTIC_WRITE),
                    buildCommand(Watch9Constants.CMD_CALIBRATION_KEEP_ALIVE,
                            Watch9Constants.KEEP_ALIVE));
            performImmediately(builder);
        } catch (IOException e) {
            LOG.warn("Unable to keep calibration mode alive", e);
        }
    }

    private void sendCalibrationData(@IntRange(from=0,to=23)int hour, @IntRange(from=0,to=59)int minute, @IntRange(from=0,to=59)int second) {
        try {
            isCalibrationActive = true;
            TransactionBuilder builder = performInitialized("calibrate");
            int handsPosition = ((hour % 12) * 60 + minute) * 60 + second;
            builder.write(getCharacteristic(Watch9Constants.UUID_CHARACTERISTIC_WRITE),
                    buildCommand(Watch9Constants.CMD_CALIBRATION_TASK,
                            Watch9Constants.TASK,
                            Conversion.toByteArr16(handsPosition)));
            performImmediately(builder);
        } catch (IOException e) {
            isCalibrationActive = false;
            LOG.warn("Unable to send calibration data", e);
        }
    }

    private void getTime() {
        try {
            TransactionBuilder builder = performInitialized("getTime");
            builder.write(getCharacteristic(Watch9Constants.UUID_CHARACTERISTIC_WRITE),
                    buildCommand(Watch9Constants.CMD_TIME_SETTINGS,
                            Watch9Constants.READ_VALUE));
            performImmediately(builder);
        } catch (IOException e) {
            LOG.warn("Unable to get device time", e);
        }
    }

    private void handleTime(byte[] time) {
        GregorianCalendar now = BLETypeConversions.createCalendar();
        GregorianCalendar nowDevice = BLETypeConversions.createCalendar();
        int year = (nowDevice.get(Calendar.YEAR) / 100) * 100 + BcdUtil.fromBcd8(time[8]);
        nowDevice.set(year,
                BcdUtil.fromBcd8(time[9]) - 1,
                BcdUtil.fromBcd8(time[10]),
                BcdUtil.fromBcd8(time[11]),
                BcdUtil.fromBcd8(time[12]),
                BcdUtil.fromBcd8(time[13]));
        nowDevice.set(Calendar.DAY_OF_WEEK, BcdUtil.fromBcd8(time[16]) + 1);

        long timeDiff = (Math.abs(now.getTimeInMillis() - nowDevice.getTimeInMillis())) / 1000;
        if (10 < timeDiff && timeDiff < 120) {
            enableCalibration(true);
            setTime(BLETypeConversions.createCalendar());
            enableCalibration(false);
        }
    }

    private void setTime(Calendar calendar) {
        try {
            TransactionBuilder builder = performInitialized("setTime");
            int timezoneOffsetMinutes = (calendar.get(Calendar.ZONE_OFFSET) + calendar.get(Calendar.DST_OFFSET)) / (60 * 1000);
            int timezoneOffsetIndustrialMinutes = Math.round((Math.abs(timezoneOffsetMinutes) % 60) * 100f / 60f);
            byte[] time = new byte[]{BcdUtil.toBcd8(calendar.get(Calendar.YEAR) % 100),
                    BcdUtil.toBcd8(calendar.get(Calendar.MONTH) + 1),
                    BcdUtil.toBcd8(calendar.get(Calendar.DAY_OF_MONTH)),
                    BcdUtil.toBcd8(calendar.get(Calendar.HOUR_OF_DAY)),
                    BcdUtil.toBcd8(calendar.get(Calendar.MINUTE)),
                    BcdUtil.toBcd8(calendar.get(Calendar.SECOND)),
                    (byte) (timezoneOffsetMinutes / 60),
                    (byte) timezoneOffsetIndustrialMinutes,
                    (byte) (calendar.get(Calendar.DAY_OF_WEEK) - 1)
            };
            builder.write(getCharacteristic(Watch9Constants.UUID_CHARACTERISTIC_WRITE),
                    buildCommand(Watch9Constants.CMD_TIME_SETTINGS,
                            Watch9Constants.WRITE_VALUE,
                            time));
            performImmediately(builder);
        } catch (IOException e) {
            LOG.warn("Unable to set time", e);
        }
    }

    public Watch9DeviceSupport getFirmwareVersion(TransactionBuilder builder) {
        builder.write(getCharacteristic(Watch9Constants.UUID_CHARACTERISTIC_WRITE),
                buildCommand(Watch9Constants.CMD_FIRMWARE_INFO,
                        Watch9Constants.READ_VALUE));

        return this;
    }

    private Watch9DeviceSupport getBatteryState(TransactionBuilder builder) {
        builder.write(getCharacteristic(Watch9Constants.UUID_CHARACTERISTIC_WRITE),
                buildCommand(Watch9Constants.CMD_BATTERY_INFO,
                        Watch9Constants.READ_VALUE));

        return this;
    }

    private Watch9DeviceSupport setFitnessGoal(TransactionBuilder builder) {
        int fitnessGoal = new ActivityUser().getStepsGoal();
        builder.write(getCharacteristic(Watch9Constants.UUID_CHARACTERISTIC_WRITE),
                buildCommand(Watch9Constants.CMD_FITNESS_GOAL_SETTINGS,
                        Watch9Constants.WRITE_VALUE,
                        Conversion.toByteArr16(fitnessGoal)));

        return this;
    }

    public Watch9DeviceSupport initialize(TransactionBuilder builder) {
        getFirmwareVersion(builder)
                .getBatteryState(builder)
                .enableNotificationChannels(builder)
                .enableDoNotDisturb(builder, false)
                .setFitnessGoal(builder);
        builder.add(new SetDeviceStateAction(getDevice(), GBDevice.State.INITIALIZED, getContext()));
        builder.setCallback(this);

        return this;
    }

    @Override
    public void onSetTime() {
        getTime();
    }

    @Override
    public void onSetAlarms(ArrayList<? extends Alarm> alarms) {
        try {
            TransactionBuilder builder = performInitialized("setAlarms");
            for (Alarm alarm : alarms) {
                setAlarm(alarm, alarm.getPosition() + 1, builder);
            }
            builder.queue(getQueue());
        } catch (IOException e) {
            LOG.warn("Unable to set alarms", e);
        }
    }

    // No useful use case at the moment, used to clear alarm slots for testing.
    private void deleteAlarm(TransactionBuilder builder, int index) {
        if (0 < index && index < 4) {
            byte[] alarmValue = new byte[]{(byte) index, 0x00, 0x00, 0x00, 0x00, 0x00};
            builder.write(getCharacteristic(Watch9Constants.UUID_CHARACTERISTIC_WRITE),
                    buildCommand(Watch9Constants.CMD_ALARM_SETTINGS,
                            Watch9Constants.WRITE_VALUE,
                            alarmValue));
        }
    }

    private void setAlarm(Alarm alarm, int index, TransactionBuilder builder) {
        // Shift the GB internal repetition mask to match the device specific one.
        byte repetitionMask = (byte) ((alarm.getRepetition() << 1) | (alarm.isRepetitive() ? 0x80 : 0x00));
        repetitionMask |= (alarm.getRepetition(Alarm.ALARM_SUN) ? 0x01 : 0x00);
        if (0 < index && index < 4) {
            byte[] alarmValue = new byte[]{(byte) index,
                    BcdUtil.toBcd8(AlarmUtils.toCalendar(alarm).get(Calendar.HOUR_OF_DAY)),
                    BcdUtil.toBcd8(AlarmUtils.toCalendar(alarm).get(Calendar.MINUTE)),
                    repetitionMask,
                    (byte) (alarm.getEnabled() ? 0x01 : 0x00),
                    0x00 // TODO: Unknown
            };
            builder.write(getCharacteristic(Watch9Constants.UUID_CHARACTERISTIC_WRITE),
                    buildCommand(Watch9Constants.CMD_ALARM_SETTINGS,
                            Watch9Constants.WRITE_VALUE,
                            alarmValue));
        }
    }

    @Override
    public void onSetCallState(CallSpec callSpec) {
        switch (callSpec.command) {
            case CallSpec.CALL_INCOMING:
                sendNotification(Watch9Constants.NOTIFICATION_CHANNEL_PHONE_CALL, false);
                break;
            case CallSpec.CALL_START:
            case CallSpec.CALL_END:
                sendNotification(Watch9Constants.NOTIFICATION_CHANNEL_PHONE_CALL, true);
                break;
            default:
                break;
        }
    }

    @Override
    public void onSendConfiguration(String config) {
        TransactionBuilder builder;
        try {
            builder = performInitialized("sendConfig: " + config);
            switch (config) {
                case ActivityUser.PREF_USER_STEPS_GOAL:
                    setFitnessGoal(builder);
                    break;
            }
            builder.queue(getQueue());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean onCharacteristicChanged(BluetoothGatt gatt,
                                           BluetoothGattCharacteristic characteristic) {
        super.onCharacteristicChanged(gatt, characteristic);

        UUID characteristicUUID = characteristic.getUuid();
        if (Watch9Constants.UUID_CHARACTERISTIC_WRITE.equals(characteristicUUID)) {
            byte[] value = characteristic.getValue();
            if (ArrayUtils.equals(value, Watch9Constants.RESP_FIRMWARE_INFO, 5)) {
                handleFirmwareInfo(value);
            } else if (ArrayUtils.equals(value, Watch9Constants.RESP_BATTERY_INFO, 5)) {
                handleBatteryState(value);
            } else if (ArrayUtils.equals(value, Watch9Constants.RESP_TIME_SETTINGS, 5)) {
                handleTime(value);
            } else if (ArrayUtils.equals(value, Watch9Constants.RESP_BUTTON_INDICATOR, 5)) {
                LOG.info("Unhandled action: Button pressed");
            } else if (ArrayUtils.equals(value, Watch9Constants.RESP_ALARM_INDICATOR, 5)) {
                LOG.info("Alarm active: id=" + value[8]);
            } else if (isCalibrationActive && value.length == 7 && value[4] == ACK_CALIBRATION) {
                setTime(BLETypeConversions.createCalendar());
                isCalibrationActive = false;
            }

            return true;
        } else {
            LOG.info("Unhandled characteristic changed: " + characteristicUUID);
            logMessageContent(characteristic.getValue());
        }

        return false;
    }

    private byte[] buildCommand(byte[] command, byte action) {
        return buildCommand(command, action, null);
    }

    private byte[] buildCommand(byte[] command, byte action, byte[] value) {
        if (Arrays.equals(command, Watch9Constants.CMD_CALIBRATION_TASK)) {
            ACK_CALIBRATION = (byte) sequenceNumber;
        }
        command = BLETypeConversions.join(command, value);
        byte[] result = new byte[7 + command.length];
        System.arraycopy(Watch9Constants.CMD_HEADER, 0, result, 0, 5);
        System.arraycopy(command, 0, result, 6, command.length);
        result[2] = (byte) (command.length + 1);
        result[3] = Watch9Constants.REQUEST;
        result[4] = (byte) sequenceNumber++;
        result[5] = action;
        result[result.length - 1] = calculateChecksum(result);

        return result;
    }

    private byte calculateChecksum(byte[] bytes) {
        byte checksum = 0x00;
        for (int i = 0; i < bytes.length - 1; i++) {
            checksum += (bytes[i] ^ i) & 0xFF;
        }
        return (byte) (checksum & 0xFF);
    }

    private void handleFirmwareInfo(byte[] value) {
        versionInfo.fwVersion = String.format(Locale.US,"%d.%d.%d", value[8], value[9], value[10]);
        handleGBDeviceEvent(versionInfo);
    }

    private void handleBatteryState(byte[] value) {
        batteryInfo.state = value[9] == 1 ? BatteryState.BATTERY_NORMAL : BatteryState.BATTERY_LOW;
        batteryInfo.level = GBDevice.BATTERY_UNKNOWN;
        handleGBDeviceEvent(batteryInfo);
    }

    @Override
    public void dispose() {
        LocalBroadcastManager broadcastManager = LocalBroadcastManager.getInstance(getContext());
        broadcastManager.unregisterReceiver(broadcastReceiver);
        super.dispose();
    }

    @Override
    public boolean getImplicitCallbackModify() {
        return true;
    }

    @Override
    public boolean getSendWriteRequestResponse() {
        return false;
    }

    private static class Conversion {
        static byte[] toByteArr16(int value) {
            return new byte[]{(byte) (value >> 8), (byte) value};
        }

        static byte[] toByteArr32(int value) {
            return new byte[]{(byte) (value >> 24),
                    (byte) (value >> 16),
                    (byte) (value >> 8),
                    (byte) value};
        }
    }
}
