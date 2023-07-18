package m.co.rh.id.a_jarwis.base.util;

import android.graphics.Bitmap;
import android.graphics.Rect;

public class BitmapUtils {
    public static Bitmap cropBitmap(Bitmap bitmap, Rect dest) {
        return Bitmap.createBitmap(bitmap, dest.left, dest.top, dest.width(), dest.height());
    }

    private BitmapUtils() {
    }
}
