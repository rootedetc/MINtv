package local.mintv;

import android.app.AlertDialog;
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

import local.mintv.data.BookmarkRepository;
import local.mintv.data.ContinueWatchingItem;
import local.mintv.data.Session;
import local.mintv.net.ApiException;
import local.mintv.net.Background;
import local.mintv.ui.CardItem;
import local.mintv.ui.DeepLinks;
import local.mintv.ui.ProgramAdapter;
import local.mintv.ui.ShellHost;
import local.mintv.ui.Tv;
import local.mintv.ui.TvRail;

/** "Nastavi gledati": the full wall, with the same TV multi-select delete as the web app. */
public final class ContinueWatchingActivity extends BaseActivity {

    private ShellHost host;
    private BookmarkRepository repository;
    private RecyclerView grid;
    private ProgramAdapter adapter;
    private ProgressBar progress;
    private LinearLayout emptyPanel;
    private TextView emptyText;
    private LinearLayout footer;
    private TextView editAction;
    private TextView deleteAction;
    private int generation;
    private boolean manage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        host = new ShellHost(this, TvRail.Tab.HOME);
        setContentView(host.root);
        repository = new BookmarkRepository(this);
        buildUi();
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAccount();
        if (Session.get(this).isLoggedIn()
                && emptyText != null
                && getString(R.string.cw_sign_in).contentEquals(emptyText.getText())) {
            load();
        }
    }

    private void buildUi() {
        LinearLayout header = host.header(getString(R.string.cw_title),
                getString(R.string.cw_subtitle));
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
                if (manage) {
                    adapter.toggle(item.id);
                    updateFooter();
                    return;
                }
                if (item.source instanceof ContinueWatchingItem) {
                    DeepLinks.openContinueWatching(ContinueWatchingActivity.this,
                            (ContinueWatchingItem) item.source);
                }
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
        emptyText.setText(R.string.cw_empty);
        panel.addView(emptyText);
        TextView body = Tv.text(this, 13f, Tv.TEXT_2, false);
        body.setText(R.string.cw_empty_body);
        body.setGravity(Gravity.CENTER);
        body.setMaxLines(3);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                Tv.dp(this, 560), ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = Tv.dp(this, 8);
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

        TextView cancel = Tv.ghost(this, getString(R.string.common_cancel));
        cancel.setOnClickListener(view -> setManage(false));
        bar.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Tv.dp(this, 44)));
        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        deleteAction = Tv.button(this, getString(R.string.watchlist_delete_selected), true);
        deleteAction.setOnClickListener(view -> confirmDelete());
        bar.addView(deleteAction, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Tv.dp(this, 46)));
        return bar;
    }

    private void load() {
        if (!Session.get(this).isLoggedIn()) {
            progress.setVisibility(View.GONE);
            emptyPanel.setVisibility(View.VISIBLE);
            emptyText.setText(R.string.cw_sign_in);
            return;
        }
        int current = ++generation;
        progress.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
        emptyPanel.setVisibility(View.GONE);
        Background.run(() -> repository.loadAllTypes(),
                new Background.Result<List<ContinueWatchingItem>>() {
                    @Override
                    public void onSuccess(List<ContinueWatchingItem> items) {
                        if (current != generation) {
                            return;
                        }
                        progress.setVisibility(View.GONE);
                        List<CardItem> cards = new ArrayList<>();
                        for (ContinueWatchingItem item : items) {
                            cards.add(CardItem.of(item, ContinueWatchingActivity.this));
                        }
                        adapter.submit(cards);
                        emptyPanel.setVisibility(cards.isEmpty() ? View.VISIBLE : View.GONE);
                        emptyText.setText(R.string.cw_empty);
                        if (!cards.isEmpty()) {
                            grid.post(() -> {
                                RecyclerView.ViewHolder holder =
                                        grid.findViewHolderForAdapterPosition(0);
                                if (holder != null) {
                                    host.rail.setContentFocusTarget(holder.itemView);
                                    holder.itemView.requestFocus();
                                } else {
                                    grid.requestFocus();
                                }
                            });
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
                            emptyText.setText(R.string.cw_error);
                        } else {
                            Toast.makeText(ContinueWatchingActivity.this,
                                    String.valueOf(error.getMessage()), Toast.LENGTH_LONG).show();
                        }
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
            footer.post(() -> footer.getChildAt(0).requestFocus());
        } else {
            grid.requestFocus();
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
    }

    private void confirmDelete() {
        List<CardItem> selected = adapter.selectedItems();
        if (selected.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.cw_delete_title)
                .setMessage(getString(R.string.cw_delete_body, selected.size()))
                .setNegativeButton(R.string.common_cancel, null)
                .setPositiveButton(R.string.watchlist_delete_confirm,
                        (dialog, which) -> delete(selected))
                .show();
    }

    private void delete(List<CardItem> selected) {
        List<ContinueWatchingItem> items = new ArrayList<>();
        for (CardItem card : selected) {
            if (card.source instanceof ContinueWatchingItem) {
                items.add((ContinueWatchingItem) card.source);
            }
        }
        if (items.isEmpty()) {
            return;
        }
        deleteAction.setEnabled(false);
        Background.run(() -> {
            repository.remove(items);
            return repository.loadAllTypes();
        }, new Background.Result<List<ContinueWatchingItem>>() {
            @Override
            public void onSuccess(List<ContinueWatchingItem> remaining) {
                setManage(false);
                List<CardItem> cards = new ArrayList<>();
                for (ContinueWatchingItem item : remaining) {
                    cards.add(CardItem.of(item, ContinueWatchingActivity.this));
                }
                adapter.submit(cards);
                emptyPanel.setVisibility(cards.isEmpty() ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onError(ApiException error) {
                Toast.makeText(ContinueWatchingActivity.this,
                        String.valueOf(error.getMessage()), Toast.LENGTH_LONG).show();
                load();
            }
        });
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
