package com.opendashcam;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.opendashcam.models.Recording;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Adapter for the recordings browser. Displays a mix of folders (dated / hourly) and video
 * clips for the current directory, and supports a multi-select mode for deletion.
 */
public class ViewRecordingsRecyclerViewAdapter extends RecyclerView
        .Adapter<ViewRecordingsRecyclerViewAdapter
        .RecordingHolder> {

    public interface ItemListener {
        void onItemClick(File file);

        void onItemLongClick(File file);
    }

    private final Context mContext;
    private final ItemListener mListener;
    private final List<File> mItems = new ArrayList<>();
    private final Set<String> mSelectedPaths = new HashSet<>();
    private boolean mSelectionMode = false;
    private final int mWidth, mHeight;

    ViewRecordingsRecyclerViewAdapter(Context context, ItemListener listener) {
        mContext = context;
        mListener = listener;
        mWidth = (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, 150, context.getResources().getDisplayMetrics());
        mHeight = (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, 100, context.getResources().getDisplayMetrics());
    }

    void setItems(List<File> files) {
        mItems.clear();
        if (files != null) mItems.addAll(files);
        // Drop selections that are no longer present
        Set<String> present = new HashSet<>();
        for (File f : mItems) present.add(f.getAbsolutePath());
        mSelectedPaths.retainAll(present);
        notifyDataSetChanged();
    }

    boolean isSelectionMode() {
        return mSelectionMode;
    }

    void setSelectionMode(boolean on) {
        mSelectionMode = on;
        if (!on) mSelectedPaths.clear();
        notifyDataSetChanged();
    }

    void toggleSelection(File file) {
        String p = file.getAbsolutePath();
        if (mSelectedPaths.contains(p)) {
            mSelectedPaths.remove(p);
        } else {
            mSelectedPaths.add(p);
        }
        notifyDataSetChanged();
    }

    void selectAll() {
        mSelectedPaths.clear();
        for (File f : mItems) mSelectedPaths.add(f.getAbsolutePath());
        notifyDataSetChanged();
    }

    int getSelectedCount() {
        return mSelectedPaths.size();
    }

    List<File> getSelectedFiles() {
        List<File> out = new ArrayList<>();
        for (File f : mItems) {
            if (mSelectedPaths.contains(f.getAbsolutePath())) out.add(f);
        }
        return out;
    }

    private boolean isSelected(File file) {
        return mSelectedPaths.contains(file.getAbsolutePath());
    }

    @Override
    public RecordingHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.view_recordings_row, parent, false);
        return new RecordingHolder(view);
    }

    @Override
    public void onBindViewHolder(RecordingHolder holder, int position) {
        final int adapterPosition = holder.getAdapterPosition();
        if (adapterPosition < 0 || adapterPosition >= mItems.size()) return;
        final File item = mItems.get(adapterPosition);

        // Selection highlight
        holder.itemView.setBackgroundColor(
                isSelected(item)
                        ? ContextCompat.getColor(mContext, R.color.colorSelected)
                        : ContextCompat.getColor(mContext, android.R.color.transparent));

        if (item.isDirectory()) {
            bindFolder(holder, item);
        } else {
            bindVideo(holder, item);
        }

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mListener != null) mListener.onItemClick(item);
            }
        });
        holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (mListener != null) mListener.onItemLongClick(item);
                return true;
            }
        });
    }

    private void bindFolder(RecordingHolder holder, File folder) {
        holder.starred.setVisibility(View.GONE);
        holder.starred.setOnCheckedChangeListener(null);

        holder.thumbnail.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        holder.thumbnail.setImageResource(R.drawable.ic_folder);

        holder.label.setText(folder.getName());

        File[] children = folder.listFiles();
        int count = children != null ? children.length : 0;
        holder.dateTime.setText(count + (count == 1 ? " item" : " items"));
    }

    private void bindVideo(RecordingHolder holder, File file) {
        final Recording recording = new Recording(file.getAbsolutePath());

        holder.label.setText(recording.getDateSaved());
        holder.dateTime.setText(recording.getTimeSaved());

        // Show the star only in normal mode; hide it while multi-selecting
        if (mSelectionMode) {
            holder.starred.setVisibility(View.GONE);
            holder.starred.setOnCheckedChangeListener(null);
        } else {
            holder.starred.setVisibility(View.VISIBLE);
            holder.starred.setOnCheckedChangeListener(null);
            holder.starred.setChecked(recording.isStarred());
            holder.starred.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    if (buttonView.isPressed()) {
                        recording.toggleStar(isChecked);
                    }
                }
            });
        }

        holder.thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Glide.with(mContext)
                .load(file.getAbsolutePath())
                .dontAnimate()
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .placeholder(R.drawable.ic_videocam_red_128dp)
                .override(mWidth, mHeight)
                .into(holder.thumbnail);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    static class RecordingHolder extends RecyclerView.ViewHolder {
        ImageView thumbnail;
        TextView label;
        TextView dateTime;
        CheckBox starred;

        RecordingHolder(View itemView) {
            super(itemView);
            thumbnail = itemView.findViewById(R.id.thumbnail);
            label = itemView.findViewById(R.id.recordingDate);
            dateTime = itemView.findViewById(R.id.recordingTime);
            starred = itemView.findViewById(R.id.starred);
        }
    }
}
