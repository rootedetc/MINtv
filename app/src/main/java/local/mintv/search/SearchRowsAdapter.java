package local.mintv.search;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import local.mintv.R;
import local.mintv.ui.Tv;

/** Rows used by the search surface: section headings, programs, people, channels and history. */
public final class SearchRowsAdapter extends RecyclerView.Adapter<SearchRowsAdapter.Holder> {

    public static final int TYPE_HEADER = 0;
    public static final int TYPE_PROGRAM = 1;
    public static final int TYPE_PERSON = 2;
    public static final int TYPE_CHANNEL = 3;
    public static final int TYPE_HISTORY = 4;
    public static final int TYPE_ACTION = 5;
    public static final int TYPE_MESSAGE = 6;

    public interface Listener {
        void onRow(SearchRow row);
    }

    private final List<SearchRow> rows = new ArrayList<>();
    private final Listener listener;

    public SearchRowsAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<SearchRow> next) {
        rows.clear();
        if (next != null) {
            rows.addAll(next);
        }
        notifyDataSetChanged();
    }

    public void append(List<SearchRow> next) {
        if (next == null || next.isEmpty()) {
            return;
        }
        int start = rows.size();
        rows.addAll(next);
        notifyItemRangeInserted(start, next.size());
    }

    public List<SearchRow> rows() {
        return rows;
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).type;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = build(parent, viewType);
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                viewType == TYPE_ACTION
                        ? ViewGroup.LayoutParams.WRAP_CONTENT
                        : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        if (viewType == TYPE_PROGRAM || viewType == TYPE_PERSON
                || viewType == TYPE_CHANNEL || viewType == TYPE_HISTORY
                || viewType == TYPE_ACTION) {
            lp.bottomMargin = Tv.dp(parent.getContext(), 8);
        }
        view.setLayoutParams(lp);
        return new Holder(view);
    }

    private View build(ViewGroup parent, int viewType) {
        android.content.Context context = parent.getContext();
        if (viewType == TYPE_HEADER) {
            TextView header = Tv.displayText(context, 19f, Tv.TEXT_2);
            header.setPadding(0, Tv.dp(context, 14), 0, Tv.dp(context, 6));
            return header;
        }
        if (viewType == TYPE_MESSAGE) {
            TextView message = Tv.text(context, 14f, Tv.TEXT_3, false);
            message.setGravity(Gravity.CENTER);
            message.setPadding(0, Tv.dp(context, 28), 0, Tv.dp(context, 28));
            message.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return message;
        }
        if (viewType == TYPE_ACTION) {
            TextView action = Tv.ghost(context, "");
            action.setGravity(Gravity.CENTER_VERTICAL);
            return action;
        }

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(Tv.states(context, Tv.SURFACE, Tv.SURFACE_FOCUS, Tv.SURFACE_3,
                8f, Tv.STROKE, 1f, 0xFFFFFFFF, 2f));
        Tv.pad(row, 14, 9, 16, 9);
        Tv.focusScale(row, 1.01f);

        if (viewType == TYPE_HISTORY) {
            // A history entry is one row: search icon, query, nothing else.
            ImageView icon = Tv.icon(context, R.drawable.ic_search, Tv.TEXT_3, 20f);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                    Tv.dp(context, 34), Tv.dp(context, 34));
            iconLp.rightMargin = Tv.dp(context, 10);
            row.addView(icon, iconLp);

            TextView query = Tv.displayText(context, 18f, Tv.TEXT);
            query.setSingleLine(true);
            query.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(query, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            return row;
        }

        if (viewType == TYPE_PERSON) {
            ImageView avatar = new ImageView(context);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avatar.setBackgroundResource(R.drawable.bg_avatar);
            avatar.setImageResource(R.drawable.ic_person);
            row.addView(avatar, new LinearLayout.LayoutParams(
                    Tv.dp(context, 58), Tv.dp(context, 58)));
        } else {
            FrameLayout frame = new FrameLayout(context);
            frame.setBackground(Tv.box(context, Tv.SURFACE_2, 6f, Tv.STROKE, 1f));
            ImageView image = new ImageView(context);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImageResource(R.drawable.ic_channel_placeholder);
            frame.addView(image, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            int width = viewType == TYPE_CHANNEL ? 96 : 148;
            int height = viewType == TYPE_CHANNEL ? 54 : 84;
            LinearLayout.LayoutParams frameLp = new LinearLayout.LayoutParams(
                    Tv.dp(context, width), Tv.dp(context, height));
            frameLp.rightMargin = Tv.dp(context, 14);
            row.addView(frame, frameLp);
        }

        LinearLayout text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView title = Tv.displayText(context, 18f, Tv.TEXT);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(title);
        TextView meta = Tv.text(context, 12f, Tv.TEXT_3, false);
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = Tv.dp(context, 3);
        text.addView(meta, metaLp);

        TextView badge = Tv.label(context, "", Tv.BRAND_BRIGHT);
        badge.setPadding(Tv.dp(context, 8), Tv.dp(context, 4),
                Tv.dp(context, 8), Tv.dp(context, 4));
        badge.setBackgroundResource(R.drawable.bg_chip_quiet);
        row.addView(badge);
        return row;
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        SearchRow row = rows.get(position);
        holder.bind(row, listener);
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        Holder(View itemView) {
            super(itemView);
        }

        void bind(SearchRow row, Listener listener) {
            if (row.type == TYPE_HEADER || row.type == TYPE_MESSAGE || row.type == TYPE_ACTION) {
                TextView text = (TextView) itemView;
                text.setText(row.title);
                if (row.type == TYPE_ACTION) {
                    text.setOnClickListener(view -> listener.onRow(row));
                }
                return;
            }
            LinearLayout layout = (LinearLayout) itemView;
            if (row.type == TYPE_HISTORY) {
                TextView query = (TextView) layout.getChildAt(1);
                query.setText(row.title);
            } else {
                LinearLayout text = (LinearLayout) layout.getChildAt(1);
                TextView title = (TextView) text.getChildAt(0);
                TextView meta = (TextView) text.getChildAt(1);
                title.setText(row.title);
                meta.setText(row.subtitle);
                meta.setVisibility(row.subtitle.isEmpty() ? View.GONE : View.VISIBLE);

                if (row.type == TYPE_PERSON) {
                    ImageView avatar = (ImageView) layout.getChildAt(0);
                    local.mintv.ui.ImageLoader.load(avatar, row.imageUrl);
                    TextView role = (TextView) layout.getChildAt(2);
                    role.setVisibility(View.GONE);
                } else {
                    FrameLayout frame = (FrameLayout) layout.getChildAt(0);
                    ImageView image = (ImageView) frame.getChildAt(0);
                    local.mintv.ui.ImageLoader.load(image, row.imageUrl);
                    TextView badge = (TextView) layout.getChildAt(2);
                    badge.setText(row.badge);
                    badge.setVisibility(row.badge.isEmpty() ? View.GONE : View.VISIBLE);
                }
            }
            itemView.setOnClickListener(view -> listener.onRow(row));
            itemView.setOnLongClickListener(view -> {
                if (row.type == TYPE_HISTORY) {
                    listener.onRow(row.deleteVariant());
                    return true;
                }
                return false;
            });
        }
    }
}
