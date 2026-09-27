package m.co.rh.id.a_jarwis.app;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.subjects.PublishSubject;
import m.co.rh.id.a_jarwis.base.BaseApplication;
import m.co.rh.id.a_jarwis.base.provider.RxProviderModule;
import m.co.rh.id.a_jarwis.base.rx.RxDisposer;
import m.co.rh.id.a_jarwis.settings.provider.component.SettingsSharedPreferences;
import m.co.rh.id.aprovider.Provider;

public class MainActivity extends AppCompatActivity {

    private Provider mProvider;
    private RxDisposer mRxDisposer;
    private SettingsSharedPreferences mSettingsSharedPreferences;
    private PublishSubject<Boolean> mRebuildUi;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        mProvider = Provider.createProvider(this, new RxProviderModule());
        mRxDisposer = mProvider.get(RxDisposer.class);
        mSettingsSharedPreferences = BaseApplication.of(this).getProvider()
                .get(SettingsSharedPreferences.class);
        mRebuildUi = PublishSubject.create();
        // rebuild UI is expensive and error prone, avoid spam rebuild (especially due to day and night mode)
        mRxDisposer
                .add("onCreate_rebuildUI", mRebuildUi.debounce(100, TimeUnit.MILLISECONDS)
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(aBoolean -> {
                            if (aBoolean) {
                                BaseApplication.of(this).getNavigator(this).reBuildAllRoute();
                                // Switching to night mode didn't update window background for some reason?
                                // seemed to occur on android 8 and below
                                getWindow().setBackgroundDrawableResource(m.co.rh.id.a_jarwis.base.R.color.daynight_white_black);
                            }
                        })
                );
        mRxDisposer.add("onCreate_onSelectedThemeChanged",
                mSettingsSharedPreferences.getSelectedThemeFlow().observeOn(AndroidSchedulers.mainThread())
                        .subscribe(AppCompatDelegate::setDefaultNightMode));

        getOnBackPressedDispatcher().addCallback(this,
                new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        BaseApplication.of(MainActivity.this)
                                .getNavigator(MainActivity.this).onBackPressed();
                    }
                });
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
        mProvider.dispose();
        mProvider = null;
        mRebuildUi.onComplete();
        mRebuildUi = null;
    }
}