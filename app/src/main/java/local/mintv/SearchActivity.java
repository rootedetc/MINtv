package local.mintv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import local.mintv.data.Channel;
import local.mintv.data.HomeAsset;
import local.mintv.data.Session;
import local.mintv.net.ApiException;
import local.mintv.net.Background;
import local.mintv.player.PlaybackLauncher;
import local.mintv.search.Person;
import local.mintv.search.SearchPage;
import local.mintv.search.SearchRepository;
import local.mintv.search.SearchRow;
import local.mintv.search.SearchRowsAdapter;
import local.mintv.search.SearchType;
import local.mintv.ui.DeepLinks;
import local.mintv.ui.ShellHost;
import local.mintv.ui.Skeleton;
import local.mintv.ui.Tv;
import local.mintv.ui.TvRail;

/** Search: a TV search bar, server result sections, client-side channel filtering and local history. */
public final class SearchActivity extends BaseActivity {

    private static final int MIN_QUERY_LENGTH = 3;
    private static final int PAGE_SIZE = 30;
    private static final int ALL_SIZE = 3;
    private static final long DEBOUNCE_MS = 350L;

    private ShellHost host;
    private SearchRepository repository;
    private EditText search;
    private LinearLayout tabs;
    private RecyclerView results;
    private SearchRowsAdapter adapter;
    private SkeletonAdapter skeletonAdapter;
    private boolean skeletonVisible;
    private TextView status;

    private SearchType active = SearchType.ALL;
    private String query = "";
    private int generation;
    private int offset;
    private boolean hasMore;
    private boolean loadingMore;
    private boolean includeStreaming;
    private final List<Channel> channels = new ArrayList<>();
    private boolean channelsLoading;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable debounce = this::runSearch;

    public static Intent intent(Context context, String initialQuery) {
        Intent intent = new Intent(context, SearchActivity.class);
        if (initialQuery != null) {
            intent.putExtra("query", initialQuery);
        }
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        host = new ShellHost(this, TvRail.Tab.SEARCH);
        setContentView(host.root);
        repository = new SearchRepository(this);
        buildUi();
        boolean signedIn = Session.get(this).isLoggedIn();
        if (!signedIn) {
            showRows(Collections.singletonList(SearchRow.message(
                    getString(R.string.search_sign_in))));
            search.setEnabled(false);
            return;
        }
        showHistory();
        loadChannels();
        String initial = getIntent() == null ? "" : getIntent().getStringExtra("query");
        if (!TextUtils.isEmpty(initial)) {
            search.setText(initial);
            search.setSelection(initial.length());
        }
        search.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Session.get(this).isLoggedIn()) {
            if (!search.isEnabled()) {
                search.setEnabled(true);
                showHistory();
                loadChannels();
                search.requestFocus();
            }
        } else {
            search.setEnabled(false);
        }
        updateAccount();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(debounce);
        super.onPause();
    }

    private void buildUi() {
        host.addHeader(getString(R.string.search_title), getString(R.string.search_subtitle), 76);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(Tv.states(this, Tv.SURFACE, Tv.SURFACE_2, Tv.SURFACE_3,
                9f, Tv.STROKE, 1f, 0xFFFFFFFF, 2f));
        Tv.pad(bar, 16, 0, 16, 0);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(this, 54));
        barLp.leftMargin = Tv.dp(this, 32);
        barLp.rightMargin = Tv.dp(this, 32);
        host.content.addView(bar, barLp);

        bar.addView(Tv.icon(this, R.drawable.ic_search, Tv.TEXT_3, 20f),
                new LinearLayout.LayoutParams(Tv.dp(this, 28), Tv.dp(this, 28)));

        search = new EditText(this);
        search.setId(View.generateViewId());
        search.setSingleLine(true);
        search.setTextSize(20f);
        search.setTextColor(Tv.TEXT);
        search.setHintTextColor(Tv.TEXT_3);
        search.setHint(R.string.search_hint);
        search.setBackgroundColor(0x00000000);
        search.setPadding(Tv.dp(this, 10), 0, 0, 0);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        search.setSelectAllOnFocus(false);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                onQueryChanged(s == null ? "" : s.toString());
            }
        });
        search.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                handler.removeCallbacks(debounce);
                runSearch();
                hideKeyboard();
                return true;
            }
            return false;
        });
        search.setOnFocusChangeListener((view, hasFocus) -> bar.setBackground(
                Tv.states(this, Tv.SURFACE,
                        hasFocus ? Tv.SURFACE_2 : Tv.SURFACE,
                        Tv.SURFACE_3, 9f, Tv.STROKE, 1f, 0xFFFFFFFF, 2f)));
        bar.addView(search, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        TextView clear = Tv.ghost(this, "×");
        clear.setTextSize(20f);
        clear.setContentDescription(getString(R.string.search_clear));
        clear.setOnClickListener(view -> {
            search.setText("");
            showHistory();
            search.requestFocus();
        });
        bar.addView(clear, new LinearLayout.LayoutParams(
                Tv.dp(this, 42), Tv.dp(this, 40)));

        tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        Tv.pad(tabs, 32, 4, 32, 4);
        host.content.addView(tabs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(this, 46)));

        FrameLayout body = new FrameLayout(this);
        host.content.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        results = new RecyclerView(this);
        results.setLayoutManager(new LinearLayoutManager(this));
        results.setItemAnimator(null);
        results.setClipToPadding(false);
        results.setPadding(Tv.dp(this, 32), Tv.dp(this, 4),
                Tv.dp(this, 32), Tv.dp(this, 24));
        results.setVerticalScrollBarEnabled(false);
        adapter = new SearchRowsAdapter(this::onRow);
        results.setAdapter(adapter);
        results.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (dy <= 0 || loadingMore || !hasMore || adapter.rows().isEmpty()) {
                    return;
                }
                LinearLayoutManager manager =
                        (LinearLayoutManager) recyclerView.getLayoutManager();
                if (manager != null && manager.findLastVisibleItemPosition()
                        >= adapter.getItemCount() - 4) {
                    loadMore();
                }
            }
        });
        body.addView(results, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        status = Tv.text(this, 14f, Tv.TEXT_3, false);
        status.setGravity(Gravity.CENTER);
        body.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        host.rail.setContentFocusTarget(search);
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

    private void onQueryChanged(String text) {
        query = text == null ? "" : text.trim();
        handler.removeCallbacks(debounce);
        generation++;
        if (query.isEmpty()) {
            active = SearchType.ALL;
            hasMore = false;
            includeStreaming = false;
            buildTabs();
            showHistory();
            return;
        }
        if (query.length() < MIN_QUERY_LENGTH) {
            showRows(Collections.singletonList(SearchRow.message(
                    getString(R.string.search_min_length, MIN_QUERY_LENGTH))));
            status.setVisibility(View.GONE);
            return;
        }
        handler.postDelayed(debounce, DEBOUNCE_MS);
    }

    private void runSearch() {
        if (query.length() < MIN_QUERY_LENGTH) {
            return;
        }
        if (channelMode) {
            searchChannels();
        } else if (active == SearchType.ALL) {
            searchAll();
        } else {
            searchCategory(true);
        }
    }

    private void searchAll() {
        int current = ++generation;
        status.setVisibility(View.GONE);
        showSkeleton();
        Background.run(() -> repository.search(query, SearchType.ALL, 0, ALL_SIZE),
                new Background.Result<SearchPage>() {
                    @Override
                    public void onSuccess(SearchPage page) {
                        if (current != generation) {
                            return;
                        }
                        hideSkeleton();
                        renderAll(page);
                    }

                    @Override
                    public void onError(ApiException error) {
                        if (current != generation) {
                            return;
                        }
                        hideSkeleton();
                        showError(error);
                    }
                });
    }

    private void searchCategory(boolean reset) {
        int current = reset ? ++generation : generation;
        if (reset) {
            offset = 0;
            status.setVisibility(View.GONE);
            showSkeleton();
        } else {
            loadingMore = true;
        }
        int requestOffset = offset;
        SearchType type = active;
        String text = query;
        Background.run(() -> repository.search(text, type, requestOffset, PAGE_SIZE),
                new Background.Result<SearchPage>() {
                    @Override
                    public void onSuccess(SearchPage page) {
                        if (current != generation) {
                            return;
                        }
                        hideSkeleton();
                        loadingMore = false;
                        hasMore = page.hasMoreItems;
                        List<SearchRow> rows = new ArrayList<>();
                        appendGroup(rows, type, page);
                        if (reset) {
                            adapter.submit(rows);
                        } else {
                            adapter.append(rows);
                        }
                        offset += rows.size();
                        if (adapter.rows().isEmpty()) {
                            status.setText(getString(R.string.search_no_results, query));
                            status.setVisibility(View.VISIBLE);
                        } else {
                            status.setVisibility(View.GONE);
                        }
                    }

                    @Override
                    public void onError(ApiException error) {
                        if (current != generation) {
                            return;
                        }
                        hideSkeleton();
                        loadingMore = false;
                        showError(error);
                    }
                });
    }

    private void loadMore() {
        if (active == SearchType.ALL || active == SearchType.STREAMING) {
            return;
        }
        searchCategory(false);
    }

    private void renderAll(SearchPage page) {
        List<SearchRow> rows = new ArrayList<>();
        boolean hadStreaming = includeStreaming;
        includeStreaming = !page.streaming.isEmpty();
        addGroup(rows, getString(R.string.search_movies), SearchRow.programList(this, page.movies));
        addGroup(rows, getString(R.string.search_shows), SearchRow.programList(this, page.shows));
        addGroup(rows, getString(R.string.search_people), personRows(page.persons));
        if (includeStreaming) {
            addGroup(rows, getString(R.string.search_streaming),
                    SearchRow.programList(this, page.streaming));
        }
        if (rows.isEmpty()) {
            rows.add(SearchRow.message(getString(R.string.search_no_results, query)));
        }
        adapter.submit(rows);
        status.setVisibility(View.GONE);
        if (hadStreaming != includeStreaming) {
            buildTabs();
        }
        results.scrollToPosition(0);
    }

    private void addGroup(List<SearchRow> rows, String title, List<SearchRow> group) {
        if (group.isEmpty()) {
            return;
        }
        rows.add(SearchRow.header(title));
        rows.addAll(group);
    }

    private List<SearchRow> personRows(List<Person> people) {
        List<SearchRow> rows = new ArrayList<>();
        for (Person person : people) {
            rows.add(SearchRow.person(person));
        }
        return rows;
    }

    private void appendGroup(List<SearchRow> rows, SearchType type, SearchPage page) {
        switch (type) {
            case MOVIES:
                for (HomeAsset asset : page.movies) {
                    rows.add(SearchRow.program(this, asset));
                }
                break;
            case SHOWS:
                for (HomeAsset asset : page.shows) {
                    rows.add(SearchRow.program(this, asset));
                }
                break;
            case STREAMING:
                for (HomeAsset asset : page.streaming) {
                    rows.add(SearchRow.program(this, asset));
                }
                break;
            case PEOPLE:
                rows.addAll(personRows(page.persons));
                break;
            default:
                break;
        }
    }

    private void showHistory() {
        List<SearchRow> rows = new ArrayList<>();
        List<String> history = repository.history();
        if (history.isEmpty()) {
            rows.add(SearchRow.message(getString(R.string.search_no_history)));
            rows.add(SearchRow.message(getString(R.string.search_history_hint)));
            status.setVisibility(View.GONE);
        } else {
            rows.add(SearchRow.header(getString(R.string.search_history)));
            for (String item : history) {
                rows.add(SearchRow.history(item));
            }
            rows.add(SearchRow.action(getString(R.string.search_clear_history),
                    SearchRow.ACTION_CLEAR));
        }
        showRows(rows);
        buildTabs();
    }

    private void showRows(List<SearchRow> rows) {
        hideSkeleton();
        adapter.submit(rows);
    }

    private void showError(ApiException error) {
        List<SearchRow> rows = new ArrayList<>();
        rows.add(SearchRow.message(String.valueOf(error.getMessage())));
        rows.add(SearchRow.action(getString(R.string.action_retry), "retry"));
        showRows(rows);
    }

    /**
     * Replaces the rundown with four quiet placeholder rows while the first page of a new
     * search is in flight. Rows already on screen are left alone during pagination.
     */
    private void showSkeleton() {
        if (skeletonVisible) {
            return;
        }
        skeletonVisible = true;
        status.setVisibility(View.GONE);
        if (skeletonAdapter == null) {
            skeletonAdapter = new SkeletonAdapter();
        }
        results.setAdapter(skeletonAdapter);
    }

    private void hideSkeleton() {
        if (!skeletonVisible) {
            return;
        }
        skeletonVisible = false;
        results.setAdapter(adapter);
    }

    /** A 148x84 thumbnail, a 60% title line, a 40% metadata line and a badge, four times. */
    private static final class SkeletonAdapter
            extends RecyclerView.Adapter<SkeletonAdapter.Holder> {

        private static final int ROWS = 4;

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(skeletonRow(parent.getContext()));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
        }

        @Override
        public int getItemCount() {
            return ROWS;
        }

        static final class Holder extends RecyclerView.ViewHolder {
            Holder(View itemView) {
                super(itemView);
            }
        }
    }

    private static View skeletonRow(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Tv.box(context, Tv.SURFACE, 8f, Tv.STROKE, 1f));
        Tv.pad(row, 14, 9, 16, 9);
        row.setFocusable(false);
        row.setClickable(false);
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(
                Tv.dp(context, 148), Tv.dp(context, 84));
        thumbLp.rightMargin = Tv.dp(context, 14);
        content.addView(Skeleton.box(context, 148f, 84f), thumbLp);

        LinearLayout text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        content.addView(text, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        text.addView(fractionLine(context, 0.6f, 14f));
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(context, 10));
        metaLp.topMargin = Tv.dp(context, 8);
        text.addView(fractionLine(context, 0.4f, 10f), metaLp);

        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                Tv.dp(context, 52), Tv.dp(context, 20));
        badgeLp.leftMargin = Tv.dp(context, 16);
        content.addView(Skeleton.box(context, 52f, 20f), badgeLp);

        Skeleton.pulse(content);
        return row;
    }

    /** A line occupying a fraction of its lane, with the remainder kept empty. */
    private static View fractionLine(Context context, float fraction, float heightDp) {
        LinearLayout lane = new LinearLayout(context);
        lane.setOrientation(LinearLayout.HORIZONTAL);
        lane.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(context, heightDp)));
        lane.addView(Skeleton.line(context, 0f, heightDp), new LinearLayout.LayoutParams(
                0, Tv.dp(context, heightDp), fraction));
        View remainder = new View(context);
        lane.addView(remainder, new LinearLayout.LayoutParams(0, Math.max(1, Tv.dp(context, 1)),
                1f - fraction));
        return lane;
    }

    private void onRow(SearchRow row) {
        if (SearchRow.ACTION_CLEAR.equals(row.action)) {
            repository.clearHistory();
            showHistory();
            return;
        }
        if (SearchRow.ACTION_DELETE.equals(row.action)) {
            repository.removeQuery(row.title);
            showHistory();
            return;
        }
        if ("retry".equals(row.action)) {
            runSearch();
            return;
        }
        if (row.type == SearchRowsAdapter.TYPE_HISTORY) {
            search.setText(row.title);
            search.setSelection(row.title.length());
            handler.removeCallbacks(debounce);
            active = SearchType.ALL;
            runSearch();
            return;
        }
        if (row.type == SearchRowsAdapter.TYPE_PROGRAM && row.source instanceof HomeAsset) {
            DeepLinks.openAsset(this, (HomeAsset) row.source);
            return;
        }
        if (row.type == SearchRowsAdapter.TYPE_PERSON && row.source instanceof Person) {
            Person person = (Person) row.source;
            startActivity(PersonActivity.intent(this, person.id, person.name, person.imageUrl));
            return;
        }
        if (row.type == SearchRowsAdapter.TYPE_CHANNEL && row.source instanceof Channel) {
            Channel channel = (Channel) row.source;
            PlaybackLauncher.open(this, channel, channels);
        }
    }

    private void buildTabs() {
        tabs.removeAllViews();
        addTab(getString(R.string.search_tab_all), SearchType.ALL);
        addTab(getString(R.string.search_tab_movies), SearchType.MOVIES);
        addTab(getString(R.string.search_tab_shows), SearchType.SHOWS);
        addTab(getString(R.string.search_tab_people), SearchType.PEOPLE);
        addTab(getString(R.string.search_tab_channels), SearchType.ALL);
        if (includeStreaming) {
            addTab(getString(R.string.search_tab_streaming), SearchType.STREAMING);
        }
    }

    private void addTab(String label, SearchType type) {
        boolean isChannels = getString(R.string.search_tab_channels).equals(label);
        TextView chip = Tv.displayText(this, 13.5f, Tv.TEXT_3);
        chip.setText(label);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(Tv.dp(this, 13), 0, Tv.dp(this, 13), 0);
        chip.setBackground(Tv.states(this, 0x00000000, Tv.SURFACE_2, Tv.SURFACE_3,
                6f, 0, 0f, 0xFFFFFFFF, 2f));
        chip.setFocusable(true);
        chip.setClickable(true);
        boolean selected = isChannels ? channelMode
                : (!channelMode && active == type);
        chip.setTextColor(selected ? Tv.BRAND_BRIGHT : Tv.TEXT_3);
        chip.setOnClickListener(view -> {
            channelMode = isChannels;
            active = isChannels ? SearchType.ALL : type;
            buildTabs();
            if (query.length() >= MIN_QUERY_LENGTH) {
                if (channelMode) {
                    searchChannels();
                } else {
                    handler.removeCallbacks(debounce);
                    runSearch();
                }
            }
            chip.requestFocus();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Tv.dp(this, 34));
        lp.rightMargin = Tv.dp(this, 6);
        tabs.addView(chip, lp);
    }

    private boolean channelMode;

    private void searchChannels() {
        if (channels.isEmpty() && !channelsLoading) {
            loadChannels();
        }
        List<SearchRow> rows = new ArrayList<>();
        String needle = query.toLowerCase(Locale.getDefault());
        List<Channel> exact = new ArrayList<>();
        List<Channel> partial = new ArrayList<>();
        for (Channel channel : channels) {
            String title = channel.getTitle(this).toLowerCase(Locale.getDefault());
            String number = String.valueOf(channel.getNumber());
            if (number.equals(query) || number.startsWith(query)) {
                exact.add(channel);
            } else if (number.contains(query) || title.startsWith(needle)
                    || title.contains(needle)) {
                partial.add(channel);
            }
        }
        rows.add(SearchRow.header(getString(R.string.search_channels_header)));
        for (Channel channel : exact) {
            rows.add(channelRow(channel));
        }
        for (Channel channel : partial) {
            rows.add(channelRow(channel));
        }
        if (rows.size() == 1) {
            rows.add(SearchRow.message(getString(R.string.search_no_results, query)));
        }
        showRows(rows);
        status.setVisibility(View.GONE);
    }

    private SearchRow channelRow(Channel channel) {
        local.mintv.epg.EpgProgram now = local.mintv.epg.EpgCache.programmeAt(
                channel.getNumber(), System.currentTimeMillis());
        return SearchRow.channel(channel.getTitle(this),
                now == null ? getString(R.string.channels_now) : now.displayTitle(this),
                channel.getLogoUrl(), channel.getNumber(), channel);
    }

    private void loadChannels() {
        if (channelsLoading) {
            return;
        }
        channelsLoading = true;
        Background.run(() -> repository.channels(),
                new Background.Result<List<Channel>>() {
                    @Override
                    public void onSuccess(List<Channel> loaded) {
                        channelsLoading = false;
                        channels.clear();
                        channels.addAll(loaded);
                        if (channelMode && query.length() >= MIN_QUERY_LENGTH) {
                            searchChannels();
                        }
                    }

                    @Override
                    public void onError(ApiException error) {
                        channelsLoading = false;
                    }
                });
    }

    private void hideKeyboard() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) {
            imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN
                && search.hasFocus() && !skeletonVisible && adapter.getItemCount() > 0) {
            results.requestFocus();
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP
                && results.hasFocus()) {
            search.requestFocus();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
