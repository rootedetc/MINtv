package local.maxtv.lite.epg;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import local.maxtv.lite.net.GuestAuth;
import local.maxtv.lite.net.Http;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Loads the anonymous guest EPG (channel line-up + one day of schedules) and keeps a compact
 * per-day cache on disk.
 *
 * <p>The wire needs nine requests: one for the line-up and eight three-hour schedule pages.
 * Pages overlap by design, so programmes are de-duplicated on (station, start, end) before the
 * line-up and schedules are joined. Only stations that exist in the line-up are returned, in
 * {@code channel_number} order; stations without programmes still get a row with an empty list.</p>
 *
 * <p>Threading: all network and JSON work runs on a small background executor, callbacks are
 * posted to the main thread. A load is ignored while another one is running, and a generation
 * counter stops a late callback from an older load from overwriting a newer day.</p>
 *
 * <p>Cache: {@code filesDir/epg/<yyyy-MM-dd>.json}, compact, written atomically through a
 * {@code .tmp} file. {@link #loadDay} delivers the cache first (when present) and then the
 * network result, so the grid paints immediately. Files older than seven days are pruned.</p>
 *
 * <p>The cache carries a {@code version}: a file written by an older build (no version, or a
 * different one) is ignored rather than served, so the first load after an upgrade refetches the
 * day and picks up the catch-up metadata instead of playing from an incomplete row.</p>
 */
public final class EpgRepository {

    public interface Callback {
        void onLoaded(List<EpgChannel> channels);
    }

    public interface ErrorCallback {
        void onError(String message);
    }

    private static final String TAG = "EpgRepo";
    private static final String BASE_URL = "https://tv-hr-prod.yo-digital.com/hr-bifrost/epg/";
    private static final String CACHE_DIR = "epg";
    private static final int PAGE_COUNT = 8;
    private static final int PAGE_HOURS = 3;
    private static final int PAGE_THREADS = 3;
    private static final int CACHE_RETENTION_DAYS = 7;
    private static final int CACHE_BUFFER = 64 * 1024;
    /** Bumped whenever the cached programme shape changes; older files are refetched. */
    private static final int CACHE_VERSION = 2;

    private static final String MSG_BAD_DATE = "Neispravan datum.";
    private static final String MSG_NO_DAY = "Nijedan dan nije učitan.";
    private static final String MSG_CHANNELS = "Popis kanala nije dostupan.";
    private static final String MSG_SCHEDULE = "Programska šema nije dostupna.";
    private static final String MSG_UNEXPECTED = "Neočekivana pogreška pri učitavanju EPG-a.";

    private final Context appContext;
    private final File cacheDir;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(
            runnable -> daemon(runnable, "epg-repo"));
    private final ExecutorService pagePool = Executors.newFixedThreadPool(
            PAGE_THREADS, runnable -> daemon(runnable, "epg-page"));
    private final AtomicBoolean loading = new AtomicBoolean(false);
    private final AtomicInteger generation = new AtomicInteger(0);

    private volatile List<EpgChannel> channels = Collections.emptyList();
    private volatile String currentDate;

    public EpgRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.cacheDir = new File(appContext.getFilesDir(), CACHE_DIR);
    }

    /** Cached channels for the last loaded day, empty until the first load. */
    public List<EpgChannel> channels() {
        return channels;
    }

    /** Loads channels + a full day of EPG. Callback on the main thread. */
    public void loadDay(String isoDate, Callback onLoaded, ErrorCallback onError) {
        final String date = normaliseDate(isoDate);
        if (date == null) {
            Log.w(TAG, "Rejected invalid date: " + isoDate);
            postNow(onError, MSG_BAD_DATE);
            return;
        }
        if (!loading.compareAndSet(false, true)) {
            Log.i(TAG, "loadDay(" + date + ") ignored: a load is already running");
            return;
        }
        final int gen = generation.incrementAndGet();
        Log.i(TAG, "loadDay(" + date + ") gen=" + gen);
        coordinator.execute(() -> runLoad(gen, date, onLoaded, onError));
    }

    /** Reloads the day already in use. */
    public void refresh(Callback onLoaded, ErrorCallback onError) {
        String date = currentDate;
        if (date == null) {
            postNow(onError, MSG_NO_DAY);
            return;
        }
        loadDay(date, onLoaded, onError);
    }

    /** ISO date of the loaded day, or {@code null} before the first delivery. */
    public String currentDate() {
        return currentDate;
    }

    public boolean isLoading() {
        return loading.get();
    }

    // ---------------------------------------------------------------------------------------
    // Load pipeline (background)
    // ---------------------------------------------------------------------------------------

    private void runLoad(int gen, String date, Callback onLoaded, ErrorCallback onError) {
        try {
            ensureCacheDir();
            pruneCache();

            List<EpgChannel> cached = readCache(date);
            if (cached != null && !cached.isEmpty() && isCurrent(gen)) {
                Log.i(TAG, "Delivering " + cached.size() + " cached channels for " + date);
                publish(date, cached);
                post(gen, onLoaded, cached);
            }

            List<ChannelInfo> lineup;
            try {
                lineup = parseChannels(fetchJson(channelUrl()));
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Channel line-up failed for " + date, e);
                postError(gen, onError, MSG_CHANNELS);
                return;
            }
            if (lineup.isEmpty()) {
                Log.w(TAG, "Channel line-up for " + date + " was empty");
                postError(gen, onError, MSG_CHANNELS);
                return;
            }
            if (!isCurrent(gen)) {
                return;
            }

            ScheduleResult schedules = fetchSchedules(gen, date);
            if (!isCurrent(gen)) {
                return;
            }
            if (schedules.okPages == 0 || schedules.programmes == 0) {
                Log.w(TAG, "All " + PAGE_COUNT + " EPG pages failed for " + date);
                postError(gen, onError, MSG_SCHEDULE);
                return;
            }
            if (schedules.failedPages > 0) {
                Log.w(TAG, "Partial EPG for " + date + ": " + schedules.failedPages
                        + "/" + PAGE_COUNT + " pages failed; keeping the rest");
            }

            List<EpgChannel> merged = merge(lineup, schedules.byStation);
            if (merged.isEmpty()) {
                Log.w(TAG, "Merge produced no channels for " + date);
                postError(gen, onError, MSG_SCHEDULE);
                return;
            }
            publish(date, merged);
            post(gen, onLoaded, merged);
            if (schedules.failedPages == 0) {
                writeCache(date, merged);
            } else {
                // Never replace a complete cached day with a partial one; the UI still gets
                // everything the network did return this time.
                Log.i(TAG, "Not caching " + date + " because the day is incomplete");
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "EPG load crashed for " + date, e);
            postError(gen, onError, MSG_UNEXPECTED);
        } finally {
            loading.set(false);
        }
    }

    private ScheduleResult fetchSchedules(int gen, String date) {
        List<Future<PageResult>> futures = new ArrayList<>(PAGE_COUNT);
        for (int i = 0; i < PAGE_COUNT; i++) {
            final int hourOffset = i * PAGE_HOURS;
            futures.add(pagePool.submit(() -> fetchPage(gen, date, hourOffset)));
        }

        Map<String, List<EpgProgram>> byStation = new HashMap<>(512);
        int okPages = 0;
        int failedPages = 0;
        int programmeCount = 0;
        for (Future<PageResult> future : futures) {
            PageResult result;
            try {
                result = future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failedPages++;
                continue;
            } catch (ExecutionException e) {
                Log.w(TAG, "EPG page task failed", e.getCause());
                failedPages++;
                continue;
            }
            if (result == null || !result.ok) {
                failedPages++;
                continue;
            }
            okPages++;
            for (Map.Entry<String, List<EpgProgram>> entry : result.byStation.entrySet()) {
                List<EpgProgram> target = byStation.get(entry.getKey());
                if (target == null) {
                    target = new ArrayList<>();
                    byStation.put(entry.getKey(), target);
                }
                target.addAll(entry.getValue());
                programmeCount += entry.getValue().size();
            }
        }
        Log.i(TAG, "EPG " + date + ": " + okPages + " pages ok, " + failedPages
                + " failed, " + programmeCount + " programme rows");
        return new ScheduleResult(byStation, okPages, failedPages, programmeCount);
    }

    private PageResult fetchPage(int gen, String date, int hourOffset) {
        if (!isCurrent(gen)) {
            return PageResult.failed();
        }
        try {
            JSONObject root = fetchJson(schedulesUrl(date, hourOffset));
            return new PageResult(parsePage(root), true);
        } catch (IOException e) {
            Log.w(TAG, "EPG page offset=" + hourOffset + " failed: " + e.getMessage());
            return PageResult.failed();
        } catch (RuntimeException e) {
            Log.w(TAG, "EPG page offset=" + hourOffset + " is malformed", e);
            return PageResult.failed();
        }
    }

    /**
     * Executes one guest GET. OkHttp transparently gunzips the body (no Accept-Encoding header is
     * sent), and android's {@code JSONTokener} only accepts a String, so one page at a time is
     * materialised; the tree and text become garbage as soon as the page is flattened.
     */
    private JSONObject fetchJson(HttpUrl url) throws IOException {
        Request.Builder request = new Request.Builder().url(url).get();
        for (Map.Entry<String, String> header : GuestAuth.headers(appContext).entrySet()) {
            request.header(header.getKey(), header.getValue());
        }
        try (Response response = Http.client().newCall(request.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " " + url.encodedPath());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Empty body " + url.encodedPath());
            }
            try {
                return new JSONObject(body.string());
            } catch (JSONException e) {
                throw new IOException("Malformed JSON from " + url.encodedPath(), e);
            }
        }
    }

    private static HttpUrl channelUrl() {
        return HttpUrl.get(BASE_URL).newBuilder()
                .addPathSegment("channel")
                .addQueryParameter("channelMap_id", "")
                .addQueryParameter("includeVirtualChannels", "false")
                .addQueryParameter("natco_key", GuestAuth.NATCO_KEY)
                .addQueryParameter("app_language", GuestAuth.APP_LANGUAGE)
                .addQueryParameter("natco_code", GuestAuth.NATCO_CODE)
                .build();
    }

    private static HttpUrl schedulesUrl(String date, int hourOffset) {
        return HttpUrl.get(BASE_URL).newBuilder()
                .addPathSegment("channel")
                .addPathSegment("schedules")
                .addQueryParameter("date", date)
                .addQueryParameter("hour_offset", Integer.toString(hourOffset))
                .addQueryParameter("hour_range", Integer.toString(PAGE_HOURS))
                .addQueryParameter("channelMap_id", "")
                .addQueryParameter("filler", "true")
                .addQueryParameter("app_language", GuestAuth.APP_LANGUAGE)
                .addQueryParameter("natco_code", GuestAuth.NATCO_CODE)
                .build();
    }

    // ---------------------------------------------------------------------------------------
    // Parsing + merge (no Android dependencies, exercised by the JVM harness)
    // ---------------------------------------------------------------------------------------

    static List<ChannelInfo> parseChannels(JSONObject root) {
        JSONArray array = root.optJSONArray("channels");
        if (array == null || array.length() == 0) {
            return Collections.emptyList();
        }
        List<ChannelInfo> lineup = new ArrayList<>(array.length());
        Set<String> seenStations = new HashSet<>(array.length() * 2);
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.optJSONObject(i);
            if (object == null) {
                continue;
            }
            String stationId = text(object, "station_id");
            String title = text(object, "title");
            if (stationId.isEmpty() && title.isEmpty()) {
                continue;
            }
            if (!stationId.isEmpty() && !seenStations.add(stationId)) {
                continue;
            }
            lineup.add(new ChannelInfo(
                    object.optInt("channel_number", Integer.MAX_VALUE),
                    stationId,
                    title,
                    text(object, "channel_logo"),
                    text(object, "quality"),
                    object.optBoolean("is_free_to_air", false)));
        }
        lineup.sort(Comparator.comparingInt((ChannelInfo channel) -> channel.number)
                .thenComparing(channel -> channel.title, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(channel -> channel.stationId));
        return lineup;
    }

    /**
     * Flattens one schedule page into station-id -> programmes. Invalid rows are dropped here so
     * they never reach the merge or the cache.
     */
    static Map<String, List<EpgProgram>> parsePage(JSONObject root) {
        JSONObject stations = root.optJSONObject("channels");
        if (stations == null || stations.length() == 0) {
            return Collections.emptyMap();
        }
        Map<String, List<EpgProgram>> byStation = new HashMap<>(stations.length() * 2);
        for (Iterator<String> keys = stations.keys(); keys.hasNext(); ) {
            String stationId = keys.next();
            JSONArray array = stations.optJSONArray(stationId);
            if (array == null || array.length() == 0) {
                continue;
            }
            List<EpgProgram> programmes = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                EpgProgram programme = EpgProgram.fromJson(stationId, object);
                if (!isValid(programme)) {
                    continue;
                }
                programmes.add(programme);
            }
            if (!programmes.isEmpty()) {
                byStation.put(stationId, programmes);
            }
        }
        return byStation;
    }

    /** Joins the line-up with the schedules, keeping row order and empty channels. */
    static List<EpgChannel> merge(List<ChannelInfo> lineup,
                                  Map<String, List<EpgProgram>> schedules) {
        List<EpgChannel> result = new ArrayList<>(lineup.size());
        for (ChannelInfo channel : lineup) {
            List<EpgProgram> raw = channel.stationId.isEmpty()
                    ? Collections.emptyList()
                    : schedules.get(channel.stationId);
            result.add(new EpgChannel(
                    channel.number,
                    channel.stationId,
                    channel.title,
                    channel.logoUrl,
                    channel.quality,
                    channel.freeToAir,
                    dedupe(raw)));
        }
        return result;
    }

    /** Drops invalid times and rows repeated by overlapping schedule pages. */
    static List<EpgProgram> dedupe(List<EpgProgram> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        List<EpgProgram> valid = new ArrayList<>(raw.size());
        for (EpgProgram programme : raw) {
            if (programme != null && isValid(programme)) {
                valid.add(programme);
            }
        }
        valid.sort(Comparator.comparingLong((EpgProgram programme) -> programme.startMs)
                .thenComparingLong(programme -> programme.endMs)
                .thenComparing(programme -> programme.id == null ? "" : programme.id));
        List<EpgProgram> unique = new ArrayList<>(valid.size());
        EpgProgram previous = null;
        for (EpgProgram programme : valid) {
            if (previous != null
                    && previous.startMs == programme.startMs
                    && previous.endMs == programme.endMs) {
                continue;
            }
            unique.add(programme);
            previous = programme;
        }
        return unique;
    }

    private static boolean isValid(EpgProgram programme) {
        return programme.startMs > 0L && programme.endMs > programme.startMs;
    }

    private static String text(JSONObject object, String key) {
        String value = object.optString(key, "");
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return "null".equalsIgnoreCase(trimmed) ? "" : trimmed;
    }

    // ---------------------------------------------------------------------------------------
    // Cache
    // ---------------------------------------------------------------------------------------

    private void ensureCacheDir() {
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
            Log.w(TAG, "Unable to create " + cacheDir);
        }
    }

    private void pruneCache() {
        File[] files = cacheDir.listFiles();
        if (files == null) {
            return;
        }
        LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(CACHE_RETENTION_DAYS);
        long staleBefore = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(CACHE_RETENTION_DAYS);
        int deleted = 0;
        for (File file : files) {
            String name = file.getName();
            boolean expired;
            if (name.endsWith(".json")) {
                try {
                    expired = LocalDate.parse(name.substring(0, name.length() - 5))
                            .isBefore(cutoff);
                } catch (DateTimeParseException e) {
                    expired = file.lastModified() < staleBefore;
                }
            } else {
                expired = file.lastModified() < staleBefore;
            }
            if (expired && file.delete()) {
                deleted++;
            }
        }
        if (deleted > 0) {
            Log.i(TAG, "Pruned " + deleted + " expired EPG cache file(s)");
        }
    }

    private List<EpgChannel> readCache(String date) {
        File file = new File(cacheDir, date + ".json");
        if (!file.isFile()) {
            return null;
        }
        // Streaming read: the cached day is ~1.7 MB and must not become a String plus a tree.
        try (Reader input = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8),
                CACHE_BUFFER);
             JsonReader reader = new JsonReader(input)) {
            List<EpgChannel> cached = parseCache(reader, date);
            if (cached.isEmpty()) {
                Log.w(TAG, "Cache for " + date + " has no channels; ignoring it");
                return null;
            }
            cached.sort(Comparator.comparingInt((EpgChannel channel) -> channel.number)
                    .thenComparing(channel -> channel.title, String.CASE_INSENSITIVE_ORDER));
            Log.i(TAG, "Read " + cached.size() + " channels from cache " + file.getName());
            return cached;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Cache " + file.getName() + " is unreadable; deleting it", e);
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return null;
        }
    }

    private void writeCache(String date, List<EpgChannel> data) {
        ensureCacheDir();
        File target = new File(cacheDir, date + ".json");
        File temp = new File(cacheDir, date + ".json.tmp");
        boolean written = false;
        try (Writer output = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8),
                CACHE_BUFFER);
             JsonWriter writer = new JsonWriter(output)) {
            writer.beginObject();
            writer.name("version").value(CACHE_VERSION);
            writer.name("date").value(date);
            writer.name("channels").beginArray();
            for (EpgChannel channel : data) {
                writer.beginObject();
                writer.name("number").value(channel.number);
                writer.name("station_id").value(nonNull(channel.stationId));
                writer.name("title").value(nonNull(channel.title));
                writer.name("logo_url").value(nonNull(channel.logoUrl));
                writer.name("quality").value(nonNull(channel.quality));
                writer.name("free_to_air").value(channel.freeToAir);
                writer.name("programs").beginArray();
                for (EpgProgram programme : channel.programs()) {
                    writer.beginObject();
                    writer.name("id").value(nonNull(programme.id));
                    writer.name("title").value(nonNull(programme.title));
                    writer.name("description").value(nonNull(programme.description));
                    writer.name("genre").value(nonNull(programme.genre));
                    writer.name("show_type").value(nonNull(programme.showType));
                    writer.name("start").value(programme.startMs);
                    writer.name("end").value(programme.endMs);
                    writer.name("catchup").value(programme.catchup);
                    writer.name("adult").value(programme.adult);
                    writer.name("listing_guid").value(nonNull(programme.listingGuid));
                    writer.name("catchup_id").value(nonNull(programme.catchupId));
                    writer.name("catchup_key").value(nonNull(programme.catchupKey));
                    writer.name("media_id").value(nonNull(programme.mediaId));
                    writer.name("has_start_over").value(programme.hasStartOver);
                    writer.name("has_timeshift").value(programme.hasTimeshift);
                    writer.endObject();
                }
                writer.endArray();
                writer.endObject();
            }
            writer.endArray();
            writer.endObject();
            written = true;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Cache write failed for " + date, e);
        }
        if (!written) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            return;
        }
        if (!temp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            if (!temp.renameTo(target)) {
                Log.w(TAG, "Unable to replace cache file for " + date);
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                return;
            }
        }
        Log.i(TAG, "Cached " + data.size() + " channels for " + date
                + " (" + target.length() + " bytes)");
    }

    static List<EpgChannel> parseCache(JsonReader reader, String expectedDate)
            throws IOException {
        int version = 0;
        String date = null;
        List<EpgChannel> channels = new ArrayList<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if ("version".equals(name)) {
                version = (int) nextLong(reader);
            } else if ("date".equals(name)) {
                date = nextString(reader);
            } else if ("channels".equals(name)) {
                reader.beginArray();
                while (reader.hasNext()) {
                    channels.add(readCachedChannel(reader));
                }
                reader.endArray();
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
        if (version != CACHE_VERSION) {
            // Old shape (no version at all, or a previous one): let the caller refetch.
            return Collections.emptyList();
        }
        if (date != null && !date.equals(expectedDate)) {
            return Collections.emptyList();
        }
        return channels;
    }

    private static EpgChannel readCachedChannel(JsonReader reader) throws IOException {
        int number = 0;
        String stationId = "";
        String title = "";
        String logoUrl = "";
        String quality = "";
        boolean freeToAir = false;
        List<EpgProgram> programmes = new ArrayList<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            switch (name) {
                case "number":
                    number = (int) nextLong(reader);
                    break;
                case "station_id":
                    stationId = nextString(reader);
                    break;
                case "title":
                    title = nextString(reader);
                    break;
                case "logo_url":
                    logoUrl = nextString(reader);
                    break;
                case "quality":
                    quality = nextString(reader);
                    break;
                case "free_to_air":
                    freeToAir = nextBoolean(reader);
                    break;
                case "programs":
                    reader.beginArray();
                    while (reader.hasNext()) {
                        programmes.add(readCachedProgram(reader));
                    }
                    reader.endArray();
                    break;
                default:
                    reader.skipValue();
                    break;
            }
        }
        reader.endObject();
        return new EpgChannel(number, stationId, title, logoUrl, quality, freeToAir, programmes);
    }

    private static EpgProgram readCachedProgram(JsonReader reader) throws IOException {
        String id = "";
        String title = "";
        String description = "";
        String genre = "";
        String showType = "";
        long startMs = 0L;
        long endMs = 0L;
        boolean catchup = false;
        boolean adult = false;
        String listingGuid = "";
        String catchupId = "";
        String catchupKey = "";
        String mediaId = "";
        boolean hasStartOver = false;
        boolean hasTimeshift = false;
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            switch (name) {
                case "id":
                    id = nextString(reader);
                    break;
                case "title":
                    title = nextString(reader);
                    break;
                case "description":
                    description = nextString(reader);
                    break;
                case "genre":
                    genre = nextString(reader);
                    break;
                case "show_type":
                    showType = nextString(reader);
                    break;
                case "start":
                    startMs = nextLong(reader);
                    break;
                case "end":
                    endMs = nextLong(reader);
                    break;
                case "catchup":
                    catchup = nextBoolean(reader);
                    break;
                case "adult":
                    adult = nextBoolean(reader);
                    break;
                case "listing_guid":
                    listingGuid = nextString(reader);
                    break;
                case "catchup_id":
                    catchupId = nextString(reader);
                    break;
                case "catchup_key":
                    catchupKey = nextString(reader);
                    break;
                case "media_id":
                    mediaId = nextString(reader);
                    break;
                case "has_start_over":
                    hasStartOver = nextBoolean(reader);
                    break;
                case "has_timeshift":
                    hasTimeshift = nextBoolean(reader);
                    break;
                default:
                    reader.skipValue();
                    break;
            }
        }
        reader.endObject();
        return cachedProgram(id, title, description, genre, showType, startMs, endMs,
                catchup, adult, listingGuid, catchupId, catchupKey, mediaId,
                hasStartOver, hasTimeshift);
    }

    /**
     * Rebuilds an {@link EpgProgram} from cache fields. {@link EpgProgram} only offers the wire
     * factory, so the epoch millis are rendered back to the ISO shape it parses.
     */
    static EpgProgram cachedProgram(String id, String title, String description, String genre,
                                    String showType, long startMs, long endMs,
                                    boolean catchup, boolean adult,
                                    String listingGuid, String catchupId, String catchupKey,
                                    String mediaId, boolean hasStartOver, boolean hasTimeshift) {
        try {
            JSONObject object = new JSONObject();
            object.put("program_id", nonNull(id));
            object.put("episode_name", nonNull(title));
            object.put("description", nonNull(description));
            JSONArray genres = new JSONArray();
            if (genre != null && !genre.isEmpty()) {
                genres.put(new JSONObject().put("name", genre));
            }
            object.put("genres", genres);
            object.put("show_type", nonNull(showType));
            object.put("start_time", Instant.ofEpochMilli(startMs).toString());
            object.put("end_time", Instant.ofEpochMilli(endMs).toString());
            object.put("is_catchup_enabled", catchup);
            object.put("is_adult", adult);
            object.put("listing_guid", nonNull(listingGuid));
            object.put("catchup_id", nonNull(catchupId));
            object.put("catchup_key", nonNull(catchupKey));
            object.put("media_id", nonNull(mediaId));
            JSONObject entitlements = new JSONObject();
            entitlements.put("hasStartOver", hasStartOver);
            entitlements.put("hasTimeshift", hasTimeshift);
            object.put("entitlements", entitlements);
            return EpgProgram.fromJson("", object);
        } catch (JSONException e) {
            throw new IllegalStateException("Cannot rebuild a cached programme", e);
        }
    }

    private static String nextString(JsonReader reader) throws IOException {
        JsonToken token = reader.peek();
        if (token == JsonToken.NULL) {
            reader.nextNull();
            return "";
        }
        if (token == JsonToken.STRING) {
            return reader.nextString();
        }
        reader.skipValue();
        return "";
    }

    private static long nextLong(JsonReader reader) throws IOException {
        JsonToken token = reader.peek();
        if (token == JsonToken.NULL) {
            reader.nextNull();
            return 0L;
        }
        if (token == JsonToken.NUMBER || token == JsonToken.STRING) {
            return reader.nextLong();
        }
        reader.skipValue();
        return 0L;
    }

    private static boolean nextBoolean(JsonReader reader) throws IOException {
        JsonToken token = reader.peek();
        if (token == JsonToken.NULL) {
            reader.nextNull();
            return false;
        }
        if (token == JsonToken.BOOLEAN) {
            return reader.nextBoolean();
        }
        reader.skipValue();
        return false;
    }

    // ---------------------------------------------------------------------------------------
    // Delivery helpers
    // ---------------------------------------------------------------------------------------

    private void publish(String date, List<EpgChannel> data) {
        channels = Collections.unmodifiableList(new ArrayList<>(data));
        currentDate = date;
    }

    private void post(int gen, Callback callback, List<EpgChannel> data) {
        if (callback == null) {
            return;
        }
        mainHandler.post(() -> {
            if (isCurrent(gen)) {
                callback.onLoaded(data);
            }
        });
    }

    private void postError(int gen, ErrorCallback callback, String message) {
        if (callback == null) {
            return;
        }
        mainHandler.post(() -> {
            if (isCurrent(gen)) {
                callback.onError(message);
            }
        });
    }

    private void postNow(ErrorCallback callback, String message) {
        if (callback == null) {
            return;
        }
        mainHandler.post(() -> callback.onError(message));
    }

    private boolean isCurrent(int gen) {
        return gen == generation.get();
    }

    private static String normaliseDate(String isoDate) {
        if (isoDate == null) {
            return null;
        }
        String value = isoDate.trim();
        if (value.length() != 10) {
            return null;
        }
        try {
            return LocalDate.parse(value).toString();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    // ---------------------------------------------------------------------------------------
    // Small value types
    // ---------------------------------------------------------------------------------------

    /** One line-up row; the fields the EPG grid needs. */
    static final class ChannelInfo {
        final int number;
        final String stationId;
        final String title;
        final String logoUrl;
        final String quality;
        final boolean freeToAir;

        ChannelInfo(int number, String stationId, String title, String logoUrl,
                    String quality, boolean freeToAir) {
            this.number = number;
            this.stationId = stationId;
            this.title = title;
            this.logoUrl = logoUrl;
            this.quality = quality;
            this.freeToAir = freeToAir;
        }
    }

    private static final class PageResult {
        final Map<String, List<EpgProgram>> byStation;
        final boolean ok;

        PageResult(Map<String, List<EpgProgram>> byStation, boolean ok) {
            this.byStation = byStation;
            this.ok = ok;
        }

        static PageResult failed() {
            return new PageResult(Collections.emptyMap(), false);
        }
    }

    private static final class ScheduleResult {
        final Map<String, List<EpgProgram>> byStation;
        final int okPages;
        final int failedPages;
        final int programmes;

        ScheduleResult(Map<String, List<EpgProgram>> byStation, int okPages, int failedPages,
                       int programmes) {
            this.byStation = byStation;
            this.okPages = okPages;
            this.failedPages = failedPages;
            this.programmes = programmes;
        }
    }
}
