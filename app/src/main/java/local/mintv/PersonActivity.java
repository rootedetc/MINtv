package local.mintv;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import local.mintv.data.ContentDetailRepository;
import local.mintv.data.HomeAsset;
import local.mintv.details.PersonFeed;
import local.mintv.net.ApiException;
import local.mintv.net.Background;
import local.mintv.search.Person;
import local.mintv.ui.CardItem;
import local.mintv.ui.DeepLinks;
import local.mintv.ui.ProgramAdapter;
import local.mintv.ui.ShellHost;
import local.mintv.ui.Tv;
import local.mintv.ui.TvRail;

/** Cast/crew screen: a person header and their credits. */
public final class PersonActivity extends BaseActivity {

    private ShellHost host;
    private ContentDetailRepository repository;
    private ProgramAdapter adapter;
    private ProgressBar progress;
    private TextView status;
    private String personId;

    public static Intent intent(Context context, String personId, String name, String image) {
        Intent intent = new Intent(context, PersonActivity.class);
        intent.putExtra("person", personId);
        intent.putExtra("name", name);
        intent.putExtra("image", image);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        host = new ShellHost(this, TvRail.Tab.HOME);
        setContentView(host.root);
        repository = new ContentDetailRepository(this);
        personId = getIntent() == null ? "" : getIntent().getStringExtra("person");
        buildUi();
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        host.updateAccount();
    }

    private void buildUi() {
        String name = getIntent() == null ? "" : getIntent().getStringExtra("name");
        host.addHeader(name == null || name.isEmpty()
                ? getString(R.string.person_title) : name,
                getString(R.string.person_subtitle), 88);

        FrameLayout body = new FrameLayout(this);
        host.content.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        RecyclerView grid = new RecyclerView(this);
        grid.setLayoutManager(new GridLayoutManager(this,
                Math.max(2, Math.min(5,
                        getResources().getConfiguration().screenWidthDp / 280))));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.setClipToPadding(false);
        grid.setPadding(Tv.dp(this, 32), Tv.dp(this, 12),
                Tv.dp(this, 32), Tv.dp(this, 20));
        grid.setVerticalScrollBarEnabled(false);
        adapter = new ProgramAdapter(item -> {
            if (item.source instanceof HomeAsset) {
                DeepLinks.openAsset(this, (HomeAsset) item.source);
            }
        });
        grid.setAdapter(adapter);
        body.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        progress = new ProgressBar(this);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Tv.BRAND));
        body.addView(progress, new FrameLayout.LayoutParams(
                Tv.dp(this, 38), Tv.dp(this, 38), Gravity.CENTER));

        status = Tv.text(this, 14f, Tv.TEXT_3, false);
        status.setGravity(Gravity.CENTER);
        body.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        host.rail.setContentFocusTarget(grid);
    }

    private void load() {
        if (personId == null || personId.isEmpty()) {
            status.setText(R.string.person_error);
            return;
        }
        progress.setVisibility(View.VISIBLE);
        status.setVisibility(View.GONE);
        Background.run(() -> repository.personItems(personId, 20, 0, true),
                new Background.Result<PersonFeed>() {
                    @Override
                    public void onSuccess(PersonFeed feed) {
                        progress.setVisibility(View.GONE);
                        List<CardItem> cards = new ArrayList<>();
                        for (HomeAsset asset : feed.items) {
                            cards.add(CardItem.of(asset, PersonActivity.this));
                        }
                        adapter.submit(cards);
                        if (cards.isEmpty()) {
                            status.setText(R.string.person_empty);
                            status.setVisibility(View.VISIBLE);
                        }
                    }

                    @Override
                    public void onError(ApiException error) {
                        progress.setVisibility(View.GONE);
                        status.setText(String.valueOf(error.getMessage()));
                        status.setVisibility(View.VISIBLE);
                    }
                });
    }
}
