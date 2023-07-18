package m.co.rh.id.a_jarwis.base.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

public class SerializeUtils {
    public static byte[] serialize(Serializable serializable) {
        byte[] bytes;
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(serializable);
            bytes = bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return bytes;
    }

    @SuppressWarnings("unchecked")
    public static <O extends Serializable> O deserialize(byte[] serializable) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(serializable);
             ObjectInputStream ois = new ObjectInputStream(bis)) {
            return (O) ois.readObject();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] serializeBitmap(Bitmap bitmap) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream);
        return byteArrayOutputStream.toByteArray();
    }

    public static Bitmap deserializeBitmap(byte[] bytes) {
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }

    private SerializeUtils() {
    }
}
