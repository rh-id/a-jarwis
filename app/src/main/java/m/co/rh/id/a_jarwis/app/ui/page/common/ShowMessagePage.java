package m.co.rh.id.a_jarwis.app.ui.page.common;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;

import androidx.appcompat.app.AlertDialog;

import java.io.Serializable;

import m.co.rh.id.a_jarwis.app.ui.page.nav.param.MessageText;
import m.co.rh.id.a_jarwis.app.ui.page.nav.param.SelectedChoice;
import m.co.rh.id.anavigator.NavRoute;
import m.co.rh.id.anavigator.StatefulViewDialog;
import m.co.rh.id.anavigator.annotation.NavInject;

public class ShowMessagePage extends StatefulViewDialog<Activity> implements DialogInterface.OnClickListener {

    @NavInject
    private transient NavRoute mNavRoute;

    private SelectedChoice mSelectedChoice;

    public ShowMessagePage() {
        mSelectedChoice = new SelectedChoice();
    }

    @Override
    protected Dialog createDialog(Activity activity) {
        int title = 0;
        int message = 0;
        boolean enableCancel = false;
        if (mNavRoute != null) {
            Serializable serializable = mNavRoute.getRouteArgs();
            if (serializable instanceof MessageText) {
                title = ((MessageText) serializable).getTitle();
                message = ((MessageText) serializable).getBody();
                enableCancel = ((MessageText) serializable).isEnableCancel();
            }
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setPositiveButton(android.R.string.ok, this)
                .setTitle(title)
                .setMessage(message);

        if (enableCancel) {
            builder.setNegativeButton(android.R.string.cancel, this);
        }
        return builder
                .create();
    }

    @Override
    public void onClick(DialogInterface dialogInterface, int which) {
        switch (which) {
            case DialogInterface.BUTTON_NEGATIVE:
                mSelectedChoice.setSelectedChoice(SelectedChoice.NEGATIVE);
                break;
            case DialogInterface.BUTTON_POSITIVE:
                mSelectedChoice.setSelectedChoice(SelectedChoice.POSITIVE);
                break;
            default:
                mSelectedChoice.setSelectedChoice(SelectedChoice.NO_SELECT);
        }
    }

    @Override
    protected Serializable getDialogResult() {
        return mSelectedChoice;
    }
}
