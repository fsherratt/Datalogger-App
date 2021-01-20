package com.fsherratt.imudatalogger;

import android.content.Context;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;

public class SavePopUpClass {
    private static final String TAG = "PopUpClass";

    Button mCancelButton;
    PopupWindow mPopupWindow;

    ProgressBar mProgress;
    TextView mFileName;
    TextView mTitle;

    Context mContext;
    // Popup callbacks
    popupCallbacks callback;

    public SavePopUpClass(Context context) {
        mContext = context;

        LayoutInflater inflater = LayoutInflater.from(mContext);
        View popupView = inflater.inflate(R.layout.upload_popup, null);

        mProgress = popupView.findViewById(R.id.upload_progressBar);
        mFileName = popupView.findViewById(R.id.Filename_textview);
        mTitle = popupView.findViewById(R.id.titleText);

        //Specify the length and width through constants
        int width = LinearLayout.LayoutParams.MATCH_PARENT;
        int height = LinearLayout.LayoutParams.MATCH_PARENT;

        //Create a window with our parameters
        mPopupWindow = new PopupWindow(popupView, width, height, true);

        mCancelButton = popupView.findViewById(R.id.cancelButton);
        mCancelButton.setOnClickListener(v -> mPopupWindow.dismiss());

        mPopupWindow.setOnDismissListener(() -> callback.onDismiss());
    }

    public void showPopupWindow(final View view) {
        mPopupWindow.showAtLocation(view, Gravity.CENTER, 0, 0);

        // Dim behind
        View container = mPopupWindow.getContentView().getRootView();

        WindowManager wm = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
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

    public void setButtonDone(Boolean state) {
        if (state) {
            mCancelButton.setText("Close");
            mCancelButton.setBackgroundColor(mContext.getColor(R.color.colorAccent));
            mCancelButton.setTextColor(mContext.getColor(R.color.colorWhite));
        } else {
            mCancelButton.setText("Cancel");
            mCancelButton.setBackgroundColor(mContext.getColor(R.color.colorLight));
            mCancelButton.setTextColor(mContext.getColor(R.color.colorBlack));
        }
    }

    public void setOnDismissCallback(popupCallbacks callback) {
        this.callback = callback;
    }

    public interface popupCallbacks {
        void onDismiss();
    }
}

