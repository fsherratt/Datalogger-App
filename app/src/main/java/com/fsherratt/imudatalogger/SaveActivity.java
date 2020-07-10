package com.fsherratt.imudatalogger;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.PopupWindow;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.tasks.OnCanceledListener;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.OnProgressListener;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

public class SaveActivity extends AppCompatActivity {
    private static final String TAG = "SaveActivity";

    final static String EXTRA_FILENAME = "com.example.fsherratt.imudatalogger.EXTRA_FILENAME";

    private String mfileName;

    private TextView mFileName_UI;
    private TextView mFileSize_UI;

    private EditText mDescription;
    private EditText mHeight;
    private Spinner mGender;
    private Button mUpload;



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_save);

        Intent intent = getIntent();
        mfileName = intent.getStringExtra(EXTRA_FILENAME);

        mFileName_UI = (TextView)findViewById(R.id.file_name_text);
        mFileSize_UI = (TextView)findViewById(R.id.file_size_text);
        mDescription = (EditText)findViewById(R.id.Description_Input);
        mHeight = (EditText)findViewById(R.id.height_input);
        mGender = (Spinner)findViewById(R.id.gender_spinner);
        mUpload = (Button)findViewById(R.id.upload_button);

        getFiles();
        getFileSize();
        mFileName_UI.setText(mfileName + ".txt");

        mAuth = FirebaseAuth.getInstance();

        setUpSpinner();

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

    private void setUpSpinner() {
        // Spinner
        final ArrayList<String> genderList = new ArrayList<>(Arrays.asList(getResources().getStringArray(R.array.gender_options)));
        final ArrayAdapter<String> genderAdapter = new ArrayAdapter<String>(this, R.layout.spinner_item, genderList) {
            @Override
            public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                TextView tv = (TextView)view;

                if (position == 0) {
                    tv.setTextColor(getResources().getColor(R.color.colorLight));
                }

                return view;
            }

            @Override
            public boolean isEnabled(int position) {
                if(position == 0) {
                    return false;
                } else {
                    return true;
                }
            }
        };

        genderAdapter.setDropDownViewResource(R.layout.spinner_item);
        mGender.setAdapter(genderAdapter);
    }


    // UI Uploading Popup
    private PopUpClass mPopUpWindow;
    private void createPopup(View view) {
        if (mCurrentUser == null) {
            return;
        }

        mPopUpWindow = new PopUpClass(this);

        mPopUpWindow.setOnDismissCallback(new PopUpClass.popupCallbacks() {
            @Override
            public void onDismiss() {
                onDismissClass();
            }
        });

        mPopUpWindow.showPopupWindow(view);

        setFileName("");
        setProgressBar(0);
    }

    public void onDismissClass() {
        Log.d(TAG, "Dismiss");

        if (mUploadTask != null) {
            cancelUpload();
        } else if (!mUploadSuccess) {
            return;
        } else {
            mAuth.signOut();
            finish();
            Log.d(TAG, "Upload Success");
        }
    }

    private void setFileName(String file) {
        mPopUpWindow.setFileName(file);
    }

    private void setProgressBar(int progress) {
        mPopUpWindow.setProgressBar(progress);
    }

    private void setButtonText(String text) {
        mPopUpWindow.setButtonName(text);
    }

    private void setTitleText(String title) {
        mPopUpWindow.setTitle(title);
    }


    // Firebase Authorization
    FirebaseAuth mAuth;
    FirebaseUser mCurrentUser = null;

    private void loginToFireBase() {
        // Check if we are already logged in
        mCurrentUser = mAuth.getCurrentUser();

        if (mCurrentUser == null) {
            // Perform anonymous login
            mAuth.signInAnonymously().addOnCompleteListener(this, new OnCompleteListener<AuthResult>() {
                @Override
                public void onComplete(@NonNull Task task) {
                    if (task.isSuccessful()) {
                        mCurrentUser = mAuth.getCurrentUser();
                        Log.d(TAG, "signInAnonymously:success");
                        successfulLogin();
                    } else {
                        Log.d(TAG, "signInAnonymously:failed");
                        mCurrentUser = null;
                        failedLogin();
                    }
                }
            });
        } else {
            successfulLogin();
        }
    }

    private void successfulLogin() {
        Toast.makeText(this, "Succsefully connected to Firebase as UID " + mCurrentUser.getUid(), Toast.LENGTH_SHORT).show();

        mUpload.setEnabled(true);
        setupStorage();
    }

    private void failedLogin() {
        View contextView = findViewById(android.R.id.content).getRootView();
        Snackbar snackbar = Snackbar.make(contextView, "Failed to connect to Firebase, you can still save data locally", Snackbar.LENGTH_SHORT);
        snackbar.setAction("Close", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                snackbar.dismiss();
            }
        });
        snackbar.show();

        mUpload.setText("Save");
        mUpload.setEnabled(true);
    }


    // File Operations
    float mFileSize = 0;
    ArrayList<File> mfileList = null;
    private void getFiles() {
        String logDir = getResources().getString(R.string.log_directory);
        String path = Environment.getExternalStorageDirectory().toString() + File.separator + logDir;
        File dir = new File(path);

        FileFilter filter = new FileFilter() {
            @Override
            public boolean accept(File pathname) {
                if (pathname.getName().contains(mfileName)) {
                    return true;
                }
                return false;
            }
        };
        mfileList = new ArrayList<File>( Arrays.asList(dir.listFiles(filter)) );
    }

    private void getFileSize() {
        float size = 0;
        for (File file : mfileList) {
            size += Integer.parseInt(String.valueOf(file.length()/1024/1024));
        }

        mFileSize = size;
        mFileSize_UI.setText(String.valueOf(mFileSize) + "Mb");
    }

    private void saveMetaData() {
        String metaFileName = mfileName + "_meta.txt";

        String timestamp = "timestamp: " + mfileName;
        String gender =  "gender: " + mGender.getSelectedItem().toString();
        String height = "height: " + mHeight.getText().toString();
        String description = "description: " + mDescription.getText().toString();

        FileOutputStream fileStream = logService.openFileStream(this, metaFileName);

        if (fileStream == null) {
            Log.d(TAG, "Failed to save meta data");
            return;
        }

        try {
            fileStream.write(timestamp.getBytes());
            fileStream.write(gender.getBytes());
            fileStream.write(height.getBytes());
            fileStream.write(description.getBytes());
            fileStream.flush();

            fileStream.close();
        }
        catch (IOException e) {
            Log.e("Exception", "File write failed: " + e.toString());
        }

        getFiles();
    }


    // Firebase Storage
    FirebaseStorage mStorage = null;
    UploadTask mUploadTask = null;
    Boolean mUploadSuccess = false;
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
        setButtonText("Close");
        setFileName("All files successfully uploaded");
        mUpload.setEnabled(false);
        mUploadSuccess = true;
    }

    private void startNextUpload() {
        if (mfileList.size() == 0) {
            allUploaded();
            return;
        }

        if (mUploadTask != null ) {
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
        storageReference.getStorage().setMaxUploadRetryTimeMillis(1000*60*1); // Timeout after 1 minutes
        setFileName(fileUri.getLastPathSegment());

        mUploadTask = storageReference.putFile(fileUri);

        mUploadTask.addOnFailureListener(new OnFailureListener() {
            @Override
            public void onFailure(@NonNull Exception exception) {
                fileUploadFailed(exception);
            }
        }).addOnSuccessListener(new OnSuccessListener<UploadTask.TaskSnapshot>() {
            @Override
            public void onSuccess(UploadTask.TaskSnapshot taskSnapshot) {
                fileUploadSuccess(taskSnapshot);
            }
        }).addOnProgressListener(new OnProgressListener<UploadTask.TaskSnapshot>() {
            @Override
            public void onProgress(@NonNull UploadTask.TaskSnapshot taskSnapshot) {
                fileProgressListener(taskSnapshot);
            }
        }).addOnCanceledListener(new OnCanceledListener() {
            @Override
            public void onCanceled() {
                fileUploadCancelled();
            }
        });
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
        setButtonText("Close");
    }

    private void fileUploadCancelled() {
        mUploadTask = null;
        mfileList = null;
    }

    private void fileProgressListener(UploadTask.TaskSnapshot taskSnapshot) {
        int progress = (int)((100.0 * taskSnapshot.getBytesTransferred()) / taskSnapshot.getTotalByteCount());
        setProgressBar(progress);
    }
}

