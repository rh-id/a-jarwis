package m.co.rh.id.a_jarwis.settings.provider.component;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.concurrent.ExecutorService;

import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.subjects.BehaviorSubject;
import m.co.rh.id.aprovider.Provider;

public class SettingsSharedPreferences {
    private static final String SHARED_PREFERENCES_NAME = "SettingsSharedPreferences";
    /** Default: Material You dynamic colors are enabled (when supported by the device). */
    public static final boolean DEFAULT_DYNAMIC_COLORS_ENABLED = true;
    private ExecutorService mExecutorService;
    private SharedPreferences mSharedPreferences;

    private BehaviorSubject<Integer> mSelectedTheme;
    private String mSelectedThemeKey;
    private BehaviorSubject<Boolean> mDynamicColorsEnabled;
    private String mDynamicColorsEnabledKey;

    public SettingsSharedPreferences(Provider provider) {
        mExecutorService = provider.get(ExecutorService.class);
        mSharedPreferences = provider.getContext().getSharedPreferences(
                SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
        mSelectedTheme = BehaviorSubject.createDefault(-1);
        mDynamicColorsEnabled = BehaviorSubject.createDefault(DEFAULT_DYNAMIC_COLORS_ENABLED);
        initValue();
    }

    private void initValue() {
        mSelectedThemeKey = SHARED_PREFERENCES_NAME
                + ".selectedTheme";

        int selectedTheme = mSharedPreferences.getInt(
                mSelectedThemeKey,
                mSelectedTheme.getValue());
        setSelectedTheme(selectedTheme);

        mDynamicColorsEnabledKey = SHARED_PREFERENCES_NAME
                + ".dynamicColorsEnabled";
        boolean dynamicColorsEnabled = mSharedPreferences.getBoolean(
                mDynamicColorsEnabledKey,
                mDynamicColorsEnabled.getValue());
        setDynamicColorsEnabled(dynamicColorsEnabled);
    }

    private void selectedTheme(int setting) {
        mSelectedTheme.onNext(setting);
        mExecutorService.execute(() ->
                mSharedPreferences.edit().putInt(mSelectedThemeKey, setting)
                        .commit());
    }

    public void setSelectedTheme(int setting) {
        selectedTheme(setting);
    }

    public Flowable<Integer> getSelectedThemeFlow() {
        return Flowable.fromObservable(mSelectedTheme, BackpressureStrategy.BUFFER);
    }

    private void dynamicColorsEnabled(boolean enabled) {
        mDynamicColorsEnabled.onNext(enabled);
        mExecutorService.execute(() ->
                mSharedPreferences.edit().putBoolean(mDynamicColorsEnabledKey, enabled)
                        .commit());
    }

    public void setDynamicColorsEnabled(boolean enabled) {
        dynamicColorsEnabled(enabled);
    }

    public boolean isDynamicColorsEnabled() {
        Boolean current = mDynamicColorsEnabled.getValue();
        return current != null ? current : DEFAULT_DYNAMIC_COLORS_ENABLED;
    }

    public Flowable<Boolean> getDynamicColorsEnabledFlow() {
        return Flowable.fromObservable(mDynamicColorsEnabled, BackpressureStrategy.BUFFER);
    }
}
