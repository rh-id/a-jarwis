package m.co.rh.id.a_jarwis.app.ui.page;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import m.co.rh.id.a_jarwis.R;
import m.co.rh.id.a_jarwis.app.ui.component.HomeDrawerSV;
import m.co.rh.id.a_jarwis.base.constants.Routes;
import m.co.rh.id.a_jarwis.base.provider.IStatefulViewProvider;
import m.co.rh.id.a_jarwis.base.ui.component.AppBarSV;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.anavigator.StatefulView;
import m.co.rh.id.anavigator.annotation.NavInject;
import m.co.rh.id.anavigator.component.INavigator;
import m.co.rh.id.anavigator.component.NavOnActivityResult;
import m.co.rh.id.anavigator.component.NavOnBackPressed;
import m.co.rh.id.anavigator.component.NavOnRequestPermissionResult;
import m.co.rh.id.anavigator.component.RequireComponent;
import m.co.rh.id.aprovider.Provider;

public class HomePage extends StatefulView<Activity> implements RequireComponent<Provider>, NavOnBackPressed<Activity>, NavOnActivityResult<Activity>, NavOnRequestPermissionResult<Activity>, View.OnClickListener {
    private static final String TAG = "HomePage";

    @NavInject
    private transient INavigator mNavigator;
    @NavInject
    private AppBarSV mAppBarSV;
    private HomeDrawerSV mHomeDrawerSV;
    private HomeWorkflow mWorkflow;
    private transient long mLastBackPressMilis;

    // component
    private transient IStatefulViewProvider mSvProvider;
    private transient ILogger mLogger;

    public HomePage() {
        mAppBarSV = new AppBarSV();
        mHomeDrawerSV = new HomeDrawerSV();
        mWorkflow = new HomeWorkflow();
    }

    @Override
    public void provideComponent(Provider provider) {
        mSvProvider = provider.get(IStatefulViewProvider.class);
        mLogger = mSvProvider.get(ILogger.class);
        mWorkflow.init(mNavigator, mSvProvider);
    }

    @Override
    protected View createView(Activity activity, ViewGroup container) {
        View rootLayout = activity.getLayoutInflater().inflate(R.layout.page_home, container, false);
        DrawerLayout drawerLayout = rootLayout.findViewById(R.id.drawer);
        DrawerLayout.LayoutParams drawerLayoutParams = new DrawerLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        drawerLayoutParams.gravity = GravityCompat.START;
        drawerLayout.addView(mHomeDrawerSV.buildView(activity, container), drawerLayoutParams);
        mHomeDrawerSV.attachDrawer(drawerLayout);
        mHomeDrawerSV.setOnSettingsClickListener(v -> mNavigator.push(Routes.SETTINGS_PAGE));
        mHomeDrawerSV.setOnDonationsClickListener(v -> mNavigator.push(Routes.DONATIONS_PAGE));
        mAppBarSV.setTitle(activity.getString(R.string.home));
        mAppBarSV.setNavigationOnClick(v -> mHomeDrawerSV.open());
        Button blurFaceButton = rootLayout.findViewById(R.id.button_blur_face);
        blurFaceButton.setOnClickListener(this);
        Button nstApplyPictureButton = rootLayout.findViewById(R.id.button_nst_apply_picture);
        nstApplyPictureButton.setOnClickListener(this);
        ViewGroup containerAppBar = rootLayout.findViewById(R.id.container_app_bar);
        containerAppBar.addView(mAppBarSV.buildView(activity, container));
        return rootLayout;
    }

    @Override
    public void dispose(Activity activity) {
        super.dispose(activity);
        mAppBarSV.dispose(activity);
        mAppBarSV = null;
        mHomeDrawerSV.dispose(activity);
        mHomeDrawerSV = null;
        if (mSvProvider != null) {
            mSvProvider.dispose();
            mSvProvider = null;
        }
        mWorkflow = null;
    }

    @Override
    public void onBackPressed(View currentView, Activity activity, INavigator navigator) {
        if (mHomeDrawerSV.isOpen()) {
            mHomeDrawerSV.close();
        } else {
            long currentMilis = System.currentTimeMillis();
            if ((currentMilis - mLastBackPressMilis) < 1000) {
                navigator.finishActivity(null);
            } else {
                mLastBackPressMilis = currentMilis;
                mLogger.i(TAG,
                        activity.getString(R.string.toast_back_press_exit));
            }
        }
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        if (id == R.id.button_blur_face) {
            mWorkflow.startBlurFace();
        } else if (id == R.id.button_nst_apply_picture) {
            mWorkflow.startNstApply();
        }
    }

    @Override
    public void onActivityResult(View currentView, Activity activity, INavigator INavigator, int requestCode, int resultCode, Intent data) {
        mWorkflow.onImagePicked(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(View currentView, Activity activity, INavigator INavigator, int requestCode, String[] permissions, int[] grantResults) {
        mWorkflow.onPermissionResult(requestCode, grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED);
    }
}
