package com.fsherratt.imudatalogger;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

// Management of GATT devices.
// Creation, stream enable/disable and destruction
public class bleService extends Service {
    public final static String ACTION_ADD_GATT_DEVICES = "com.fsherratt.imudatalogger.ACTION_ADD_GATT_DEVICE";
    public final static String ACTION_CLOSE_GATT_DEVICES = "com.fsherratt.imudatalogger.ACTION_CLOSE_GATT_DEVICE";
    public final static String ACTION_CLOSE_GATT_ALL = "com.fsherratt.imudatalogger.ACTION_CLOSE_GATT_ALL";

    public final static String ACTION_START_STREAM_DEVICES = "com.fsherratt.imudatalogger.ACTION_START_STREAM_DEVICE";
    public final static String ACTION_STOP_STREAM_DEVICES = "com.fsherratt.imudatalogger.ACTION_STOP_STREAM_DEVICE";
    public final static String ACTION_GATT_STATE_CHANGED = "com.fsherratt.imudatalogger.ACTION_GATT_STATE_CHANGED";
    public final static String ACTION_REQUEST_RSSI = "com.fsherratt.imudatalogger.ACTION_REQUEST_RSSI";
    public final static String ACTION_REQUEST_MTU = "com.fsherratt.imudatalogger.ACTION_REQUEST_MTU";
    public final static String ACTION_REQUEST_CONNECTION_STATUS = "com.fsherratt.imudatalogger.ACTION_REQUEST_CONNECTION_STATUS";
    public final static String ACTION_REQUEST_BATTERY_LEVEL = "com.fsherratt.imudatalogger.ACTION_REQUEST_BATTERY_LEVEL";

    public final static String ACTION_SDO_READ = "com.fsherratt.imudatalogger.ACTION_SDO_READ";
    public final static String ACTION_SDO_WRITE = "com.fsherratt.imudatalogger.ACTION_SDO_WRITE";
    public final static String ACTION_SDO_DATA_AVAILABLE = "com.fsherratt.imudatalogger.ACTION_SDO_DATA_AVAILABLE";

    public final static String ACTION_COMPLETE = "com.fsherratt.imudatalogger.ACTION_COMPLETE";
    public final static String ACTION_SUCCESS = "com.fsherratt.imudatalogger.ACTION_SUCCESS";
    public final static String ACTION_FAILED = "com.fsherratt.imudatalogger.ACTION_COMPLETE";
    public final static String ACTION_CONNECTION_FAILED = "com.fsherratt.imudatalogger.ACTION_CONNECTION_FAILED";

    public final static String ACTION_DATA_AVAILABLE = "com.fsherratt.imudatalogger.ACTION_DATA_AVAILABLE";

    public final static String ACTION_DEVICE_RSSI = "com.fsherratt.imudatalogger.ACTION_DEVICE_RSSI";
    public final static String ACTION_BATTERY_LEVEL = "com.fsherratt.imudatalogger.ACTION_BATTERY_LEVEL";

    public final static String EXTRA_DEVICE_TYPE = "com.fsherratt.imudatalogger.EXTRA_DEVICE_TYPE";
    public final static String EXTRA_ADDRESS = "com.example.fsherratt.imudatalogger.EXTRA_ADDRESS";
    public final static String EXTRA_ADDRESS_LIST = "com.example.fsherratt.imudatalogger.EXTRA_ADDRESS_LIST";
    public final static String EXTRA_UUID = "com.fsherratt.imudatalogger.EXTRA_UUID";
    public final static String EXTRA_DATA = "com.fsherratt.imudatalogger.EXTRA_DATA";
    public final static String EXTRA_TIMESTAMP = "com.fsherratt.imudatalogger.EXTRA_TIMESTAMP";
    public final static String EXTRA_STATE = "com.fsherratt.imudatalogger.EXTRA_STATE";
    public final static String EXTRA_RSSI = "com.fsherratt.imudatalogger.EXTRA_RSSI";
    public final static String EXTRA_BATTERY = "com.fsherratt.imudatalogger.EXTRA_BATTERY";
    public final static String EXTRA_SDO_BYTE_ARRAY = "com.fsherratt.imudatalogger.EXTRA_SDO_BYTE_ARRAY";
    public final static String EXTRA_ACTION = "com.fsherratt.imudatalogger.EXTRA_ACTION";
    public final static String EXTRA_RESULT = "com.fsherratt.imudatalogger.EXTRA_RESULT";

    public final static int DEVICE_STATE_ERROR = -1;
    public final static int DEVICE_STATE_DISCONNECTED = 0;
    public final static int DEVICE_STATE_INITIALISING = 1;
    public final static int DEVICE_STATE_CONNECTING = 2;
    public final static int DEVICE_STATE_DISCOVERING = 3;
    public final static int DEVICE_STATE_CONNECTED = 4;
    public final static int DEVICE_STATE_STREAMING = 5;

    public final static String DEVICE_TYPE_MOVESENSE = "com.fsherratt.imudatalogger.DEVICE_TYPE_MOVESENSE";
    public final static String DEVICE_TYPE_THUMBREU = "com.fsherratt.imudatalogger.DEVICE_TYPE_THUMBREU";

    private final static String TAG = "bleService";

    private final static int ACTION_TIMEOUT_DELAY = 5000; // If action not complete within 5s cancel
    private final static int ACTION_TIMEOUT_DELAY_THUMBREU_CONNECT = 30000; // Thumb REU takes a while to connect

    private final BroadcastReceiver mActionReceiver;
    private final BroadcastReceiver mGattUpdateReceiver;
    private final BroadcastReceiver mConnectionReceiver;

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private HashMap<String, GattDevice> mConnectedDeviceMap = new HashMap<>();
    private Queue<BluetoothAction> actionQueue = new LinkedList<>();
    private BluetoothAction currentAction = null;
    private Semaphore mBleActionSemaphore = new Semaphore(1, true);
    private Handler mActionTimeoutHandler = new Handler();
    private Runnable mActionTimeoutRunnable = this::cancelAction;

    {
        mActionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String address = intent.getStringExtra(MainActivity.EXTRA_ADDRESS);
                final String action = intent.getStringExtra(EXTRA_ACTION);
                final String result = intent.getStringExtra(EXTRA_RESULT);

                if (currentAction == null)
                    return;

                assert address != null;
                if (!address.equals(currentAction.mdevice))
                    return;

                Log.d(TAG, "ActionReciever: Device: " + address + " Action: " + action + " Result: " + result);


                if (currentAction.mAction.equals(ACTION_ADD_GATT_DEVICES)) {
                    // If the action is connection and the result is discovering services reset timeout
                    if (result.equals(String.valueOf(DEVICE_STATE_DISCOVERING)))
                    {
                        mActionTimeoutHandler.removeCallbacks(mActionTimeoutRunnable);
                        mActionTimeoutHandler.postDelayed(mActionTimeoutRunnable, ACTION_TIMEOUT_DELAY);

                        Log.d(TAG, "mActionReceiver: Discovering services - connection timeout reset");
                        return;

                     // If the action is connection and the result is disconnected - connection failed
                    } else if (result.equals(String.valueOf(DEVICE_STATE_DISCONNECTED))) {
                        Log.d(TAG, "mActionReceiver: Connection failed - cancelling action");
                        Intent cancel_intent = new Intent(ACTION_CONNECTION_FAILED);
                        cancel_intent.putExtra(MainActivity.EXTRA_ADDRESS, address);
                        sendBroadcast(cancel_intent);

                        cancelAction();
                        return;
                    }
                }

                if (!currentAction.mExpectedOutcome.equals(result))
                    return;

                mActionTimeoutHandler.removeCallbacks(mActionTimeoutRunnable);
                mBleActionSemaphore.release();

                nextAction();
            }
        };
    }

    {
        mGattUpdateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();

                final Bundle intentExtras = intent.getExtras();
                if (action == null)
                    return;

                if (intentExtras == null)
                    return;

                if (intentExtras.containsKey(EXTRA_ADDRESS)) { // Single Operations
                    String device_address = intent.getStringExtra(EXTRA_ADDRESS);
                    // Add devices one at a time
                    switch (action) {
                        case ACTION_ADD_GATT_DEVICES: {
                            String device_type = null;
                            if (intentExtras.containsKey(EXTRA_DEVICE_TYPE))
                                device_type = intent.getStringExtra(EXTRA_DEVICE_TYPE);

                            actionQueue.add(new ConnectAction(ACTION_ADD_GATT_DEVICES, device_address, String.valueOf(DEVICE_STATE_CONNECTED), device_type));
                            actionQueue.add(new BluetoothAction(device_address, ACTION_REQUEST_MTU, ACTION_SUCCESS));

                            nextAction();
                            break;
                        }

                        case ACTION_SDO_WRITE: {
                            byte[] byte_array = {};
                            if (intentExtras.containsKey(EXTRA_SDO_BYTE_ARRAY))
                                byte_array = intent.getByteArrayExtra(EXTRA_SDO_BYTE_ARRAY);
                            actionQueue.add(new SDOWriteAction(action, device_address, ACTION_SUCCESS, byte_array));
                            break;
                        }

                        case ACTION_SDO_READ: {
                            byte[] byte_array = {};
                            actionQueue.add(new BluetoothAction(action, device_address, ACTION_SUCCESS));
                            break;
                        }
                    }
                } else if (intentExtras.containsKey(EXTRA_ADDRESS_LIST)) { // List operations
                    // For all other actions can control as many as desired
                    ArrayList<String> deviceList = new ArrayList<>();
                    deviceList = intent.getStringArrayListExtra(EXTRA_ADDRESS_LIST);

                    if (deviceList == null)
                        return;

                    switch (action) {

                        case ACTION_CLOSE_GATT_DEVICES: {
                            for (String deviceAddress : deviceList) {
                                disconnect(deviceAddress);
                                mConnectedDeviceMap.remove(deviceAddress);
                            }
                            break;
                        }

                        case ACTION_CLOSE_GATT_ALL: {
                            for (String deviceAddress : mConnectedDeviceMap.keySet()) {
                                disconnect(deviceAddress);
                            }
                            mConnectedDeviceMap.clear();
                            break;
                        }

                        case ACTION_START_STREAM_DEVICES: {
                            for (String deviceAddress : deviceList) {
                                enableStream(deviceAddress, true);
                            }
                            break;
                        }

                        case ACTION_STOP_STREAM_DEVICES: {
                            for (String deviceAddress : deviceList) {
                                enableStream(deviceAddress, false);
                            }
                            break;
                        }

                        case ACTION_REQUEST_RSSI:
                        case ACTION_REQUEST_BATTERY_LEVEL: {
                            for (String deviceAddress : deviceList) {
                                actionQueue.add(new BluetoothAction(action, deviceAddress, ACTION_SUCCESS));
                            }
                            nextAction();
                            break;
                        }

                        case ACTION_REQUEST_CONNECTION_STATUS: {
                            for (String deviceAddress : deviceList) {
                                MovesenseGattDevice device = (MovesenseGattDevice) mConnectedDeviceMap.get(deviceAddress);
                                if (device == null) {
                                    // Send back Unknown device status
                                    final Intent stateIntent = new Intent(ACTION_GATT_STATE_CHANGED);
                                    stateIntent.putExtra(MainActivity.EXTRA_ADDRESS, deviceAddress);
                                    stateIntent.putExtra(EXTRA_STATE, DEVICE_STATE_ERROR);

                                    sendBroadcast(stateIntent);
                                } else {
                                    device.requestConnectionState();
                                }
                            }
                            break;
                        }
                    }
                }
            }
        };
    }

    {
        mConnectionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();

                if (action == null)
                    return;

                if (action.equals(bleService.ACTION_GATT_STATE_CHANGED)) {
                    final String address = intent.getStringExtra(MainActivity.EXTRA_ADDRESS);
                    final int state = intent.getIntExtra(bleService.EXTRA_STATE, -1);

                    if (state == DEVICE_STATE_DISCONNECTED) {
                        mConnectedDeviceMap.remove(address);
                    } else if (state == DEVICE_STATE_ERROR) {
                        GattDevice device = mConnectedDeviceMap.get(address);

                        if (device == null) {
                            return;
                        }

                        device.close();
                        mConnectedDeviceMap.remove(currentAction.mdevice);
                    }
                }
            }
        };
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        if (!initialize()) {
            Log.e(TAG, "onCreate: Error creating bluetooth manager");
            return;
        }

        startReceiver();
        startActionReceiver();
        startConnectionReceiver();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Close all BLE connections
        for (GattDevice device : mConnectedDeviceMap.values()) {
            device.close();
        }

        stopReceiver();
        stopActionReceiver();
        stopConnectionReceiver();

        mActionTimeoutHandler.removeCallbacks(mActionTimeoutRunnable);
    }

    // Control device state
    public boolean initialize() {
        if (mBluetoothManager != null) {
            return false;
        }
        mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);

        if (mBluetoothManager == null) {
            Log.e(TAG, "Unable to initialize BluetoothManager.");
            return false;
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();

        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
            return false;
        }

        return true;
    }

    public boolean connect(String deviceAddress, String deviceType) {
        if (mBluetoothAdapter == null || deviceAddress == null) {
            Log.w(TAG, "connect: BluetoothAdapter not initialized or unspecified address.");
            return false;
        }

        if (mConnectedDeviceMap.containsKey(deviceAddress)) {
            Log.w(TAG, "connect: Device " + deviceAddress + " already connected");
            return false;
        }

        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(deviceAddress);

        if (device == null) {
            Log.w(TAG, "connect: Device not found.  Unable to connect.");
            return false;
        }

        Log.d(TAG, "connect: Found device");

        GattDevice newDevice;
        if (deviceType.equals(DEVICE_TYPE_MOVESENSE))
            newDevice = new MovesenseGattDevice(this, device);
        else if (deviceType.equals(DEVICE_TYPE_THUMBREU))
            newDevice = new ThumbREUGattDevice(this, device);
        else
            return false;

        mConnectedDeviceMap.put(deviceAddress, newDevice);

        return true;
    }

    public void disconnect(String deviceAddress) {
        GattDevice device = mConnectedDeviceMap.get(deviceAddress);

        if (device == null) {
            return;
        }

        device.close();
    }

    public void enableStream(String deviceAddress, boolean enable) {
        GattDevice device = mConnectedDeviceMap.get(deviceAddress);

        if (device == null) {
            return;
        }

        if (device instanceof ThumbREUGattDevice)
            ((ThumbREUGattDevice)device).setStream(enable);
        else if (device instanceof MovesenseGattDevice)
            ((MovesenseGattDevice)device).setStream(enable);
    }

    public boolean requestRssi(String deviceAddress) {
        GattDevice device = mConnectedDeviceMap.get(deviceAddress);

        if (device == null) {
            return false;
        }

        if (device instanceof ThumbREUGattDevice)
            return ((ThumbREUGattDevice)device).readRssi();
        else if (device instanceof MovesenseGattDevice)
            return ((MovesenseGattDevice)device).readRssi();

        return false;
    }

    public boolean requestMTU(String deviceAddress) {
        GattDevice device = mConnectedDeviceMap.get(deviceAddress);

        if (device == null) {
            return false;
        }

        int size = 300;

        if (device instanceof ThumbREUGattDevice)
            return ((ThumbREUGattDevice)device).requestMtu(size);
        else if (device instanceof MovesenseGattDevice)
            return ((MovesenseGattDevice)device).requestMtu(size);

        return false;
    }

    public boolean requestBattery(String deviceAddress) {
        GattDevice device = mConnectedDeviceMap.get(deviceAddress);

        if (device == null) {
            return false;
        }

        if (device instanceof ThumbREUGattDevice)
            return ((ThumbREUGattDevice)device).requestBatteryLevel();
        else if (device instanceof MovesenseGattDevice)
            return ((MovesenseGattDevice)device).requestBatteryLevel();

        return false;
    }

    public boolean readSDOAction(String deviceAddress) {
        ThumbREUGattDevice device = (ThumbREUGattDevice)mConnectedDeviceMap.get(deviceAddress);

        if (device == null) {
            return false;
        }

        return device.readSDO();
    }

    public boolean writeSDOAction(String deviceAddress, byte[] byte_array) {
        ThumbREUGattDevice device = (ThumbREUGattDevice)mConnectedDeviceMap.get(deviceAddress);

        if (device == null) {
            return false;
        }

        return device.writeSDO(byte_array);
    }

    // Device connection state callbacks
    private void startActionReceiver() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_COMPLETE);

        try {
            registerReceiver(mActionReceiver, intentFilter);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Receiver already registered");
        }
    }

    private void stopActionReceiver() {
        try {
            unregisterReceiver(mActionReceiver);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Receiver not registered");
        }
    }

    public void nextAction() {
        if (actionQueue.size() == 0) {
            return;
        }

        if (!mBleActionSemaphore.tryAcquire()) {
            return;
        }

        BluetoothAction newAction = actionQueue.remove();

        boolean success = false;
        int timeout_delay = ACTION_TIMEOUT_DELAY;

        switch (newAction.mAction) {
            case ACTION_ADD_GATT_DEVICES: {
                ConnectAction typed_action = (ConnectAction) newAction;
                success = connect(newAction.mdevice, typed_action.mDeviceType);

                if (typed_action.mDeviceType.equals(DEVICE_TYPE_THUMBREU)) {
                    timeout_delay = ACTION_TIMEOUT_DELAY_THUMBREU_CONNECT; // Thumb REU can take a long time
                }
                break;
            }

            case ACTION_REQUEST_RSSI:
                success = requestRssi(newAction.mdevice);
                break;

            case ACTION_REQUEST_MTU:
                success = requestMTU(newAction.mdevice);
                break;

            case ACTION_REQUEST_BATTERY_LEVEL:
                success = requestBattery(newAction.mdevice);
                break;

            case ACTION_SDO_READ:
                success = readSDOAction(newAction.mdevice);
                Log.d(TAG, "nextAction: SDO read: Success: " + String.valueOf(success));
                break;

            case ACTION_SDO_WRITE: {
                SDOWriteAction typed_action = (SDOWriteAction) newAction;
                success = writeSDOAction(newAction.mdevice, typed_action.mWriteRequest);
                Log.d(TAG, "nextAction: SDO Write: " + byteTypecast.bytesToHex(typed_action.mWriteRequest) + " Success: " + String.valueOf(success));
                break;
            }

            default:
                break;
        }

        if (!success) {
            mBleActionSemaphore.release();
            currentAction = null;

            nextAction();
            return;
        }

        currentAction = newAction;
        mActionTimeoutHandler.postDelayed(mActionTimeoutRunnable, timeout_delay);
    }

    public void cancelAction() {
        if (currentAction == null) {
            return;
        }

        GattDevice device = mConnectedDeviceMap.get(currentAction.mdevice);

        if (device != null) {
            device.setConnectionState(DEVICE_STATE_ERROR);
            device.close();

            mConnectedDeviceMap.remove(currentAction.mdevice);
        }

        mBleActionSemaphore.release();
        currentAction = null;

        nextAction();
    }

    // Broadcast receiver for instructions from UI
    private void startReceiver() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_ADD_GATT_DEVICES);
        intentFilter.addAction(ACTION_CLOSE_GATT_DEVICES);
        intentFilter.addAction(ACTION_CLOSE_GATT_ALL);
        intentFilter.addAction(ACTION_START_STREAM_DEVICES);
        intentFilter.addAction(ACTION_STOP_STREAM_DEVICES);
        intentFilter.addAction(ACTION_REQUEST_RSSI);
        intentFilter.addAction(ACTION_REQUEST_CONNECTION_STATUS);
        intentFilter.addAction(ACTION_REQUEST_BATTERY_LEVEL);
        intentFilter.addAction(ACTION_SDO_READ);
        intentFilter.addAction(ACTION_SDO_WRITE);

        try {
            registerReceiver(mGattUpdateReceiver, intentFilter);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Receiver already registered");
        }
    }

    private void stopReceiver() {
        try {
            unregisterReceiver(mGattUpdateReceiver);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Receiver not registered");
        }
    }

    // Broadcast reciever for device connection state
    private void startConnectionReceiver() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_GATT_STATE_CHANGED);

        try {
            registerReceiver(mConnectionReceiver, intentFilter);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Receiver already registered");
        }
    }

    private void stopConnectionReceiver() {
        try {
            unregisterReceiver(mConnectionReceiver);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Receiver not registered");
        }
    }

    // Queueable Actions
    static class BluetoothAction {
        String mAction;
        String mdevice;
        String mExpectedOutcome;

        BluetoothAction(String action, String device, String expectedOutcome) {
            this.mAction = action;
            this.mdevice = device;
            this.mExpectedOutcome = expectedOutcome;
        }
    }

    static class ConnectAction extends BluetoothAction {
        String mDeviceType;

        ConnectAction(String action, String device, String expectedOutcome, String deviceType) {
            super(action, device, expectedOutcome);
            this.mDeviceType = deviceType;
        }
    }

    static class SDOWriteAction extends BluetoothAction {
        byte[] mWriteRequest;

        SDOWriteAction(String action, String device, String expectedOutcome, byte[] writeRequest ) {
            super(action, device, expectedOutcome);
            this.mWriteRequest = writeRequest;
        }
    }

    // GATT Device Classes
    static class GattDevice {
        final UUID clientCharacterisitcConfig = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

        protected String TAG;
        protected Context mContext;
        protected int mConnectionState;

        protected BluetoothDevice mBluetoothDevice;
        protected BluetoothGatt mDeviceGatt;
        protected BluetoothGattCallback mGattCallback;

        protected Semaphore mCharWriteSemaphore = new Semaphore(1, true);

        GattDevice(Context context, BluetoothDevice device) {
            TAG = "BluetoothGattDevice-" + device.getAddress();

            mContext = context;
            mBluetoothDevice = device;

            setConnectionState(bleService.DEVICE_STATE_INITIALISING);

            mGattCallback = new GattDevice.gattCallback();
            connect();
        }

        void connect() {
            mConnectionState = bleService.DEVICE_STATE_CONNECTING;
            mDeviceGatt = mBluetoothDevice.connectGatt(mContext, false, mGattCallback);

            if (mDeviceGatt == null) {
                setConnectionState(DEVICE_STATE_ERROR);
                return;
            }

            setConnectionState(DEVICE_STATE_CONNECTING);
        }

        void close() {
            if (mDeviceGatt == null) {
                setConnectionState(DEVICE_STATE_ERROR);
                return;
            }

            mDeviceGatt.disconnect();
        }

        protected void setCharacteristicNotification(BluetoothGattCharacteristic characteristic, boolean enabled) {
            if (mDeviceGatt == null) {
                return;
            }

            // Request write lock
            try {
                mCharWriteSemaphore.tryAcquire(1000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Log.e(TAG, Objects.requireNonNull(e.getMessage()));
                return;
            }

            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(clientCharacterisitcConfig);

            if (descriptor == null) {
                return;
            }

            // Enable on android
            mDeviceGatt.setCharacteristicNotification(characteristic, enabled);

            if (enabled) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            } else {
                descriptor.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
            }

            // Enable on device
            mDeviceGatt.writeDescriptor(descriptor);
        }

        protected void actionComplete(String action, String result) {
            final Intent intent = new Intent(bleService.ACTION_COMPLETE);
            intent.putExtra(MainActivity.EXTRA_ADDRESS, mBluetoothDevice.getAddress());
            intent.putExtra(bleService.EXTRA_ACTION, action);
            intent.putExtra(bleService.EXTRA_RESULT, result);

            mContext.sendBroadcast(intent);
        }

        boolean requestMtu(int size) {
            return mDeviceGatt.requestMtu(size);
        }

        protected void setConnectionState(int newState) {
            mConnectionState = newState;
            requestConnectionState();
        }

        void requestConnectionState() {
            final Intent intent = new Intent(ACTION_GATT_STATE_CHANGED);
            intent.putExtra(MainActivity.EXTRA_ADDRESS, mBluetoothDevice.getAddress());
            intent.putExtra(EXTRA_STATE, mConnectionState);

            mContext.sendBroadcast(intent);

            actionComplete(bleService.ACTION_GATT_STATE_CHANGED, String.valueOf(mConnectionState));
        }

        boolean readRssi() {
            if (mDeviceGatt == null) {
                setConnectionState(DEVICE_STATE_ERROR);
                return false;
            }

            if (mConnectionState != DEVICE_STATE_CONNECTED && mConnectionState != DEVICE_STATE_STREAMING)
                return false;

            return mDeviceGatt.readRemoteRssi();
        }

        private void setRssiState(int rssi) {
            final Intent intent = new Intent(bleService.ACTION_DEVICE_RSSI);
            intent.putExtra(MainActivity.EXTRA_ADDRESS, mBluetoothDevice.getAddress());
            intent.putExtra(bleService.EXTRA_RSSI, rssi);

            mContext.sendBroadcast(intent);
            actionComplete(bleService.ACTION_REQUEST_RSSI, ACTION_SUCCESS);
        }

        class gattCallback extends BluetoothGattCallback {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                switch (newState) {
                    case BluetoothProfile.STATE_CONNECTED: {
                        gatt.discoverServices();
                        setConnectionState(DEVICE_STATE_DISCOVERING);
                        break;
                    }

                    case BluetoothProfile.STATE_DISCONNECTED: {
                        setConnectionState(DEVICE_STATE_DISCONNECTED);
                        gatt.close();
                        break;
                    }

                    default:
                        setConnectionState(DEVICE_STATE_ERROR);

                }
            }

            @Override
            public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
                super.onReadRemoteRssi(gatt, rssi, status);

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    setRssiState(rssi);
                } else {
                    setConnectionState(DEVICE_STATE_ERROR);
                }
            }

            @Override
            public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
                super.onMtuChanged(gatt, mtu, status);

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    actionComplete(ACTION_REQUEST_MTU, ACTION_SUCCESS);
                } else {
                    actionComplete(ACTION_REQUEST_MTU, ACTION_FAILED);
                    setConnectionState(DEVICE_STATE_ERROR);
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                super.onServicesDiscovered(gatt, status);

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    setConnectionState(DEVICE_STATE_CONNECTED);
                    servicesDiscoveredAction(gatt);
                } else {
                    setConnectionState(DEVICE_STATE_ERROR);
                }
            }

            @Override
            public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                super.onCharacteristicRead(gatt, characteristic, status);

                boolean success = (status == BluetoothGatt.GATT_SUCCESS);
                mCharWriteSemaphore.release();

                characteristicReadAction(characteristic, success);

                if (!success) {
                    setConnectionState(DEVICE_STATE_ERROR);
                }
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                super.onCharacteristicWrite(gatt, characteristic, status);

                boolean success = (status == BluetoothGatt.GATT_SUCCESS);
                mCharWriteSemaphore.release();

                characteristicWriteAction(characteristic, success);

                if (!success) {
                    setConnectionState(DEVICE_STATE_ERROR);
                }
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                super.onCharacteristicChanged(gatt, characteristic);
                characteristicNotificationAction(characteristic);
            }

            @Override
            public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                super.onDescriptorRead(gatt, descriptor, status);

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    descriptorReadAction(descriptor);
                } else {
                    setConnectionState(bleService.DEVICE_STATE_ERROR);
                }
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                super.onDescriptorWrite(gatt, descriptor, status);
                mCharWriteSemaphore.release();

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    descriptorWriteAction(descriptor);
                } else {
                    setConnectionState(bleService.DEVICE_STATE_ERROR);
                }


            }
        }

        void setStream(boolean enable) {}
        boolean requestBatteryLevel() { return false; }

        public boolean readCharacteristic(BluetoothGattCharacteristic characteristic) {
            if (mBluetoothDevice == null || mDeviceGatt == null) {
                Log.w(TAG, "readCharacteristic: BluetoothAdapter not initialized");
                return false;
            }

            try {
                mCharWriteSemaphore.tryAcquire(1000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Log.e(TAG, Objects.requireNonNull(e.getMessage()));
                return false;
            }

            if ( !mDeviceGatt.readCharacteristic(characteristic) ) {
                Log.e(TAG, "readCharacteristic: read failed");
                mCharWriteSemaphore.release();
                return false;
            }

            return true;
        }

        public boolean writeCharacteristic(BluetoothGattCharacteristic characteristic, byte[] value) {
            if (mBluetoothDevice == null || mDeviceGatt == null) {
                Log.w(TAG, "readCharacteristic: BluetoothAdapter not initialized");
                return false;
            }

            try {
                mCharWriteSemaphore.tryAcquire(1000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Log.e(TAG, Objects.requireNonNull(e.getMessage()));
                return false;
            }

            characteristic.setValue( value );

            if ( !mDeviceGatt.writeCharacteristic(characteristic) ) {
                Log.e(TAG, "readCharacteristic: read failed");
                mCharWriteSemaphore.release();
                return false;
            }
            return true;
        }

        protected void servicesDiscoveredAction(BluetoothGatt gatt) {};

        protected void characteristicReadAction(BluetoothGattCharacteristic characteristic, boolean success) {};
        protected void characteristicWriteAction(BluetoothGattCharacteristic characteristic, boolean success) {};
        protected void characteristicNotificationAction(BluetoothGattCharacteristic characteristic) {};

        protected void descriptorReadAction(BluetoothGattDescriptor descriptor) {};
        protected void descriptorWriteAction(BluetoothGattDescriptor descriptor) {}

    }
    // Bluetooth GATT Connection class

    static class MovesenseGattDevice extends GattDevice {
        final UUID streamServiceUUID = UUID.fromString("8f68223d-01df-3097-2e45-01a046aada78");
        final UUID streamChar1UUID = UUID.fromString("00000001-0000-1000-8000-00805f9b34fb");
        final UUID batteryServiceUUID = UUID.fromString("180F-0000-1000-8000-00805f9b34fb");
        final UUID batteryCharacteristicUUID = UUID.fromString("2A19-0000-1000-8000-00805f9b34fb");


        MovesenseGattDevice(Context context, BluetoothDevice device) {
            super(context, device);
        }

        protected void characteristicReadAction(BluetoothGattCharacteristic characteristic, boolean success) {
            if (!success)
                return;

            if (characteristic.getUuid().equals(batteryCharacteristicUUID)) {
                actionComplete(ACTION_REQUEST_BATTERY_LEVEL, ACTION_SUCCESS);
                byte batteryLevel = characteristic.getValue()[0];
                setBatteryLevel(batteryLevel);
            }
        }

        protected void characteristicNotificationAction(BluetoothGattCharacteristic characteristic) {
            setNewData(characteristic.getValue(), characteristic.getUuid().toString());
        }

        protected void servicesDiscoveredAction(BluetoothGatt gatt) {
            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
        }

        void setStream(boolean enable) {
            if (mConnectionState != DEVICE_STATE_CONNECTED && mConnectionState != DEVICE_STATE_STREAMING)
                return;

            final BluetoothGattService streamService = mDeviceGatt.getService(streamServiceUUID);

            if (streamService == null) {
                Log.e(TAG, "setStream: Stream service not found");
                return;
            }

            final BluetoothGattCharacteristic streamChar1 = streamService.getCharacteristic(streamChar1UUID);

            if (streamChar1 == null) {
                Log.e(TAG, "setStream: Stream 1 characteristics not found");
                return;
            }

            setCharacteristicNotification(streamChar1, enable);
        }

        protected void descriptorWriteAction(BluetoothGattDescriptor descriptor) {
            if (descriptor.getValue() == BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) {
                setConnectionState(DEVICE_STATE_STREAMING);
//                        setStreamStatus(descriptorUpdateUUID, true );
            } else if (descriptor.getValue() == BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) {
                setConnectionState(DEVICE_STATE_CONNECTED);
//                        setStreamStatus(descriptorUpdateUUID, false );
            }
        }

        boolean requestBatteryLevel() {
            if (mConnectionState != DEVICE_STATE_CONNECTED && mConnectionState != DEVICE_STATE_STREAMING)
                return false;

            final BluetoothGattService streamService = mDeviceGatt.getService(batteryServiceUUID);

            if (streamService == null) {
                Log.e(TAG, "setStream: Stream service not found");
                return false;
            }

            final BluetoothGattCharacteristic battChar1 = streamService.getCharacteristic(batteryCharacteristicUUID);

            if (battChar1 == null) {
                Log.e(TAG, "setStream: Stream 1 characteristics not found");
                return false;
            }

            return mDeviceGatt.readCharacteristic(battChar1);
        }

        private void setBatteryLevel(byte batteryLevel) {
            final Intent intent = new Intent(bleService.ACTION_BATTERY_LEVEL);
            intent.putExtra(MainActivity.EXTRA_ADDRESS, mBluetoothDevice.getAddress());
            intent.putExtra(bleService.EXTRA_BATTERY, (int) batteryLevel);

            mContext.sendBroadcast(intent);
        }

        private void setNewData(byte[] data, String uuid) {
            final Intent intent = new Intent(bleService.ACTION_DATA_AVAILABLE);
            intent.putExtra(MainActivity.EXTRA_ADDRESS, mBluetoothDevice.getAddress());
            intent.putExtra(bleService.EXTRA_UUID, uuid);
            intent.putExtra(bleService.EXTRA_DATA, data);
            intent.putExtra(bleService.EXTRA_TIMESTAMP, logService.makeTimestamp());

            mContext.sendBroadcast(intent);
        }
    }

    static class ThumbREUGattDevice extends GattDevice {
        private final static String CANOPEN_SDO_SERVICE = "a7eda1fc-dd16-4455-9994-cc42d8ae1a81";
        private final static String CANOPEN_SDO_CHARACTERISTIC = "d3bfcff2-0171-4c74-a373-7e4ce5f88cc6";

        private BluetoothGattCharacteristic SDOCharacteristic = null;

        ThumbREUGattDevice(Context context, BluetoothDevice device) {
            super(context, device);
        }

        protected void servicesDiscoveredAction(BluetoothGatt gatt) {
            for (BluetoothGattService service : gatt.getServices()) {
                if( !service.getUuid().toString().equals(CANOPEN_SDO_SERVICE) )
                    continue;

                for (BluetoothGattCharacteristic character : service.getCharacteristics()) {
                    if (!character.getUuid().toString().equals(CANOPEN_SDO_CHARACTERISTIC))
                        continue;

                    Log.d(TAG, "servicesDiscoveredAction: Found SDO Characteristic");

                    SDOCharacteristic = character;
                    return;
                }
            }
        }

//        boolean readRssi() { return false; }

        boolean requestBatteryLevel() { return false; }

        boolean requestMtu(int size) { return false; }

        boolean readSDO() {
            if (SDOCharacteristic == null) {
                Log.d(TAG, "readSDO: Unknown SDO Characteristic");
                return false;
            }

            return readCharacteristic(SDOCharacteristic);
        }

        boolean writeSDO(byte[] byte_array) {
            if (SDOCharacteristic == null) {
                Log.d(TAG, "writeSDO: Unknow SDO Characteristic");
                return false;
            }

            return writeCharacteristic(SDOCharacteristic, byte_array);
        }

        protected void characteristicReadAction(BluetoothGattCharacteristic characteristic, boolean success) {
            // Send characteristics update
            final Intent intent = new Intent(ACTION_SDO_DATA_AVAILABLE);
            intent.putExtra(EXTRA_ADDRESS, mBluetoothDevice.getAddress());
            intent.putExtra(EXTRA_UUID, characteristic.getUuid().toString());
            intent.putExtra(EXTRA_DATA, characteristic.getValue());

            mContext.sendBroadcast(intent);

            if (success) {
                actionComplete(ACTION_SDO_READ, ACTION_SUCCESS);
            } else {
                actionComplete(ACTION_SDO_READ, ACTION_FAILED);
            }
        }

        protected void characteristicWriteAction(BluetoothGattCharacteristic characteristic,  boolean success) {
            if (success) {
                actionComplete(ACTION_SDO_WRITE, ACTION_SUCCESS);
            } else {
                actionComplete(ACTION_SDO_WRITE, ACTION_FAILED);
            }
        }

        protected void characteristicNotificationAction(BluetoothGattCharacteristic characteristic) {}
    }
}