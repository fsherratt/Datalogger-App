package com.fsherratt.imudatalogger;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;

import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat;
import no.nordicsemi.android.support.v18.scanner.ScanCallback;
import no.nordicsemi.android.support.v18.scanner.ScanFilter;
import no.nordicsemi.android.support.v18.scanner.ScanResult;
import no.nordicsemi.android.support.v18.scanner.ScanSettings;


public class MainActivity extends AppCompatActivity {
    public final static String EXTRA_ADDRESS = "com.example.fsherratt.imudatalogger.EXTRA_ADDRESS";
    private static final String TAG = "MainActivity";
    private final BroadcastReceiver mGattUpdateReceiver;

    private Menu mMenu;
    private Button mRecord;
    private Handler mHandler;
    private Runnable mRssiRunable;
    private Runnable mBattRunable;
    private Runnable mSDOTimeRunable;
    private BleServiceHolder mBleServices;
    private logServiceHolder mLogService;
    private recyclerViewHolder mRecyclerHolder;
    private SwipeRefreshLayout mSwipeRefresh;
    private Button lastClicked = null;

    // Recording
    boolean recording = false;
    String mRecordingFileName = "Unknown";

    // BLE Devices
    private HashMap<String, BleDevices> mDevices = new HashMap<>();

    // BLE Scanning
    private boolean mScanning;

    private ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onBatchScanResults(final List<ScanResult> results) {
            BluetoothDevice device;

            ArrayList<String> selectList = new ArrayList<>();
            for (final ScanResult result : results) {
                device = result.getDevice();
                String name = device.getName();

                if (name == null)
                    continue;

                BleDevices deviceHolder;
                if (device.getName().toUpperCase().contains("MOVESENSE"))
                    deviceHolder = new MovesenseDevice(device);
                else if (device.getName().toUpperCase().contains("-V7REU-"))
                    deviceHolder = new ThumbREUDevice(device);

                else
                    continue;

                if (mDevices.containsKey(device.getAddress()))
                    continue;

                String friendly_name = getFriendlyName(device.getAddress());
                deviceHolder.setFriendlyName(friendly_name);

                mDevices.put(device.getAddress(), deviceHolder);
                mRecyclerHolder.addDevice(deviceHolder);

                selectList.add(device.getAddress());

                Log.d(TAG, "onBatchScanResults: Added device: " + device.getName() + " " + device.getAddress());
            }

            mBleServices.connect(selectList);
            mBleServices.requestBatteryLevel(selectList);
        }

        @Override
        public void onScanFailed(final int errorCode) {
            // empty
        }
    };
    private Runnable mScanTimeout = this::stopScanner;
    {
        mGattUpdateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();

                if (action == null)
                    return;

                if (action.equals(logService.ACTION_SAVE_FILE_NAME)) {
                    updateFileName(intent.getStringExtra(logService.EXTRA_FILE_NAME));
                } else {
                    updateBluetoothDevice(action, intent);
                }
            }
        };
    }

    // Lifecycle code
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        startReceiver();

        mRecord = findViewById(R.id.ble_record_button);

        mSwipeRefresh = findViewById(R.id.devices_swipe_refresh);
        mSwipeRefresh.setOnRefreshListener(this::startScanner);

        mSwipeRefresh.setRefreshing(false);

        mBleServices = new BleServiceHolder(this);
        mLogService = new logServiceHolder(this);

        initRecyclerView();

        mHandler = new Handler();

        mRssiRunable = new Runnable() {
            @Override
            public void run() {
                ArrayList<String> devices = new ArrayList<>(mDevices.keySet());
                mBleServices.requestRssi(devices);

                mHandler.postDelayed(this, 5000);
            }
        };

        mSDOTimeRunable = new Runnable() {
            @Override
            public void run() {
                for (BleDevices device : mDevices.values())
                    if (device.deviceType == DEVICE_TYPE_THUMBREU) {
                        if (device.connectionStatus >= bleService.DEVICE_STATE_CONNECTED) {
                            getRTC(device.address);
                            getSDCardLogState(device.address);
                            getBattery(device.address);
                            getSysStatus(device.address);
                        }
                    }

                mHandler.postDelayed(this, 5000);
            }
        };

        mBattRunable = new Runnable() {
            @Override
            public void run() {
                ArrayList<String> devices = new ArrayList<>(mDevices.keySet());
                mBleServices.requestBatteryLevel(devices);

                mHandler.postDelayed(this, 30000);
            }
        };

        startCallbacks();
    }

    private void removeCallbacks() {
        Log.d(TAG, "removeCallbacks: removing callbacks");
        mHandler.removeCallbacks(mRssiRunable);
        mHandler.removeCallbacks(mBattRunable);
        mHandler.removeCallbacks(mSDOTimeRunable);
    }

    private void startCallbacks() {
        Log.d(TAG, "startCallbacks: removing callbacks");
        mHandler.postDelayed(mRssiRunable, 300);
        mHandler.postDelayed(mBattRunable, 500);
        mHandler.postDelayed(mSDOTimeRunable, 100);
    }
    private void restartCallbacks() {
        removeCallbacks();
        startCallbacks();
    }

    @Override
    protected void onResume() {
        super.onResume();
        restartCallbacks();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopScanner();
        removeCallbacks();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopScanner();
        stopReceiver();

        mBleServices.stopService();
        mLogService.stopService();
        mHandler.removeCallbacks(mRssiRunable);
        mHandler.removeCallbacks(mBattRunable);
        mHandler.removeCallbacks(mSDOTimeRunable);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        mMenu = menu;

        toggleScanner();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.refresh_ble) {
            toggleScanner();
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v,
                                    ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        switch (permissions[0]) {
            case Manifest.permission.ACCESS_FINE_LOCATION:
                Log.d(TAG, "Location permission granted");
                startScanner();
                break;

            case Manifest.permission.WRITE_EXTERNAL_STORAGE:
                Log.d(TAG, "Writing permission granted");
                startRecording();
                break;

            default:
        }
    }

    // UI Elements
    public void record_button(View view) {
        toggleRecording(view);
    }

    public void item_select_button(String address) {
        Log.d(TAG, "Device: " + address + " button click");

        BleDevices device = mDevices.get(address);

        if (device == null)
            return;

        device.setSelected(!device.itemExpanded);
    }

    public void action_label_button(View view) {
        if (!recording) {
            return;
        }

        int id = view.getId();

        setButtonHighlight(id);

        String action;

        switch (id) {
            case R.id.button_1:
                action = getString(R.string.button_1_shorthand);
                break;
            case R.id.button_3:
                action = getString(R.string.button_3_shorthand);
                break;
            case R.id.button_4:
                action = getString(R.string.button_4_shorthand);
                break;
            case R.id.button_5:
                action = getString(R.string.button_5_shorthand);
                break;
            case R.id.button_6:
                action = getString(R.string.button_6_shorthand);
                break;
            case R.id.button_7:
                action = getString(R.string.button_7_shorthand);
                break;
            default:
                action = getString(R.string.button_unknown_shorthand);
        }

        mLogService.keyframe(action);
    }

    private void clearButtonHighlight() {
        if (lastClicked != null) {
            lastClicked.setTextColor(getColor(R.color.colorBlack));
            lastClicked.setBackgroundColor(getColor(R.color.colorLight));

            lastClicked = null;
        }
    }

    private void setButtonHighlight(int id) {
        clearButtonHighlight();

        Button clickedButton = findViewById(id);

        lastClicked = clickedButton;

        clickedButton.setTextColor(getColor(R.color.colorWhite));
        clickedButton.setBackgroundColor(getColor(R.color.colorAccent));
    }

    // Recycler View
    public void initRecyclerView() {
        Log.d(TAG, "initRecyclerView: init recycler view");

        RecyclerView recyclerView = findViewById(R.id.device_recycler_view);
        mRecyclerHolder = new recyclerViewHolder(this);

        recyclerView.setAdapter(mRecyclerHolder);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setItemAnimator(null);

        registerForContextMenu(findViewById(R.id.main_constrainedLayout));
    }

    // BLE Device updates
    private void startReceiver() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(bleService.ACTION_GATT_STATE_CHANGED);
        intentFilter.addAction(bleService.ACTION_DEVICE_RSSI);
        intentFilter.addAction(bleService.ACTION_BATTERY_LEVEL);
        intentFilter.addAction(logService.ACTION_DEVICE_FREQ);
        intentFilter.addAction(logService.ACTION_SAVE_FILE_NAME);
        intentFilter.addAction(bleService.ACTION_SDO_DATA_AVAILABLE);

        try {
            registerReceiver(mGattUpdateReceiver, intentFilter);
            Log.d(TAG, "startReceiver: ACTION_DATA_AVAILABLE receiver listening");
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Receiver already registered");
        }
    }

    private void stopReceiver() {
        try {
            unregisterReceiver(mGattUpdateReceiver);
            Log.d(TAG, "stopReceiver: ACTION_DATA_AVAILABLE receiver stopped");
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Receiver not registered");
        }
    }

    private void updateBluetoothDevice(String action, Intent intent) {

        final String address = intent.getStringExtra(EXTRA_ADDRESS);
        BleDevices device = mDevices.get(address);

        if (device == null)
            return;

        switch (action) {
            case bleService.ACTION_GATT_STATE_CHANGED: {
                final int state = intent.getIntExtra(bleService.EXTRA_STATE, -1);

                device.setConnectionStatus(state);
                break;
            }

            case bleService.ACTION_DEVICE_RSSI: {
                int rssi = intent.getIntExtra(bleService.EXTRA_RSSI, 0);

                device.setRssi(rssi);
                break;
            }

            case bleService.ACTION_BATTERY_LEVEL: {
                int batLvl = intent.getIntExtra(bleService.EXTRA_BATTERY, 0);

                device.setBatt(batLvl);
                Log.d(TAG, "mGattUpdateReceiver: Action_Battery_Level: " + batLvl);
                break;
            }

            case logService.ACTION_DEVICE_FREQ: {
                int freq = intent.getIntExtra(logService.EXTRA_FREQ, 0);
                int err = intent.getIntExtra(logService.EXTRA_ERROR, 0);

                if (err == logService.FREQ_ERROR_NO_UPDATE) {
                    BleDevices bleDevice = mDevices.get(address);
                    if (bleDevice == null) {
                        return;
                    }

                    String name = bleDevice.friendlyName;
                    if (name == null) {
                        name = bleDevice.name;
                    }

                    device.errorMessage = "Error: Streaming failed";
                    device.setConnectionStatus(bleService.DEVICE_STATE_ERROR);

                    View contextView = findViewById(android.R.id.content);
                    Snackbar snackbar = Snackbar.make(contextView, "Error with sensor " + name, Snackbar.LENGTH_INDEFINITE);
                    snackbar.setAction("Close", v -> snackbar.dismiss());
                    snackbar.show();
                }

                MovesenseDevice mvDevice = (MovesenseDevice) device;
                mvDevice.setFreq(freq);
                break;
            }

            case bleService.ACTION_SDO_DATA_AVAILABLE: {
                final byte[] byte_array = intent.getByteArrayExtra(bleService.EXTRA_DATA);

                ThumbREUDevice thumbDevice = (ThumbREUDevice)device;
                thumbDevice.decodeSDO(byte_array);
            }
        }
    }

    public void toggleScanner() {
        if (!mScanning) {
            startScanner();
        } else {
            stopScanner();
        }
    }

    public void startScanner() {
        if (mScanning)
            return;

        if (PackageManager.PERMISSION_DENIED == ContextCompat.checkSelfPermission(
                MainActivity.this,
                Manifest.permission.ACCESS_FINE_LOCATION)) {
            Log.d(TAG, "startScanner: Permission denied");
            ActivityCompat.requestPermissions(MainActivity.this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    100);
        }

        if (!checkBluetooth() || !checkLocation()) {
            return;
        }

        mScanning = true;
        mSwipeRefresh.setRefreshing(true);

        if (mMenu != null) {
            mMenu.getItem(0).setIcon(ContextCompat.getDrawable(this, R.drawable.ic_cancel_black_24dp));
        }

        BluetoothLeScannerCompat scanner = BluetoothLeScannerCompat.getScanner();
        ScanSettings settings = new ScanSettings.Builder()
                .setLegacy(false)
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(1)
                .setUseHardwareBatchingIfSupported(false)
                .build();
        List<ScanFilter> filters = new ArrayList<>();
        scanner.startScan(filters, settings, mScanCallback);

        Log.d(TAG, "stopScanner: Scan started");

        mHandler.postDelayed(mScanTimeout, 4000);
    }

    public void stopScanner() {
        if (!mScanning)
            return;

        final BluetoothLeScannerCompat scanner = BluetoothLeScannerCompat.getScanner();
        scanner.stopScan(mScanCallback);

        Log.d(TAG, "stopScanner: Scan stopped");
        mScanning = false;
        mSwipeRefresh.setRefreshing(false);

        if (mMenu != null) {
            mMenu.getItem(0).setIcon(ContextCompat.getDrawable(this, R.drawable.ic_refresh_black_24dp));
        }

        mHandler.removeCallbacks(mScanTimeout);

        if (mDevices.size() == 0) {
            Log.d(TAG, "No Devices Found");
        }
    }

    public boolean checkBluetooth() {
        int REQUEST_ENABLE_BLUETOOTH = 0;

        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            return false;
        }
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BLUETOOTH);
            return false;
        }

        return true;
    }

    public boolean checkLocation() {
        LocationManager locationManger = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
        if (!locationManger.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Log.d(TAG, "Location Disabled");
            final AlertDialog.Builder builder = new AlertDialog.Builder(this);

            builder.setMessage("Location Services are required to discover devices")
                    .setCancelable(false)
                    .setPositiveButton("Enable", (dialog, id) -> {
                        Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                        startActivity(intent);
                    })
                    .setNegativeButton("Ignore", (dialog, id) -> dialog.cancel());
            final AlertDialog alert = builder.create();
            alert.show();

            return false;
        }

        return true;
    }

    private boolean AnyDeviceConnected() {
        for (BleDevices device : mDevices.values()) {
            if (device.connectionStatus >= bleService.DEVICE_STATE_CONNECTED) {
                return true;
            }
        }
        return false;
    }

    public void startRecording() {
        if (!AnyDeviceConnected()) {
            View contextView = findViewById(android.R.id.content);
//            View contextView = Activity.getCurrentFocus();

            Snackbar snackbar = Snackbar.make(contextView, "No devices connected", Snackbar.LENGTH_SHORT);
            snackbar.setAction("Close", v -> snackbar.dismiss());
            snackbar.show();
            return;
        }

        if (PackageManager.PERMISSION_GRANTED != ContextCompat.checkSelfPermission(MainActivity.this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            Log.d(TAG, "startRecording: Permission denied");
            ActivityCompat.requestPermissions(MainActivity.this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    100);
            return;
        }

        recording = true;
        mRecord.setText(getString(R.string.record_stop));
        mRecord.setBackgroundColor(getColor(R.color.colorAccent));
        mRecord.setTextColor(getColor(R.color.colorWhite));

        mRecord.setCompoundDrawablesWithIntrinsicBounds(getDrawable(R.drawable.ic_stop_box), null, null, null);

        mLogService.startRecording();


        // Enable recording on devices
        ArrayList<String> movesenseDevices = new ArrayList<>();

        for (BleDevices device : mDevices.values()) {
            if (device.deviceType == DEVICE_TYPE_MOVESENSE ) {
                movesenseDevices.add(device.address);
            } else if (device.deviceType == DEVICE_TYPE_THUMBREU) {
                setSDCardLogState(device.address, SDCARD_ENABLE_LOG_COMMAND);
                getSDCardLogState(device.address);
            }
        }

        mBleServices.startRecording(movesenseDevices);
    }

    public void stopRecording() {
        // Disable recording on devices
        ArrayList<String> movesenseDevices = new ArrayList<>();

        for (BleDevices device : mDevices.values()) {
            if (device.deviceType == DEVICE_TYPE_MOVESENSE ) {
                movesenseDevices.add(device.address);
            } else if (device.deviceType == DEVICE_TYPE_THUMBREU) {
                setSDCardLogState(device.address, SDCARD_DISABLE_LOG_COMMAND);
                getSDCardLogState(device.address);
            }
        }

        mBleServices.stopRecording(movesenseDevices);
        mLogService.stopRecording();

        recording = false;

        clearButtonHighlight();
        mRecord.setText(getString(R.string.record_start));
        mRecord.setBackgroundColor(getColor(R.color.colorLight));
        mRecord.setTextColor(getColor(R.color.colorBlack));

        mRecord.setCompoundDrawablesWithIntrinsicBounds(getDrawable(R.drawable.circle), null, null, null);

        launchSaveActivity();
    }

    public void toggleRecording(View view) {
        if (recording) {
            stopRecording();
        } else {
            startRecording();
        }
    }

    public void updateFileName(String filename) {
        mRecordingFileName = filename;
    }

    public void launchSaveActivity() {
        Intent intent = new Intent(this, SaveActivity.class);
        intent.putExtra(SaveActivity.EXTRA_FILENAME, mRecordingFileName);
        startActivity(intent);
    }

    public void saveFriendlyName(String address, String newName) {
        BleDevices device = mDevices.get(address);
        if (device == null)
            return;

        SharedPreferences sharedPref = this.getSharedPreferences(
                getString(R.string.preference_file_key), Context.MODE_PRIVATE);

        SharedPreferences.Editor editor = sharedPref.edit();
        editor.putString(getString(R.string.friendly_name_prefix) + address, newName);
        editor.apply();

        device.setFriendlyName(newName);
    }

    public void clearFriendlyName(String address) {
        BleDevices device = mDevices.get(address);
        if (device == null)
            return;

        SharedPreferences sharedPref = this.getSharedPreferences(
                getString(R.string.preference_file_key), Context.MODE_PRIVATE);

        SharedPreferences.Editor editor = sharedPref.edit();
        editor.remove(getString(R.string.friendly_name_prefix) + address);
        editor.apply();

        device.setFriendlyName(null);
    }

    public String getFriendlyName(String address) {
        SharedPreferences sharedPref = this.getSharedPreferences(
                getString(R.string.preference_file_key), Context.MODE_PRIVATE);

        return sharedPref.getString(getString(R.string.friendly_name_prefix) + address, null);
    }


    // Log service
    static class logServiceHolder {
        private Context mContext;

        logServiceHolder(Context context) {
            mContext = context;

            Intent intent = new Intent(mContext, logService.class);
            mContext.startService(intent);
        }

        void stopService() {
            Intent intent = new Intent(mContext, logService.class);
            mContext.stopService(intent);
        }

        void keyframe(String descriptor) {
            Intent intent = new Intent(logService.ACTION_KEYFRAME_LABEL);
            intent.putExtra(logService.EXTRA_LABEL, descriptor);
            intent.putExtra(logService.EXTRA_TIMESTAMP, logService.makeTimestamp());

            mContext.sendBroadcast(intent);
        }

        void startRecording() {
            Intent intent = new Intent(logService.ACTION_RECORD_START);

            mContext.sendBroadcast(intent);
        }

        void stopRecording() {
            Intent intent = new Intent(logService.ACTION_RECORD_STOP);

            mContext.sendBroadcast(intent);
        }
    }


    // Device Data holder
    private final static int DEVICE_TYPE_GENERIC = 0;
    private final static int DEVICE_TYPE_MOVESENSE = 1;
    private final static int DEVICE_TYPE_THUMBREU = 2;

    class BleDevices {
        int deviceType;

        BluetoothDevice device;
        String address;
        String name;
        String friendlyName;

        int connectionStatus;

        Boolean itemExpanded;
        Boolean editing;
        Boolean enableEdit;

        String errorMessage;

        int rssi;
        int batt;

        BleDevices(BluetoothDevice device) {
            this.deviceType = DEVICE_TYPE_GENERIC;

            this.device = device;
            this.address = device.getAddress();

            lookupName(device.getName());
            this.friendlyName = null;

            this.connectionStatus = 0;

            this.itemExpanded = false;
            this.editing = false;
            this.enableEdit = false;

            this.rssi = 0;
            this.batt = 0;
        }

        private void lookupName(String device_Name) {
            this.name = device_Name;
        }

        void setFriendlyName(String friendlyName) {
            this.friendlyName = friendlyName;
            mRecyclerHolder.updateItem(address);
        }

        void setConnectionStatus(int connectionStatus) {
            this.connectionStatus = connectionStatus;

            if (connectionStatus == bleService.DEVICE_STATE_ERROR) {
                errorMessage = "Error: During Connection";
            } else if (connectionStatus == bleService.DEVICE_STATE_DISCONNECTED) {
                this.rssi = 0;
                this.batt = 0;
            }

            mRecyclerHolder.updateItem(address);
        }

        void setSelected(boolean selected) {
            this.itemExpanded = selected;
            mRecyclerHolder.updateItem(address);
        }

        void setEditing(Boolean newMode) {
            editing = newMode;

            if (newMode)
                enableEdit = true;

            mRecyclerHolder.updateItem(address);
        }

        void setRssi(int rssi) {
            this.rssi = rssi;
            mRecyclerHolder.updateItem(address);
        }

        void setBatt(int batt) { }
    }

    class MovesenseDevice extends BleDevices {

        int freq;

        MovesenseDevice(BluetoothDevice device) {
            super(device);

            this.deviceType = DEVICE_TYPE_MOVESENSE;
            this.freq = 0;

            setName(device.getName());
        }

        private void setName(String device_Name) {
            String dict_name = deviceNameDict.lookup(address);
            if (dict_name != null) {
                this.name = dict_name;
            } else {
                String[] temp = device_Name.split(" ", 2);
                List<String> list = new ArrayList<>(Arrays.asList(temp));

                this.name = list.get(1);
            }
        }

        void setFreq(int freq) {
            this.freq = freq;
            mRecyclerHolder.updateItem(address);
        }

        void setBatt(int batt) {
            this.batt = batt;
            mRecyclerHolder.updateItem(address);
        }

        void setConnectionStatus(int connectionStatus) {
            super.setConnectionStatus(connectionStatus);

            if (connectionStatus == bleService.DEVICE_STATE_CONNECTED && this.batt == 0) {
                ArrayList<String> deviceList = new  ArrayList<>(Arrays.asList(this.address));
                mBleServices.requestBatteryLevel(deviceList);
                mBleServices.requestRssi(deviceList);
            } else if (connectionStatus == bleService.DEVICE_STATE_DISCONNECTED) {
                this.freq = 0;
            }

            mRecyclerHolder.updateItem(address);
        }
    }

    class ThumbREUDevice extends BleDevices {
        private static final int SDO_OFFSET = 1;
        private static final int DATA_OFFSET = 4;

        private static final byte READ_RESPONSE = 0x42; // Read Response: 0x42,   Data: 0xKK-LL-MM-NN [Valid data from REU]
        private static final byte WRITE_RESPONSE = 0x60; // Write Response: 0x60,  Data: 0x00-00-00-00
        private static final byte ABORT_RESPONSE = (byte)0x80; // Abort Response: 0x80,  Data: 0xKK-LL-MM-NN [ Valid Abort code from REU]

        int mRTCDateVal;
        int mRTCTimeVal;
        String rtc_text;
        int mFirmware;

        int recording;
        int actualState;
        int sys_state;

        ThumbREUDevice(BluetoothDevice device) {
            super(device);

            this.deviceType = DEVICE_TYPE_THUMBREU;

            mFirmware = 0;
            sys_state = SYS_STATE_INITALIZE;

            mRTCDateVal = 0;
            mRTCTimeVal = 0;

            update_rtc_text();
        }


        // Convert incoming SDO to human readable
        public void decodeSDO( byte[] data) {
            Log.d(TAG, "setSDORead: byte array " + Arrays.toString(data));
            int incomingSDO = byteTypecast.bytesToInt16(data, SDO_OFFSET);
            if ( data[0] == READ_RESPONSE ) {
                switch (incomingSDO) {
                    case RTC_READ_DATE_SDO:
                        mRTCDateVal = byteTypecast.bytesToInt32(data, DATA_OFFSET);
                        update_rtc_text();
                        break;

                    case RTC_READ_TIME_SDO:
                        mRTCTimeVal = byteTypecast.bytesToInt32(data, DATA_OFFSET);
                        update_rtc_text();
                        break;

                    case SW_VERSION_SDO:
                        mFirmware = byteTypecast.bytesToInt32(data, DATA_OFFSET);
                        break;

                    case SDCARD_LOG_ENABLE_SDO:
                        recording = byteTypecast.bytesToInt32(data, DATA_OFFSET);
                        setConnectionStatus(actualState);
                        break;

                    case BAT_REMAINING_SDO:
                        batt = byteTypecast.bytesToInt32(data, DATA_OFFSET);
                        break;

                    case SYSTEM_STATE_SDO:
                        sys_state = byteTypecast.bytesToInt32(data, DATA_OFFSET);
                        break;
                }

                mRecyclerHolder.updateItem(address);
//            } else if ( data[0] == WRITE_RESPONSE ) {
//                Toast.makeText(getApplicationContext(), "Write Response: " + typesetting.bytesToHex(data), Toast.LENGTH_SHORT).show();
            } else if ( data[0] == ABORT_RESPONSE ) {
                String abort_response = byteTypecast.bytesToHex(data, DATA_OFFSET);
                String msg = "Abort Response: " + name + "\n";
                switch (incomingSDO) {
                    case SDCARD_LOG_ENABLE_SDO:
                        msg += "SDCARD_LOG_ENABLE";
                        break;

                    case RTC_SET_DATE_SDO:
                        msg += "RTC_SET_DATE";
                        break;

                    case RTC_SET_TIME_SDO:
                        msg += "RTC_SET_TIME";
                        break;
                }

                View contextView = findViewById(android.R.id.content);
                Snackbar snackbar = Snackbar.make(contextView, msg + " 0x" + abort_response, Snackbar.LENGTH_INDEFINITE);
                snackbar.setAction("Close", v -> snackbar.dismiss());
                snackbar.show();
            }
        }

        private void update_rtc_text() {
            int hour = mRTCTimeVal / 10000;
            int minute = (mRTCTimeVal % 10000) / 100;
            int second = mRTCTimeVal % 100;


            int year = (mRTCDateVal / 1000000) + 2000;
            int month = (mRTCDateVal % 1000000) / 10000;
            int day = (mRTCDateVal % 10000) / 100;

            rtc_text = String.format("%04d-%02d-%02d - %02d:%02d:%02d", year, month, day, hour, minute, second);
        }

        void setConnectionStatus(int connectionStatus) {
            super.setConnectionStatus(connectionStatus);

            actualState = connectionStatus;
            if (actualState == bleService.DEVICE_STATE_CONNECTED && recording == SDCARD_ENABLE_LOG_COMMAND ) {
                this.connectionStatus = bleService.DEVICE_STATE_STREAMING;
            }

            if (actualState == bleService.DEVICE_STATE_DISCONNECTED) {
                this.mRTCTimeVal = 0;
                this.mRTCDateVal = 0;
                this.mFirmware = 0;
                this.sys_state = -1;
                update_rtc_text();

            } else if (actualState == bleService.DEVICE_STATE_CONNECTED && mRTCDateVal == 0) {  // Action on first connection
                getFirmware(address);
                setRTC(address);
                getBattery(address);
                getSysStatus(address);
            }

            mRecyclerHolder.updateItem(address);
        }
    }


    // BLE Service Connection
    class BleServiceHolder {
        private final Context mContext;

        BleServiceHolder(Context context) {
            mContext = context;

            Intent intent = new Intent(mContext, bleService.class);
            mContext.startService(intent);
        }

        void stopService() {
            Intent intent = new Intent(mContext, bleService.class);
            mContext.stopService(intent);
        }

        void connect(ArrayList<String> bleDevices) {

            for (String deviceAddress : bleDevices) {
                BleDevices device = mDevices.get(deviceAddress);
                String device_type = null;

                if (device instanceof MovesenseDevice)
                    device_type = bleService.DEVICE_TYPE_MOVESENSE;
                else if (device instanceof ThumbREUDevice)
                    device_type = bleService.DEVICE_TYPE_THUMBREU;

                Intent intent = new Intent(bleService.ACTION_ADD_GATT_DEVICES);
                intent.putExtra(bleService.EXTRA_ADDRESS, deviceAddress);
                intent.putExtra(bleService.EXTRA_DEVICE_TYPE, device_type);

                mContext.sendBroadcast(intent);
            }

        }

        void disconnect(ArrayList<String> bleDevices) {
            Intent intent = new Intent(bleService.ACTION_CLOSE_GATT_DEVICES);
            intent.putStringArrayListExtra(bleService.EXTRA_ADDRESS_LIST, bleDevices);

            mContext.sendBroadcast(intent);
        }

        void startRecording(ArrayList<String> bleDevices) {
            Intent intent = new Intent(bleService.ACTION_START_STREAM_DEVICES);
            intent.putStringArrayListExtra(bleService.EXTRA_ADDRESS_LIST, bleDevices);
            mContext.sendBroadcast(intent);
        }

        void stopRecording(ArrayList<String> bleDevices) {
            Intent intent = new Intent(bleService.ACTION_STOP_STREAM_DEVICES);
            intent.putStringArrayListExtra(bleService.EXTRA_ADDRESS_LIST, bleDevices);

            mContext.sendBroadcast(intent);
        }

        void requestRssi(ArrayList<String> bleDevices) {
            Intent intent = new Intent(bleService.ACTION_REQUEST_RSSI);
            intent.putStringArrayListExtra(bleService.EXTRA_ADDRESS_LIST, bleDevices);
            sendBroadcast(intent);
        }

        void requestBatteryLevel(ArrayList<String> bleDevices) {
            Intent intent = new Intent(bleService.ACTION_REQUEST_BATTERY_LEVEL);
            intent.putStringArrayListExtra(bleService.EXTRA_ADDRESS_LIST, bleDevices);
            sendBroadcast(intent);
        }


        // SDO Queue actions
        void readSDOAction(String bleDevice) {
            Intent read_intent = new Intent(bleService.ACTION_SDO_READ);
            read_intent.putExtra(bleService.EXTRA_ADDRESS, bleDevice);
            sendBroadcast(read_intent);
        }

        void writeSDOAction(String bleDevice, byte[] byte_array) {
            Intent write_intent = new Intent(bleService.ACTION_SDO_WRITE);
            write_intent.putExtra(bleService.EXTRA_ADDRESS, bleDevice);
            write_intent.putExtra(bleService.EXTRA_SDO_BYTE_ARRAY, byte_array);
            sendBroadcast(write_intent);
        }

        void readSDO( String bleDevice, int SDO ) {

            byte SDO_LSB = (byte) (SDO & 0xFF);
            byte SDO_MSB = (byte) ((SDO >> 8) & 0xFF);

            byte[] writeRequest = {READ_COMMAND, SDO_LSB, SDO_MSB, 0x00, 0x00, 0x00, 0x00, 0x00};

            writeSDOAction(bleDevice, writeRequest);
            readSDOAction(bleDevice);
        }

        void writeByteSDO( String bleDevice, int SDO, byte val  ) {

            byte SDO_LSB = (byte) (SDO & 0xFF);
            byte SDO_MSB = (byte) ((SDO >> 8) & 0xFF);

            byte[] writeRequest = {WRITE_COMNAND, SDO_LSB, SDO_MSB, 0x00, val, 0x00, 0x00, 0x00};

            writeSDOAction(bleDevice, writeRequest);
            readSDOAction(bleDevice);
        }

        void writeFourByteArraySDO(String bleDevice, int SDO, byte[] val ) {

            byte SDO_LSB = (byte) (SDO & 0xFF);
            byte SDO_MSB = (byte) ((SDO >> 8) & 0xFF);

            byte[] writeRequest = {WRITE_COMNAND, SDO_LSB, SDO_MSB, 0x00, val[0], val[1], val[2], val[3]};

            writeSDOAction(bleDevice, writeRequest);
            readSDOAction(bleDevice);
        }

        private void writeFloatSDO( String bleDevice, int SDO, float value ) {
            byte SDO_LSB = (byte) (SDO & 0xFF);
            byte SDO_MSB = (byte) ((SDO >> 8) & 0xFF);

            byte[] dataBytes = byteTypecast.floatToBytes(value);

            byte[] writeRequest = {WRITE_COMNAND, SDO_LSB, SDO_MSB, 0x00, dataBytes[0], dataBytes[1], dataBytes[2], dataBytes[3]};

            writeSDOAction(bleDevice, writeRequest);
            readSDOAction(bleDevice);
        }
    }



    // Thumb REU SDO Commands
    private static final byte READ_COMMAND = 0x40; // Read Command: 0x40,  Data: 0x00-00-00-00
    private static final byte WRITE_COMNAND = 0x22; // Write Command: 0x22, Data: 0xKK-LL-MM-NN [Valid data from BLE App]

    private static final int SW_VERSION_SDO = 0x2003; // SW Version = 0x2003, 0x00, READ_ONLY, UNSIGNED_INT32

    private static final int SDCARD_LOG_ENABLE_SDO = 0x4431; // SD Car log enable
    private static final byte SDCARD_ENABLE_LOG_COMMAND = 0x01;
    private static final byte SDCARD_DISABLE_LOG_COMMAND = 0x00;

    private static final int RTC_READ_DATE_SDO = 0x4420; // RTC Read Date = 0x4420, 0x00, READ_ONLY, UNSIGNED_INT32
    private static final int RTC_READ_TIME_SDO = 0x4421; // RTC Read Time = 0x4421, 0x00, READ_ONLY, UNSIGNED_INT32
    private static final int RTC_WRITE_DATE_SDO = 0x4422; // RTC Write Date = 0x4422, 0x00, READ_WRITE, UNSIGNED_INT32
    private static final int RTC_WRITE_TIME_SDO = 0x4423; // RTC Write Time = 0x4423, 0x00, READ_WRITE, UNSIGNED_INT32
    private static final int RTC_SET_DATE_SDO = 0x4424; // RTC Set Date = 0x4424, 0x00, READ_WRITE, UNSIGNED_INT32
    private static final int RTC_SET_TIME_SDO = 0x4425; // RTC Set Time = 0x4425, 0x00, READ_WRITE, UNSIGNED_INT32

    private static final int BAT_REMAINING_SDO = 0x4406; // BAT Rem Capacity = 0x4406, 0x00, READ_ONLY, UNSIGNED_INT32

    private static final int SYSTEM_STATE_SDO = 0x2F00;
    private static final byte SYS_STATE_INITALIZE = 0x00;
    private static final byte SYS_STATE_PREOP = 0x7F;
    private static final byte SYS_STATE_OP = 0x05;
    private static final byte SYS_STATE_RECOVERABLE_FAIL = 0x04;
    private static final byte SYS_STATE_NON_RECOVER_FAIL = (byte)0x8F; /* unsigned doesn't exist in java... */


    // SDO Actions
    private void getRTC(String address) {
        Log.d(TAG, "getRTC: get RTC Date and Time");

        mBleServices.readSDO(address, RTC_READ_DATE_SDO); // Have to read date to update time
        mBleServices.readSDO(address, RTC_READ_TIME_SDO);
    }

    private void setRTC(String address) {
        Log.d(TAG, "setRTC: set RTC Time");

        Calendar cal = Calendar.getInstance();

        int dayOfWeek = cal.get( Calendar.DAY_OF_WEEK ) - 1;
        int day = cal.get( Calendar.DAY_OF_MONTH );
        int month = cal.get( Calendar.MONTH ) + 1;
        int year = cal.get( Calendar.YEAR ) - 2000; // In 80 years this will be a bug

        int hour = cal.get( Calendar.HOUR_OF_DAY );
        int minute = cal.get( Calendar.MINUTE );
        int second = cal.get( Calendar.SECOND );

        int dateNum = year * 1000000 + month * 10000 +  day * 100 + dayOfWeek; //YYMMDDdd
        int timeNum = hour * 10000 + minute * 100 + second; //HHmmss

        mBleServices.writeFourByteArraySDO(address, RTC_WRITE_DATE_SDO, byteTypecast.int32ToBytes(dateNum));
        mBleServices.writeFourByteArraySDO(address, RTC_SET_DATE_SDO, byteTypecast.int32ToBytes(1));
        mBleServices.writeFourByteArraySDO(address, RTC_SET_DATE_SDO, byteTypecast.int32ToBytes(0));
        mBleServices.readSDO(address, RTC_READ_DATE_SDO);

        mBleServices.writeFourByteArraySDO(address, RTC_WRITE_TIME_SDO, byteTypecast.int32ToBytes(timeNum) );
        mBleServices.writeFourByteArraySDO(address, RTC_SET_TIME_SDO, byteTypecast.int32ToBytes(1));
        mBleServices.readSDO(address, RTC_READ_TIME_SDO);
    }

    private void getFirmware(String address) {
        mBleServices.readSDO(address, SW_VERSION_SDO);
    }

    private void getSDCardLogState(String address) {
        mBleServices.readSDO(address, SDCARD_LOG_ENABLE_SDO);
    }

    private void setSDCardLogState(String address, byte state) {
        mBleServices.writeByteSDO(address, SDCARD_LOG_ENABLE_SDO, state);
    }

    private void getBattery(String address) {
        mBleServices.readSDO(address, BAT_REMAINING_SDO);
    }

    private void getSysStatus(String address) {
        mBleServices.readSDO(address, SYSTEM_STATE_SDO);
    }

    // Recycler View
    class recyclerViewHolder extends RecyclerView.Adapter<recyclerViewHolder.ViewHolder> {
        private Context mContext;

        private HashMap<String, Integer> mDeviceLocation = new HashMap<>();
        private ArrayList<BleDevices> mDevices = new ArrayList<>();

        recyclerViewHolder(Context mContext) {
            this.mContext = mContext;
        }

        void addDevice(BleDevices newDevice) {
            mDevices.add(newDevice);
            mDeviceLocation.put(newDevice.address, mDevices.indexOf(newDevice));
            notifyItemInserted(mDevices.indexOf(newDevice));
        }

        void updateItem(String address) {
            if (!mDeviceLocation.containsKey(address))
                return;

            //noinspection ConstantConditions
            notifyItemChanged(mDeviceLocation.get(address));
        }

        @Override
        public int getItemViewType(int position) {
            BleDevices generic_device = mDevices.get(position);
            return generic_device.deviceType;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            ViewHolder viewHolder = null;

            if (viewType == DEVICE_TYPE_MOVESENSE) {
                view = LayoutInflater.from(mContext).inflate(R.layout.ble_device_recycler_item, parent, false);
                viewHolder = new MovesenseViewHolder(view);
            } else if (viewType == DEVICE_TYPE_THUMBREU) {
                view = LayoutInflater.from(mContext).inflate(R.layout.ble_device_recycler_item, parent, false);
                viewHolder = new ThumbREUViewHolder(view);
            }

            return viewHolder;
        }

        private String systemStateToName(int state) {
            switch (state) {
                case SYS_STATE_INITALIZE:
                    return "Initialising";
                case SYS_STATE_PREOP:
                    return "Pre-operational";
                case SYS_STATE_OP:
                    return "Operational";
                case SYS_STATE_RECOVERABLE_FAIL:
                    return "Recoverable Fail";
                case SYS_STATE_NON_RECOVER_FAIL:
                    return "Non-recoverable Fail";
                default:
                    return "Unknown";
            }
        }

        private String connectionStateToName(int state) {
            switch (state) {
                case bleService.DEVICE_STATE_ERROR:
                    return "Error: During Connection";
                case bleService.DEVICE_STATE_DISCONNECTED:
                    return "Not Connected";
                case bleService.DEVICE_STATE_INITIALISING:
                    return "Initialising";
                case bleService.DEVICE_STATE_CONNECTING:
                    return "Connecting";
                case bleService.DEVICE_STATE_DISCOVERING:
                    return "Discovering Services";
                case bleService.DEVICE_STATE_CONNECTED:
                    return "Connected";
                case bleService.DEVICE_STATE_STREAMING:
                    return "Recording";
                default:
                    return "Error: Unknown State";
            }
        }

        private int connectionStateToColor(int state) {
            switch (state) {
                case bleService.DEVICE_STATE_INITIALISING:
                case bleService.DEVICE_STATE_CONNECTING:
                case bleService.DEVICE_STATE_DISCOVERING:
                    return ContextCompat.getColor(mContext, R.color.colorYellow);
                case bleService.DEVICE_STATE_CONNECTED:
                    return ContextCompat.getColor(mContext, R.color.colorPrimary);
                case bleService.DEVICE_STATE_STREAMING:
                    return ContextCompat.getColor(mContext, R.color.colorAccent);
                default:
                    return ContextCompat.getColor(mContext, R.color.colorLight);
            }
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder generic_viewHolder, int position) {
            BleDevices generic_device = mDevices.get(position);

            generic_viewHolder.address = generic_device.address;

            generic_viewHolder.connectionState.setText(connectionStateToName(generic_device.connectionStatus));
            generic_viewHolder.statusDot.setColorFilter(connectionStateToColor(generic_device.connectionStatus));

            switch (generic_device.connectionStatus) {
                case bleService.DEVICE_STATE_DISCONNECTED:
                    generic_viewHolder.connectButton.setText("Connect");
                    generic_viewHolder.connectButton.setEnabled(true);
                    generic_viewHolder.connectButton.setBackgroundColor(getColor(R.color.colorAccent));
                    generic_viewHolder.connectButton.setTextColor(getColor(R.color.colorWhite));

                    break;
                case bleService.DEVICE_STATE_CONNECTING:
                case bleService.DEVICE_STATE_INITIALISING:
                case bleService.DEVICE_STATE_DISCOVERING:
                    generic_viewHolder.connectButton.setText("Connect");
                    generic_viewHolder.connectButton.setEnabled(false);
                    generic_viewHolder.connectButton.setBackgroundColor(getColor(R.color.colorLight));
                    generic_viewHolder.connectButton.setTextColor(getColor(R.color.colorText));
                    break;

                case bleService.DEVICE_STATE_ERROR:
                    generic_viewHolder.connectButton.setText("Reconnect");
                    generic_viewHolder.connectButton.setEnabled(true);
                    generic_viewHolder.connectButton.setBackgroundColor(getColor(R.color.colorLight));
                    generic_viewHolder.connectButton.setTextColor(getColor(R.color.colorBlack));
                    break;

                default:
                    generic_viewHolder.connectButton.setText("Disconnect");
                    generic_viewHolder.connectButton.setEnabled(true);
                    generic_viewHolder.connectButton.setBackgroundColor(getColor(R.color.colorAccent));
                    generic_viewHolder.connectButton.setTextColor(getColor(R.color.colorWhite));
            }

            if (generic_device.itemExpanded) {
                generic_viewHolder.expandIcon.setImageDrawable(getDrawable(R.drawable.ic_expand_less_black_24dp));
                generic_viewHolder.mAdditionalDetails.setVisibility(View.VISIBLE);
            } else {
                generic_viewHolder.expandIcon.setImageDrawable(getDrawable(R.drawable.ic_expand_more_black_24dp));
                generic_viewHolder.mAdditionalDetails.setVisibility(View.GONE);
            }

            if (generic_device.connectionStatus == bleService.DEVICE_STATE_ERROR) {
                generic_viewHolder.connectionState.setText(generic_device.errorMessage);
                setErrorState(generic_viewHolder);
            } else {
                clearErrorState(generic_viewHolder);
            }

            if (generic_device.enableEdit) {
                generic_viewHolder.deviceNameEdit.setEnabled(true);
                generic_viewHolder.deviceNameEdit.requestFocus();
                generic_viewHolder.deviceNameEdit.setSelection(generic_viewHolder.deviceNameEdit.getText().length());

                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.showSoftInput(generic_viewHolder.deviceNameEdit, InputMethodManager.SHOW_IMPLICIT);

                generic_viewHolder.renameButton.setText("Save");

                generic_device.enableEdit = false;
            }

            if (!generic_device.editing) {
                generic_viewHolder.deviceNameEdit.setHint(generic_device.name);
                if (generic_device.friendlyName == null)
                    generic_viewHolder.deviceNameEdit.setText(generic_device.name);
                else
                    generic_viewHolder.deviceNameEdit.setText(generic_device.friendlyName);

                generic_viewHolder.deviceNameEdit.setEnabled(false);
                generic_viewHolder.renameButton.setText("Rename");
            }

            // Movesense Specific
            if (generic_device.deviceType == DEVICE_TYPE_MOVESENSE) {
                MovesenseDevice device = (MovesenseDevice)generic_device;
                MovesenseViewHolder viewHolder = (MovesenseViewHolder) generic_viewHolder;
                viewHolder.deviceAddress.setText(device.address);
                viewHolder.deviceID.setText(device.name);

                viewHolder.deviceRssi.setText(device.rssi + "dB");
                viewHolder.deviceFreq.setText(String.valueOf(device.freq));
                viewHolder.deviceBatt.setText(device.batt + "%");
            }

            if (generic_device.deviceType == DEVICE_TYPE_THUMBREU) {
                assert generic_device instanceof ThumbREUDevice;
                ThumbREUDevice device = (ThumbREUDevice)generic_device;

                assert generic_viewHolder instanceof ThumbREUViewHolder;
                ThumbREUViewHolder viewHolder = (ThumbREUViewHolder) generic_viewHolder;


                viewHolder.address.setText(device.address);
                viewHolder.fw_version.setText(String.valueOf(device.mFirmware));

                viewHolder.rssi.setText(device.rssi + "dB");
                viewHolder.battery.setText(device.batt + "%");
                viewHolder.rtc_time.setText(device.rtc_text);
                viewHolder.system_state.setText(systemStateToName(device.sys_state));
            }
        }

        private void setErrorState(ViewHolder generic_viewHolder) {
            generic_viewHolder.cardView.setCardBackgroundColor(getColor(R.color.colorError));
            generic_viewHolder.deviceNameEdit.setTextColor(getColor(R.color.colorWhite));
            generic_viewHolder.connectionState.setTextColor(getColor(R.color.colorWhite));

            generic_viewHolder.renameButton.setTextColor(getColor(R.color.colorBlack));
            generic_viewHolder.renameButton.setBackgroundColor(getColor(R.color.colorLight));

            if (generic_viewHolder instanceof MovesenseViewHolder) {
                MovesenseViewHolder viewHolder = (MovesenseViewHolder) generic_viewHolder;
                viewHolder.deviceRssi.setTextColor(getColor(R.color.colorWhite));
                viewHolder.deviceFreq.setTextColor(getColor(R.color.colorWhite));
                viewHolder.deviceBatt.setTextColor(getColor(R.color.colorWhite));

                viewHolder.rssiImage.setColorFilter(ContextCompat.getColor(mContext, R.color.colorWhite));
                viewHolder.battImage.setColorFilter(ContextCompat.getColor(mContext, R.color.colorWhite));
                viewHolder.samplesImage.setColorFilter(ContextCompat.getColor(mContext, R.color.colorWhite));

                viewHolder.deviceAddressLabel.setTextColor(getColor(R.color.colorWhite));
                viewHolder.deviceIdLabel.setTextColor(getColor(R.color.colorWhite));
                viewHolder.deviceAddress.setTextColor(getColor(R.color.colorWhite));
                viewHolder.deviceID.setTextColor(getColor(R.color.colorWhite));

            } else if (generic_viewHolder instanceof ThumbREUViewHolder) {
                ThumbREUViewHolder viewHolder = (ThumbREUViewHolder)generic_viewHolder;

                viewHolder.rssi.setTextColor(getColor(R.color.colorWhite));
                viewHolder.battery.setTextColor(getColor(R.color.colorWhite));
                viewHolder.rtc_time.setTextColor(getColor(R.color.colorWhite));

                viewHolder.rssiImage.setColorFilter(ContextCompat.getColor(mContext, R.color.colorWhite));
                viewHolder.battImage.setColorFilter(ContextCompat.getColor(mContext, R.color.colorWhite));
                viewHolder.rtcImage.setColorFilter(ContextCompat.getColor(mContext, R.color.colorWhite));

                viewHolder.adrress_heading.setTextColor(getColor(R.color.colorWhite));
                viewHolder.address.setTextColor(getColor(R.color.colorWhite));
                viewHolder.system_heading.setTextColor(getColor(R.color.colorWhite));
                viewHolder.system_state.setTextColor(getColor(R.color.colorWhite));
                viewHolder.fw_heading.setTextColor(getColor(R.color.colorWhite));
                viewHolder.fw_version.setTextColor(getColor(R.color.colorWhite));
            }
        }

        private void clearErrorState(ViewHolder generic_viewHolder) {
            generic_viewHolder.cardView.setCardBackgroundColor(getColor(R.color.colorWhite));
            generic_viewHolder.deviceNameEdit.setTextColor(getColor(R.color.colorPrimary));
            generic_viewHolder.connectionState.setTextColor(getColor(R.color.colorText));

            generic_viewHolder.renameButton.setTextColor(getColor(R.color.colorWhite));
            generic_viewHolder.renameButton.setBackgroundColor(getColor(R.color.colorAccent));

            if (generic_viewHolder instanceof MovesenseViewHolder) {
                MovesenseViewHolder viewHolder = (MovesenseViewHolder)generic_viewHolder;

                viewHolder.deviceRssi.setTextColor(getColor(R.color.colorText));
                viewHolder.deviceFreq.setTextColor(getColor(R.color.colorText));
                viewHolder.deviceBatt.setTextColor(getColor(R.color.colorText));

                viewHolder.rssiImage.setColorFilter(ContextCompat.getColor(mContext, R.color.colorBlack));
                viewHolder.battImage.setColorFilter(ContextCompat.getColor(mContext, R.color.colorBlack));
                viewHolder.samplesImage.setColorFilter(ContextCompat.getColor(mContext, R.color.colorBlack));

                viewHolder.deviceAddressLabel.setTextColor(getColor(R.color.colorText));
                viewHolder.deviceIdLabel.setTextColor(getColor(R.color.colorText));
                viewHolder.deviceAddress.setTextColor(getColor(R.color.colorText));
                viewHolder.deviceID.setTextColor(getColor(R.color.colorText));
            } else if (generic_viewHolder instanceof ThumbREUViewHolder) {
                ThumbREUViewHolder viewHolder = (ThumbREUViewHolder)generic_viewHolder;

                viewHolder.rssi.setTextColor(getColor(R.color.colorText));
                viewHolder.battery.setTextColor(getColor(R.color.colorText));
                viewHolder.rtc_time.setTextColor(getColor(R.color.colorText));

                viewHolder.rssiImage.setColorFilter(ContextCompat.getColor(mContext, R.color.colorBlack));
                viewHolder.battImage.setColorFilter(ContextCompat.getColor(mContext, R.color.colorBlack));
                viewHolder.rtcImage.setColorFilter(ContextCompat.getColor(mContext, R.color.colorBlack));

                viewHolder.adrress_heading.setTextColor(getColor(R.color.colorText));
                viewHolder.address.setTextColor(getColor(R.color.colorText));
                viewHolder.system_heading.setTextColor(getColor(R.color.colorText));
                viewHolder.system_state.setTextColor(getColor(R.color.colorText));
                viewHolder.fw_heading.setTextColor(getColor(R.color.colorText));
                viewHolder.fw_version.setTextColor(getColor(R.color.colorText));
            }
        }

        @Override
        public int getItemCount() {
            return mDevices.size();
        }

        private void onConnectClickCallback(String address) {
            if (!mDeviceLocation.containsKey(address))
                return;

            //noinspection ConstantConditions
            int pos = mDeviceLocation.get(address);


            BleDevices device = mDevices.get(pos);

            ArrayList<String> deviceList = new ArrayList<>();
            deviceList.add(address);

            if (device.connectionStatus != bleService.DEVICE_STATE_DISCONNECTED
                    && device.connectionStatus != bleService.DEVICE_STATE_ERROR) {
                mBleServices.disconnect(deviceList);
            } else {
                mBleServices.connect(deviceList);
            }
        }

        private void onButtonClickCallback(String address) {
            item_select_button(address);
            updateItem(address);
        }

        private void onNameClickCallback(String address, String newName) {
            if (!mDeviceLocation.containsKey(address))
                return;

            //noinspection ConstantConditions
            int pos = mDeviceLocation.get(address);

            BleDevices device = mDevices.get(pos);

            if (device.editing) {
                if (newName.equals(""))
                    clearFriendlyName(address);
                else
                    saveFriendlyName(address, newName);
            }

            device.setEditing(!device.editing);
        }


        // View holders
        class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {
            ViewStub additional_details_stub;
            String address;

            CardView cardView;
            ConstraintLayout mAdditionalDetails;

            ImageView statusDot;
            ImageView expandIcon;
            ImageView deviceIcon;

            EditText deviceNameEdit;

            TextView connectionState;

            Button connectButton;
            Button renameButton;

            ViewHolder(@NonNull View itemView) {
                super(itemView);

                additional_details_stub = (ViewStub)itemView.findViewById(R.id.addition_details_stub);

                mAdditionalDetails = itemView.findViewById(R.id.addition_details);

                cardView = itemView.findViewById(R.id.deviceCardView);
                cardView.setOnClickListener(this);

                deviceNameEdit = itemView.findViewById(R.id.ble_device_name_edit);
                deviceNameEdit.setBackground(null);
                deviceNameEdit.setOnEditorActionListener((textView, i, keyEvent) -> {
                    if (i == EditorInfo.IME_ACTION_DONE || keyEvent.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                        onNameClickCallback(address, textView.getText().toString());
                        return true;
                    }
                    return false;
                });

                connectionState = itemView.findViewById(R.id.connection_state_id);
                statusDot = itemView.findViewById(R.id.status_dot);
                expandIcon = itemView.findViewById(R.id.expand_icon);
                deviceIcon = itemView.findViewById(R.id.movesense_icon);

                connectButton = itemView.findViewById(R.id.connect_button);
                renameButton = itemView.findViewById(R.id.rename_button);

                renameButton.setOnClickListener(this::onClick);
                connectButton.setOnClickListener(this::onClick);
            }

            @Override
            public void onClick(View v) {
                if (v == cardView) {
                    onButtonClickCallback(address);
                    Log.d(TAG, "Cardview Pressed");
                } else if (v == renameButton) {
                    onNameClickCallback(address, deviceNameEdit.getText().toString());
                } else if (v == connectButton) {
                    onConnectClickCallback(address);
                }
            }
        }

        class MovesenseViewHolder extends ViewHolder {
            TextView deviceRssi;
            TextView deviceFreq;
            TextView deviceBatt;

            TextView deviceAddressLabel;
            TextView deviceIdLabel;
            TextView deviceAddress;
            TextView deviceID;

            ImageView rssiImage;
            ImageView battImage;
            ImageView samplesImage;

            MovesenseViewHolder(@NonNull View itemView) {
                super(itemView);

                deviceIcon.setImageDrawable(getDrawable(R.drawable.movesense_icon));

                additional_details_stub.setLayoutResource(R.layout.movesense_additional_details);
                View additional_details_inflated = additional_details_stub.inflate();

                deviceRssi = additional_details_inflated.findViewById(R.id.rssi_text_view);
                deviceFreq = additional_details_inflated.findViewById(R.id.freq_value);
                deviceBatt = additional_details_inflated.findViewById(R.id.rssi_value);

                rssiImage = additional_details_inflated.findViewById(R.id.rssi_image_view);
                battImage = additional_details_inflated.findViewById(R.id.rssi_image_view);
                samplesImage = additional_details_inflated.findViewById(R.id.time_image_view);

                deviceAddressLabel = additional_details_inflated.findViewById(R.id.address_Label);
                deviceIdLabel = additional_details_inflated.findViewById(R.id.id_label);
                deviceAddress = additional_details_inflated.findViewById(R.id.textView_physical);
                deviceID = additional_details_inflated.findViewById(R.id.textView_move_id);
            }
        }

        class ThumbREUViewHolder extends ViewHolder {
            TextView rssi;
            TextView battery;
            TextView rtc_time;

            ImageView rssiImage;
            ImageView battImage;
            ImageView rtcImage;

            TextView adrress_heading;
            TextView address;
            TextView system_heading;
            TextView system_state;
            TextView fw_heading;
            TextView fw_version;

            ThumbREUViewHolder(@NonNull View itemView) {
                super(itemView);

                deviceIcon.setImageDrawable(getDrawable(R.drawable.prosthetic_icon));

                additional_details_stub.setLayoutResource(R.layout.thumbreu_additional_details);
                View additional_details_inflated = additional_details_stub.inflate();

                address = additional_details_inflated.findViewById(R.id.phy_address_value);
                adrress_heading = additional_details_inflated.findViewById(R.id.address_heading);
                system_state = additional_details_inflated.findViewById(R.id.sys_state_value);
                system_heading = additional_details_inflated.findViewById(R.id.sys_state_heading);
                fw_version = additional_details_inflated.findViewById(R.id.fw_version_value);
                fw_heading = additional_details_inflated.findViewById(R.id.fw_version_heading);

                rtc_time = additional_details_inflated.findViewById(R.id.rtc_value);
                rssi = additional_details_inflated.findViewById(R.id.rssi_value);
                battery = additional_details_inflated.findViewById(R.id.battery_value);

                rtcImage = additional_details_inflated.findViewById(R.id.time_image_view);
                rssiImage = additional_details_inflated.findViewById(R.id.rssi_image_view);
                battImage = additional_details_inflated.findViewById(R.id.battery_image_view);

            }
        }
    }

}