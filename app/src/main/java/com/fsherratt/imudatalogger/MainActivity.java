package com.fsherratt.imudatalogger;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.ActionMode;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.snackbar.BaseTransientBottomBar;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat;
import no.nordicsemi.android.support.v18.scanner.ScanSettings;
import no.nordicsemi.android.support.v18.scanner.ScanFilter;
import no.nordicsemi.android.support.v18.scanner.ScanCallback;
import no.nordicsemi.android.support.v18.scanner.ScanResult;


public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    public final static String EXTRA_ADDRESS = "com.example.fsherratt.imudatalogger.EXTRA_ADDRESS";

    private Menu mMenu;
    private Button mRecord;
    private ProgressBar mProgressBar;

    private Handler mHandler;
    private Runnable mRssiRunable;

    private BleServiceHolder mBleServices;
    private logServiceHolder mLogService;
    private recyclerViewHolder mRecyclerHolder;

    // Lifecycle code
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        startReceiver();

        mRecord = findViewById(R.id.ble_record_button);
        mProgressBar = findViewById(R.id.ble_refresh_progress_bar);

        mProgressBar.setIndeterminate(false);

        mBleServices = new BleServiceHolder(this);
        mLogService = new logServiceHolder(this);

        initRecyclerView();

        mHandler = new Handler();

        mRssiRunable = new Runnable() {
            @Override
            public void run() {
                ArrayList<String> devices = new ArrayList<>(mDevices.keySet());
                mBleServices.requestRssi(devices);
//                mBleServices.requestBatteryLevel(devices);

                mHandler.postDelayed(this, 5000);
            }
        };
        mHandler.postDelayed(mRssiRunable, 5000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.postDelayed(mRssiRunable, 5000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopScanner();
        mHandler.removeCallbacks(mRssiRunable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopScanner();
        stopReceiver();

        mBleServices.stopService();
        mLogService.stopService();
        mHandler.removeCallbacks(mRssiRunable);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        mMenu = menu;

        mMenu.getItem(0).setIconTintList(ContextCompat.getColorStateList(this, R.color.colorWhite));

        toggleScanner();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.refresh_ble:
                toggleScanner();
                break;
            case R.id.ble_disconnect:
                disconnectAllDevices();
                stopRecording();
                break;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v,
                                    ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.selected_context_menu, menu);
    }

    // UI Elements
    public void record_button(View view) {
        toggleRecording();
    }

    public void item_select_button(String address) {
        Log.d(TAG, "Device: " + address + " button click");

        BleDevices device = mDevices.get(address);

        if (device == null)
            return;

        device.setSelected( !device.selected );
        updateActionBar();
    }

    public void action_label_button(View view) {
        int id = view.getId();

        String action;

        switch (id) {
            case R.id.button_1:
                action = getString(R.string.button_1_shorthand);
                break;
//            case R.id.button_2:
//                action = getString(R.string.button_2_shorthand);
//                break;
//            case R.id.button_3:
//                action = getString(R.string.button_3_shorthand);
//                break;
//            case R.id.button_4:
//                action = getString(R.string.button_4_shorthand);
//                break;
            case R.id.button_5:
                action = getString(R.string.button_5_shorthand);
                break;
            case R.id.button_6:
                action = getString(R.string.button_6_shorthand);
                break;
            case R.id.button_7:
                action = getString(R.string.button_7_shorthand);
                break;
//            case R.id.button_8:
//                action = getString(R.string.button_8_shorthand);
//                break;
            default:
                action = getString(R.string.button_unknown_shorthand);
        }

        mLogService.keyframe(action);
    }


    // Recycler View
    public void initRecyclerView() {
        Log.d(TAG, "initRecyclerView: init recycler view");

        RecyclerView recyclerView = findViewById(R.id.device_recycler_view);
        mRecyclerHolder = new recyclerViewHolder(this);

        recyclerView.setAdapter(mRecyclerHolder);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAnimation(null);

        registerForContextMenu(findViewById(R.id.main_linearLayout));
    }

    // BLE Devices
    private HashMap<String, BleDevices> mDevices = new HashMap<>();

    public void disconnectAllDevices() {
        if (mBleServices == null) {
            Log.d(TAG, "disconnectAllDevices: No devices connected");
            return;
        }

        mBleServices.disconnectAll();
    }

    // Selected items function
    public int countSelected() {
        int selectCount = 0;
        for ( BleDevices device : mDevices.values()) {

            if (device.selected )
                selectCount++;
        }

        return selectCount;
    }

    public void connectSelected(Boolean connect) {
        ArrayList<String> selectList = new ArrayList<>();
        for ( BleDevices device : mDevices.values()) {
            if (device.selected )
                selectList.add(device.address);
        }

        if (selectList.size() == 0 )
            return;

        if (connect)
            mBleServices.connect(selectList);
        else
            mBleServices.disconnect(selectList);
    }

    public void clearSelected() {
        for ( BleDevices device : mDevices.values()) {
            device.setSelected(false);
        }
    }


    // BLE Device updates
    private void startReceiver() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(bleService.ACTION_GATT_STATE_CHANGED);
        intentFilter.addAction(bleService.ACTION_DEVICE_RSSI);
        intentFilter.addAction(bleService.ACTION_BATTERY_LEVEL);
        intentFilter.addAction(logService.ACTION_DEVICE_FREQ);

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

    private final BroadcastReceiver mGattUpdateReceiver; {
        mGattUpdateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();
                final String address = intent.getStringExtra(EXTRA_ADDRESS);

                BleDevices device = mDevices.get(address);

                if (action == null || device == null)
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
                            View contextView = findViewById(R.id.main_linearLayout);
                            Snackbar.make(contextView, "Device: " + address + " error", Snackbar.LENGTH_LONG).show();
                        }

                        device.setFreq(freq);
                        break;
                }
            }
        };
    }


    // BLE Scanning
    private boolean mScanning;
    private Runnable mScanTimeout = this::stopScanner;

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

        if (mMenu != null) {
            mMenu.getItem(1).setIcon(ContextCompat.getDrawable(this, R.drawable.ic_cancel_black_24dp));
            mMenu.getItem(1).setIconTintList(ContextCompat.getColorStateList(this, R.color.colorWhite));
        }

        mProgressBar.setIndeterminate(true);

        mHandler.postDelayed(mScanTimeout, 10000);
    }

    public void stopScanner() {
        if (!mScanning)
            return;

        final BluetoothLeScannerCompat scanner = BluetoothLeScannerCompat.getScanner();
        scanner.stopScan(mScanCallback);

        Log.d(TAG, "stopScanner: Scan stopped");
        mScanning = false;

        if (mMenu != null) {
            mMenu.getItem(1).setIcon(ContextCompat.getDrawable(this, R.drawable.ic_refresh_black_24dp));
            mMenu.getItem(1).setIconTintList(ContextCompat.getColorStateList(this, R.color.colorWhite));
        }

        mProgressBar.setIndeterminate(false);

        mHandler.removeCallbacks(mScanTimeout);
    }

    private ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onBatchScanResults(final List<ScanResult> results) {
            BluetoothDevice device;

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
                if ( friendlyName != null)
                    deviceHolder.setFriendlyName(friendlyName);

                mDevices.put(device.getAddress(), deviceHolder);
                mRecyclerHolder.addDevice(deviceHolder);

                Log.d(TAG, "onBatchScanResults: Added device: " + device.getName() + " " + device.getAddress());
            }
        }

        @Override
        public void onScanFailed(final int errorCode) {
            // empty
        }
    };


    // Recording
    boolean recording = false;

    public void startRecording() {
        if (mBleServices == null) {
            Toast.makeText(this, "No devices connected", Toast.LENGTH_SHORT).show();
            return;
        }
        mLogService.startRecording();
        mBleServices.startRecording(new ArrayList<>(mDevices.keySet()));
    }

    public void stopRecording() {
        if (mBleServices == null) {
            Toast.makeText(this, "No devices connected", Toast.LENGTH_SHORT).show();
            return;
        }

        mLogService.stopRecording();
        mBleServices.stopRecording(new ArrayList<>(mDevices.keySet()));
    }

    public void toggleRecording() {
        if (recording) {
            recording = false;
            stopRecording();
            mRecord.setText(getString(R.string.record_start));
        } else {
            recording = true;
            startRecording();
            mRecord.setText(getString(R.string.record_stop));
        }
    }


    // Friendly names
    public void editFriendlyName(String address) {
        BleDevices device = mDevices.get(address);
        if (device == null)
            return;

        AlertDialog.Builder alert = new AlertDialog.Builder(this);

        final EditText edittext = new EditText(this);
        edittext.setSingleLine();

        if (device.friendlyName != null)
            edittext.setText(device.friendlyName);
        else
            edittext.setText(getString(R.string.default_friendly_name));

        alert.setMessage("For device " + device.name);
        alert.setTitle("Set Friendly Name");

        //noinspection deprecation
        alert.setView(edittext, 50, 0, 50, 0);

        alert.setPositiveButton("OK", (dialog, whichButton) -> saveFriendlyName(address, edittext.getText().toString()));

        alert.setNegativeButton("Reset", (dialog, whichButton) -> clearFriendlyName(address) );

        alert.setCancelable(true);
        alert.show();
    }

    public void saveFriendlyName(String address, String newName) {
        BleDevices device = mDevices.get(address);
        if (device == null )
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
        if (device == null )
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

        return sharedPref.getString(getString(R.string.friendly_name_prefix)+address, null);
    }


    // Helper classes
    class BleDevices {
        BluetoothDevice device;
        String address;
        String name;
        String friendlyName;
        int connectionStatus;
        int rssi;
        int batt;
        int freq;

        boolean selected;

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

            this.selected = false;
        }

        void setConnectionStatus(int connectionStatus) {
            this.connectionStatus = connectionStatus;

            if (connectionStatus == bleService.DEVICE_STATE_DISCONNECTED) {
                this.rssi = 0;
                this.batt = 0;
                this.freq = 0;
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

        void setSelected( boolean selected ) {
            this.selected = selected;
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

        void disconnectAll() {
            Intent intent = new Intent(bleService.ACTION_CLOSE_GATT_ALL);
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

    class logServiceHolder {
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

        @Override
        public void onBindViewHolder(@NonNull ViewHolder viewHolder, int position) {
            BleDevices device = mDevices.get(position);
            viewHolder.address = device.address;

            String rssi = String.valueOf(device.rssi);
            String freq = String.valueOf(device.freq);

            viewHolder.deviceAddress.setText(device.address);

            if (device.friendlyName == null)
                viewHolder.deviceName.setText(device.name);
            else
                viewHolder.deviceName.setText(device.friendlyName);

            viewHolder.deviceStatus.setText(String.valueOf(device.connectionStatus));
            viewHolder.deviceRssi.setText(rssi);
            viewHolder.deviceFreq.setText(freq);

            if ( device.selected ) {
                viewHolder.deviceAddButton.setBackground(ContextCompat.getDrawable(mContext, R.drawable.ic_add_circle_black_24dp));
            }
            else {
                viewHolder.deviceAddButton.setBackground(ContextCompat.getDrawable(mContext, R.drawable.ic_add_circle_outline_black_24dp));
            }
        }

        @Override
        public int getItemCount() {
            return mDevices.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener, View.OnLongClickListener {
            String address;

            TextView deviceName;
            TextView deviceAddress;
            TextView deviceStatus;
            TextView deviceRssi;
            TextView deviceFreq;

            ImageView deviceRssiImage;
            ImageView deviceFreqImage;

            Button deviceAddButton;

            ViewHolder(@NonNull View itemView)  {
                super(itemView);

                deviceName = itemView.findViewById(R.id.ble_device_name);
                deviceAddress = itemView.findViewById(R.id.ble_device_uuid);
                deviceStatus = itemView.findViewById(R.id.status_id);
                deviceRssi = itemView.findViewById(R.id.rssi_text_view);
                deviceFreq = itemView.findViewById(R.id.battery_text_view);

                deviceFreqImage = itemView.findViewById(R.id.batt_image_view);
                deviceRssiImage = itemView.findViewById(R.id.rssi_image_view);

                deviceAddButton = itemView.findViewById(R.id.add_device_button);

                deviceAddButton.setOnClickListener(this);
                deviceName.setOnLongClickListener(this);
            }

            @Override
            public void onClick(View v) {
                if (v == deviceAddButton) {
                    onButtonClickCallback(address);
                }
            }

            @Override
            public boolean onLongClick(View v) {
                if (v == deviceName) {
                    onNameClickCallback(address);
                    return true;
                }
                return false;
            }
        }

        private void onButtonClickCallback(String address) {
            item_select_button(address);
        }

        private void onNameClickCallback(String address) { editFriendlyName(address); }
    }


    // Action Mode Bar
    ActionMode actionMode;

    private void updateActionBar() {
        int selectedItems = countSelected();

        if (selectedItems == 0) {
            stopActionMode();
            return;
        }

        startActionMode();
        actionMode.setSubtitle(selectedItems + " Items Selected");
    }

    private void startActionMode() {
        if ( actionMode == null ) {
            actionMode = startActionMode(actionModeCallback);

            assert actionMode != null;
            actionMode.setTitle("Connect/Disconnect");
        }
    }

    private void stopActionMode() {
        if ( actionMode != null )
            actionMode.finish();
    }


    private ActionMode.Callback actionModeCallback = new ActionMode.Callback() {

        // Called when the action mode is created; startActionMode() was called
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            // Inflate a menu resource providing context menu items
            MenuInflater inflater = mode.getMenuInflater();
            inflater.inflate(R.menu.selected_context_menu, menu);
            return true;
        }

        // Called each time the action mode is shown. Always called after onCreateActionMode, but
        // may be called multiple times if the mode is invalidated.
        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false; // Return false if nothing is done
        }

        // Called when the user selects a contextual menu item
        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            switch (item.getItemId()) {
                case R.id.connect:
                    connectSelected(true);
                    mode.finish(); // Action picked, so close the CAB
                    return true;

                case R.id.disconnect:
                    connectSelected(false);
                    mode.finish();
                    return true;

                default:
                    mode.finish();
                    return false;
            }
        }

        // Called when the user exits the action mode
        @Override
        public void onDestroyActionMode(ActionMode mode) {
            // clear all selected items
            clearSelected();
            actionMode = null;
        }
    };
}