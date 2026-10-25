package local.mintv.ui;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import local.mintv.R;
import local.mintv.data.Channel;

/**
 * DPAD-first grid adapter. Cards are focusable roots; focus animates a modest scale and
 * the magenta ring comes from {@code bg_channel_card}. All touch listeners are incidental.
 */
public final class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.ChannelHolder> {

    public interface OnChannelClick {
        void onChannelClick(Channel channel);
    }

    private static final float FOCUS_SCALE = 1.035f;

    private final List<Channel> channels = new ArrayList<>();
    private final OnChannelClick listener;

    public ChannelAdapter(OnChannelClick listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    @SuppressLint("NotifyDataSetChanged")
    public void submit(List<Channel> newChannels) {
        channels.clear();
        if (newChannels != null) {
            channels.addAll(newChannels);
        }
        notifyDataSetChanged();
    }

    /** True when a refresh produced the same line-up, so the grid need not be rebound. */
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
        holder.bind(channels.get(position));
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
        private final TextView free;
        private final float elevation;
        private Channel channel;

        ChannelHolder(View itemView, OnChannelClick listener) {
            super(itemView);
            logo = itemView.findViewById(R.id.channel_logo);
            number = itemView.findViewById(R.id.channel_number);
            title = itemView.findViewById(R.id.channel_title);
            quality = itemView.findViewById(R.id.channel_quality);
            free = itemView.findViewById(R.id.channel_free);
            elevation = 10f * itemView.getResources().getDisplayMetrics().density;

            itemView.setOnClickListener(view -> {
                if (channel != null) {
                    listener.onChannelClick(channel);
                }
            });
            itemView.setOnFocusChangeListener((view, hasFocus) -> {
                float scale = hasFocus ? FOCUS_SCALE : 1f;
                view.animate()
                        .scaleX(scale)
                        .scaleY(scale)
                        .setDuration(hasFocus ? 130L : 100L)
                        .start();
                view.setElevation(hasFocus ? elevation : 0f);
            });
        }

        void bind(Channel newChannel) {
            this.channel = newChannel;
            // Reset any scale/elevation left over from a recycled focused row, while
            // preserving the focused look if this holder is the one currently focused.
            boolean focused = itemView.isFocused();
            float scale = focused ? FOCUS_SCALE : 1f;
            itemView.setScaleX(scale);
            itemView.setScaleY(scale);
            itemView.setElevation(focused ? elevation : 0f);

            number.setText(newChannel.getNumber() >= 0
                    ? String.valueOf(newChannel.getNumber())
                    : "–");
            title.setText(newChannel.getTitle());
            quality.setText(newChannel.getQuality());
            quality.setVisibility(newChannel.getQuality().isEmpty()
                    ? View.GONE
                    : View.VISIBLE);
            free.setVisibility(newChannel.isFreeToAir() ? View.VISIBLE : View.GONE);
            itemView.setContentDescription(itemView.getContext().getString(
                    R.string.channel_description,
                    newChannel.getTitle(),
                    newChannel.getNumber()));
            ImageLoader.load(logo, newChannel.getLogoUrl());
        }
    }
}
