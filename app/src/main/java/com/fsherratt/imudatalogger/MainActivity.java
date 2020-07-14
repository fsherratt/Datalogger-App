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

                if (!device.getName().toUpperCase().contains("MOVESENSE"))
                    continue;

                if (mDevices.containsKey(device.getAddress()))
                    continue;

                BleDevices deviceHolder = new BleDevices(device);

                String friendlyName = getFriendlyName(device.getAddress());
                if (friendlyName != null)
                    deviceHolder.setFriendlyName(friendlyName);

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
        mHandler.postDelayed(mRssiRunable, 5000);


        mBattRunable = new Runnable() {
            @Override
            public void run() {
                ArrayList<String> devices = new ArrayList<>(mDevices.keySet());
                mBleServices.requestBatteryLevel(devices);

                mHandler.postDelayed(this, 30000);
            }
        };
        mHandler.postDelayed(mBattRunable, 30000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.postDelayed(mRssiRunable, 5000);
        mHandler.postDelayed(mBattRunable, 30000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopScanner();
        mHandler.removeCallbacks(mRssiRunable);
        mHandler.removeCallbacks(mBattRunable);
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
            case bleService.ACTION_GATT_STATE_CHANGED:
                final int state = intent.getIntExtra(bleService.EXTRA_STATE, -1);

                device.setConnectionStatus(state);
                break;

            case bleService.ACTION_DEVICE_RSSI:
                int rssi = intent.getIntExtra(bleService.EXTRA_RSSI, 0);

                device.setRssi(rssi);
                break;

            case bleService.ACTION_BATTERY_LEVEL:
                int batLvl = intent.getIntExtra(bleService.EXTRA_BATTERY, 0);

                device.setBatt(batLvl);
                Log.d(TAG, "mGattUpdateReceiver: Action_Battery_Level: " + batLvl);
                break;

            case logService.ACTION_DEVICE_FREQ:
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

                device.setFreq(freq);
                break;
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

        BluetoothLeScannerCompat scanner = BluetoothLeScannerCompat.getScanner();
        ScanSettings settings = new ScanSettings.Builder()
                .setLegacy(false)
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(1000)
                .setUseHardwareBatchingIfSupported(false)
                .build();
        List<ScanFilter> filters = new ArrayList<>();
        scanner.startScan(filters, settings, mScanCallback);

        Log.d(TAG, "stopScanner: Scan started");

        mScanning = true;
        mSwipeRefresh.setRefreshing(true);

        if (mMenu != null) {
            mMenu.getItem(0).setIcon(ContextCompat.getDrawable(this, R.drawable.ic_cancel_black_24dp));
        }

        mHandler.postDelayed(mScanTimeout, 2500);
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

        if (PackageManager.PERMISSION_DENIED == ContextCompat.checkSelfPermission(MainActivity.this,
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
        mBleServices.startRecording(new ArrayList<>(mDevices.keySet()));
    }

    public void stopRecording() {
        mLogService.stopRecording();
        mBleServices.stopRecording(new ArrayList<>(mDevices.keySet()));

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

    class BleDevices {
        BluetoothDevice device;
        String address;
        String name;
        String friendlyName;

        int connectionStatus;
        int rssi;
        int batt;
        int freq;

        Boolean itemExpanded;
        Boolean editing;
        Boolean enableEdit;

        String errorMessage;

        BleDevices(BluetoothDevice device) {
            setup(device);
        }

        private void setup(BluetoothDevice device) {
            this.device = device;
            this.address = device.getAddress();

            String[] temp = device.getName().split(" ", 2);
            List<String> list = new ArrayList<>(Arrays.asList(temp));

            this.name = list.get(1);
            this.friendlyName = null;

            this.connectionStatus = 0;
            this.rssi = 0;
            this.batt = 0;

            this.itemExpanded = false;
            this.editing = false;
            this.enableEdit = false;
        }

        void setConnectionStatus(int connectionStatus) {
            this.connectionStatus = connectionStatus;

            if (connectionStatus == bleService.DEVICE_STATE_DISCONNECTED) {
                this.rssi = 0;
                this.batt = 0;
                this.freq = 0;
            } else if (connectionStatus == bleService.DEVICE_STATE_ERROR) {
                errorMessage = "Error: During Connection";
            }

            mRecyclerHolder.updateItem(address);
        }

        void setRssi(int rssi) {
            this.rssi = rssi;
            mRecyclerHolder.updateItem(address);
        }

        void setBatt(int batt) {
            this.batt = batt;
            mRecyclerHolder.updateItem(address);
        }

        void setSelected(boolean selected) {
            this.itemExpanded = selected;
            mRecyclerHolder.updateItem(address);
        }

        void setFreq(int freq) {
            this.freq = freq;
            mRecyclerHolder.updateItem(address);
        }

        void setFriendlyName(String friendlyName) {
            this.friendlyName = friendlyName;
            mRecyclerHolder.updateItem(address);
        }

        void setEditing(Boolean newMode) {
            editing = newMode;

            if (newMode)
                enableEdit = true;

            mRecyclerHolder.updateItem(address);
        }
    }

    class BleServiceHolder {
        private Context mContext;

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
            Intent intent = new Intent(bleService.ACTION_ADD_GATT_DEVICES);
            intent.putStringArrayListExtra(bleService.EXTRA_ADDRESS_LIST, bleDevices);

            mContext.sendBroadcast(intent);
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
    }

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

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(mContext).inflate(R.layout.movesense_device_recycler_item, parent, false);
            return new ViewHolder(view);
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
        public void onBindViewHolder(@NonNull ViewHolder viewHolder, int position) {
            BleDevices device = mDevices.get(position);
            viewHolder.address = device.address;

            viewHolder.connectionState.setText(connectionStateToName(device.connectionStatus));
            viewHolder.statusDot.setColorFilter(connectionStateToColor(device.connectionStatus));

            viewHolder.deviceAddress.setText(device.address);
            viewHolder.deviceID.setText(device.name);

            viewHolder.deviceRssi.setText(device.rssi + "dB");
            viewHolder.deviceFreq.setText(String.valueOf(device.freq));
            viewHolder.deviceBatt.setText(device.batt + "%");

            switch (device.connectionStatus) {
                case bleService.DEVICE_STATE_DISCONNECTED:
                    viewHolder.connectButton.setText("Connect");
                    viewHolder.connectButton.setEnabled(true);
                    viewHolder.connectButton.setBackgroundColor(getColor(R.color.colorAccent));
                    viewHolder.connectButton.setTextColor(getColor(R.color.colorWhite));

                    break;
                case bleService.DEVICE_STATE_CONNECTING:
                case bleService.DEVICE_STATE_INITIALISING:
                case bleService.DEVICE_STATE_DISCOVERING:
                    viewHolder.connectButton.setText("Connect");
                    viewHolder.connectButton.setEnabled(false);
                    viewHolder.connectButton.setBackgroundColor(getColor(R.color.colorLight));
                    viewHolder.connectButton.setTextColor(getColor(R.color.colorText));
                    break;

                case bleService.DEVICE_STATE_ERROR:
                    viewHolder.connectButton.setText("Reconnect");
                    viewHolder.connectButton.setEnabled(true);
                    viewHolder.connectButton.setBackgroundColor(getColor(R.color.colorLight));
                    viewHolder.connectButton.setTextColor(getColor(R.color.colorBlack));
                    break;

                default:
                    viewHolder.connectButton.setText("Disconnect");
                    viewHolder.connectButton.setEnabled(true);
                    viewHolder.connectButton.setBackgroundColor(getColor(R.color.colorAccent));
                    viewHolder.connectButton.setTextColor(getColor(R.color.colorWhite));
            }

            if (device.itemExpanded) {
                viewHolder.expandIcon.setImageDrawable(getDrawable(R.drawable.ic_expand_less_black_24dp));
                viewHolder.mAdditionalDetails.setVisibility(View.VISIBLE);
            } else {
                viewHolder.expandIcon.setImageDrawable(getDrawable(R.drawable.ic_expand_more_black_24dp));
                viewHolder.mAdditionalDetails.setVisibility(View.GONE);
            }

            if (device.connectionStatus == bleService.DEVICE_STATE_ERROR) {
                viewHolder.connectionState.setText(device.errorMessage);
                setErrorState(viewHolder);
            } else {
                clearErrorState(viewHolder);
            }

            if (device.enableEdit) {
                viewHolder.deviceNameEdit.setEnabled(true);
                viewHolder.deviceNameEdit.requestFocus();
                viewHolder.deviceNameEdit.setSelection(viewHolder.deviceNameEdit.getText().length());

                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.showSoftInput(viewHolder.deviceNameEdit, InputMethodManager.SHOW_IMPLICIT);

                viewHolder.renameButton.setText("Save");

                device.enableEdit = false;
            }

            if (!device.editing) {
                viewHolder.deviceNameEdit.setHint(device.name);
                if (device.friendlyName == null)
                    viewHolder.deviceNameEdit.setText(device.name);
                else
                    viewHolder.deviceNameEdit.setText(device.friendlyName);

                viewHolder.deviceNameEdit.setEnabled(false);
                viewHolder.renameButton.setText("Rename");
            }
        }

        private void setErrorState(ViewHolder viewHolder) {
            viewHolder.cardView.setCardBackgroundColor(getColor(R.color.colorError));
            viewHolder.deviceNameEdit.setTextColor(getColor(R.color.colorWhite));
            viewHolder.connectionState.setTextColor(getColor(R.color.colorWhite));

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

            viewHolder.renameButton.setTextColor(getColor(R.color.colorBlack));
            viewHolder.renameButton.setBackgroundColor(getColor(R.color.colorLight));
        }

        private void clearErrorState(ViewHolder viewHolder) {
            viewHolder.cardView.setCardBackgroundColor(getColor(R.color.colorWhite));
            viewHolder.deviceNameEdit.setTextColor(getColor(R.color.colorPrimary));
            viewHolder.connectionState.setTextColor(getColor(R.color.colorText));

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

            viewHolder.renameButton.setTextColor(getColor(R.color.colorWhite));
            viewHolder.renameButton.setBackgroundColor(getColor(R.color.colorAccent));
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

        class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {
            String address;

            CardView cardView;
            ConstraintLayout mAdditionalDetails;

            ImageView statusDot;
            ImageView expandIcon;

            ImageView rssiImage;
            ImageView battImage;
            ImageView samplesImage;

            EditText deviceNameEdit;

            TextView connectionState;
            TextView deviceRssi;
            TextView deviceFreq;
            TextView deviceBatt;

            TextView deviceAddressLabel;
            TextView deviceIdLabel;
            TextView deviceAddress;
            TextView deviceID;

            Button connectButton;
            Button renameButton;

            ViewHolder(@NonNull View itemView) {
                super(itemView);

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

                deviceRssi = itemView.findViewById(R.id.rssi_text_view);
                deviceFreq = itemView.findViewById(R.id.samples_text_view);
                deviceBatt = itemView.findViewById(R.id.battery_text_view);

                rssiImage = itemView.findViewById(R.id.rssi_image_view);
                battImage = itemView.findViewById(R.id.batt_image_view);
                samplesImage = itemView.findViewById(R.id.samples_image_view);

                deviceAddressLabel = itemView.findViewById(R.id.address_Label);
                deviceIdLabel = itemView.findViewById(R.id.id_label);
                deviceAddress = itemView.findViewById(R.id.textView_physical);
                deviceID = itemView.findViewById(R.id.textView_move_id);

                connectButton = itemView.findViewById(R.id.connect_button);
                renameButton = itemView.findViewById(R.id.rename_button);

                renameButton.setOnClickListener(this);
                connectButton.setOnClickListener(this);
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
    }
}