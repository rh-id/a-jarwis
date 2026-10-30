package m.co.rh.id.a_jarwis.app.ui.component;

import android.app.Activity;
import android.view.View;
import android.widget.ProgressBar;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatTextView;

import m.co.rh.id.a_jarwis.R;

/**
 * Reusable busy-guard progress dialog of the editor pages: shows a
 * non-cancelable progress dialog for the duration of a pipeline (decode,
 * segmentation, erase, save, share) and doubles as the busy-guard —
 * {@link #isWorking()} is the gate every editor pipeline relies on to
 * reject a re-entry while a pipeline is running (a pipeline shows the
 * dialog for its whole lifetime, so a showing dialog means "busy").
 * The dialog is created lazily on the first show and every show/hide
 * nulls out the dialog and its views, so the busy-guard reads
 * mDialog != null && isShowing() exactly like the page-level field
 * discipline it replaced.
 */
public class EditorProgressDialog {
    private final Activity mActivity;
    /**
     * Title resource of the dialog, the owning editor page's title
     */
    private final int mTitleRes;
    private AlertDialog mDialog;
    private ProgressBar mProgressBar;
    private AppCompatTextView mTextProgress;

    public EditorProgressDialog(Activity activity, int titleRes) {
        mActivity = activity;
        mTitleRes = titleRes;
    }

    /**
     * Busy-guard of the editor pipelines: true while the progress dialog is
     * showing (a pipeline holds it open for its whole lifetime)
     */
    public boolean isWorking() {
        return mDialog != null && mDialog.isShowing();
    }

    /**
     * Show (or update) the progress dialog.
     *
     * @param determinate true to show a determinate progress bar with the given max,
     *                    false to show an indeterminate one
     */
    public void show(boolean determinate, int max, String message) {
        if (mDialog == null) {
            View contentView = mActivity.getLayoutInflater()
                    .inflate(R.layout.dialog_face_editor_progress, null);
            mProgressBar = contentView.findViewById(R.id.progressBar);
            mTextProgress = contentView.findViewById(R.id.text_progress);
            mDialog = new AlertDialog.Builder(mActivity)
                    .setTitle(mTitleRes)
                    .setView(contentView)
                    .setCancelable(false)
                    .create();
            mDialog.show();
        }
        mProgressBar.setIndeterminate(!determinate);
        if (determinate) {
            mProgressBar.setMax(max);
            mProgressBar.setProgress(0);
        }
        mTextProgress.setText(message);
    }

    /**
     * Update the progress of a running determinate pipeline without
     * touching the dialog itself
     */
    public void update(int progress, int max, String message) {
        if (mProgressBar != null) {
            mProgressBar.setIndeterminate(false);
            mProgressBar.setMax(max);
            mProgressBar.setProgress(progress);
        }
        if (mTextProgress != null) {
            mTextProgress.setText(message);
        }
    }

    /**
     * Dismiss the progress dialog at the end of a pipeline, the dialog and
     * its views are nulled out so the busy-guard reads false again
     */
    public void hide() {
        if (mDialog != null && mDialog.isShowing()) {
            mDialog.dismiss();
        }
        mDialog = null;
        mProgressBar = null;
        mTextProgress = null;
    }

    /**
     * Release the dialog when the owning page is disposed (the dialog is
     * dismissed when showing), the dialog and its views are nulled out
     */
    public void dispose() {
        if (mDialog != null) {
            if (mDialog.isShowing()) {
                mDialog.dismiss();
            }
            mDialog = null;
        }
        mProgressBar = null;
        mTextProgress = null;
    }
}
