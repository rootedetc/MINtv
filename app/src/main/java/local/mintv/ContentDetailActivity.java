package local.mintv;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import local.mintv.data.Channel;
import local.mintv.data.ChannelRepository;
import local.mintv.data.ContentDetailRepository;
import local.mintv.data.HomeRailFeed;
import local.mintv.data.Session;
import local.mintv.details.ActionSet;
import local.mintv.details.BookmarkInfo;
import local.mintv.details.Episode;
import local.mintv.details.MetaRow;
import local.mintv.details.PersonCardAdapter;
import local.mintv.details.ProgramDetail;
import local.mintv.details.Season;
import local.mintv.details.SeriesDetail;
import local.mintv.details.WatchAction;
import local.mintv.net.ApiException;
import local.mintv.net.Background;
import local.mintv.net.BffJson;
import local.mintv.player.PlaybackLauncher;
import local.mintv.search.Person;
import local.mintv.ui.CardItem;
import local.mintv.ui.DeepLinks;
import local.mintv.ui.ImageLoader;
import local.mintv.ui.ProgramAdapter;
import local.mintv.ui.ProgramCard;
import local.mintv.ui.ShellHost;
import local.mintv.ui.Tv;
import local.mintv.ui.TvRail;

/** The details hub: guest metadata, authenticated actions, episodes, cast and related rails. */
public final class ContentDetailActivity extends BaseActivity {

    public static final String EXTRA_ID = "local.mintv.extra.DETAIL_ID";
    public static final String EXTRA_SERIES = "local.mintv.extra.DETAIL_SERIES";
    public static final String EXTRA_TITLE = "local.mintv.extra.DETAIL_TITLE";
    public static final String EXTRA_TAB = "local.mintv.extra.DETAIL_TAB";

    private ShellHost host;
    private ContentDetailRepository repository;

    private ScrollView scroll;
    private LinearLayout contentHost;
    private ProgressBar progress;
    private LinearLayout errorPanel;
    private TextView errorText;

    private FrameLayout heroHost;
    private LinearLayout actionsHost;
    private LinearLayout detailRowsHost;
    private LinearLayout seasonsHost;
    private LinearLayout episodesHost;
    private LinearLayout castSection;
    private RecyclerView castRail;
    private LinearLayout relatedSection;
    private RecyclerView relatedRail;

    private String id;
    private boolean isSeries;
    private String titleHint;
    private ProgramDetail program;
    private SeriesDetail series;
    private ActionSet actions;
    private int generation;
    private Season selectedSeason;

    public static Intent intent(Context context, String id, boolean series, String title,
                                TvRail.Tab tab) {
        Intent intent = new Intent(context, ContentDetailActivity.class);
        intent.putExtra(EXTRA_ID, id);
        intent.putExtra(EXTRA_SERIES, series);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_TAB, tab == null ? TvRail.Tab.HOME.ordinal() : tab.ordinal());
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        id = getIntent() == null ? "" : getIntent().getStringExtra(EXTRA_ID);
        isSeries = getIntent() != null && getIntent().getBooleanExtra(EXTRA_SERIES, false);
        titleHint = getIntent() == null ? "" : getIntent().getStringExtra(EXTRA_TITLE);
        TvRail.Tab tab = TvRail.Tab.HOME;
        int ordinal = getIntent() == null ? -1 : getIntent().getIntExtra(EXTRA_TAB, -1);
        if (ordinal >= 0 && ordinal < TvRail.Tab.values().length) {
            tab = TvRail.Tab.values()[ordinal];
        }
        host = new ShellHost(this, tab);
        setContentView(host.root);
        repository = new ContentDetailRepository(this);
        buildUi();
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAccount();
        boolean signedIn = Session.get(this).isLoggedIn();
        if (signedIn && actions == null && (program != null || series != null)) {
            loadActions(generation);
        } else if (!signedIn && actions != null) {
            actions = null;
            renderActions();
        }
    }

    private void buildUi() {
        host.addHeader(getString(R.string.details_title), getString(R.string.details_subtitle), 72);

        FrameLayout body = new FrameLayout(this);
        host.content.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        contentHost = new LinearLayout(this);
        contentHost.setOrientation(LinearLayout.VERTICAL);
        Tv.pad(contentHost, 32, 4, 32, 32);
        scroll.addView(contentHost, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        progress = new ProgressBar(this);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Tv.BRAND));
        body.addView(progress, new FrameLayout.LayoutParams(
                Tv.dp(this, 42), Tv.dp(this, 42), Gravity.CENTER));

        errorPanel = buildError();
        body.addView(errorPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        host.rail.setContentFocusTarget(scroll);
    }

    private LinearLayout buildError() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setBackgroundResource(R.drawable.bg_error_panel);
        Tv.pad(panel, 44, 30, 44, 30);
        panel.setVisibility(View.GONE);
        TextView title = Tv.displayText(this, 24f, Tv.TEXT);
        title.setText(R.string.details_error_title);
        panel.addView(title);
        errorText = Tv.text(this, 13f, Tv.TEXT_2, false);
        errorText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                Tv.dp(this, 560), ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = Tv.dp(this, 8);
        panel.addView(errorText, bodyLp);
        TextView retry = Tv.button(this, getString(R.string.action_retry), true);
        retry.setOnClickListener(view -> load());
        LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Tv.dp(this, 48));
        retryLp.topMargin = Tv.dp(this, 18);
        panel.addView(retry, retryLp);
        return panel;
    }

    private void load() {
        if (id == null || id.isEmpty()) {
            showError(getString(R.string.details_error_title));
            return;
        }
        int current = ++generation;
        progress.setVisibility(View.VISIBLE);
        errorPanel.setVisibility(View.GONE);
        contentHost.setVisibility(View.GONE);
        Background.run(() -> {
            if (isSeries) {
                SeriesDetail detail = repository.series(id);
                List<Episode> episodes = null;
                if (!detail.seasons.isEmpty()) {
                    Season season = detail.seasons.get(0);
                    try {
                        episodes = repository.seasonEpisodes(detail.id, season.id,
                                season.number, detail.isAdult);
                    } catch (ApiException ignored) {
                        // Guest gets 401 here; the season list still renders.
                    }
                }
                return new Loaded(detail, null, episodes);
            }
            return new Loaded(null, repository.program(id), null);
        }, new Background.Result<Loaded>() {
            @Override
            public void onSuccess(Loaded loaded) {
                if (current != generation) {
                    return;
                }
                progress.setVisibility(View.GONE);
                contentHost.setVisibility(View.VISIBLE);
                program = loaded.program;
                series = loaded.series;
                buildContent(loaded.episodes);
                loadActions(current);
                loadRelated(current);
            }

            @Override
            public void onError(ApiException error) {
                if (current != generation) {
                    return;
                }
                progress.setVisibility(View.GONE);
                showError(String.valueOf(error.getMessage()));
            }
        });
    }

    private void showError(String message) {
        errorText.setText(TextUtils.isEmpty(message)
                ? getString(R.string.details_error_body) : message);
        errorPanel.setVisibility(View.VISIBLE);
        contentHost.setVisibility(View.GONE);
    }

    private void buildContent(List<Episode> episodes) {
        contentHost.removeAllViews();
        if (isSeries) {
            buildHero(series != null && !series.posterImageUrl.isEmpty()
                    ? series.posterImageUrl : "",
                    series == null ? titleHint : series.name,
                    series == null ? "" : series.meta(this),
                    series == null ? "" : series.description,
                    series != null && series.isAdult,
                    getString(R.string.details_kind_series));
        } else {
            buildHero(program == null ? "" : program.posterImageUrl,
                    program == null ? titleHint : program.name,
                    program == null ? "" : program.meta(this),
                    program == null ? "" : program.description,
                    program != null && program.isAdult,
                    getString(R.string.details_kind_program));
        }

        actionsHost = new LinearLayout(this);
        actionsHost.setOrientation(LinearLayout.HORIZONTAL);
        actionsHost.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = Tv.dp(this, 14);
        contentHost.addView(actionsHost, actionsLp);
        renderActions();

        detailRowsHost = new LinearLayout(this);
        detailRowsHost.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rowsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowsLp.topMargin = Tv.dp(this, 16);
        contentHost.addView(detailRowsHost, rowsLp);
        renderDetailRows();

        if (isSeries && series != null && !series.seasons.isEmpty()) {
            addSectionTitle(getString(R.string.details_seasons));
            seasonsHost = new LinearLayout(this);
            seasonsHost.setOrientation(LinearLayout.HORIZONTAL);
            contentHost.addView(seasonsHost);
            renderSeasons(series.seasons, episodes);
        }

        if (isSeries) {
            addSectionTitle(getString(R.string.details_episodes));
            episodesHost = new LinearLayout(this);
            episodesHost.setOrientation(LinearLayout.VERTICAL);
            contentHost.addView(episodesHost);
            renderEpisodes(episodes);
        }

        List<Person> roles = isSeries && series != null ? series.roles
                : program == null ? null : program.roles;
        if (roles != null && !roles.isEmpty()) {
            castSection = addSection(getString(R.string.details_cast));
            castRail = new RecyclerView(this);
            castRail.setLayoutManager(new LinearLayoutManager(this,
                    LinearLayoutManager.HORIZONTAL, false));
            castRail.setHasFixedSize(true);
            castRail.setItemAnimator(null);
            castRail.setFocusable(false);
            PersonCardAdapter adapter = new PersonCardAdapter(person -> startActivity(
                    PersonActivity.intent(this, person.id, person.name, person.imageUrl)));
            castRail.setAdapter(adapter);
            adapter.submit(roles);
            castSection.addView(castRail, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(this, 148)));
        }

        relatedSection = addSection(getString(R.string.details_related));
        relatedRail = new RecyclerView(this);
        relatedRail.setLayoutManager(new LinearLayoutManager(this,
                LinearLayoutManager.HORIZONTAL, false));
        relatedRail.setHasFixedSize(true);
        relatedRail.setItemAnimator(null);
        relatedRail.setFocusable(false);
        ProgramAdapter relatedAdapter = new ProgramAdapter(item -> {
            if (item.source instanceof local.mintv.data.HomeAsset) {
                DeepLinks.openAsset(this, (local.mintv.data.HomeAsset) item.source);
            }
        });
        relatedRail.setAdapter(relatedAdapter);
        relatedSection.addView(relatedRail, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(this, ProgramCard.HEIGHT_DP + 6)));
        relatedSection.setVisibility(View.GONE);
    }

    private void buildHero(String poster, String title, String meta, String description,
                           boolean adult, String kind) {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setBackground(Tv.box(this, Tv.SURFACE, 10f, Tv.STROKE, 1f));
        hero.setClipToOutline(true);
        hero.setPadding(0, 0, 0, 0);
        contentHost.addView(hero, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(this, 320)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        Tv.pad(copy, 24, 20, 24, 20);
        hero.addView(copy, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1.05f));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.HORIZONTAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        TextView kindView = Tv.label(this, kind, Tv.BRAND_BRIGHT);
        labels.addView(kindView);
        if (adult) {
            TextView age = Tv.label(this, "18+", Tv.WARNING);
            age.setPadding(Tv.dp(this, 8), 0, 0, 0);
            labels.addView(age);
        }
        copy.addView(labels);

        TextView titleView = Tv.displayText(this, 38f, Tv.TEXT);
        titleView.setText(TextUtils.isEmpty(title) ? titleHint : title);
        titleView.setMaxLines(2);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = Tv.dp(this, 8);
        copy.addView(titleView, titleLp);

        TextView metaView = Tv.text(this, 13f, Tv.TEXT_2, false);
        metaView.setText(meta);
        metaView.setSingleLine(true);
        metaView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = Tv.dp(this, 5);
        copy.addView(metaView, metaLp);

        TextView descriptionView = Tv.text(this, 13.5f, Tv.TEXT_2, false);
        descriptionView.setText(TextUtils.isEmpty(description)
                ? getString(R.string.guide_no_description) : description);
        descriptionView.setMaxLines(4);
        descriptionView.setEllipsize(TextUtils.TruncateAt.END);
        descriptionView.setLineSpacing(Tv.dp(this, 2), 1f);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = Tv.dp(this, 12);
        copy.addView(descriptionView, descLp);

        FrameLayout imageFrame = new FrameLayout(this);
        imageFrame.setBackgroundColor(Tv.SURFACE_2);
        hero.addView(imageFrame, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 0.95f));
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImageResource(R.drawable.ic_channel_placeholder);
        imageFrame.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImageLoader.load(image, poster);
    }

    private void renderActions() {
        actionsHost.removeAllViews();
        Session session = Session.get(this);
        boolean signedIn = session.isLoggedIn();
        BookmarkInfo bookmark = actions == null ? null : actions.bookmark;
        boolean resume = bookmark != null && bookmark.hasResume();

        TextView watch = Tv.button(this, resume
                ? getString(R.string.details_resume) : getString(R.string.details_watch), true);
        watch.setId(View.generateViewId());
        watch.setOnClickListener(view -> playPrimary());
        watch.setEnabled(actions != null && actions.hasWatch());
        watch.setAlpha(actions != null && actions.hasWatch() ? 1f : 0.45f);
        actionsHost.addView(watch, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Tv.dp(this, 50)));

        if (signedIn) {
            if (actions != null && actions.hasTrailer()) {
                TextView trailer = Tv.ghost(this, getString(R.string.details_trailer));
                trailer.setOnClickListener(view -> playTrailer());
                LinearLayout.LayoutParams trailerLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, Tv.dp(this, 46));
                trailerLp.leftMargin = Tv.dp(this, 10);
                actionsHost.addView(trailer, trailerLp);
            }
        } else {
            TextView signIn = Tv.ghost(this, getString(R.string.settings_account_sign_in));
            signIn.setId(View.generateViewId());
            signIn.setOnClickListener(view -> startActivity(new Intent(this, LoginActivity.class)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Tv.dp(this, 46));
            lp.leftMargin = Tv.dp(this, 10);
            actionsHost.addView(signIn, lp);
        }

        if (actions != null && (actions.deviceNotCompatible || actions.geoBlocked)) {
            TextView warning = Tv.text(this, 12f, Tv.WARNING, false);
            warning.setText(actions.geoBlocked
                    ? getString(R.string.details_geo_blocked)
                    : getString(R.string.details_device_blocked));
            LinearLayout.LayoutParams warningLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            warningLp.leftMargin = Tv.dp(this, 16);
            actionsHost.addView(warning, warningLp);
        }
        actionsHost.post(() -> {
            if (actionsHost.getChildCount() == 0) {
                return;
            }
            View target = actionsHost.getChildAt(0);
            for (int i = 0; i < actionsHost.getChildCount(); i++) {
                View child = actionsHost.getChildAt(i);
                if (child.isEnabled() && child.isFocusable()) {
                    target = child;
                    break;
                }
            }
            host.rail.setContentFocusTarget(target);
            focusAction(target);
            final View chosen = target;
            actionsHost.postDelayed(() -> focusAction(chosen), 350L);
        });
    }

    private boolean detailFocusGiven;

    private void focusAction(View target) {
        if (detailFocusGiven || target == null || !target.isShown()) {
            return;
        }
        View current = getCurrentFocus();
        if (current != null && !isInRail(current)) {
            return;
        }
        if (target.requestFocus()) {
            detailFocusGiven = true;
        }
    }

    private boolean isInRail(View view) {
        android.view.ViewParent parent = view.getParent();
        while (parent != null) {
            if (parent == host.rail) {
                return true;
            }
            parent = parent.getParent();
        }
        return view == host.rail;
    }

    private void renderDetailRows() {
        detailRowsHost.removeAllViews();
        List<MetaRow> metadata = isSeries && series != null ? series.metadata
                : program == null ? null : program.metadata;
        if (metadata != null) {
            for (MetaRow row : metadata) {
                addDetailRow(row.name, row.value);
            }
        }
        if (program != null && program.runtimeSeconds > 0) {
            addDetailRow(getString(R.string.details_runtime),
                    getString(R.string.meta_minutes,
                            Math.max(1, program.runtimeSeconds / 60)));
        }
    }

    private void addDetailRow(String name, String value) {
        if (TextUtils.isEmpty(value)) {
            return;
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Tv.pad(row, 0, 7, 0, 7);
        TextView label = Tv.label(this, name, Tv.TEXT_3);
        row.addView(label, new LinearLayout.LayoutParams(
                Tv.dp(this, 150), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView text = Tv.text(this, 13f, Tv.TEXT_2, false);
        text.setText(value);
        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        detailRowsHost.addView(row);
        detailRowsHost.addView(Tv.hairline(this, true));
    }

    private LinearLayout addSection(String title) {
        addSectionTitle(title);
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        contentHost.addView(section);
        return section;
    }

    private void addSectionTitle(String title) {
        TextView heading = Tv.displayText(this, 22f, Tv.TEXT);
        heading.setText(title);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Tv.dp(this, 20);
        lp.bottomMargin = Tv.dp(this, 8);
        contentHost.addView(heading, lp);
    }

    private void renderSeasons(List<Season> seasons, List<Episode> episodes) {
        seasonsHost.removeAllViews();
        for (Season season : seasons) {
            TextView chip = Tv.displayText(this, 14f, Tv.TEXT_3);
            chip.setText(season.label(this));
            chip.setPadding(Tv.dp(this, 14), 0, Tv.dp(this, 14), 0);
            chip.setGravity(Gravity.CENTER);
            chip.setBackground(Tv.states(this, TmSurface(), Tv.SURFACE_2, Tv.SURFACE_3,
                    6f, Tv.STROKE, 1f, 0xFFFFFFFF, 2f));
            chip.setFocusable(true);
            chip.setClickable(true);
            boolean active = selectedSeason != null
                    ? selectedSeason.id.equals(season.id)
                    : seasons.get(0).id.equals(season.id);
            chip.setTextColor(active ? Tv.BRAND_BRIGHT : Tv.TEXT_3);
            chip.setOnClickListener(view -> loadEpisodes(season));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Tv.dp(this, 36));
            lp.rightMargin = Tv.dp(this, 6);
            seasonsHost.addView(chip, lp);
        }
    }

    private int TmSurface() {
        return 0x00000000;
    }

    private void renderEpisodes(List<Episode> episodes) {
        episodesHost.removeAllViews();
        if (!Session.get(this).isLoggedIn()) {
            TextView hint = Tv.text(this, 13f, Tv.TEXT_3, false);
            hint.setText(R.string.details_episodes_sign_in);
            episodesHost.addView(hint);
            return;
        }
        if (episodes == null || episodes.isEmpty()) {
            TextView empty = Tv.text(this, 13f, Tv.TEXT_3, false);
            empty.setText(R.string.details_episodes_empty);
            episodesHost.addView(empty);
            return;
        }
        for (Episode episode : episodes) {
            episodesHost.addView(episodeRow(episode));
        }
    }

    private View episodeRow(Episode episode) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(Tv.states(this, Tv.SURFACE, Tv.SURFACE_FOCUS, Tv.SURFACE_3,
                7f, Tv.STROKE, 1f, 0xFFFFFFFF, 2f));
        Tv.pad(row, 14, 10, 14, 10);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = Tv.dp(this, 6);

        TextView number = Tv.numeral(this, 22f, Tv.BRAND_BRIGHT);
        number.setText(episode.episodeNumber > 0
                ? String.format("%02d", episode.episodeNumber) : "—");
        row.addView(number, new LinearLayout.LayoutParams(
                Tv.dp(this, 42), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView title = Tv.displayText(this, 17f, Tv.TEXT);
        title.setText(episode.episodeName);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(title);
        TextView meta = Tv.text(this, 11.5f, Tv.TEXT_3, false);
        meta.setText(episodeMeta(episode));
        text.addView(meta);
        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView play = Tv.label(this, getString(R.string.details_play_episode),
                Tv.BRAND_BRIGHT);
        row.addView(play);
        row.setOnClickListener(view -> playEpisode(episode));
        row.setLayoutParams(rowLp);
        return row;
    }

    private String episodeMeta(Episode episode) {
        List<String> parts = new ArrayList<>();
        if (episode.runtimeSeconds > 0) {
            parts.add(getString(R.string.meta_minutes,
                    Math.max(1, episode.runtimeSeconds / 60)));
        }
        if (episode.startTime > 0) {
            parts.add(Tv.clock(episode.startTime));
        }
        if (episode.restricted) {
            parts.add(getString(R.string.details_restricted));
        }
        return String.join(" · ", parts);
    }

    private void loadEpisodes(Season season) {
        if (season == null || season.id.isEmpty() || season.id.equals(
                selectedSeason == null ? "" : selectedSeason.id)) {
            return;
        }
        selectedSeason = season;
        renderSeasons(series.seasons, null);
        episodesHost.removeAllViews();
        TextView loading = Tv.text(this, 13f, Tv.TEXT_3, false);
        loading.setText(R.string.common_loading);
        episodesHost.addView(loading);
        int current = generation;
        Background.run(() -> repository.seasonEpisodes(series.id, season.id, season.number,
                        series.isAdult), new Background.Result<List<Episode>>() {
                    @Override
                    public void onSuccess(List<Episode> episodes) {
                        if (current != generation) {
                            return;
                        }
                        renderEpisodes(episodes);
                    }

                    @Override
                    public void onError(ApiException error) {
                        if (current != generation) {
                            return;
                        }
                        episodesHost.removeAllViews();
                        TextView failed = Tv.text(ContentDetailActivity.this, 13f, Tv.TEXT_3,
                                false);
                        failed.setText(String.valueOf(error.getMessage()));
                        episodesHost.addView(failed);
                    }
                });
    }

    private void loadActions(int current) {
        if (!Session.get(this).isLoggedIn()) {
            return;
        }
        boolean adult = isSeries ? series != null && series.isAdult
                : program != null && program.isAdult;
        Background.run(() -> {
            if (isSeries) {
                return repository.actionsForSeries(id, adult, false);
            }
            return repository.actionsForProgram(id, adult, false);
        }, new Background.Result<ActionSet>() {
            @Override
            public void onSuccess(ActionSet loaded) {
                if (current != generation) {
                    return;
                }
                actions = loaded;
                renderActions();
            }

            @Override
            public void onError(ApiException error) {
                if (current != generation) {
                    return;
                }
                // 401 on a sub-resource means "not signed in"; actions stay hidden.
                if (error.httpStatus() == 401 || error.httpStatus() == 403) {
                    return;
                }
                Toast.makeText(ContentDetailActivity.this,
                        String.valueOf(error.getMessage()), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void loadRelated(int current) {
        if (!Session.get(this).isLoggedIn() || relatedSection == null) {
            return;
        }
        String key = isSeries ? id : program == null ? id : program.id;
        Background.run(() -> repository.related(key, false, isSeries, 12, 0),
                new Background.Result<HomeRailFeed>() {
                    @Override
                    public void onSuccess(HomeRailFeed feed) {
                        if (current != generation || feed.assets.isEmpty()) {
                            return;
                        }
                        List<CardItem> cards = new ArrayList<>();
                        for (local.mintv.data.HomeAsset asset : feed.assets) {
                            cards.add(CardItem.of(asset, ContentDetailActivity.this));
                        }
                        ((ProgramAdapter) relatedRail.getAdapter()).submit(cards);
                        relatedSection.setVisibility(View.VISIBLE);
                    }

                    @Override
                    public void onError(ApiException error) {
                        relatedSection.setVisibility(View.GONE);
                    }
                });
    }

    private void playPrimary() {
        if (actions == null) {
            Toast.makeText(this, R.string.details_not_signed_in, Toast.LENGTH_SHORT).show();
            return;
        }
        WatchAction action = actions.primaryWatch();
        if (action == null) {
            Toast.makeText(this, R.string.details_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        playAction(action, isSeries ? id : program == null ? id : program.id,
                isSeries ? titleHint : program == null ? titleHint : program.name,
                isSeries ? "" : program == null ? "" : program.seriesId,
                program == null ? 0 : program.runtimeSeconds,
                actions.bookmark == null ? 0 : actions.bookmark.timeSeconds);
    }

    private void playEpisode(Episode episode) {
        if (episode.actions != null && episode.actions.hasWatch()) {
            playAction(episode.actions.primaryWatch(), episode.programId,
                    episode.episodeName, series == null ? "" : series.id,
                    episode.runtimeSeconds, 0);
            return;
        }
        if (!Session.get(this).isLoggedIn()) {
            Toast.makeText(this, R.string.details_not_signed_in, Toast.LENGTH_SHORT).show();
            return;
        }
        Background.run(() -> repository.actionsForProgram(episode.programId,
                        series != null && series.isAdult, false),
                new Background.Result<ActionSet>() {
                    @Override
                    public void onSuccess(ActionSet loaded) {
                        WatchAction action = loaded.primaryWatch();
                        if (action == null) {
                            Toast.makeText(ContentDetailActivity.this,
                                    R.string.details_unavailable, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        playAction(action, episode.programId, episode.episodeName,
                                series == null ? "" : series.id, episode.runtimeSeconds,
                                loaded.bookmark == null ? 0 : loaded.bookmark.timeSeconds);
                    }

                    @Override
                    public void onError(ApiException error) {
                        Toast.makeText(ContentDetailActivity.this,
                                String.valueOf(error.getMessage()), Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void playAction(WatchAction action, String programId, String title,
                            String seriesId, long runtimeSeconds, long resumeSeconds) {
        String rawProgram = BffJson.str(action.raw, "program_id", "programId");
        String effectiveProgram = rawProgram.isEmpty() ? programId : rawProgram;
        if (action.isVod()) {
            PlaybackLauncher.openProgram(this, effectiveProgram, action.mediaId, title,
                    getString(R.string.details_kind_program), seriesId, "", runtimeSeconds,
                    resumeSeconds);
            return;
        }
        if (action.isCatchup()) {
            PlaybackLauncher.openCatchup(this, action, effectiveProgram, title, seriesId,
                    runtimeSeconds, resumeSeconds);
            return;
        }
        if (action.isLive() && action.channelNumber >= 0) {
            openLive(action.channelNumber);
            return;
        }
        Toast.makeText(this, R.string.details_unavailable, Toast.LENGTH_SHORT).show();
    }

    private void openLive(int channelNumber) {
        List<Channel> channels = ChannelRepository.get(this).cachedChannels();
        Channel channel = findChannel(channels, channelNumber);
        if (channel != null) {
            PlaybackLauncher.open(this, channel, channels);
            return;
        }
        ChannelRepository.get(this).load(false, new ChannelRepository.Listener() {
            @Override
            public void onChannels(List<Channel> loaded, boolean fromCache) {
                Channel found = findChannel(loaded, channelNumber);
                if (found != null) {
                    PlaybackLauncher.open(ContentDetailActivity.this, found, loaded);
                }
            }

            @Override
            public void onError(String message, Throwable cause) {
                Toast.makeText(ContentDetailActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static Channel findChannel(List<Channel> channels, int number) {
        for (Channel channel : channels) {
            if (channel.getNumber() == number) {
                return channel;
            }
        }
        return null;
    }

    private void playTrailer() {
        if (actions == null || actions.trailer.isEmpty()) {
            return;
        }
        WatchAction trailer = actions.trailer.get(0);
        String url = BffJson.str(trailer.raw, "url", "video_src");
        if (url.isEmpty()) {
            Toast.makeText(this, R.string.details_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        long runtime = BffJson.longValue(trailer.raw, 0L, "runtime_seconds");
        Intent intent = PlayerActivity.intentForMedia(this, url, "", "",
                getString(R.string.details_trailer), titleHint);
        startActivity(intent);
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

    private static final class Loaded {
        final SeriesDetail series;
        final ProgramDetail program;
        final List<Episode> episodes;

        Loaded(SeriesDetail series, ProgramDetail program, List<Episode> episodes) {
            this.series = series;
            this.program = program;
            this.episodes = episodes;
        }
    }
}
