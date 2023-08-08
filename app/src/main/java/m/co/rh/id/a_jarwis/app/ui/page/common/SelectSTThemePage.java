package m.co.rh.id.a_jarwis.app.ui.page.common;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import co.rh.id.lib.rx3_utils.subject.SerialBehaviorSubject;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import m.co.rh.id.a_jarwis.R;
import m.co.rh.id.a_jarwis.app.ui.page.nav.param.SelectedTheme;
import m.co.rh.id.a_jarwis.base.provider.IStatefulViewProvider;
import m.co.rh.id.a_jarwis.base.rx.RxDisposer;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.STEngine;
import m.co.rh.id.anavigator.StatefulView;
import m.co.rh.id.anavigator.annotation.NavInject;
import m.co.rh.id.anavigator.component.INavigator;
import m.co.rh.id.anavigator.component.RequireComponent;
import m.co.rh.id.aprovider.Provider;

public class SelectSTThemePage extends StatefulView<Activity> implements RequireComponent<Provider>, View.OnClickListener {

    @NavInject
    private transient INavigator mNavigator;

    private transient Provider mSvProvider;

    private transient RxDisposer mRxDisposer;

    private SerialBehaviorSubject<HashSet<Integer>> mSelectedThemesId;

    public SelectSTThemePage() {
        mSelectedThemesId = new SerialBehaviorSubject<>(new HashSet<>());
    }

    @Override
    public void provideComponent(Provider provider) {
        mSvProvider = provider.get(IStatefulViewProvider.class);
        mRxDisposer = mSvProvider.get(RxDisposer.class);
    }

    @Override
    protected View createView(Activity activity, ViewGroup container) {
        View rootView = activity.getLayoutInflater().inflate(R.layout.page_select_st_theme, container, false);
        View mosaic = rootView.findViewById(R.id.container_mosaic_theme);
        mosaic.setOnClickListener(this);
        View candy = rootView.findViewById(R.id.container_candy_theme);
        candy.setOnClickListener(this);
        View rainPrincess = rootView.findViewById(R.id.container_rain_princess_theme);
        rainPrincess.setOnClickListener(this);
        View udnie = rootView.findViewById(R.id.container_udnie_theme);
        udnie.setOnClickListener(this);
        View pointilism = rootView.findViewById(R.id.container_pointilism_theme);
        pointilism.setOnClickListener(this);
        View backButton = rootView.findViewById(R.id.button_back);
        backButton.setOnClickListener(this);
        View nextButton = rootView.findViewById(R.id.button_next);
        nextButton.setOnClickListener(this);
        mRxDisposer.add("createView_onSelectedThemesChanged", mSelectedThemesId.getSubject()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(integers -> {
                    mosaic.setSelected(false);
                    candy.setSelected(false);
                    rainPrincess.setSelected(false);
                    udnie.setSelected(false);
                    pointilism.setSelected(false);
                    for (Integer id : integers) {
                        rootView.findViewById(id).setSelected(true);
                    }
                    nextButton.setEnabled(!integers.isEmpty());
                }));
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
    public void onClick(View view) {
        int id = view.getId();
        if (id == R.id.button_back) {
            mNavigator.pop();
        } else if (id == R.id.button_next) {
            List<Integer> result = new ArrayList<>();
            HashSet<Integer> hashSet = mSelectedThemesId.getValue();
            for (Integer themeId : hashSet) {
                if (themeId == R.id.container_mosaic_theme) {
                    result.add(STEngine.THEME_MOSAIC);
                } else if (themeId == R.id.container_candy_theme) {
                    result.add(STEngine.THEME_CANDY);
                } else if (themeId == R.id.container_rain_princess_theme) {
                    result.add(STEngine.THEME_RAIN_PRINCESS);
                } else if (themeId == R.id.container_udnie_theme) {
                    result.add(STEngine.THEME_UDNIE);
                } else if (themeId == R.id.container_pointilism_theme) {
                    result.add(STEngine.THEME_POINTILISM);
                }
            }
            mNavigator.pop(new SelectedTheme(result));
        } else {
            selectReselectTheme(id);
        }
    }

    private void selectReselectTheme(int theme) {
        HashSet<Integer> hashSet = mSelectedThemesId.getValue();
        boolean added = hashSet.add(theme);
        if (!added) {
            hashSet.remove(theme);
        }
        mSelectedThemesId.onNext(hashSet);
    }
}
