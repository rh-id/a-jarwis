package m.co.rh.id.a_jarwis.base.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;

import m.co.rh.id.a_jarwis.base.constants.Constants;


public class UiUtils {
    public static void shareFile(Context context, File file, String chooserMessage) {
        shareFile(context, file, chooserMessage, "*/*");
    }

    public static void shareFile(Context context, File file, String chooserMessage, String mime) {
        Uri fileUri =
                FileProvider.getUriForFile(
                        context,
                        Constants.FILE_PROVIDER_AUTHORITY,
                        file);
        Intent shareIntent = new Intent();
        shareIntent.setAction(Intent.ACTION_SEND);
        shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
        shareIntent.setType(mime);
        shareIntent = Intent.createChooser(shareIntent, chooserMessage);
        shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(shareIntent);
    }

    private UiUtils() {
    }
}
