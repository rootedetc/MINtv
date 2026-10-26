package local.mintv;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import local.mintv.data.HomeAsset;
import local.mintv.data.HomePage;
import local.mintv.data.HomeRepository;
import local.mintv.net.ApiException;
import local.mintv.net.Background;
import local.mintv.ui.CardItem;
import local.mintv.ui.DeepLinks;
import local.mintv.ui.ProgramAdapter;
import local.mintv.ui.ShellHost;
import local.mintv.ui.Tv;
import local.mintv.ui.TvRail;

/** "Prikaži više": a grid over {@code /home/subpage/{componentId}}. */
public final class SubpageActivity extends BaseActivity {

    private ShellHost host;
    private HomeRepository repository;
    private ProgramAdapter adapter;
    private ProgressBar progress;
    private TextView status;
    private String componentId;
    private String endPoint;
    private String feedType;
    private String storeId;
    private int nextOffset;
    private boolean loading;
    private int generation;

    public static Intent intent(Context context, String componentId, String title,
                                String endPoint, String type, String storeId) {
        Intent intent = new Intent(context, SubpageActivity.class);
        intent.putExtra("component", componentId);
        intent.putExtra("title", title);
        intent.putExtra("end_point", endPoint);
        intent.putExtra("type", type);
        intent.putExtra("store_id", storeId);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        host = new ShellHost(this, TvRail.Tab.HOME);
        setContentView(host.root);
        repository = new HomeRepository(this);
        componentId = getIntent() == null ? "" : getIntent().getStringExtra("component");
        endPoint = getIntent() == null ? "" : getIntent().getStringExtra("end_point");
        feedType = getIntent() == null ? "" : getIntent().getStringExtra("type");
        storeId = getIntent() == null ? "" : getIntent().getStringExtra("store_id");
        buildUi();
        load(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        host.updateAccount();
    }

    private void buildUi() {
        String title = getIntent() == null ? "" : getIntent().getStringExtra("title");
        host.addHeader(title == null || title.isEmpty()
                ? getString(R.string.home_show_more) : title,
                getString(R.string.subpage_subtitle), 80);
        FrameLayout body = new FrameLayout(this);
        host.content.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        RecyclerView grid = new RecyclerView(this);
        grid.setId(View.generateViewId());
        grid.setLayoutManager(new GridLayoutManager(this,
                Math.max(2, Math.min(5,
                        getResources().getConfiguration().screenWidthDp / 280))));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.setClipToPadding(false);
        grid.setPadding(Tv.dp(this, 32), Tv.dp(this, 14),
                Tv.dp(this, 32), Tv.dp(this, 24));
        grid.setVerticalScrollBarEnabled(false);
        adapter = new ProgramAdapter(item -> {
            if (item.source instanceof HomeAsset) {
                DeepLinks.openAsset(this, (HomeAsset) item.source);
            }
        });
        grid.setAdapter(adapter);
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (dy <= 0 || loading || nextOffset < 0) {
                    return;
                }
                GridLayoutManager manager = (GridLayoutManager) recyclerView.getLayoutManager();
                if (manager != null && manager.findLastVisibleItemPosition()
                        >= adapter.getItemCount() - 5) {
                    load(false);
                }
            }
        });
        body.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        progress = new ProgressBar(this);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Tv.BRAND));
        body.addView(progress, new FrameLayout.LayoutParams(
                Tv.dp(this, 40), Tv.dp(this, 40), Gravity.CENTER));

        status = Tv.text(this, 14f, Tv.TEXT_3, false);
        status.setGravity(Gravity.CENTER);
        body.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        host.rail.setContentFocusTarget(grid);
    }

    private void load(boolean reset) {
        if (reset) {
            progress.setVisibility(View.VISIBLE);
            status.setVisibility(View.GONE);
            adapter.submit(new ArrayList<>());
            nextOffset = 0;
        } else if (loading) {
            return;
        }
        loading = true;
        int current = reset ? ++generation : generation;
        int offset = reset ? 0 : nextOffset;
        if (componentId == null || componentId.isEmpty()) {
            loading = false;
            progress.setVisibility(View.GONE);
            status.setText(R.string.home_empty);
            status.setVisibility(View.VISIBLE);
            return;
        }
        Background.run(() -> repository.subPage(componentId, offset, 30, storeId),
                new Background.Result<HomePage>() {
            @Override
            public void onSuccess(HomePage page) {
                if (current != generation) {
                    return;
                }
                loading = false;
                progress.setVisibility(View.GONE);
                List<CardItem> cards = new ArrayList<>();
                for (HomeAsset asset : page.assets) {
                    cards.add(CardItem.of(asset, SubpageActivity.this));
                }
                if (reset) {
                    adapter.submit(cards);
                } else {
                    adapter.append(cards);
                }
                nextOffset = page.nextOffset;
                if (adapter.getItemCount() == 0) {
                    status.setText(R.string.home_empty);
                    status.setVisibility(View.VISIBLE);
                } else {
                    status.setVisibility(View.GONE);
                    if (reset) {
                        host.content.post(() -> {
                            RecyclerView grid = findGrid();
                            if (grid != null) {
                                RecyclerView.ViewHolder holder =
                                        grid.findViewHolderForAdapterPosition(0);
                                if (holder != null) {
                                    host.rail.setContentFocusTarget(holder.itemView);
                                    holder.itemView.requestFocus();
                                } else {
                                    grid.requestFocus();
                                }
                            }
                        });
                    }
                }
            }

            @Override
            public void onError(ApiException error) {
                if (current != generation) {
                    return;
                }
                loading = false;
                progress.setVisibility(View.GONE);
                status.setText(String.valueOf(error.getMessage()));
                status.setVisibility(View.VISIBLE);
            }
        });
    }

    private RecyclerView findGrid() {
        ViewGroup content = host.content;
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (child instanceof FrameLayout) {
                ViewGroup frame = (ViewGroup) child;
                for (int j = 0; j < frame.getChildCount(); j++) {
                    if (frame.getChildAt(j) instanceof RecyclerView) {
                        return (RecyclerView) frame.getChildAt(j);
                    }
                }
            }
        }
        return null;
    }
}
