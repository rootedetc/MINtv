package local.mintv.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** RecyclerView adapter for {@link ProgramCard} and {@link ChannelCard}; rails and grids. */
public final class ProgramAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_POSTER = 0;
    private static final int TYPE_CHANNEL = 1;

    public interface Listener {
        void onCard(CardItem item);

        default void onCardLongClick(CardItem item) {
        }
    }

    private final List<CardItem> items = new ArrayList<>();
    private final Listener listener;
    private final Set<String> selected = new LinkedHashSet<>();
    private boolean selectionMode;
    private int railFocusId = View.NO_ID;

    public ProgramAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    public void setRailFocusId(int railFocusId) {
        this.railFocusId = railFocusId;
    }

    public void submit(List<CardItem> next) {
        items.clear();
        if (next != null) {
            items.addAll(next);
        }
        selected.clear();
        notifyDataSetChanged();
    }

    public void append(List<CardItem> next) {
        if (next == null || next.isEmpty()) {
            return;
        }
        int start = items.size();
        items.addAll(next);
        notifyItemRangeInserted(start, next.size());
    }

    public List<CardItem> items() {
        return Collections.unmodifiableList(items);
    }

    public void setSelectionMode(boolean enabled) {
        selectionMode = enabled;
        if (!enabled) {
            selected.clear();
        }
        notifyDataSetChanged();
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public void toggle(String id) {
        if (id == null) {
            return;
        }
        if (!selected.remove(id)) {
            selected.add(id);
        }
        notifyDataSetChanged();
    }

    public List<CardItem> selectedItems() {
        List<CardItem> out = new ArrayList<>();
        for (CardItem item : items) {
            if (selected.contains(item.id)) {
                out.add(item);
            }
        }
        return out;
    }

    public void selectAll(boolean select) {
        selected.clear();
        if (select) {
            for (CardItem item : items) {
                selected.add(item.id);
            }
        }
        notifyDataSetChanged();
    }

    public int selectedCount() {
        return selected.size();
    }

    @Override
    public long getItemId(int position) {
        CardItem item = items.get(position);
        return item.id == null ? position : item.id.hashCode();
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).channel ? TYPE_CHANNEL : TYPE_POSTER;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_CHANNEL) {
            ChannelCard card = new ChannelCard(parent.getContext());
            card.setLayoutParams(new RecyclerView.LayoutParams(
                    Tv.dp(parent.getContext(), ChannelCard.WIDTH_DP),
                    Tv.dp(parent.getContext(), ChannelCard.HEIGHT_DP)));
            return new ChannelHolder(card);
        }
        ProgramCard card = new ProgramCard(parent.getContext());
        card.setLayoutParams(new RecyclerView.LayoutParams(
                Tv.dp(parent.getContext(), ProgramCard.WIDTH_DP),
                Tv.dp(parent.getContext(), ProgramCard.HEIGHT_DP)));
        return new Holder(card);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        CardItem item = items.get(position);
        View card;
        if (holder instanceof ChannelHolder) {
            ChannelCard channelCard = ((ChannelHolder) holder).card;
            channelCard.bind(item);
            card = channelCard;
        } else {
            ProgramCard poster = ((Holder) holder).card;
            poster.bind(item);
            poster.setSelected(selected.contains(item.id));
            card = poster;
        }
        card.setOnClickListener(view -> {
            if (selectionMode) {
                toggle(item.id);
            } else {
                listener.onCard(item);
            }
        });
        card.setOnLongClickListener(view -> {
            listener.onCardLongClick(item);
            return true;
        });
        if (position % 6 == 0 && railFocusId != View.NO_ID) {
            card.setNextFocusLeftId(railFocusId);
        } else {
            card.setNextFocusLeftId(View.NO_ID);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ProgramCard card;

        Holder(ProgramCard card) {
            super(card);
            this.card = card;
        }
    }

    static final class ChannelHolder extends RecyclerView.ViewHolder {
        final ChannelCard card;

        ChannelHolder(ChannelCard card) {
            super(card);
            this.card = card;
        }
    }
}
