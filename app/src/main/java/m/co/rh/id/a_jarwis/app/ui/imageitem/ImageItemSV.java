package m.co.rh.id.a_jarwis.app.ui.imageitem;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;

import co.rh.id.lib.rx3_utils.subject.SerialBehaviorSubject;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import m.co.rh.id.a_jarwis.R;
import m.co.rh.id.a_jarwis.app.ui.imageitem.model.ImageItem;
import m.co.rh.id.a_jarwis.base.provider.IStatefulViewProvider;
import m.co.rh.id.a_jarwis.base.rx.RxDisposer;
import m.co.rh.id.anavigator.StatefulView;
import m.co.rh.id.anavigator.component.RequireComponent;
import m.co.rh.id.aprovider.Provider;

public class ImageItemSV extends StatefulView<Activity> implements RequireComponent<Provider>, CompoundButton.OnCheckedChangeListener, View.OnClickListener {

    private transient Provider mSvProvider;
    private transient RxDisposer mRxDisposer;

    private SerialBehaviorSubject<ImageItem> mModel;

    public ImageItemSV() {
        mModel = new SerialBehaviorSubject<>();
    }

    @Override
    public void provideComponent(Provider provider) {
        mSvProvider = provider.get(IStatefulViewProvider.class);
        mRxDisposer = mSvProvider.get(RxDisposer.class);
    }

    @Override
    protected View createView(Activity activity, ViewGroup container) {
        View rootView = activity.getLayoutInflater().inflate(R.layout.image_item_item, container, false);
        rootView.setOnClickListener(this);
        ImageView imageView = rootView.findViewById(R.id.imageView);
        CheckBox checkBox = rootView.findViewById(R.id.checkbox);
        checkBox.setOnCheckedChangeListener(this);
        mRxDisposer.add("createView_onItemImageChanged", mModel
                .getSubject().observeOn(AndroidSchedulers.mainThread())
                .subscribe(imageItemModel -> {
                            imageView.setImageBitmap(imageItemModel.getImage());
                            checkBox.setChecked(imageItemModel.isSelected());
                        }
                ));
        return rootView;
    }

    @Override
    public void dispose(Activity activity) {
        super.dispose(activity);
        if (mSvProvider != null) {
            mSvProvider.dispose();
            mSvProvider = null;
        }
    }

    @Override
    public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
        mModel.getValue().setSelected(b);
    }

    public void setImageItem(ImageItem imageItem) {
        mModel.onNext(imageItem);
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        if (id == R.id.root_layout) {
            ImageItem imageItem = mModel.getValue();
            imageItem.setSelected(!imageItem.isSelected());
            mModel.onNext(imageItem);
        }
    }
}
