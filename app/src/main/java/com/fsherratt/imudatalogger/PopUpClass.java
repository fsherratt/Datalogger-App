package com.fsherratt.imudatalogger;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

public class PopUpClass  {
    private static final String TAG = "PopUpClass";

    Button mCancelButton;
    PopupWindow mPopupWindow;

    ProgressBar mProgress;
    TextView mFileName;
    TextView mTitle;

    Context mContext;

    public PopUpClass(Context context) {
        mContext = context;

        LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(mContext.LAYOUT_INFLATER_SERVICE);
        View popupView = inflater.inflate(R.layout.upload_popup, null);

        mProgress = (ProgressBar)popupView.findViewById(R.id.upload_progressBar);
        mFileName = (TextView)popupView.findViewById(R.id.Filename_textview);
        mTitle = (TextView)popupView.findViewById(R.id.titleText);

        //Specify the length and width through constants
        int width = LinearLayout.LayoutParams.MATCH_PARENT;
        int height = LinearLayout.LayoutParams.MATCH_PARENT;

        //Make Inactive Items Outside Of PopupWindow
        boolean focusable = true;

        //Create a window with our parameters
        mPopupWindow = new PopupWindow(popupView, width, height, focusable);

        mCancelButton = popupView.findViewById(R.id.cancelButton);
        mCancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mPopupWindow.dismiss();
            }
        });

        mPopupWindow.setOnDismissListener(new PopupWindow.OnDismissListener() {
            @Override
            public void onDismiss() {
                callback.onDismiss();
            }
        });
    }

    public void showPopupWindow(final View view) {
        mPopupWindow.showAtLocation(view, Gravity.CENTER, 0, 0);

        // Dim behind
        View container = mPopupWindow.getContentView().getRootView();

        WindowManager wm = (WindowManager)mContext.getSystemService(mContext.WINDOW_SERVICE);
        WindowManager.LayoutParams p = (WindowManager.LayoutParams) container.getLayoutParams();

        p.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        p.dimAmount = 0.5f;

        wm.updateViewLayout(container, p);
    }


    // Update UI
    public void setProgressBar(int progress) {
        Log.d(TAG, "setProgressBar: Updated progress");
        mProgress.setProgress(progress);
    }

    public void setTitle(String title) {
        mTitle.setText(title);
    }

    public void setFileName(String fileName) {
        Log.d(TAG, "setFileName: Update filename");
        mFileName.setText(fileName);
    }

    public void setButtonName(String name) {
        mCancelButton.setText(name);
    }

    // Popup callbacks
    popupCallbacks callback;

    public interface popupCallbacks {
        void onDismiss();
    }

    public void setOnDismissCallback(popupCallbacks callback) {
        this.callback = callback;
    }
}

