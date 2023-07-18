package m.co.rh.id.a_jarwis.app.provider;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.view.LayoutInflater;

import androidx.collection.ArrayMap;

import java.util.LinkedHashMap;
import java.util.Map;

import m.co.rh.id.a_jarwis.R;
import m.co.rh.id.a_jarwis.app.MainActivity;
import m.co.rh.id.a_jarwis.app.ui.page.DonationsPage;
import m.co.rh.id.a_jarwis.app.ui.page.HomePage;
import m.co.rh.id.a_jarwis.app.ui.page.SplashPage;
import m.co.rh.id.a_jarwis.app.ui.page.common.SelectFaceImagePage;
import m.co.rh.id.a_jarwis.app.ui.page.common.ShowMessagePage;
import m.co.rh.id.a_jarwis.base.constants.Routes;
import m.co.rh.id.a_jarwis.settings.ui.page.SettingsPage;
import m.co.rh.id.anavigator.NavConfiguration;
import m.co.rh.id.anavigator.Navigator;
import m.co.rh.id.anavigator.StatefulView;
import m.co.rh.id.anavigator.component.INavigator;
import m.co.rh.id.anavigator.component.StatefulViewFactory;
import m.co.rh.id.anavigator.extension.dialog.ui.NavExtDialogConfig;
import m.co.rh.id.aprovider.Provider;
import m.co.rh.id.aprovider.ProviderDisposable;

@SuppressWarnings("rawtypes")
public class NavigatorProvider implements ProviderDisposable {
    private Application mApplication;
    private Provider mProvider;
    private NavExtDialogConfig mNavExtDialogConfig;
    private Map<Class<? extends Activity>, Navigator> mActivityNavigatorMap;

    public NavigatorProvider(Application application, Provider provider) {
        mApplication = application;
        mProvider = provider;
        mActivityNavigatorMap = new LinkedHashMap<>();
        mNavExtDialogConfig = mProvider.get(NavExtDialogConfig.class);
        setupMainActivityNavigator();
    }

    public INavigator getNavigator(Activity activity) {
        return mActivityNavigatorMap.get(activity.getClass());
    }

    @SuppressLint("InflateParams")
    @SuppressWarnings("unchecked")
    private Navigator setupMainActivityNavigator() {
        Map<String, StatefulViewFactory> navMap = new ArrayMap<>();
        navMap.put(Routes.SPLASH_PAGE, (args, activity) -> new SplashPage(Routes.HOME_PAGE));
        navMap.put(Routes.HOME_PAGE, (args, activity) -> new HomePage());
        navMap.put(Routes.SHOW_MESSAGE_PAGE, (args, activity) -> new ShowMessagePage());
        navMap.put(Routes.SELECT_FACE_IMAGE_PAGE, (args, activity) -> new SelectFaceImagePage());
        navMap.put(Routes.SETTINGS_PAGE, (args, activity) -> new SettingsPage());
        navMap.put(Routes.DONATIONS_PAGE, (args, activity) -> new DonationsPage());
        navMap.putAll(mNavExtDialogConfig.getNavMap());
        NavConfiguration.Builder<Activity, StatefulView> navBuilder =
                new NavConfiguration.Builder(Routes.SPLASH_PAGE, navMap);
        navBuilder.setRequiredComponent(mProvider);
        navBuilder.setMainHandler(mProvider.get(Handler.class));
        navBuilder.setLoadingView(LayoutInflater.from(mProvider.getContext())
                .inflate(R.layout.page_splash, null));
        NavConfiguration<Activity, StatefulView> navConfiguration = navBuilder.build();
        Navigator navigator = new Navigator(MainActivity.class, navConfiguration);
        mActivityNavigatorMap.put(MainActivity.class, navigator);
        mApplication.registerActivityLifecycleCallbacks(navigator);
        mApplication.registerComponentCallbacks(navigator);
        return navigator;
    }

    @Override
    public void dispose(Context context) {
        if (mActivityNavigatorMap != null && !mActivityNavigatorMap.isEmpty()) {
            for (Map.Entry<Class<? extends Activity>, Navigator> navEntry : mActivityNavigatorMap.entrySet()) {
                Navigator navigator = navEntry.getValue();
                mApplication.unregisterActivityLifecycleCallbacks(navigator);
                mApplication.unregisterComponentCallbacks(navigator);
            }
            mActivityNavigatorMap.clear();
        }
        mActivityNavigatorMap = null;
        mProvider = null;
        mApplication = null;
    }
}
