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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import androidx.annotation.Nullable;

// Management of GATT devices.
// Creation, stream enable/disable and destruction
public class bleService extends Service {
    private static final String TAG = "bleService";

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

    public final static String ACTION_COMPLETE = "com.fsherratt.imudatalogger.ACTION_COMPLETE";
    public final static String ACTION_SUCCESS = "com.fsherratt.imudatalogger.ACTION_SUCCESS";
    public final static String ACTION_FAILED = "com.fsherratt.imudatalogger.ACTION_COMPLETE";
    public final static String ACTION_DATA_AVAILABLE = "com.fsherratt.imudatalogger.ACTION_DATA_AVAILABLE";
    public final static String ACTION_DEVICE_RSSI = "com.fsherratt.imudatalogger.ACTION_DEVICE_RSSI";
    public final static String ACTION_BATTERY_LEVEL = "com.fsherratt.imudatalogger.ACTION_BATTERY_LEVEL";

    public final static String EXTRA_ADDRESS_LIST = "com.example.fsherratt.imudatalogger.EXTRA_ADDRESS_LIST";
    public final static String EXTRA_UUID = "com.fsherratt.imudatalogger.EXTRA_UUID";
    public final static String EXTRA_DATA = "com.fsherratt.imudatalogger.EXTRA_DATA";
    public final static String EXTRA_TIMESTAMP = "com.fsherratt.imudatalogger.EXTRA_TIMESTAMP";
    public final static String EXTRA_STATE = "com.fsherratt.imudatalogger.EXTRA_STATE";
    public final static String EXTRA_RSSI = "com.fsherratt.imudatalogger.EXTRA_RSSI";
    public final static String EXTRA_BATTERY = "com.fsherratt.imudatalogger.EXTRA_RSSI";
    public final static String EXTRA_ACTION = "com.fsherratt.imudatalogger.EXTRA_ACTION";
    public final static String EXTRA_RESULT = "com.fsherratt.imudatalogger.EXTRA_RESULT";

    public final static int DEVICE_STATE_ERROR = -1;
    public final static int DEVICE_STATE_DISCONNECTED = 0;
    public final static int DEVICE_STATE_INITIALISING = 1;
    public final static int DEVICE_STATE_CONNECTING = 2;
    public final static int DEVICE_STATE_DISCOVERING = 3;
    public final static int DEVICE_STATE_CONNECTED = 4;
    public final static int DEVICE_STATE_STREAMING = 5;

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;

    private HashMap<String, bleGattDevice> mConnectedDeviceMap = new HashMap<>();

    private Queue<BluetoothAction> actionQueue = new LinkedList<>();
    private BluetoothAction currentAction = null;

    private Semaphore mBleActionSemaphore = new Semaphore(1, true);

    private Handler mActionTimeoutHandler = new Handler();
    private Runnable mActionTimeoutRunnable = this::cancelAction;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        if ( !initialize() ) {
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
        for ( bleGattDevice device : mConnectedDeviceMap.values() ) {
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
            Log.e(TAG,  "Unable to initialize BluetoothManager.");
            return false;
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();

        if (mBluetoothAdapter == null) {
            Log.e(TAG,  "Unable to obtain a BluetoothAdapter.");
            return false;
        }

        return true;
    }

    public boolean connect(String deviceAddress) {
        if (mBluetoothAdapter == null || deviceAddress == null) {
            Log.w(TAG,  "connect: BluetoothAdapter not initialized or unspecified address.");
            return false;
        }

        if (mConnectedDeviceMap.containsKey(deviceAddress)) {
            Log.w(TAG, "connect: Device " + deviceAddress + " already connected");
            return false;
        }

        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(deviceAddress);

        if (device == null) {
            Log.w(TAG,  "connect: Device not found.  Unable to connect.");
            return false;
        }

        Log.d(TAG,  "connect: Found device");

        mConnectedDeviceMap.put(deviceAddress, new bleGattDevice(this, device));

        return true;
    }

    public void disconnect(String deviceAddress) {
        bleGattDevice device = mConnectedDeviceMap.get(deviceAddress);

        if (device == null) {
            return;
        }

        device.close();
    }

    public void enableStream(String deviceAddress, boolean enable) {
        bleGattDevice device = mConnectedDeviceMap.get(deviceAddress);

        if (device == null) {
            return;
        }

        device.setStream(enable);
    }

    public boolean requestRssi(String deviceAddress) {
        bleGattDevice device = mConnectedDeviceMap.get(deviceAddress);

        if (device == null) {
            return false;
        }

        return device.readRssi();
    }

    public boolean requestMTU(String deviceAddress) {
        bleGattDevice device = mConnectedDeviceMap.get(deviceAddress);

        if (device == null) {
            return false;
        }

        int size = 300;

        return device.requestMtu(size);
    }

    public boolean requestBattery(String deviceAddress) {
        bleGattDevice device = mConnectedDeviceMap.get(deviceAddress);

        if (device == null) {
            return false;
        }

        return device.requestBatteryLevel();
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
        } catch(IllegalArgumentException e) {
            Log.e(TAG, "Receiver not registered");
        }
    }

    private final BroadcastReceiver mActionReceiver; {
        mActionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String address = intent.getStringExtra(MainActivity.EXTRA_ADDRESS);
                final String action = intent.getStringExtra(EXTRA_ACTION);
                final String result = intent.getStringExtra(EXTRA_RESULT);

                if ( currentAction == null)
                    return;

                if ( !address.equals(currentAction.mdevice))
                    return;

                Log.d(TAG, "ActionReciever: Device: " + address + " Action: " + action + " Result: " + result);

                if ( !currentAction.mExpectedOutcome.equals(result) )
                    return;

                mActionTimeoutHandler.removeCallbacks(mActionTimeoutRunnable);
                mBleActionSemaphore.release();

                nextAction();
            }
        };
    }

    public void nextAction() {
        if ( actionQueue.size() == 0 )
        {
            return;
        }

        if ( !mBleActionSemaphore.tryAcquire() ) {
            return;
        }

        BluetoothAction newAction = actionQueue.remove();

        boolean success = false;

        switch( newAction.mAction) {
            case ACTION_ADD_GATT_DEVICES:
                success = connect(newAction.mdevice);
                break;

            case ACTION_REQUEST_RSSI:
                success = requestRssi(newAction.mdevice);
                break;

            case ACTION_REQUEST_MTU:
                success = requestMTU(newAction.mdevice);
                break;

            case ACTION_REQUEST_BATTERY_LEVEL:
                success = requestBattery(newAction.mdevice);
                break;

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
        mActionTimeoutHandler.postDelayed(mActionTimeoutRunnable, 5000);
    }

    public void cancelAction() {
        if ( currentAction == null ) {
            return;
        }

        bleGattDevice device = mConnectedDeviceMap.get(currentAction.mdevice);

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

        try {
            registerReceiver(mGattUpdateReceiver, intentFilter);
        } catch(IllegalArgumentException e) {
            Log.e(TAG, "Receiver already registered");
        }
    }

    private void stopReceiver() {
        try {
            unregisterReceiver(mGattUpdateReceiver);
        } catch(IllegalArgumentException e) {
            Log.e(TAG, "Receiver not registered");
        }
    }

    private final BroadcastReceiver mGattUpdateReceiver; {
        mGattUpdateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();

                final Bundle intentExtras = intent.getExtras();

                ArrayList<String> deviceList = new ArrayList<>();

                if (intentExtras != null) {
                    if (intentExtras.containsKey(EXTRA_ADDRESS_LIST))
                        deviceList = intent.getStringArrayListExtra(EXTRA_ADDRESS_LIST);
                }

                if (action == null)
                    return;

                switch (action) {
                    case ACTION_ADD_GATT_DEVICES: {
                        for (String deviceAddress : deviceList) {
                            actionQueue.add(new BluetoothAction(ACTION_ADD_GATT_DEVICES, deviceAddress, String.valueOf(DEVICE_STATE_CONNECTED)));
                            actionQueue.add(new BluetoothAction(deviceAddress, ACTION_REQUEST_MTU, ACTION_SUCCESS));
                        }
                        nextAction();
                        break;
                    }

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
                            bleGattDevice device = mConnectedDeviceMap.get(deviceAddress);
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
        };
    }

    static class BluetoothAction {
        String mAction;
        String mdevice;
        String mExpectedOutcome;

        BluetoothAction(String action, String device, String expectedOutcome ) {
            this.mAction = action;
            this.mdevice = device;
            this.mExpectedOutcome = expectedOutcome;
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
        } catch(IllegalArgumentException e) {
            Log.e(TAG, "Receiver not registered");
        }
    }

    private final BroadcastReceiver mConnectionReceiver; {
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
                        bleGattDevice device = mConnectedDeviceMap.get(address);

                        if ( device == null ) {
                            return;
                        }

                        device.close();
                        mConnectedDeviceMap.remove(currentAction.mdevice);
                    }
                }
            }
        };
    }


    // Bluetooth GATT Connection class
    static class bleGattDevice {
        private String TAG;

        final UUID streamServiceUUID = UUID.fromString("8f68223d-01df-3097-2e45-01a046aada78");
        final UUID streamChar1UUID = UUID.fromString("00000001-0000-1000-8000-00805f9b34fb");
        final UUID batteryServiceUUID = UUID.fromString("180F-0000-1000-8000-00805f9b34fb");
        final UUID batteryCharacteristicUUID = UUID.fromString("2A19-0000-1000-8000-00805f9b34fb");
        final UUID clientCharacterisitcConfig = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

        private Context mContext;
        private int mConnectionState;

        private BluetoothDevice mBluetoothDevice;
        private BluetoothGatt mDeviceGatt;
        private BluetoothGattCallback mGattCallback;

        private Semaphore mCharWriteSemaphore = new Semaphore(1, true);


        bleGattDevice( Context context, BluetoothDevice device ) {
            TAG = "BluetoothGattDevice-" + device.getAddress();

            mContext = context;
            mBluetoothDevice = device;

            setConnectionState(bleService.DEVICE_STATE_INITIALISING);

            mGattCallback = new gattCallback();
            connect();
        }

        void connect() {
            mConnectionState = bleService.DEVICE_STATE_CONNECTING;
            mDeviceGatt = mBluetoothDevice.connectGatt(mContext, false, mGattCallback);

            if (mDeviceGatt == null)
            {
                setConnectionState(DEVICE_STATE_ERROR);
                return;
            }

            setConnectionState(DEVICE_STATE_CONNECTING);
        }

        void close() {
            if ( mDeviceGatt == null )
            {
                setConnectionState(DEVICE_STATE_ERROR);
                return;
            }

            mDeviceGatt.disconnect();
        }

        void setStream( boolean enable ) {
            if ( mConnectionState != DEVICE_STATE_CONNECTED && mConnectionState != DEVICE_STATE_STREAMING )
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

        boolean readRssi() {
            if (mDeviceGatt == null) {
                setConnectionState(DEVICE_STATE_ERROR);
                return false;
            }

            return mDeviceGatt.readRemoteRssi();
        }

        boolean requestMtu( int size ) {
            return mDeviceGatt.requestMtu(size);
        }

        boolean requestBatteryLevel() {
            if ( mConnectionState != DEVICE_STATE_CONNECTED && mConnectionState != DEVICE_STATE_STREAMING )
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

        void requestConnectionState() {
            final Intent intent = new Intent(ACTION_GATT_STATE_CHANGED);
            intent.putExtra(MainActivity.EXTRA_ADDRESS, mBluetoothDevice.getAddress());
            intent.putExtra(EXTRA_STATE, mConnectionState);

            mContext.sendBroadcast(intent);

            actionComplete(bleService.ACTION_GATT_STATE_CHANGED, String.valueOf(mConnectionState));
        }


        private void setConnectionState( int newState ) {
            mConnectionState = newState;
            requestConnectionState();
        }


        private void setRssiState( int rssi ) {
            final Intent intent = new Intent(bleService.ACTION_DEVICE_RSSI);
            intent.putExtra(MainActivity.EXTRA_ADDRESS, mBluetoothDevice.getAddress());
            intent.putExtra(bleService.EXTRA_RSSI, rssi);

            mContext.sendBroadcast(intent);
            actionComplete(bleService.ACTION_REQUEST_RSSI, ACTION_SUCCESS);
        }

        private void setBatteryLevel(byte batteryLevel) {
            final Intent intent = new Intent(bleService.ACTION_BATTERY_LEVEL);
            intent.putExtra(MainActivity.EXTRA_ADDRESS, mBluetoothDevice.getAddress());
            intent.putExtra(bleService.EXTRA_BATTERY, (int)batteryLevel);

            mContext.sendBroadcast(intent);
        }

        private void setNewData( byte[] data, String uuid ) {
            final Intent intent = new Intent(bleService.ACTION_DATA_AVAILABLE);
            intent.putExtra(MainActivity.EXTRA_ADDRESS, mBluetoothDevice.getAddress());
            intent.putExtra(bleService.EXTRA_UUID, uuid);
            intent.putExtra(bleService.EXTRA_DATA, data);
            intent.putExtra(bleService.EXTRA_TIMESTAMP, logService.makeTimestamp());

            mContext.sendBroadcast(intent);
        }

        private void actionComplete( String action, String result ) {
            final Intent intent = new Intent(bleService.ACTION_COMPLETE);
            intent.putExtra(MainActivity.EXTRA_ADDRESS, mBluetoothDevice.getAddress());
            intent.putExtra(bleService.EXTRA_ACTION, action);
            intent.putExtra(bleService.EXTRA_RESULT, result);

            mContext.sendBroadcast(intent);
        }


        class gattCallback extends BluetoothGattCallback {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if ( newState == BluetoothProfile.STATE_CONNECTED ) {
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);

                    gatt.discoverServices();
                    setConnectionState(DEVICE_STATE_DISCOVERING);

                } else if ( newState == BluetoothProfile.STATE_DISCONNECTED ) {
                    setConnectionState(DEVICE_STATE_DISCONNECTED);
                    gatt.close();
                } else {
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

                } else {
                    setConnectionState(DEVICE_STATE_ERROR);
                }
            }

            @Override
            public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                super.onCharacteristicRead(gatt, characteristic, status);

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    if ( characteristic.getUuid().equals(batteryCharacteristicUUID) ) {
                        actionComplete(ACTION_REQUEST_BATTERY_LEVEL, ACTION_SUCCESS);
                        byte batteryLevel = characteristic.getValue()[0];
                        setBatteryLevel(batteryLevel);
                    }
                } else {
                    setConnectionState(DEVICE_STATE_ERROR);
                }
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                super.onDescriptorWrite(gatt, descriptor, status);

                if (status == BluetoothGatt.GATT_SUCCESS) {

                    if ( descriptor.getValue() == BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE ) {
                        setConnectionState(DEVICE_STATE_STREAMING);
//                        setStreamStatus(descriptorUpdateUUID, true );
                    }
                    else if ( descriptor.getValue() == BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE ) {
                        setConnectionState(DEVICE_STATE_CONNECTED);
//                        setStreamStatus(descriptorUpdateUUID, false );
                    }

                } else {
                    setConnectionState(bleService.DEVICE_STATE_ERROR);
                }

                mCharWriteSemaphore.release();
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                super.onCharacteristicChanged(gatt, characteristic);

                setNewData(characteristic.getValue(), characteristic.getUuid().toString());
            }
        }

        private void setCharacteristicNotification(BluetoothGattCharacteristic characteristic, boolean enabled) {
            if (mDeviceGatt == null) {
                return;
            }

            // Request write lock
            try {
                mCharWriteSemaphore.tryAcquire(1000, TimeUnit.MILLISECONDS);
            } catch ( InterruptedException e ) {
                Log.e(TAG, e.getMessage() );
                return;
            }

            BluetoothGattDescriptor descriptor = characteristic.getDescriptor( clientCharacterisitcConfig );

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
//            descriptorUpdateUUID = characteristic.getUuid().toString();
            mDeviceGatt.writeDescriptor(descriptor);
        }
    }
}