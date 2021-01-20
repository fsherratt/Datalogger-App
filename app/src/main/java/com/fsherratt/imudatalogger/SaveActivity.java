package com.fsherratt.imudatalogger;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

public class SaveActivity extends AppCompatActivity {
    final static String EXTRA_FILENAME = "com.example.fsherratt.imudatalogger.EXTRA_FILENAME";
    private static final String TAG = "SaveActivity";
    // Firebase Authorization
    FirebaseAuth mAuth;
    FirebaseUser mCurrentUser = null;
    // File Operations
    ArrayList<File> mfileList = null;
    // Firebase Storage
    FirebaseStorage mStorage = null;
    UploadTask mUploadTask = null;
    Boolean mUploadSuccess = false;
    private String mfileName;
    private EditText mDescription;
    private EditText mHeight;
    private AutoCompleteTextView mGender;
    private Button mUpload;
    // UI Uploading Popup
    private SavePopUpClass mPopUpWindow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_save);

        Intent intent = getIntent();
        mfileName = intent.getStringExtra(EXTRA_FILENAME);

        mDescription = findViewById(R.id.Description_Input);
        mHeight = findViewById(R.id.height_input);
        mGender = findViewById(R.id.gender_spinner);
        mUpload = findViewById(R.id.upload_button);

        final ArrayList<String> genderList = new ArrayList<>(Arrays.asList(getResources().getStringArray(R.array.gender_options)));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.gender_list_item, genderList);
        mGender.setAdapter(adapter);
        getFiles();

        mAuth = FirebaseAuth.getInstance();

        Log.d(TAG, mfileName);
    }

    @Override
    protected void onStart() {
        super.onStart();

        loginToFireBase();
    }

    // UI Activity
    public void upload_button(View view) {
        saveMetaData();
        createPopup(view);
        startUpload();
    }

    public void cancel_button(View view) {
        saveMetaData();

        mAuth.signOut();
        finish();
    }

    private void createPopup(View view) {
        if (mCurrentUser == null) {
            return;
        }

        mPopUpWindow = new SavePopUpClass(this);

        mPopUpWindow.setOnDismissCallback(this::onDismissClass);

        mPopUpWindow.showPopupWindow(view);

        setFileName("");
        setProgressBar(0);
    }

    public void onDismissClass() {
        Log.d(TAG, "Dismiss");

        if (mUploadTask != null) {
            cancelUpload();
            return;
        }

        if (!mUploadSuccess) {
            return;
        }

        mAuth.signOut();
        finish();
        Log.d(TAG, "Upload Success");
    }

    private void setFileName(String file) {
        mPopUpWindow.setFileName(file);
    }

    private void setProgressBar(int progress) {
        mPopUpWindow.setProgressBar(progress);
    }

    private void setButtonDone(Boolean state) {
        mPopUpWindow.setButtonDone(state);
    }

    private void setTitleText(String title) {
        mPopUpWindow.setTitle(title);
    }

    private void loginToFireBase() {
        // Check if we are already logged in
        mCurrentUser = mAuth.getCurrentUser();

        if (mCurrentUser == null) {
            // Perform anonymous login
            mAuth.signInAnonymously().addOnCompleteListener(this, task -> {
                if (task.isSuccessful()) {
                    mCurrentUser = mAuth.getCurrentUser();
                    Log.d(TAG, "signInAnonymously:success");
                    successfulLogin();
                } else {
                    Log.d(TAG, "signInAnonymously:failed");
                    mCurrentUser = null;
                    failedLogin();
                }
            });
        } else {
            successfulLogin();
        }
    }

    private void successfulLogin() {
        Toast.makeText(this, "Succsefully connected to Firebase as UID " + mCurrentUser.getUid(), Toast.LENGTH_SHORT).show();

        mUpload.setEnabled(true);
        mUpload.setBackgroundColor(getColor(R.color.colorAccent));
        mUpload.setTextColor(getColor(R.color.colorWhite));
        setupStorage();
    }

    private void failedLogin() {
        View contextView = findViewById(android.R.id.content).getRootView();
        Snackbar snackbar = Snackbar.make(contextView, "Failed to connect to Firebase, you can still save data locally", Snackbar.LENGTH_SHORT);
        snackbar.setAction("Close", v -> snackbar.dismiss());
        snackbar.show();

        mUpload.setText(R.string.upload_button_alt_text);
        mUpload.setEnabled(true);
    }

    private void getFiles() {
        String logDir = getResources().getString(R.string.log_directory);
        String path = Environment.getExternalStorageDirectory().toString() + File.separator + logDir;
        File dir = new File(path);

        FileFilter filter = pathname -> pathname.getName().contains(mfileName);
        mfileList = new ArrayList<>(Arrays.asList(Objects.requireNonNull(dir.listFiles(filter))));
    }

    private void saveMetaData() {
        String metaFileName = mfileName + "_meta.txt";

        String file = "file: " + mfileName + System.getProperty("line.separator");
        String gender = "gender: " + mGender.getText().toString() + System.getProperty("line.separator");
        String height = "height: " + mHeight.getText().toString() + System.getProperty("line.separator");
        String description = "description: " + mDescription.getText().toString() + System.getProperty("line.separator");

        FileOutputStream fileStream = logService.openFileStream(this, metaFileName);

        if (fileStream == null) {
            Log.d(TAG, "Failed to save meta data");
            return;
        }

        try {
            fileStream.write(file.getBytes());
            fileStream.write(gender.getBytes());
            fileStream.write(height.getBytes());
            fileStream.write(description.getBytes());
            fileStream.flush();

            fileStream.close();
        } catch (IOException e) {
            Log.e("Exception", "File write failed: " + e.toString());
        }

        getFiles();
    }

    private void setupStorage() {
        String customApp = "gs://gaitdatalogger.appspot.com";
        mStorage = FirebaseStorage.getInstance(customApp);
    }

    private void startUpload() {
        getFiles();
        startNextUpload();
    }

    private void allUploaded() {
        setTitleText("Success");
        setButtonDone(true);

        setFileName("All files successfully uploaded");
        mUpload.setEnabled(false);
        mUploadSuccess = true;
    }

    private void startNextUpload() {
        if (mfileList.size() == 0) {
            allUploaded();
            return;
        }

        if (mUploadTask != null) {
            return;
        }

        File file = mfileList.get(0);
        if (file == null) {
            return;
        }

        mfileList.remove(file);
        uploadFile(file);
    }

    private void cancelUpload() {
        if (mUploadTask == null) {
            return;
        }

        mUploadTask.cancel();
    }

    private void uploadFile(File file) {
        if (mStorage == null) {
            Toast.makeText(this, "Firebase storage not found", Toast.LENGTH_SHORT).show();
        }

        Uri fileUri = Uri.fromFile(file);

        StorageReference storageReference = mStorage.getReference(mfileName + "/" + fileUri.getLastPathSegment());
        storageReference.getStorage().setMaxUploadRetryTimeMillis(1000 * 60); // Timeout after 1 minutes
        setFileName(fileUri.getLastPathSegment());

        mUploadTask = storageReference.putFile(fileUri);

        mUploadTask.addOnFailureListener(this::fileUploadFailed)
                .addOnSuccessListener(this::fileUploadSuccess)
                .addOnProgressListener(this::fileProgressListener)
                .addOnCanceledListener(this::fileUploadCancelled);
    }


    // Firebase Storage callbacks
    private void fileUploadSuccess(UploadTask.TaskSnapshot taskSnapshot) {
        mUploadTask = null;
        startNextUpload();
    }

    private void fileUploadFailed(@NonNull Exception exception) {
        mUploadTask = null;
        setTitleText("Error");
        setFileName("Upload Failed: Please try again");
        setButtonDone(false);
    }

    private void fileUploadCancelled() {
        mUploadTask = null;
        mfileList = null;
    }

    private void fileProgressListener(UploadTask.TaskSnapshot taskSnapshot) {
        int progress = (int) ((100.0 * taskSnapshot.getBytesTransferred()) / taskSnapshot.getTotalByteCount());
        setProgressBar(progress);
    }
}

