package m.co.rh.id.a_jarwis.base.util;

import static org.junit.Assert.assertEquals;

import android.graphics.Bitmap;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

@RunWith(AndroidJUnit4.class)
public class SerializeUtilsTest {

    @Test
    public void serializeBitmapTest() {
        int width = 100;
        int height = 200;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        byte[] bytes = SerializeUtils.serializeBitmap(bitmap);
        Bitmap result = SerializeUtils.deserializeBitmap(bytes);
        assertEquals(width, result.getWidth());
        assertEquals(height, result.getHeight());
    }

    @Test
    public void serializeBitmapTest_class() throws IOException, ClassNotFoundException {
        int width = 100;
        int height = 200;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        BitmapBean bitmapBean = new BitmapBean(bitmap);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(bos);
        oos.writeObject(bitmapBean);
        ByteArrayInputStream bis = new ByteArrayInputStream(bos.toByteArray());
        ObjectInputStream ois = new ObjectInputStream(bis);
        BitmapBean result = ((BitmapBean) ois.readObject());
        assertEquals(bitmapBean.bitmap.getWidth(), result.bitmap.getWidth());
        assertEquals(bitmapBean.bitmap.getHeight(), result.bitmap.getHeight());
    }

    private static class BitmapBean implements Serializable {
        private Bitmap bitmap;

        public BitmapBean(Bitmap bitmap) {
            this.bitmap = bitmap;
        }

        private void writeObject(ObjectOutputStream oos) throws IOException {
            oos.writeObject(SerializeUtils.serializeBitmap(bitmap));
        }

        private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
            bitmap = SerializeUtils.deserializeBitmap((byte[]) ois.readObject());
        }
    }
}
