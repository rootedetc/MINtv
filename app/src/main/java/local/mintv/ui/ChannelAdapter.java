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
import java.util.Locale;

import local.mintv.R;
import local.mintv.data.Channel;
import local.mintv.epg.EpgCache;
import local.mintv.epg.EpgProgram;

/** Channel cards with the current programme and a live progress line. */
public final class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.ChannelHolder> {

    public interface OnChannelClick {
        void onChannelClick(Channel channel);
    }

    public interface OnChannelLongClick {
        void onChannelLongClick(Channel channel);
    }

    private final List<Channel> channels = new ArrayList<>();
    private final OnChannelClick listener;
    private OnChannelLongClick longClickListener;
    private int spanCount = 6;
    private int railFocusId = View.NO_ID;
    private java.util.Set<Integer> favourites = java.util.Collections.emptySet();
    private boolean favouriteKnown;
    private java.util.Set<Integer> subscriptions = java.util.Collections.emptySet();
    private boolean subscriptionKnown;

    public ChannelAdapter(OnChannelClick listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    public ChannelAdapter(OnChannelClick listener, OnChannelLongClick longClickListener) {
        this(listener);
        this.longClickListener = longClickListener;
    }

    public void setFavouriteState(java.util.Set<Integer> numbers, boolean known) {
        favourites = numbers == null ? java.util.Collections.emptySet() : numbers;
        favouriteKnown = known;
        notifyDataSetChanged();
    }

    /** Marks the channels the signed-in account cannot watch; unknown state never locks. */
    public void setSubscriptionState(java.util.Set<Integer> numbers, boolean known) {
        subscriptions = numbers == null ? java.util.Collections.emptySet() : numbers;
        subscriptionKnown = known;
        notifyDataSetChanged();
    }

    public void setLongClickListener(OnChannelLongClick listener) {
        this.longClickListener = listener;
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

    /** Moves one row in the local reorder preview. */
    public int move(int from, int to) {
        if (from < 0 || from >= channels.size() || to < 0 || to >= channels.size()
                || from == to) {
            return from;
        }
        Channel channel = channels.remove(from);
        channels.add(to, channel);
        notifyItemMoved(from, to);
        return to;
    }

    public int indexOf(Channel channel) {
        return channels.indexOf(channel);
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
        holder.bind(channels.get(position), position, spanCount, railFocusId,
                favourites, favouriteKnown, subscriptions, subscriptionKnown, longClickListener);
    }

    @Override
    public int getItemCount() {
        return channels.size();
    }

    static final class ChannelHolder extends RecyclerView.ViewHolder {

        private final ImageView logo;
        private final ImageView favourite;
        private final ImageView lock;
        private final TextView number;
        private final TextView title;
        private final TextView quality;
        private final TextView now;
        private final ProgressBar progress;
        private Channel channel;

        ChannelHolder(View itemView, OnChannelClick listener) {
            super(itemView);
            logo = itemView.findViewById(R.id.channel_logo);
            favourite = itemView.findViewById(R.id.channel_favourite);
            lock = itemView.findViewById(R.id.channel_lock);
            number = itemView.findViewById(R.id.channel_number);
            title = itemView.findViewById(R.id.channel_title);
            quality = itemView.findViewById(R.id.channel_quality);
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

        void bind(Channel newChannel, int position, int spanCount, int railFocusId,
                  java.util.Set<Integer> favourites, boolean favouriteKnown,
                  java.util.Set<Integer> subscriptions, boolean subscriptionKnown,
                  OnChannelLongClick longClickListener) {
            this.channel = newChannel;
            boolean focused = itemView.isFocused();
            itemView.setScaleX(focused ? 1.035f : 1f);
            itemView.setScaleY(focused ? 1.035f : 1f);

            boolean isFavourite = favourites.contains(newChannel.getNumber());
            favourite.setVisibility(favouriteKnown && isFavourite
                    ? View.VISIBLE : View.GONE);
            favourite.setImageResource(R.drawable.ic_favorite);
            favourite.setColorFilter(Tv.BRAND_BRIGHT);

            boolean locked = subscriptionKnown && !newChannel.isFreeToAir()
                    && !subscriptions.contains(newChannel.getNumber());
            lock.setVisibility(locked ? View.VISIBLE : View.GONE);
            lock.setImageResource(R.drawable.ic_lock);
            lock.setColorFilter(Tv.TEXT_2);
            itemView.setOnLongClickListener(view -> {
                if (longClickListener != null) {
                    longClickListener.onChannelLongClick(newChannel);
                    return true;
                }
                return false;
            });

            number.setText(newChannel.getNumber() >= 0
                    ? String.valueOf(newChannel.getNumber()) : "–");
            title.setText(newChannel.getTitle(itemView.getContext()));
            String cardQuality = qualityLabel(newChannel.getQuality());
            quality.setText(cardQuality);
            quality.setVisibility(cardQuality.isEmpty() ? View.GONE : View.VISIBLE);
            String description = itemView.getContext().getString(
                    R.string.channel_description,
                    newChannel.getTitle(itemView.getContext()),
                    newChannel.getNumber());
            if (newChannel.isFreeToAir()) {
                description += ", " + itemView.getContext().getString(
                        R.string.channel_free_accessibility);
            }
            if (locked) {
                description += ", " + itemView.getContext().getString(
                        R.string.channel_locked_accessibility);
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

        /**
         * The card-sized quality label. Empty quality means there is nothing truthful to show.
         * FHD/1080 and UHD/4K collapse to one label each; unknown values pass through uppercase.
         */
        private static String qualityLabel(String quality) {
            if (quality == null) {
                return "";
            }
            String value = quality.trim().toUpperCase(Locale.US);
            if (value.isEmpty()) {
                return "";
            }
            if (value.contains("UHD") || value.contains("4K")) {
                return "4K";
            }
            if (value.contains("FHD") || value.contains("1080") || value.contains("FULL HD")) {
                return "FULL HD";
            }
            if (value.contains("HD")) {
                return "HD";
            }
            if (value.contains("SD")) {
                return "SD";
            }
            return value;
        }
    }
}
