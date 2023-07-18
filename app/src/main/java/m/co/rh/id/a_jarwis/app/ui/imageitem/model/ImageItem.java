package m.co.rh.id.a_jarwis.app.ui.imageitem.model;

import android.graphics.Bitmap;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import m.co.rh.id.a_jarwis.base.util.SerializeUtils;

public class ImageItem implements Serializable {
    private boolean selected;
    private Bitmap image;

    public ImageItem(Bitmap image, boolean selected) {
        this.image = image;
        this.selected = selected;
    }

    public Bitmap getImage() {
        return image;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    private void writeObject(ObjectOutputStream oos) throws IOException {
        oos.writeBoolean(selected);
        oos.writeObject(SerializeUtils.serializeBitmap(image));
    }

    private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
        selected = ois.readBoolean();
        image = SerializeUtils.deserializeBitmap((byte[]) ois.readObject());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ImageItem imageItem)) return false;

        if (selected != imageItem.selected) return false;
        return image.equals(imageItem.image);
    }

    @Override
    public int hashCode() {
        return image.hashCode();
    }
}
