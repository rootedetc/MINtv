package local.mintv;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import local.mintv.data.Session;
import local.mintv.data.WatchlistItem;
import local.mintv.data.WatchlistRepository;
import local.mintv.net.ApiException;
import local.mintv.net.Background;
import local.mintv.ui.CardItem;
import local.mintv.ui.DeepLinks;
import local.mintv.ui.ProgramAdapter;
import local.mintv.ui.ShellHost;
import local.mintv.ui.Tv;
import local.mintv.ui.TvRail;

/** "Moja lista": 16:9 poster grid with TV-style multi-select delete. */
public final class WatchlistActivity extends BaseActivity {

    private ShellHost host;
    private WatchlistRepository repository;
    private RecyclerView grid;
    private ProgramAdapter adapter;
    private ProgressBar progress;
    private LinearLayout emptyPanel;
    private TextView emptyText;
    private LinearLayout footer;
    private TextView editAction;
    private TextView countLabel;
    private TextView deleteAction;
    private int generation;
    private boolean manage;
    private boolean loaded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        host = new ShellHost(this, TvRail.Tab.HOME);
        setContentView(host.root);
        repository = new WatchlistRepository(this);
        buildUi();
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAccount();
        if (loaded && !manage) {
            load();
        }
    }

    private void buildUi() {
        LinearLayout header = host.header(getString(R.string.watchlist_title),
                getString(R.string.watchlist_subtitle));
        countLabel = Tv.label(this, "", Tv.TEXT_3);
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countLp.rightMargin = Tv.dp(this, 14);
        if (Session.get(this).isLoggedIn()) {
            header.addView(countLabel, countLp);
        }
        editAction = host.action(getString(R.string.watchlist_manage));
        editAction.setOnClickListener(view -> setManage(!manage));
        header.addView(editAction, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Tv.dp(this, 44)));
        FrameLayout refresh = new FrameLayout(this);
        refresh.setBackgroundResource(R.drawable.bg_button_ghost);
        refresh.setFocusable(true);
        refresh.setClickable(true);
        refresh.setContentDescription(getString(R.string.action_refresh));
        refresh.setOnClickListener(view -> load());
        Tv.focusScale(refresh);
        refresh.addView(Tv.icon(this, R.drawable.ic_refresh, Tv.TEXT_2, 21f),
                new FrameLayout.LayoutParams(
                        Tv.dp(this, 21), Tv.dp(this, 21), Gravity.CENTER));
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(
                Tv.dp(this, 44), Tv.dp(this, 44));
        refreshLp.leftMargin = Tv.dp(this, 8);
        header.addView(refresh, refreshLp);
        host.content.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(this, 88)));

        FrameLayout body = new FrameLayout(this);
        host.content.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        grid = new RecyclerView(this);
        grid.setId(View.generateViewId());
        int span = Math.max(2, Math.min(5,
                getResources().getConfiguration().screenWidthDp / 280));
        grid.setLayoutManager(new GridLayoutManager(this, span));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.setClipToPadding(false);
        grid.setPadding(Tv.dp(this, 32), Tv.dp(this, 16),
                Tv.dp(this, 32), Tv.dp(this, 20));
        grid.setVerticalScrollBarEnabled(false);
        adapter = new ProgramAdapter(new ProgramAdapter.Listener() {
            @Override
            public void onCard(CardItem item) {
                open(item);
            }

            @Override
            public void onCardLongClick(CardItem item) {
                if (!manage) {
                    setManage(true);
                    adapter.toggle(item.id);
                    updateFooter();
                }
            }
        });
        grid.setAdapter(adapter);
        body.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        progress = new ProgressBar(this);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Tv.BRAND));
        body.addView(progress, new FrameLayout.LayoutParams(
                Tv.dp(this, 40), Tv.dp(this, 40), Gravity.CENTER));

        emptyPanel = buildEmpty();
        body.addView(emptyPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        footer = buildFooter();
        host.content.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(this, 58)));

        host.rail.setContentFocusTarget(grid);
    }

    private LinearLayout buildEmpty() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setBackgroundResource(R.drawable.bg_error_panel);
        Tv.pad(panel, 44, 30, 44, 30);
        panel.setVisibility(View.GONE);
        emptyText = Tv.displayText(this, 23f, Tv.TEXT);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setText(R.string.watchlist_empty);
        panel.addView(emptyText);
        TextView body = Tv.text(this, 13f, Tv.TEXT_2, false);
        body.setText(R.string.watchlist_empty_body);
        body.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = Tv.dp(this, 7);
        panel.addView(body, bodyLp);
        return panel;
    }

    private LinearLayout buildFooter() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Tv.BG_RAISED);
        Tv.pad(bar, 32, 0, 32, 0);
        bar.setVisibility(View.GONE);

        TextView selectAll = Tv.ghost(this, getString(R.string.watchlist_select_all));
        selectAll.setOnClickListener(view -> {
            boolean all = adapter.selectedCount() == adapter.getItemCount();
            adapter.selectAll(!all);
            updateFooter();
        });
        bar.addView(selectAll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Tv.dp(this, 44)));

        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView cancel = Tv.ghost(this, getString(R.string.common_cancel));
        cancel.setOnClickListener(view -> setManage(false));
        bar.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Tv.dp(this, 44)));

        deleteAction = Tv.button(this, getString(R.string.watchlist_delete_selected), true);
        deleteAction.setOnClickListener(view -> confirmDelete());
        LinearLayout.LayoutParams deleteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Tv.dp(this, 46));
        deleteLp.leftMargin = Tv.dp(this, 10);
        bar.addView(deleteAction, deleteLp);
        return bar;
    }

    private void load() {
        if (!Session.get(this).isLoggedIn()) {
            loaded = true;
            progress.setVisibility(View.GONE);
            adapter.submit(new ArrayList<>());
            emptyPanel.setVisibility(View.VISIBLE);
            emptyText.setText(R.string.watchlist_sign_in);
            return;
        }
        int current = ++generation;
        progress.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
        emptyPanel.setVisibility(View.GONE);
        Background.run(() -> repository.loadAll(),
                new Background.Result<List<WatchlistItem>>() {
                    @Override
                    public void onSuccess(List<WatchlistItem> items) {
                        if (current != generation) {
                            return;
                        }
                        loaded = true;
                        progress.setVisibility(View.GONE);
                        List<CardItem> cards = new ArrayList<>();
                        for (WatchlistItem item : items) {
                            cards.add(CardItem.of(item, WatchlistActivity.this));
                        }
                        adapter.submit(cards);
                        countLabel.setText(items.isEmpty() ? ""
                                : getString(R.string.watchlist_count, items.size()));
                        boolean empty = cards.isEmpty();
                        emptyPanel.setVisibility(empty ? View.VISIBLE : View.GONE);
                        emptyText.setText(R.string.watchlist_empty);
                        if (!empty) {
                            focusFirst();
                        }
                    }

                    @Override
                    public void onError(ApiException error) {
                        if (current != generation) {
                            return;
                        }
                        progress.setVisibility(View.GONE);
                        if (adapter.getItemCount() == 0) {
                            emptyPanel.setVisibility(View.VISIBLE);
                            emptyText.setText(R.string.watchlist_error);
                        } else {
                            Toast.makeText(WatchlistActivity.this,
                                    String.valueOf(error.getMessage()), Toast.LENGTH_LONG).show();
                        }
                    }
                });
    }

    private void focusFirst() {
        grid.post(() -> {
            RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(0);
            if (holder != null) {
                host.rail.setContentFocusTarget(holder.itemView);
                holder.itemView.requestFocus();
            } else {
                grid.requestFocus();
            }
        });
    }

    private void setManage(boolean enabled) {
        manage = enabled;
        adapter.setSelectionMode(enabled);
        footer.setVisibility(enabled ? View.VISIBLE : View.GONE);
        editAction.setText(getString(enabled ? R.string.watchlist_done
                : R.string.watchlist_manage));
        if (enabled) {
            deleteAction.setAlpha(0.45f);
            deleteAction.setEnabled(false);
            footer.post(() -> footer.getChildAt(0).requestFocus());
        } else {
            focusFirst();
        }
        updateFooter();
    }

    private void updateFooter() {
        int count = adapter.selectedCount();
        boolean has = count > 0;
        deleteAction.setEnabled(has);
        deleteAction.setAlpha(has ? 1f : 0.45f);
        deleteAction.setText(has
                ? getString(R.string.watchlist_delete_count, count)
                : getString(R.string.watchlist_delete_selected));
        deleteAction.setBackgroundResource(has
                ? R.drawable.bg_button_primary : R.drawable.bg_button);
    }

    private void confirmDelete() {
        List<CardItem> selected = adapter.selectedItems();
        if (selected.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.watchlist_delete_title)
                .setMessage(getString(R.string.watchlist_delete_body, selected.size()))
                .setNegativeButton(R.string.common_cancel, null)
                .setPositiveButton(R.string.watchlist_delete_confirm,
                        (dialog, which) -> delete(selected))
                .show();
    }

    private void delete(List<CardItem> selected) {
        List<WatchlistItem> items = new ArrayList<>();
        for (CardItem card : selected) {
            if (card.source instanceof WatchlistItem) {
                items.add((WatchlistItem) card.source);
            }
        }
        if (items.isEmpty()) {
            return;
        }
        deleteAction.setEnabled(false);
        deleteAction.setAlpha(0.45f);
        Background.run(() -> {
            repository.removeAll(items);
            return repository.loadAll();
        }, new Background.Result<List<WatchlistItem>>() {
            @Override
            public void onSuccess(List<WatchlistItem> remaining) {
                setManage(false);
                List<CardItem> cards = new ArrayList<>();
                for (WatchlistItem item : remaining) {
                    cards.add(CardItem.of(item, WatchlistActivity.this));
                }
                adapter.submit(cards);
                countLabel.setText(remaining.isEmpty() ? ""
                        : getString(R.string.watchlist_count, remaining.size()));
                emptyPanel.setVisibility(cards.isEmpty() ? View.VISIBLE : View.GONE);
                focusFirst();
            }

            @Override
            public void onError(ApiException error) {
                Toast.makeText(WatchlistActivity.this,
                        String.valueOf(error.getMessage()), Toast.LENGTH_LONG).show();
                load();
            }
        });
    }

    private void open(CardItem card) {
        if (card.source instanceof WatchlistItem) {
            DeepLinks.openWatchlistItem(this, (WatchlistItem) card.source);
        }
    }

    private void updateAccount() {
        Session session = Session.get(this);
        if (session.isLoggedIn()) {
            String name = TextUtils.isEmpty(session.displayName())
                    ? session.username() : session.displayName();
            host.rail.setAccount(TextUtils.isEmpty(name)
                    ? getString(R.string.nav_settings) : name, true);
        } else {
            host.rail.setAccount(getString(R.string.action_sign_in), false);
        }
    }
}
