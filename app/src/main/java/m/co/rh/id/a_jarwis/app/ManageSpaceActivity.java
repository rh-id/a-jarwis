package m.co.rh.id.a_jarwis.app;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.TypedValue;

import androidx.activity.BackEventCompat;
import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.color.DynamicColors;

import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.subjects.PublishSubject;
import m.co.rh.id.a_jarwis.base.BaseApplication;
import m.co.rh.id.a_jarwis.base.provider.RxProviderModule;
import m.co.rh.id.a_jarwis.base.rx.RxDisposer;
import m.co.rh.id.a_jarwis.settings.provider.component.SettingsSharedPreferences;
import m.co.rh.id.anavigator.component.INavigator;
import m.co.rh.id.anavigator.component.NavBackEvent;
import m.co.rh.id.anavigator.component.NavOnRouteChangedListener;
import m.co.rh.id.aprovider.Provider;

/**
 * Standalone single-page activity for the OS "manage space" entry point
 * (declared through android:manageSpaceActivity in the manifest): the
 * navigator installs the {@link m.co.rh.id.a_jarwis.settings.ui.page.StoragePage}
 * ({@code standalone = true}) as the initial route, the activity itself only
 * wires the edge-to-edge/theme/navigator plumbing exactly like
 * {@link MainActivity} — the content view is NOT inflated here, it is
 * installed by the navigator. The activity-scoped provider (created in
 * onCreate, disposed in onDestroy) is separate from the app-scoped one,
 * which is only used for the application-level settings/navigator lookups.
 */
public class ManageSpaceActivity extends AppCompatActivity {

    private Provider mProvider;
    private RxDisposer mRxDisposer;
    private SettingsSharedPreferences mSettingsSharedPreferences;
    private PublishSubject<Boolean> mRebuildUi;
    private INavigator mNavigator;
    private NavOnRouteChangedListener mRouteChangedListener;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        mProvider = Provider.createProvider(this, new RxProviderModule());
        mRxDisposer = mProvider.get(RxDisposer.class);
        mSettingsSharedPreferences = BaseApplication.of(this).getProvider()
                .get(SettingsSharedPreferences.class);
        // apply Material You dynamic colors before super.onCreate installs the
        // theme; gated on both the user setting and the device support
        // (mirrors MainActivity)
        if (mSettingsSharedPreferences.isDynamicColorsEnabled()
                && DynamicColors.isDynamicColorAvailable()) {
            getTheme().applyStyle(m.co.rh.id.a_jarwis.base.R.style.Theme_Asnapblur_Overlay_DynamicColors, true);
        }
        mRebuildUi = PublishSubject.create();
        // the manifest activity declaration carries the same configChanges
        // list as MainActivity, so a uiMode change does NOT recreate this
        // activity — the MainActivity rebuildUi machinery is therefore kept
        // (not skipped) to re-apply the dynamic-color overlay and rebuild the
        // route views for the new configuration
        mRxDisposer
                .add("onCreate_rebuildUI", mRebuildUi.debounce(100, TimeUnit.MILLISECONDS)
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(aBoolean -> {
                            if (aBoolean) {
                                // mirrors the onCreate applyStyle gate: re-apply the
                                // overlay to re-resolve its DayNight variant
                                // (light/dark) for the new configuration before
                                // views rebuild
                                if (mSettingsSharedPreferences.isDynamicColorsEnabled()
                                        && DynamicColors.isDynamicColorAvailable()) {
                                    getTheme().applyStyle(m.co.rh.id.a_jarwis.base.R.style.Theme_Asnapblur_Overlay_DynamicColors, true);
                                }
                                // the window background drawable was instantiated under the
                                // previous configuration and is never re-created by applyStyle,
                                // so re-resolve it explicitly
                                TypedValue windowBackground = new TypedValue();
                                getTheme().resolveAttribute(android.R.attr.windowBackground, windowBackground, true);
                                if (windowBackground.type >= TypedValue.TYPE_FIRST_COLOR_INT
                                        && windowBackground.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                                    getWindow().setBackgroundDrawable(new ColorDrawable(windowBackground.data));
                                } else if (windowBackground.resourceId != 0) {
                                    getWindow().setBackgroundDrawableResource(windowBackground.resourceId);
                                }
                                BaseApplication.of(this).getNavigator(this).reBuildAllRoute();
                                // reBuildAllRoute re-installs the activity content view while the
                                // activity is running; the new hierarchy misses the initial inset
                                // dispatch, so request one explicitly or content lays out under
                                // the transparent status bar (edge-to-edge)
                                getWindow().getDecorView().requestApplyInsets();
                            }
                        })
                );
        mRxDisposer.add("onCreate_onSelectedThemeChanged",
                mSettingsSharedPreferences.getSelectedThemeFlow().observeOn(AndroidSchedulers.mainThread())
                        .subscribe(AppCompatDelegate::setDefaultNightMode));

        // predictive back wiring copied from MainActivity: one lifetime-long
        // callback, the enabled state synced at route changes (the single sync point)
        mNavigator = BaseApplication.of(this).getNavigator(this);
        final OnBackPressedCallback callback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                mNavigator.onBackPressed();
            }

            @Override
            public void handleOnBackStarted(@NonNull BackEventCompat backEvent) {
                mNavigator.onBackStarted(toNavBackEvent(backEvent));
            }

            @Override
            public void handleOnBackProgressed(@NonNull BackEventCompat backEvent) {
                mNavigator.onBackProgressed(toNavBackEvent(backEvent));
            }

            @Override
            public void handleOnBackCancelled() {
                mNavigator.onBackCancelled();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, callback);
        mRouteChangedListener = (previous, current) -> callback.setEnabled(mNavigator.shouldInterceptBack());
        mNavigator.addOnRouteChangedListener(mRouteChangedListener);
        callback.setEnabled(mNavigator.shouldInterceptBack());
        super.onCreate(savedInstanceState);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // this is required to let navigator handle onActivityResult
        BaseApplication.of(this).getNavigator(this).onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        BaseApplication.of(this).getNavigator(this).onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // using AppCompatDelegate.setDefaultNightMode trigger this method
        // but not triggering Application.onConfigurationChanged
        mRebuildUi.onNext(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // leak guard: the navigator is application-scoped and outlives this
        // activity, the route-changed listener must not retain it
        if (mRouteChangedListener != null && mNavigator != null) {
            mNavigator.removeOnRouteChangedListener(mRouteChangedListener);
            mRouteChangedListener = null;
        }
        mProvider.dispose();
        mProvider = null;
        mRebuildUi.onComplete();
        mRebuildUi = null;
    }

    private static NavBackEvent toNavBackEvent(BackEventCompat backEvent) {
        return new NavBackEvent(
                backEvent.getTouchX(),
                backEvent.getTouchY(),
                backEvent.getProgress());
    }
}
