package m.co.rh.id.a_jarwis.app;

import android.app.Activity;

import androidx.annotation.NonNull;
import androidx.work.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import m.co.rh.id.a_jarwis.app.provider.AppProviderModule;
import m.co.rh.id.a_jarwis.app.provider.NavigatorProvider;
import m.co.rh.id.a_jarwis.base.BaseApplication;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.anavigator.component.INavigator;
import m.co.rh.id.aprovider.Provider;

public class MainApplication extends BaseApplication implements Configuration.Provider {

    private Provider mProvider;

    @Override
    public void onCreate() {
        super.onCreate();
        mProvider = Provider.createProvider(this, new AppProviderModule(this));
        final Thread.UncaughtExceptionHandler defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            mProvider.get(ILogger.class)
                    .e("MainApplication", "App crash: " + throwable.getMessage(), throwable);
            mProvider.dispose();
            if (defaultExceptionHandler != null) {
                defaultExceptionHandler.uncaughtException(thread, throwable);
            } else {
                System.exit(99);
            }
        });
    }

    @Override
    public Provider getProvider() {
        return mProvider;
    }

    public INavigator getNavigator(Activity activity) {
        return mProvider.get(NavigatorProvider.class).getNavigator(activity);
    }

    @NonNull
    @Override
    public Configuration getWorkManagerConfiguration() {
        return new Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.INFO)
                .setExecutor(mProvider.get(ScheduledExecutorService.class))
                .setTaskExecutor(mProvider.get(ExecutorService.class))
                .build();
    }
}
