package m.co.rh.id.a_jarwis.base.util;

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

    private SerializeUtils() {
    }
}
