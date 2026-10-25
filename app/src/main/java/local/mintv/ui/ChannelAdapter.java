package local.mintv.ui;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import local.mintv.R;
import local.mintv.data.Channel;
import local.mintv.epg.EpgCache;
import local.mintv.epg.EpgProgram;

/** Channel cards with the current programme and a live progress line. */
public final class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.ChannelHolder> {

    public interface OnChannelClick {
        void onChannelClick(Channel channel);
    }

    private final List<Channel> channels = new ArrayList<>();
    private final OnChannelClick listener;
    private int spanCount = 6;
    private int railFocusId = View.NO_ID;

    public ChannelAdapter(OnChannelClick listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    public void setSpanCount(int spanCount) {
        this.spanCount = Math.max(1, spanCount);
    }

    public void setRailFocusId(int railFocusId) {
        this.railFocusId = railFocusId;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void submit(List<Channel> newChannels) {
        channels.clear();
        if (newChannels != null) {
            channels.addAll(newChannels);
        }
        notifyDataSetChanged();
    }

    /** Refresh the now/next line without rebuilding the cards or moving focus. */
    @SuppressLint("NotifyDataSetChanged")
    public void refreshNow() {
        notifyDataSetChanged();
    }

    public boolean hasSameContent(List<Channel> other) {
        if (other == null || other.size() != channels.size()) {
            return false;
        }
        for (int i = 0; i < channels.size(); i++) {
            Channel left = channels.get(i);
            Channel right = other.get(i);
            if (left.getNumber() != right.getNumber()
                    || !left.getTitle().equals(right.getTitle())
                    || !left.getLogoUrl().equals(right.getLogoUrl())
                    || !left.getDashUrl().equals(right.getDashUrl())) {
                return false;
            }
        }
        return true;
    }

    public List<Channel> current() {
        return Collections.unmodifiableList(channels);
    }

    @Override
    public long getItemId(int position) {
        Channel channel = channels.get(position);
        String identity = channel.getStationId().isEmpty()
                ? channel.getTitle() + "#" + channel.getNumber()
                : channel.getStationId();
        return identity.hashCode();
    }

    @NonNull
    @Override
    public ChannelHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_channel, parent, false);
        return new ChannelHolder(view, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull ChannelHolder holder, int position) {
        holder.bind(channels.get(position), position, spanCount, railFocusId);
    }

    @Override
    public int getItemCount() {
        return channels.size();
    }

    static final class ChannelHolder extends RecyclerView.ViewHolder {

        private final ImageView logo;
        private final TextView number;
        private final TextView title;
        private final TextView quality;
        private final View free;
        private final TextView now;
        private final ProgressBar progress;
        private Channel channel;

        ChannelHolder(View itemView, OnChannelClick listener) {
            super(itemView);
            logo = itemView.findViewById(R.id.channel_logo);
            number = itemView.findViewById(R.id.channel_number);
            title = itemView.findViewById(R.id.channel_title);
            quality = itemView.findViewById(R.id.channel_quality);
            free = itemView.findViewById(R.id.channel_free);
            now = itemView.findViewById(R.id.channel_now);
            progress = itemView.findViewById(R.id.channel_progress);

            Tv.focusScale(itemView, 1.035f);
            itemView.setOnClickListener(view -> {
                if (channel != null) {
                    listener.onChannelClick(channel);
                }
            });
            itemView.setOnFocusChangeListener((view, hasFocus) -> {
                view.animate()
                        .scaleX(hasFocus ? 1.035f : 1f)
                        .scaleY(hasFocus ? 1.035f : 1f)
                        .setDuration(hasFocus ? 140L : 110L)
                        .start();
                view.setElevation(hasFocus
                        ? Tv.dpf(view.getContext(), 8f) : 0f);
            });
        }

        void bind(Channel newChannel, int position, int spanCount, int railFocusId) {
            this.channel = newChannel;
            boolean focused = itemView.isFocused();
            itemView.setScaleX(focused ? 1.035f : 1f);
            itemView.setScaleY(focused ? 1.035f : 1f);

            number.setText(newChannel.getNumber() >= 0
                    ? String.valueOf(newChannel.getNumber()) : "–");
            title.setText(newChannel.getTitle());
            quality.setText(newChannel.getQuality());
            quality.setVisibility(newChannel.getQuality().isEmpty()
                    ? View.GONE : View.VISIBLE);
            free.setVisibility(newChannel.isFreeToAir() ? View.VISIBLE : View.GONE);
            String description = itemView.getContext().getString(
                    R.string.channel_description,
                    newChannel.getTitle(),
                    newChannel.getNumber());
            if (newChannel.isFreeToAir()) {
                description += ", " + itemView.getContext().getString(
                        R.string.channel_free_accessibility);
            }
            itemView.setContentDescription(description);

            long nowMs = System.currentTimeMillis();
            EpgProgram programme = EpgCache.programmeAt(newChannel.getNumber(), nowMs);
            if (programme == null) {
                now.setVisibility(View.GONE);
                progress.setVisibility(View.GONE);
            } else {
                now.setVisibility(View.VISIBLE);
                now.setText(programme.title);
                now.setTextColor(programme.isLive(nowMs) ? Tv.TEXT : Tv.TEXT_3);
                progress.setProgress(Math.round(programme.progress(nowMs) * 1000f));
                progress.setVisibility(View.VISIBLE);
            }

            if (position % spanCount == 0 && railFocusId != View.NO_ID) {
                itemView.setNextFocusLeftId(railFocusId);
            } else {
                itemView.setNextFocusLeftId(View.NO_ID);
            }

            ImageLoader.load(logo, newChannel.getLogoUrl());
        }
    }
}
