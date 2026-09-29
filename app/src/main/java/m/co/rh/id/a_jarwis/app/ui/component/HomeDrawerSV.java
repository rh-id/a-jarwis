package m.co.rh.id.a_jarwis.app.ui.component;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.drawerlayout.widget.DrawerLayout;

import m.co.rh.id.a_jarwis.R;
import m.co.rh.id.anavigator.StatefulView;

public class HomeDrawerSV extends StatefulView<Activity> implements DrawerLayout.DrawerListener, View.OnClickListener {
    private transient DrawerLayout mDrawerLayout;
    private transient View.OnClickListener mOnSettingsClickListener;
    private transient View.OnClickListener mOnDonationsClickListener;
    private boolean mIsDrawerOpen;

    @Override
    protected View createView(Activity activity, ViewGroup container) {
        View view = activity.getLayoutInflater().inflate(R.layout.home_drawer_menu, container, false);
        View menuSettings = view.findViewById(R.id.menu_settings);
        menuSettings.setOnClickListener(this);
        View menuDonation = view.findViewById(R.id.menu_donation);
        menuDonation.setOnClickListener(this);
        return view;
    }

    @Override
    public void dispose(Activity activity) {
        super.dispose(activity);
        mDrawerLayout = null;
        mOnSettingsClickListener = null;
        mOnDonationsClickListener = null;
    }

    public void attachDrawer(DrawerLayout drawerLayout) {
        mDrawerLayout = drawerLayout;
        mDrawerLayout.addDrawerListener(this);
        if (mIsDrawerOpen) {
            mDrawerLayout.open();
        }
    }

    public void setOnSettingsClickListener(View.OnClickListener onSettingsClickListener) {
        mOnSettingsClickListener = onSettingsClickListener;
    }

    public void setOnDonationsClickListener(View.OnClickListener onDonationsClickListener) {
        mOnDonationsClickListener = onDonationsClickListener;
    }

    public void open() {
        if (mDrawerLayout != null && !mDrawerLayout.isOpen()) {
            mDrawerLayout.open();
        }
    }

    public void close() {
        if (mDrawerLayout != null) {
            mDrawerLayout.close();
        }
    }

    public boolean isOpen() {
        return mDrawerLayout != null && mDrawerLayout.isOpen();
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        if (id == R.id.menu_settings) {
            if (mOnSettingsClickListener != null) {
                mOnSettingsClickListener.onClick(view);
            }
        } else if (id == R.id.menu_donation) {
            if (mOnDonationsClickListener != null) {
                mOnDonationsClickListener.onClick(view);
            }
        }
    }

    @Override
    public void onDrawerSlide(@NonNull View drawerView, float slideOffset) {
        // Leave blank
    }

    @Override
    public void onDrawerOpened(@NonNull View drawerView) {
        mIsDrawerOpen = true;
    }

    @Override
    public void onDrawerClosed(@NonNull View drawerView) {
        mIsDrawerOpen = false;
    }

    @Override
    public void onDrawerStateChanged(int newState) {
        // Leave blank
    }
}
