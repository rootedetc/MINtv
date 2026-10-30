package local.mintv;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import local.mintv.data.AccountChannelRepository;
import local.mintv.data.BookmarkRepository;
import local.mintv.data.Channel;
import local.mintv.data.ChannelRepository;
import local.mintv.data.ContinueWatchingItem;
import local.mintv.data.HomeAsset;
import local.mintv.data.HomeCategories;
import local.mintv.data.HomeCategory;
import local.mintv.data.HomeComponent;
import local.mintv.data.HomePage;
import local.mintv.data.HomeRailFeed;
import local.mintv.data.HomeRepository;
import local.mintv.data.Highlight;
import local.mintv.data.Session;
import local.mintv.epg.EpgCache;
import local.mintv.epg.EpgChannel;
import local.mintv.epg.EpgProgram;
import local.mintv.epg.ProgramDetailsRepository;
import local.mintv.net.ApiException;
import local.mintv.net.Background;
import local.mintv.player.PlaybackLauncher;
import local.mintv.ui.CardItem;
import local.mintv.ui.DeepLinks;
import local.mintv.ui.ImageLoader;
import local.mintv.ui.ProgramAdapter;
import local.mintv.ui.ProgramCard;
import local.mintv.ui.ShellHost;
import local.mintv.ui.Skeleton;
import local.mintv.ui.Tv;
import local.mintv.ui.TvRail;

/**
 * Server-driven home rails: hero, editorial rails, continue watching, favourite channels and
 * on-now. Rails are fetched independently so the first one paints without waiting for all
 * fifteen.
 */
public final class HomeActivity extends BaseActivity {

    private static final int PAGE_SIZE = 20;
    private static final int RAIL_SIZE = 12;
    private static final int SKELETON_RAILS = 3;
    private static final int SKELETON_CARDS = 5;
    private static final int[] SKELETON_HEADINGS = {212, 168, 236};

    /*
     * Vertical rhythm below the hero. A heading belongs to the cards under it, so the gap
     * above a heading is larger than the gap between the heading and its own row:
     *   hero ──32── heading ──8 + 6── cards ──6 + 32── next heading.
     * The 6dp rail padding sits inside the card row, so the focused card's 1.025 scale
     * never touches the row bounds.
     */
    private static final int HERO_GAP_DP = 32;
    private static final int HERO_HEIGHT_DP = 240;
    /** Near-black with a trace of the signal magenta; the quiet plate under a missing image. */
    private static final int HERO_PLATE = 0xFF120D14;
    private static final float HERO_COPY_RATIO = 0.55f;
    /** MAXtv posters ship at 356x200; the same asset answers at 1280x720 for the hero. */
    private static final Pattern MAXTV_POSTER_PATH = Pattern.compile(
            "^https?://tv-hr-prod\\.yo-digital\\.com/prod/images/\\d+/\\d+/");
    private static final String MAXTV_HERO_PATH =
            "https://tv-hr-prod.yo-digital.com/prod/images/1280/720/";
    private static final int SECTION_GAP_DP = 32;
    private static final int HEADING_HEIGHT_DP = 44;
    private static final int HEADING_TO_ROW_DP = 8;
    private static final int ROW_VPAD_DP = 6;
    private static final int CARD_ROW_HEIGHT_DP = ProgramCard.HEIGHT_DP + (ROW_VPAD_DP * 2);
    private static final int CARD_GUTTER_DP = 12;
    private static final int BOTTOM_INSET_DP = 48;

    private ShellHost host;
    private HomeRepository repository;
    private BookmarkRepository bookmarkRepository;
    private AccountChannelRepository channelRepository;

    private ScrollView scroll;
    private LinearLayout rails;
    private LinearLayout categoriesRow;
    private TextView subtitle;
    private View refreshAction;
    private View headerView;
    private View heroView;
    private View firstLoadSkeleton;
    private LinearLayout errorPanel;

    private String categoryId;
    private String categoryTitle;
    private int generation;
    private boolean firstLoad = true;
    private boolean initialFocusGiven;
    private boolean heroPending;
    private View primaryFocusTarget;
    private View heroTopScrim;
    private boolean lastLoggedIn;

    public static Intent intent(Context context, String pageId) {
        Intent intent = new Intent(context, HomeActivity.class);
        if (pageId != null && !pageId.isEmpty()) {
            intent.putExtra("page", pageId);
        }
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        host = new ShellHost(this, TvRail.Tab.HOME);
        setContentView(host.root);
        repository = new HomeRepository(this);
        bookmarkRepository = new BookmarkRepository(this);
        channelRepository = new AccountChannelRepository(this);

        categoryId = getIntent() == null ? "" : getIntent().getStringExtra("page");
        lastLoggedIn = Session.get(this).isLoggedIn();
        buildUi();
        load(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean loggedIn = Session.get(this).isLoggedIn();
        if (loggedIn != lastLoggedIn) {
            lastLoggedIn = loggedIn;
            if (!firstLoad) {
                load(true);
            }
        }
        updateAccount();
    }

    private void buildUi() {
        LinearLayout header = host.header(getString(R.string.nav_home),
                getString(R.string.home_subtitle));
        headerView = header;
        FrameLayout refresh = new FrameLayout(this);
        refresh.setBackgroundResource(R.drawable.bg_button_ghost);
        refresh.setFocusable(true);
        refresh.setClickable(true);
        refresh.setContentDescription(getString(R.string.action_refresh));
        refresh.setOnClickListener(view -> load(true));
        Tv.focusScale(refresh);
        refresh.addView(Tv.icon(this, R.drawable.ic_refresh, Tv.TEXT_2, 21f),
                new FrameLayout.LayoutParams(
                        Tv.dp(this, 21), Tv.dp(this, 21), Gravity.CENTER));
        header.addView(refresh, new LinearLayout.LayoutParams(
                Tv.dp(this, 44), Tv.dp(this, 44)));
        refreshAction = refresh;
        host.content.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(this, 80)));

        categoriesRow = new LinearLayout(this);
        categoriesRow.setOrientation(LinearLayout.HORIZONTAL);
        categoriesRow.setGravity(Gravity.CENTER_VERTICAL);
        Tv.pad(categoriesRow, 32, 0, 32, 8);
        host.content.addView(categoriesRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(this, 44)));

        FrameLayout body = new FrameLayout(this);
        host.content.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        rails = new LinearLayout(this);
        rails.setOrientation(LinearLayout.VERTICAL);
        Tv.pad(rails, 32, 4, 32, BOTTOM_INSET_DP);
        scroll.addView(rails, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        errorPanel = buildError();
        body.addView(errorPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        host.rail.setContentFocusTarget(scroll);
        host.setContentFocus(() -> {
            if (primaryFocusTarget != null && primaryFocusTarget.isShown()) {
                primaryFocusTarget.requestFocus();
            } else {
                focusSelectedCategory();
            }
        });
    }

    private void showFirstLoadSkeleton() {
        clearFirstLoadSkeleton();
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        group.setContentDescription(getString(R.string.common_loading));

        LinearLayout hero = skeletonHero();
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        heroLp.bottomMargin = Tv.dp(this, HERO_GAP_DP);
        group.addView(hero, heroLp);

        for (int i = 0; i < SKELETON_RAILS; i++) {
            group.addView(skeletonRail(i));
        }
        rails.addView(group, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        firstLoadSkeleton = group;
    }

    private LinearLayout skeletonHero() {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.addView(Skeleton.box(this, 0, HERO_HEIGHT_DP, 10f),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        Tv.dp(this, HERO_HEIGHT_DP)));
        Skeleton.pulse(group);
        return group;
    }

    private LinearLayout skeletonRail(int index) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        int width = SKELETON_HEADINGS[index % SKELETON_HEADINGS.length];

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(Skeleton.line(this, width, 20));
        group.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(this, HEADING_HEIGHT_DP)));

        group.addView(skeletonCardRow(false), skeletonRowParams());
        LinearLayout.LayoutParams groupLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        groupLp.bottomMargin = Tv.dp(this, SECTION_GAP_DP);
        group.setLayoutParams(groupLp);
        Skeleton.pulse(group);
        return group;
    }

    private LinearLayout skeletonCardRow() {
        return skeletonCardRow(true);
    }

    private LinearLayout skeletonCardRow(boolean pulse) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        row.setContentDescription(getString(R.string.common_loading));
        for (int i = 0; i < SKELETON_CARDS; i++) {
            View card = Skeleton.box(this, ProgramCard.WIDTH_DP, ProgramCard.HEIGHT_DP, 8f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    Tv.dp(this, ProgramCard.WIDTH_DP), Tv.dp(this, ProgramCard.HEIGHT_DP));
            lp.rightMargin = Tv.dp(this, CARD_GUTTER_DP);
            row.addView(card, lp);
        }
        if (pulse) {
            Skeleton.pulse(row);
        }
        return row;
    }

    private LinearLayout.LayoutParams skeletonRowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(this, CARD_ROW_HEIGHT_DP));
        lp.topMargin = Tv.dp(this, HEADING_TO_ROW_DP);
        return lp;
    }

    private void clearFirstLoadSkeleton() {
        if (firstLoadSkeleton == null) {
            return;
        }
        rails.removeView(firstLoadSkeleton);
        firstLoadSkeleton = null;
    }

    private boolean hasVisibleContent() {
        for (int i = 0; i < rails.getChildCount(); i++) {
            View child = rails.getChildAt(i);
            if (child != firstLoadSkeleton && child.getVisibility() == View.VISIBLE) {
                return true;
            }
        }
        return false;
    }

    private LinearLayout buildError() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setBackgroundResource(R.drawable.bg_error_panel);
        Tv.pad(panel, 42, 30, 42, 30);
        panel.setVisibility(View.GONE);
        TextView title = Tv.displayText(this, 24f, Tv.TEXT);
        title.setText(R.string.home_error);
        title.setGravity(Gravity.CENTER);
        panel.addView(title);
        TextView body = Tv.text(this, 13f, Tv.TEXT_2, false);
        body.setText(R.string.channels_error_body);
        body.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = Tv.dp(this, 6);
        panel.addView(body, bodyLp);
        TextView retry = Tv.button(this, getString(R.string.action_retry), true);
        retry.setOnClickListener(view -> load(true));
        LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Tv.dp(this, 48));
        retryLp.topMargin = Tv.dp(this, 18);
        panel.addView(retry, retryLp);
        return panel;
    }

    private void load(boolean force) {
        int current = ++generation;
        if (!hasVisibleContent()) {
            showFirstLoadSkeleton();
        }
        errorPanel.setVisibility(View.GONE);
        final String requested = categoryId;
        Background.run(() -> {
            HomeCategories categories = null;
            try {
                categories = repository.categories();
            } catch (ApiException ignored) {
                // fall back to the known root id; the page call is the one that matters
            }
            String id = requested == null ? "" : requested;
            if (id.isEmpty() && categories != null) {
                id = categories.defaultCategoryId;
                if (id.isEmpty() && !categories.categories.isEmpty()) {
                    id = categories.categories.get(0).pageId;
                }
            }
            if (id.isEmpty()) {
                id = HomeRepository.FALLBACK_ROOT_ID;
            }
            boolean loggedIn = Session.get(this).isLoggedIn();
            HomePage page = repository.page(id, 0, PAGE_SIZE, loggedIn);
            return new LoadResult(id, categories, page);
        }, new Background.Result<LoadResult>() {
            @Override
            public void onSuccess(LoadResult value) {
                if (current != generation) {
                    return;
                }
                clearFirstLoadSkeleton();
                firstLoad = false;
                categoryId = value.id;
                if (value.categories != null) {
                    renderCategories(value.categories.categories);
                    HomeCategory selected = value.categories.find(value.id);
                    categoryTitle = selected == null ? "" : selected.title;
                }
                if (TextUtils.isEmpty(categoryTitle) && value.page != null) {
                    categoryTitle = value.page.title;
                }
                subtitle = null;
                renderPage(value.page);
            }

            @Override
            public void onError(ApiException error) {
                if (current != generation) {
                    return;
                }
                clearFirstLoadSkeleton();
                errorPanel.setVisibility(View.VISIBLE);
            }
        });
    }

    private void renderCategories(List<HomeCategory> categories) {
        categoriesRow.removeAllViews();
        if (categories == null || categories.isEmpty()) {
            return;
        }
        for (HomeCategory category : categories) {
            TextView chip = Tv.displayText(this, 14f, Tv.TEXT_3);
            chip.setText(category.title.trim());
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(Tv.dp(this, 14), 0, Tv.dp(this, 14), 0);
            chip.setBackground(Tv.states(this, Color.TRANSPARENT, Tv.SURFACE_2,
                    Tv.SURFACE_3, 6f, 0, 0f, 0xFFFFFFFF, 2f));
            chip.setFocusable(true);
            chip.setClickable(true);
            chip.setSelected(category.id.equals(categoryId));
            chip.setTextColor(category.id.equals(categoryId) ? Tv.BRAND_BRIGHT : Tv.TEXT_3);
            chip.setOnClickListener(view -> {
                if (category.id.equals(categoryId)) {
                    return;
                }
                categoryId = category.id;
                categoryTitle = category.title.trim();
                load(true);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Tv.dp(this, 34));
            lp.rightMargin = Tv.dp(this, 6);
            categoriesRow.addView(chip, lp);
        }
    }

    private void renderPage(HomePage page) {
        rails.removeAllViews();
        heroView = null;
        firstLoadSkeleton = null;
        if (page == null || page.components.isEmpty()) {
            TextView empty = Tv.text(this, 14f, Tv.TEXT_2, false);
            empty.setText(R.string.home_empty);
            rails.addView(empty);
            return;
        }
        boolean loggedIn = Session.get(this).isLoggedIn();
        heroPending = false;
        for (HomeComponent component : page.components) {
            if (component.isHighlight()) {
                heroPending = true;
                break;
            }
        }
        for (HomeComponent component : page.components) {
            if (component.isHighlight()) {
                addHero(component);
            } else if (component.requiresAuth() && !loggedIn) {
                continue;
            } else if (component.isRentals() || component.isRecording()
                    || component.isWatchlist()) {
                continue;
            } else if (component.isContinueWatching()) {
                addContinueWatching(component);
            } else if (component.isFavouriteChannels()) {
                addFavourites(component);
            } else if (component.isOnNow()) {
                addOnNow(component);
            } else if (!component.endPoint().isEmpty()) {
                addRail(component);
            }
        }
    }

    private void addHero(HomeComponent component) {
        FrameLayout hero = new FrameLayout(this);
        heroView = hero;
        hero.setBackground(Tv.box(this, HERO_PLATE, 10f, Tv.STROKE, 1f));
        // The hero is scenery: no focus, no white outline. Focus lives on Watch and More info.
        hero.setFocusable(false);
        hero.setClickable(false);
        hero.setClipToOutline(true);
        hero.setElevation(0f);
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(this, HERO_HEIGHT_DP));
        heroLp.bottomMargin = Tv.dp(this, HERO_GAP_DP);
        rails.addView(hero, heroLp);

        // A magenta spine belongs to the quiet plate only; real artwork covers it.
        View plateRule = new View(this);
        plateRule.setBackgroundColor(Tv.BRAND);
        hero.addView(plateRule, new FrameLayout.LayoutParams(Tv.dp(this, 3),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));

        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setVisibility(View.GONE);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        hero.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View leftScrim = new View(this);
        leftScrim.setBackgroundResource(R.drawable.bg_hero_scrim_left);
        leftScrim.setVisibility(View.GONE);
        hero.addView(leftScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Real CMS hero art sometimes carries a light design band at the very top.
        // A quiet top scrim melts it into the ground without touching the photograph.
        View topScrim = new View(this);
        topScrim.setBackgroundResource(R.drawable.bg_hero_scrim_top);
        topScrim.setVisibility(View.GONE);
        heroTopScrim = topScrim;
        hero.addView(topScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(this, 96), Gravity.TOP));

        View bottomScrim = new View(this);
        bottomScrim.setBackgroundResource(R.drawable.bg_scrim_bottom);
        bottomScrim.setAlpha(0.6f);
        bottomScrim.setVisibility(View.GONE);
        hero.addView(bottomScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(this, 120), Gravity.BOTTOM));

        int copyWidth = Math.max(Tv.dp(this, 300),
                Math.round(heroWidthPx() * HERO_COPY_RATIO));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.BOTTOM);
        copy.setVisibility(View.GONE);
        Tv.pad(copy, 28, 18, 20, 16);
        hero.addView(copy, new FrameLayout.LayoutParams(copyWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.BOTTOM));

        TextView liveTag = Tv.label(this, getString(R.string.guide_live), 0xFFFFFFFF);
        liveTag.setGravity(Gravity.CENTER);
        liveTag.setPadding(Tv.dp(this, 8), Tv.dp(this, 3), Tv.dp(this, 8), Tv.dp(this, 3));
        liveTag.setBackgroundResource(R.drawable.bg_chip_live);
        liveTag.setVisibility(View.GONE);
        copy.addView(liveTag, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = Tv.displayText(this, 38f, Tv.TEXT);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = Tv.dp(this, 7);
        copy.addView(title, titleLp);

        TextView meta = Tv.text(this, 13f, Tv.TEXT_2, false);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = Tv.dp(this, 4);
        copy.addView(meta, metaLp);

        TextView description = Tv.text(this, 13.5f, Tv.TEXT_2, false);
        description.setMaxLines(2);
        description.setEllipsize(TextUtils.TruncateAt.END);
        description.setLineSpacing(Tv.dp(this, 2), 1f);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = Tv.dp(this, 5);
        copy.addView(description, descLp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = Tv.dp(this, 10);
        copy.addView(actions, actionsLp);

        TextView watch = Tv.button(this, getString(R.string.common_watch), true);
        watch.setId(View.generateViewId());
        actions.addView(watch, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Tv.dp(this, 46)));

        TextView more = Tv.ghost(this, getString(R.string.common_more_info));
        more.setId(View.generateViewId());
        more.setVisibility(View.GONE);
        LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Tv.dp(this, 46));
        moreLp.leftMargin = Tv.dp(this, 12);
        actions.addView(more, moreLp);

        LinearLayout heroSkeleton = new LinearLayout(this);
        heroSkeleton.setOrientation(LinearLayout.VERTICAL);
        heroSkeleton.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        heroSkeleton.setContentDescription(getString(R.string.common_loading));
        heroSkeleton.addView(Skeleton.box(this, 0, HERO_HEIGHT_DP, 10f),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        Skeleton.pulse(heroSkeleton);
        hero.addView(heroSkeleton, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int gen = generation;
        Background.run(() -> repository.highlights(component.id),
                new Background.Result<List<Highlight>>() {
                    @Override
                    public void onSuccess(List<Highlight> highlights) {
                        if (gen != generation) {
                            return;
                        }
                        if (highlights == null || highlights.isEmpty()) {
                            hero.removeView(heroSkeleton);
                            hero.setVisibility(View.GONE);
                            heroPending = false;
                            focusFirstAvailableRail();
                            return;
                        }
                        Highlight first = preferredHighlight(highlights);
                        if (first == null) {
                            hero.removeView(heroSkeleton);
                            hero.setVisibility(View.GONE);
                            heroPending = false;
                            focusFirstAvailableRail();
                            return;
                        }
                        hero.removeView(heroSkeleton);
                        hero.setVisibility(View.VISIBLE);
                        copy.setVisibility(View.VISIBLE);

                        liveTag.setVisibility(isLiveHighlight(first) ? View.VISIBLE : View.GONE);
                        title.setText(first.title);
                        meta.setText(first.subTitle);
                        meta.setVisibility(first.subTitle.isEmpty() ? View.GONE : View.VISIBLE);
                        description.setText(first.description);
                        description.setVisibility(first.description.isEmpty()
                                ? View.GONE : View.VISIBLE);

                        final String deeplink = first.cta == null ? "" : first.cta.deeplink.trim();
                        watch.setOnClickListener(view -> {
                            if (first.cta != null) {
                                DeepLinks.open(HomeActivity.this, first.cta.deeplink);
                            }
                        });
                        // More info is only a second door to a details page; never invent one.
                        boolean detailsLink = !detailsLinkId(deeplink).isEmpty();
                        more.setVisibility(detailsLink ? View.VISIBLE : View.GONE);
                        more.setOnClickListener(view -> DeepLinks.open(HomeActivity.this, deeplink));

                        primaryFocusTarget = watch;
                        heroPending = false;
                        requestInitialFocus(watch);
                        loadHeroArtwork(image, leftScrim, bottomScrim, first, gen);
                    }

                    @Override
                    public void onError(ApiException error) {
                        // Highlights are non-fatal: the hero simply disappears.
                        hero.removeView(heroSkeleton);
                        hero.setVisibility(View.GONE);
                        heroPending = false;
                        focusFirstAvailableRail();
                    }
                });
    }

    /**
     * The feed's first slide sometimes has {@code "image": ""} while later slides carry
     * 1280x720 artwork. Take the first slide that actually has a usable image and leave the
     * endpoint order untouched for everything after it.
     */
    private static Highlight preferredHighlight(List<Highlight> highlights) {
        for (Highlight highlight : highlights) {
            if (highlight != null && !heroImageUrl(highlight.image).isEmpty()) {
                return highlight;
            }
        }
        for (Highlight highlight : highlights) {
            if (highlight != null) {
                return highlight;
            }
        }
        return null;
    }

    /**
     * Hero artwork: MAXtv's poster service serves the same asset at any requested size, and
     * the 1280x720 rendition is verified. Only that host and path shape are rewritten.
     */
    private static String heroImageUrl(String raw) {
        if (raw == null) {
            return "";
        }
        String url = raw.trim();
        if (url.isEmpty()) {
            return "";
        }
        return MAXTV_POSTER_PATH.matcher(url).replaceFirst(MAXTV_HERO_PATH);
    }

    /** {@code magiotv://details/<id>} and {@code magiotv://details/program/<id>} both end in the id. */
    private static String detailsLinkId(String deeplink) {
        if (deeplink == null) {
            return "";
        }
        String value = deeplink.trim();
        if (value.isEmpty()) {
            return "";
        }
        Uri uri;
        try {
            uri = Uri.parse(value);
        } catch (RuntimeException e) {
            return "";
        }
        if (!"details".equalsIgnoreCase(uri.getHost())) {
            return "";
        }
        List<String> segments = uri.getPathSegments();
        if (segments.isEmpty()) {
            return "";
        }
        return segments.get(segments.size() - 1).trim();
    }

    private static boolean isLiveHighlight(Highlight highlight) {
        if (highlight == null || highlight.subTitle == null) {
            return false;
        }
        String value = highlight.subTitle.trim().toLowerCase(Locale.ROOT);
        return value.endsWith("uživo") || value.endsWith("uzivo");
    }

    /**
     * Show the best artwork available for one slide. A missing image is resolved through the
     * programme-details endpoint (guest request) and only then upgraded to the 1280x720
     * rendition. If nothing resolves, the designed plate stays; no placeholder icon is used.
     */
    private void loadHeroArtwork(ImageView image, View leftScrim, View bottomScrim,
                                 Highlight highlight, int gen) {
        String url = heroImageUrl(highlight.image);
        if (!url.isEmpty()) {
            applyHeroArtwork(image, leftScrim, bottomScrim, url);
            return;
        }
        String programId = detailsLinkId(highlight.cta == null ? "" : highlight.cta.deeplink);
        if (programId.isEmpty()) {
            return;
        }
        ProgramDetailsRepository.load(this, programId, details -> {
            if (gen != generation || details == null) {
                return;
            }
            String poster = heroImageUrl(details.posterImageUrl);
            if (!poster.isEmpty()) {
                applyHeroArtwork(image, leftScrim, bottomScrim, poster);
            }
        });
    }

    private void applyHeroArtwork(ImageView image, View leftScrim, View bottomScrim,
                                  String url) {
        image.setVisibility(View.VISIBLE);
        leftScrim.setVisibility(View.VISIBLE);
        if (heroTopScrim != null) {
            heroTopScrim.setVisibility(View.VISIBLE);
        }
        bottomScrim.setVisibility(View.VISIBLE);
        ImageLoader.loadHero(image, url);
    }

    /** Hero width before the 32dp side gutters: the source rail and the content padding. */
    private int heroWidthPx() {
        return getResources().getDisplayMetrics().widthPixels
                - Tv.dp(this, 96) - Tv.dp(this, 64);
    }

    private void addContinueWatching(HomeComponent component) {
        if (!Session.get(this).isLoggedIn()) {
            return;
        }
        LinearLayout section = addSection(component.title, component, false);
        ProgramAdapter adapter = addRailContent(section);
        int gen = generation;
        Background.run(() -> bookmarkRepository.load(), new Background.Result<List<ContinueWatchingItem>>() {
            @Override
            public void onSuccess(List<ContinueWatchingItem> items) {
                if (gen != generation) {
                    return;
                }
                List<CardItem> cards = new ArrayList<>();
                for (ContinueWatchingItem item : items) {
                    cards.add(CardItem.of(item, HomeActivity.this));
                }
                submitRail(section, adapter, cards);
                focusFirstCard(section);
            }

            @Override
            public void onError(ApiException error) {
                hideSection(section);
            }
        });
    }

    private void addFavourites(HomeComponent component) {
        if (!Session.get(this).isLoggedIn()) {
            return;
        }
        LinearLayout section = addSection(component.title, component, true);
        ProgramAdapter adapter = addRailContent(section);
        int gen = generation;
        Background.run(() -> channelRepository.resolvedFavouriteChannels(false),
                new Background.Result<List<Channel>>() {
                    @Override
                    public void onSuccess(List<Channel> channels) {
                        if (gen != generation) {
                            return;
                        }
                        List<CardItem> cards = new ArrayList<>();
                        long nowMs = System.currentTimeMillis();
                        for (Channel channel : channels) {
                            EpgProgram now = EpgCache.programmeAt(channel.getNumber(), nowMs);
                            cards.add(CardItem.channel(channel.getTitle(HomeActivity.this),
                                    channel.getLogoUrl(), channel.getNumber(),
                                    now == null ? getString(R.string.channels_now) : now.title,
                                    channel.getQuality(),
                                    now == null ? 0 : Math.round(now.progress(nowMs) * 100f),
                                    channel));
                        }
                        submitRail(section, adapter, cards);
                        focusFirstCard(section);
                    }

                    @Override
                    public void onError(ApiException error) {
                        hideSection(section);
                    }
                });
    }

    private void addOnNow(HomeComponent component) {
        LinearLayout section = addSection(component.title, component, false);
        ProgramAdapter adapter = addRailContent(section);
        List<CardItem> cards = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (EpgChannel channel : EpgCache.channelsSnapshot()) {
            EpgProgram program = channel.programAt(now);
            if (program == null) {
                continue;
            }
            cards.add(CardItem.channel(channel.title, channel.logoUrl, channel.number,
                    program.displayTitle(this), channel.quality,
                    Math.round(program.progress(now) * 100f), channel));
            if (cards.size() >= RAIL_SIZE) {
                break;
            }
        }
        submitRail(section, adapter, cards);
        focusFirstCard(section);
    }

    private void addRail(HomeComponent component) {
        LinearLayout section = addSection(component.title, component, !component.endPoint().isEmpty());
        ProgramAdapter adapter = addRailContent(section);
        int gen = generation;
        Background.run(() -> repository.rail(component, 0, RAIL_SIZE),
                new Background.Result<HomeRailFeed>() {
                    @Override
                    public void onSuccess(HomeRailFeed feed) {
                        if (gen != generation) {
                            return;
                        }
                        List<CardItem> cards = new ArrayList<>();
                        for (HomeAsset asset : feed.assets) {
                            cards.add(CardItem.of(asset, HomeActivity.this));
                        }
                        long nowMs = System.currentTimeMillis();
                        for (Channel channel : feed.channels) {
                            EpgProgram now = EpgCache.programmeAt(channel.getNumber(), nowMs);
                            cards.add(CardItem.channel(channel.getTitle(HomeActivity.this),
                                    channel.getLogoUrl(), channel.getNumber(),
                                    now == null ? getString(R.string.channels_now) : now.title,
                                    channel.getQuality(),
                                    now == null ? 0 : Math.round(now.progress(nowMs) * 100f),
                                    channel));
                        }
                        submitRail(section, adapter, cards);
                        focusFirstCard(section);
                    }

                    @Override
                    public void onError(ApiException error) {
                        hideSection(section);
                    }
                });
    }

    private LinearLayout addSection(String title, HomeComponent component, boolean showMore) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sectionLp.bottomMargin = Tv.dp(this, SECTION_GAP_DP);
        rails.addView(section, sectionLp);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = Tv.displayText(this, 23f, Tv.TEXT);
        heading.setText(title == null ? "" : title.trim());
        header.addView(heading, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (showMore || component.isContinueWatching()
                || component.isFavouriteChannels()) {
            TextView more = Tv.ghost(this, getString(R.string.home_show_more));
            more.setOnClickListener(view -> showMore(component));
            header.addView(more, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Tv.dp(this, 40)));
        }
        section.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(this, HEADING_HEIGHT_DP)));
        return section;
    }

    private ProgramAdapter addRailContent(LinearLayout section) {
        FrameLayout row = new FrameLayout(this);
        RecyclerView rail = new RecyclerView(this);
        rail.setLayoutManager(new LinearLayoutManager(this,
                LinearLayoutManager.HORIZONTAL, false));
        rail.setHasFixedSize(true);
        rail.setItemAnimator(null);
        rail.setClipToPadding(false);
        rail.setClipChildren(false);
        rail.setPadding(0, Tv.dp(this, ROW_VPAD_DP), 0, Tv.dp(this, ROW_VPAD_DP));
        rail.addItemDecoration(new GutterDecoration(Tv.dp(this, CARD_GUTTER_DP)));
        rail.setVerticalScrollBarEnabled(false);
        rail.setHorizontalScrollBarEnabled(false);
        rail.setFocusable(false);
        ProgramAdapter adapter = new ProgramAdapter(this::openCard);
        rail.setAdapter(adapter);
        row.addView(rail, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout skeleton = skeletonCardRow();
        skeleton.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(this, CARD_ROW_HEIGHT_DP),
                Gravity.CENTER_VERTICAL));
        row.addView(skeleton);

        section.setTag(rail);
        rail.setTag(skeleton);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(this, CARD_ROW_HEIGHT_DP));
        rowLp.topMargin = Tv.dp(this, HEADING_TO_ROW_DP);
        section.addView(row, rowLp);
        return adapter;
    }

    /** The 12dp gutter between poster cards, applied to the card edge, not by shrinking cards. */
    private static final class GutterDecoration extends RecyclerView.ItemDecoration {
        private final int gutter;

        GutterDecoration(int gutter) {
            this.gutter = gutter;
        }

        @Override
        public void getItemOffsets(Rect outRect, View view, RecyclerView parent,
                                   RecyclerView.State state) {
            outRect.right = gutter;
        }
    }

    private void submitRail(LinearLayout section, ProgramAdapter adapter, List<CardItem> cards) {
        adapter.submit(cards);
        removeRailSkeleton(section);
        section.setVisibility(cards == null || cards.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void hideSection(LinearLayout section) {
        removeRailSkeleton(section);
        section.setVisibility(View.GONE);
    }

    private void removeRailSkeleton(LinearLayout section) {
        Object tag = section == null ? null : section.getTag();
        if (!(tag instanceof RecyclerView)) {
            return;
        }
        RecyclerView rail = (RecyclerView) tag;
        Object skeletonTag = rail.getTag();
        if (!(skeletonTag instanceof View)) {
            return;
        }
        View skeleton = (View) skeletonTag;
        ViewGroup parent = (ViewGroup) skeleton.getParent();
        if (parent != null) {
            parent.removeView(skeleton);
        }
        rail.setTag(null);
    }

    private void showMore(HomeComponent component) {
        if (component.isContinueWatching()) {
            startActivity(new Intent(this, ContinueWatchingActivity.class));
            return;
        }
        if (component.isFavouriteChannels()) {
            startActivity(ChannelListActivity.intentFavourites(this));
            return;
        }
        if (component.id.isEmpty()) {
            return;
        }
        startActivity(SubpageActivity.intent(this, component.id, component.title,
                component.endPoint(), component.feedType(), component.storeId));
    }

    private void focusFirstCard(LinearLayout section) {
        if (heroPending) {
            return;
        }
        Object tag = section.getTag();
        if (!(tag instanceof RecyclerView)) {
            return;
        }
        RecyclerView rail = (RecyclerView) tag;
        rail.post(() -> {
            View current = getCurrentFocus();
            if (initialFocusGiven || (current != null && !isInRail(current))) {
                return;
            }
            RecyclerView.ViewHolder holder = rail.findViewHolderForAdapterPosition(0);
            if (holder != null) {
                initialFocusGiven = true;
                if (primaryFocusTarget == null) {
                    primaryFocusTarget = holder.itemView;
                }
                host.rail.setContentFocusTarget(holder.itemView);
                holder.itemView.requestFocus();
            }
        });
    }

    private void focusFirstAvailableRail() {
        for (int i = 0; i < rails.getChildCount(); i++) {
            View child = rails.getChildAt(i);
            if (child instanceof LinearLayout && child.getVisibility() == View.VISIBLE) {
                focusFirstCard((LinearLayout) child);
                return;
            }
        }
    }

    private void requestInitialFocus(View target) {
        if (initialFocusGiven) {
            return;
        }
        target.post(() -> {
            View current = getCurrentFocus();
            if (initialFocusGiven || (current != null && !isInRail(current))) {
                return;
            }
            initialFocusGiven = true;
            host.rail.setContentFocusTarget(target);
            if (!target.requestFocus()) {
                initialFocusGiven = false;
                focusFirstAvailableRail();
            }
        });
    }

    private boolean isInRail(View view) {
        return isInView(view, host.rail);
    }

    private boolean isInCategories(View view) {
        return isInView(view, categoriesRow);
    }

    private boolean isInView(View view, View ancestor) {
        if (view == null || ancestor == null) {
            return false;
        }
        if (view == ancestor) {
            return true;
        }
        android.view.ViewParent parent = view.getParent();
        while (parent != null) {
            if (parent == ancestor) {
                return true;
            }
            parent = parent.getParent();
        }
        return false;
    }

    /** The active category chip, or the first one while the page is still loading. */
    private boolean focusSelectedCategory() {
        if (categoriesRow == null || categoriesRow.getChildCount() == 0) {
            return false;
        }
        View first = null;
        for (int i = 0; i < categoriesRow.getChildCount(); i++) {
            View chip = categoriesRow.getChildAt(i);
            if (chip == null || !chip.isShown()) {
                continue;
            }
            if (first == null) {
                first = chip;
            }
            if (chip.isSelected()) {
                return chip.requestFocus();
            }
        }
        return first != null && first.requestFocus();
    }

    private void focusHeaderAction() {
        if (refreshAction != null && refreshAction.isShown()) {
            refreshAction.requestFocus();
        }
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            View current = getCurrentFocus();
            int key = event.getKeyCode();
            // The categories are outside the ScrollView, so a geometric UP from the hero
            // lands on the sidebar. Route the top-of-content climb explicitly.
            if (key == android.view.KeyEvent.KEYCODE_DPAD_UP && isInView(current, heroView)) {
                if (!focusSelectedCategory()) {
                    focusHeaderAction();
                }
                return true;
            }
            if (key == android.view.KeyEvent.KEYCODE_DPAD_UP && isInCategories(current)) {
                focusHeaderAction();
                return true;
            }
            if (key == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                    && isInView(current, headerView)) {
                return focusSelectedCategory();
            }
            if (primaryFocusTarget != null && primaryFocusTarget.isShown()
                    && ((isInRail(current)
                    && key == android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
                    || (isInCategories(current)
                    && key == android.view.KeyEvent.KEYCODE_DPAD_DOWN))) {
                return primaryFocusTarget.requestFocus();
            }
            if (current == primaryFocusTarget
                    && key == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                host.rail.item(TvRail.Tab.HOME).requestFocus();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void openCard(CardItem card) {
        if (card == null) {
            return;
        }
        Object source = card.source;
        if (source instanceof Channel) {
            Channel channel = (Channel) source;
            PlaybackLauncher.open(this, channel, ChannelRepository.get(this).cachedChannels());
            return;
        }
        if (source instanceof EpgChannel) {
            EpgChannel epg = (EpgChannel) source;
            Channel channel = findChannel(epg.number);
            if (channel != null) {
                PlaybackLauncher.open(this, channel,
                        ChannelRepository.get(this).cachedChannels());
            }
            return;
        }
        if (source instanceof HomeAsset) {
            DeepLinks.openAsset(this, (HomeAsset) source);
            return;
        }
        if (source instanceof ContinueWatchingItem) {
            DeepLinks.openContinueWatching(this, (ContinueWatchingItem) source);
            return;
        }
        if (!card.deeplink.isEmpty()) {
            DeepLinks.open(this, card.deeplink);
        }
    }

    private Channel findChannel(int number) {
        for (Channel channel : ChannelRepository.get(this).cachedChannels()) {
            if (channel.getNumber() == number) {
                return channel;
            }
        }
        return null;
    }

    private void updateAccount() {
        Session session = Session.get(this);
        if (session.isLoggedIn()) {
            String name = session.displayName();
            if (TextUtils.isEmpty(name)) {
                name = session.username();
            }
            host.rail.setAccount(TextUtils.isEmpty(name)
                    ? getString(R.string.nav_settings) : name, true);
        } else {
            host.rail.setAccount(getString(R.string.action_sign_in), false);
        }
    }

    private static final class LoadResult {
        final String id;
        final HomeCategories categories;
        final HomePage page;

        LoadResult(String id, HomeCategories categories, HomePage page) {
            this.id = id;
            this.categories = categories;
            this.page = page;
        }
    }
}
