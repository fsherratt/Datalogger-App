package com.fsherratt.imudatalogger;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.util.ArrayMap;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class logService extends Service {
    public final static String ACTION_KEYFRAME_LABEL = "com.fsherratt.imudatalogger.ACTION_KEYFRAME_LABEL";
    public final static String ACTION_RECORD_START = "com.fsherratt.imudatalogger.ACTION_RECORD_START";
    public final static String ACTION_RECORD_STOP = "com.fsherratt.imudatalogger.ACTION_RECORD_STOP";
    public final static String ACTION_DEVICE_FREQ = "com.fsherratt.imudatalogger.ACTION_DEVICE_FREQ";
    public final static String ACTION_SAVE_FILE_NAME = "com.fsherratt.imudatalogger.ACTION_SAVE_FILE_NAME";
    public final static String EXTRA_LABEL = "com.fsherratt.imudatalogger.EXTRA_LABEL";
    public final static String EXTRA_TIMESTAMP = "com.fsherratt.imudatalogger.EXTRA_TIMESTAMP";
    public final static String EXTRA_FREQ = "com.fsherratt.imudatalogger.EXTRA_FREQ";
    public final static String EXTRA_ERROR = "com.fsherratt.imudatalogger.EXTRA_ERROR";
    public final static String EXTRA_FILE_NAME = "com.fsherratt.imudatalogger.EXTRA_FILE_NAME";
    public final static int FREQ_ERROR_NONE = 0;
    public final static int FREQ_ERROR_NO_UPDATE = 1;
    private static final String TAG = "logService";
    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    private final BroadcastReceiver mLogDataReceiver;
    // Data frequency
    ArrayMap<String, bleSampleCount> sampleCount = new ArrayMap<>();
    // File operations
    String mFileName;
    boolean recordEnable = false;
    FileOutputStream fOut = null;
    FileOutputStream fLabel = null;
    int i = 0;
    private Handler mHandler;
    private Runnable mHealthRunable;

    {
        mLogDataReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();

                if (action == null)
                    return;

                switch (action) {
                    case bleService.ACTION_DATA_AVAILABLE: {
                        String Address = intent.getStringExtra(MainActivity.EXTRA_ADDRESS);
                        String Uuid = intent.getStringExtra(bleService.EXTRA_UUID);
                        String time = intent.getStringExtra(bleService.EXTRA_TIMESTAMP);
                        byte[] data = intent.getByteArrayExtra(bleService.EXTRA_DATA);

                        writeToFile(buildOutputString(Address, Uuid, data, time));
                        newSample(Address);
                        break;
                    }

                    case ACTION_KEYFRAME_LABEL: {
                        String label = intent.getStringExtra(EXTRA_LABEL);
                        String timestamp = intent.getStringExtra(EXTRA_TIMESTAMP);

                        Log.d(TAG, "onRecieve: ACTION_KEYFRAME_LABEL " + label);

                        writeLabel(label, timestamp);

                        break;
                    }

                    case ACTION_RECORD_START:
                        Log.d(TAG, "ACTION_RECORD_START");

                        resetSampleCount();
                        openFile();
                        writeLabel("START", makeTimestamp());
                        startHealthCheck();
                        break;

                    case ACTION_RECORD_STOP:
                        Log.d(TAG, "ACTION_RECORD_STOP");

                        writeLabel("END", makeTimestamp());
                        closeFile();
                        stopHealthCheck();
                        break;
                }
            }
        };
    }

    static public FileOutputStream openFileStream(Context context, String filename) {
        String logDir = context.getResources().getString(R.string.log_directory);
        File dir = new File(Environment.getExternalStorageDirectory() + File.separator + logDir);

        if (!dir.exists()) {
            if (!dir.mkdir()) {
                Log.e(TAG, "openFile: Errors: couldn't create folder");
            }
        }

        File file = new File(dir, File.separator + filename);

        FileOutputStream returnFOut;
        try {
            if (!file.createNewFile()) {
                Log.e(TAG, "Failed to create file");
                return null;
            }
        } catch (IOException e) {
            Log.e(TAG, "openFile: Error: " + e);
            return null;
        }

        if (file.exists()) {
            try {
                returnFOut = new FileOutputStream(file);
            } catch (FileNotFoundException e) {
                Log.e(TAG, "openFile: FileNotFound " + e);
                return null;
            }
        } else {
            Log.e(TAG, "can't create file");
            return null;
        }

        return returnFOut;
    }

    public static String makeTimestamp() {
        long tsLong = System.currentTimeMillis();
        return String.valueOf(tsLong);
    }

    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startReceiver();

        mHandler = new Handler();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopReceiver();
    }

    // Incoming BLE data
    private void startReceiver() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_RECORD_START);
        intentFilter.addAction(ACTION_RECORD_STOP);
        intentFilter.addAction(ACTION_KEYFRAME_LABEL);
        intentFilter.addAction(bleService.ACTION_DATA_AVAILABLE);

        try {
            registerReceiver(mLogDataReceiver, intentFilter);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Receiver already registered");
        }
    }

    private void stopReceiver() {
        try {
            unregisterReceiver(mLogDataReceiver);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Receiver not registered");
        }
    }

    void newSample(String Address) {
        int samples = 0;

        bleSampleCount deviceCount = sampleCount.get(Address);
        if (deviceCount == null) {
            sampleCount.put(Address, new bleSampleCount(Address, 0));
        } else {
            samples = deviceCount.addSample();
        }


        if (samples % 16 == 0) {
            Intent intent = new Intent(ACTION_DEVICE_FREQ);
            intent.putExtra(MainActivity.EXTRA_ADDRESS, Address);
            intent.putExtra(EXTRA_FREQ, samples);
            intent.putExtra(EXTRA_ERROR, FREQ_ERROR_NONE);

            sendBroadcast(intent);
        }
    }

    void resetSampleCount() {
        sampleCount.clear();
    }

    void checkUpdateHealth() {
        for (bleSampleCount deviceCount : sampleCount.values()) {
            if (!deviceCount.checkHealth()) {
                Intent intent = new Intent(ACTION_DEVICE_FREQ);
                intent.putExtra(MainActivity.EXTRA_ADDRESS, deviceCount.address);
                intent.putExtra(EXTRA_FREQ, deviceCount.currentCount);
                intent.putExtra(EXTRA_ERROR, FREQ_ERROR_NO_UPDATE);

                sendBroadcast(intent);
            }
        }
    }

    void startHealthCheck() {
        mHealthRunable = new Runnable() {
            @Override
            public void run() {
                checkUpdateHealth();
                mHandler.postDelayed(this, 5000);
            }
        };
        mHandler.postDelayed(mHealthRunable, 5000);
    }

    void stopHealthCheck() {
        mHandler.removeCallbacks(mHealthRunable);
    }

    void openFile() {
        if (recordEnable)
            return;

        mFileName = new SimpleDateFormat("'Log_'yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());

        fOut = openFileStream(this, mFileName + ".txt");
        fLabel = openFileStream(this, mFileName + "_label.txt");

        recordEnable = true;

        Intent intent = new Intent(ACTION_SAVE_FILE_NAME);
        intent.putExtra(EXTRA_FILE_NAME, mFileName);
        sendBroadcast(intent);
    }

    void closeFile() {
        if (!recordEnable)
            return;

        try {
            fOut.flush();
            fOut.close();

            fLabel.flush();
            fLabel.close();
        } catch (IOException e) {
            Log.e(TAG, "closeFile: Error: " + e);
        }

        fOut = null;
        fLabel = null;
        recordEnable = false;
    }

    private String buildOutputString(String address, String uuid, byte[] data, String timestamp) {
        String outputString;

        String dataString = bytesToHex(data);

        outputString = timestamp + "-" + address + "-" + uuid + "-" + dataString + "\n";
        return outputString;
    }

    private void writeToFile(String data) {
        if (!recordEnable)
            return;

        try {
            fOut.write(data.getBytes());
            i++;

            if (i > 1000) {
                fOut.flush();
                i = 0;
                Log.d(TAG, "WriteToFile: Flushed");
            }
        } catch (IOException e) {
            Log.e("Exception", "File write failed: " + e.toString());
        }
    }

    private void writeLabel(String label, String timestamp) {
        if (!recordEnable)
            return;

        String data = timestamp + ", " + label + "\n";

        try {
            fLabel.write(data.getBytes());
            fLabel.flush();
        } catch (IOException e) {
            Log.e("Exception", "File write failed: " + e.toString());
        }
    }

    static class bleSampleCount {
        String address;
        int currentCount;
        int lastHealthCount;
        boolean errorSent;

        bleSampleCount(String address, int StartCount) {
            this.address = address;
            this.currentCount = StartCount;
            this.lastHealthCount = StartCount;

            this.errorSent = false;
        }

        int addSample() {
            currentCount++;
            return currentCount;
        }

        boolean checkHealth() {
            boolean result = true;

            if (currentCount == lastHealthCount && !errorSent) {
                result = false;
                errorSent = true;
            }

            lastHealthCount = currentCount;
            return result;
        }
    }
}
