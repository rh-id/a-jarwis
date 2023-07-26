package m.co.rh.id.a_jarwis.app.ui.page.common;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import m.co.rh.id.a_jarwis.R;
import m.co.rh.id.a_jarwis.app.ui.page.nav.param.SelectedTheme;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.NSTEngine;
import m.co.rh.id.anavigator.StatefulView;
import m.co.rh.id.anavigator.annotation.NavInject;
import m.co.rh.id.anavigator.component.INavigator;

public class SelectNSTThemePage extends StatefulView<Activity> implements View.OnClickListener {

    @NavInject
    private transient INavigator mNavigator;

    @Override
    protected View createView(Activity activity, ViewGroup container) {
        View rootView = activity.getLayoutInflater().inflate(R.layout.page_select_nst_theme, container, false);
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
        return rootView;
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        if (id == R.id.container_mosaic_theme) {
            mNavigator.pop(new SelectedTheme(NSTEngine.THEME_MOSAIC));
        } else if (id == R.id.container_candy_theme) {
            mNavigator.pop(new SelectedTheme(NSTEngine.THEME_CANDY));
        } else if (id == R.id.container_rain_princess_theme) {
            mNavigator.pop(new SelectedTheme(NSTEngine.THEME_RAIN_PRINCESS));
        } else if (id == R.id.container_udnie_theme) {
            mNavigator.pop(new SelectedTheme(NSTEngine.THEME_UDNIE));
        } else if (id == R.id.container_pointilism_theme) {
            mNavigator.pop(new SelectedTheme(NSTEngine.THEME_POINTILISM));
        } else if (id == R.id.button_back) {
            mNavigator.pop();
        }
    }
}
