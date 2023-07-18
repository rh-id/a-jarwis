package m.co.rh.id.a_jarwis.app.ui.imageitem;


import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import m.co.rh.id.a_jarwis.app.ui.imageitem.model.ImageItem;
import m.co.rh.id.anavigator.StatefulView;
import m.co.rh.id.anavigator.component.INavigator;

public class ImageListAdapter extends ListAdapter<ImageItem, ImageListAdapter.ImageListViewHolder> {

    private INavigator mNavigator;
    private StatefulView mParent;
    private List<StatefulView> mSvList;

    public ImageListAdapter(INavigator navigator, StatefulView parent) {
        super(new DiffImageItem());
        mNavigator = navigator;
        mParent = parent;
        mSvList = new ArrayList<>();
    }

    public void dispose(Activity activity) {
        if (!mSvList.isEmpty()) {
            for (StatefulView statefulView : mSvList) {
                statefulView.dispose(activity);
            }
        }
    }

    @NonNull
    @Override
    public ImageListViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ImageItemSV imageItemSV = new ImageItemSV();
        mSvList.add(imageItemSV);
        mNavigator.injectRequired(mParent, imageItemSV);
        View view = imageItemSV.createView(mNavigator.getActivity(), parent);
        return new ImageListViewHolder(view, imageItemSV);
    }

    @Override
    public void onBindViewHolder(@NonNull ImageListViewHolder holder, int position) {
        holder.mImageItemSV.setImageItem(getItem(position));
    }


    public static class ImageListViewHolder extends RecyclerView.ViewHolder {

        private ImageItemSV mImageItemSV;

        public ImageListViewHolder(@NonNull View itemView, ImageItemSV imageItemSV) {
            super(itemView);
            mImageItemSV = imageItemSV;
        }

        private void setItem(ImageItem imageItem) {
            mImageItemSV.setImageItem(imageItem);
        }
    }

    private static class DiffImageItem extends DiffUtil.ItemCallback<ImageItem> {
        @Override
        public boolean areItemsTheSame(@NonNull ImageItem oldItem, @NonNull ImageItem newItem) {
            return oldItem.hashCode() == newItem.hashCode();
        }

        @Override
        public boolean areContentsTheSame(@NonNull ImageItem oldItem, @NonNull ImageItem newItem) {
            return !oldItem.equals(newItem);
        }
    }
}
