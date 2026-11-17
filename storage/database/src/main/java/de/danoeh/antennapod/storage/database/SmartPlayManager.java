package de.danoeh.antennapod.storage.database;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.greenrobot.eventbus.EventBus;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.danoeh.antennapod.event.FeedItemEvent;
import de.danoeh.antennapod.event.QueueEvent;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.SortOrder;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;

public class SmartPlayManager {
    private static final String TAG = "SmartPlayManager";
    private static final String PREFS_NAME = "BeyondPodSmartPlayPrefs";
    private static final String KEY_RULES = "rules_json";
    private static final String KEY_AUTO_REBUILD = "auto_rebuild";
    private static final String KEY_KEEP_CURRENT = "keep_current";
    private static final String KEY_DOWNLOADED_ONLY = "downloaded_only";

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static class SmartPlayRule {
        public String order;       // "OLDEST", "NEWEST", "RANDOM"
        public int count;          // 1, 2, 3, 5, 10, or -1 (All)
        public String sourceType;  // "ALL", "TAG", "FEED"
        public String sourceValue; // "" for ALL, tag name for TAG, feedId string for FEED

        public SmartPlayRule(String order, int count, String sourceType, String sourceValue) {
            this.order = order;
            this.count = count;
            this.sourceType = sourceType;
            this.sourceValue = sourceValue;
        }
    }

    public static List<SmartPlayRule> getRules(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_RULES, "[]");
        List<SmartPlayRule> rules = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                rules.add(new SmartPlayRule(
                        obj.optString("order", "OLDEST"),
                        obj.optInt("count", 1),
                        obj.optString("sourceType", "ALL"),
                        obj.optString("sourceValue", "")
                ));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading SmartPlay rules", e);
        }
        return rules;
    }

    public static void saveConfig(Context context, List<SmartPlayRule> rules,
                                  boolean autoRebuild, boolean keepCurrent, boolean downloadedOnly) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        JSONArray arr = new JSONArray();
        try {
            for (SmartPlayRule r : rules) {
                JSONObject obj = new JSONObject();
                obj.put("order", r.order);
                obj.put("count", r.count);
                obj.put("sourceType", r.sourceType);
                obj.put("sourceValue", r.sourceValue);
                arr.put(obj);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving SmartPlay rules", e);
        }
        prefs.edit()
                .putString(KEY_RULES, arr.toString())
                .putBoolean(KEY_AUTO_REBUILD, autoRebuild)
                .putBoolean(KEY_KEEP_CURRENT, keepCurrent)
                .putBoolean(KEY_DOWNLOADED_ONLY, downloadedOnly)
                .apply();
    }

    public static boolean isAutoRebuildEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_REBUILD, false);
    }

    public static boolean isKeepCurrentEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_KEEP_CURRENT, true);
    }

    public static boolean isDownloadedOnlyEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_DOWNLOADED_ONLY, false);
    }


    public static void rebuildQueueIfAutoEnabled(Context context) {
        if (isAutoRebuildEnabled(context)) {
            rebuildQueueNow(context);
        }
    }

    public static void rebuildQueueNow(Context context) {
        final Context appContext = context.getApplicationContext();
        executor.submit(() -> rebuildQueueSync(appContext));
    }

    public static void rebuildQueueSync(Context context) {
        final Context appContext = context.getApplicationContext();

            try {
                List<SmartPlayRule> rules = getRules(appContext);
                if (rules.isEmpty()) {
                    return;
                }

                List<Feed> feeds = DBReader.getFeedList();
                List<FeedItem> oldQueue = DBReader.getQueue();
                List<FeedItem> newQueue = new ArrayList<>();
                Set<Long> addedIds = new HashSet<>();

                // 1. Keep currently playing/active episode at top if enabled
                long currentMediaId = PlaybackPreferences.getCurrentlyPlayingFeedMediaId();
                if (isKeepCurrentEnabled(appContext) && currentMediaId > 0 && !oldQueue.isEmpty()) {
                    FeedItem first = oldQueue.get(0);
                    if (first.getMedia() != null && first.getMedia().getId() == currentMediaId) {
                        newQueue.add(first);
                        addedIds.add(first.getId());
                    }
                }

                // 2. Collect all candidate unplayed downloaded/local episodes
                List<FeedItem> allCandidates = new ArrayList<>();
                FeedItemFilter unplayedFilter = new FeedItemFilter(FeedItemFilter.UNPLAYED);
                for (Feed feed : feeds) {
                    if (feed.getState() != Feed.STATE_SUBSCRIBED) {
                        continue;
                    }
                    List<FeedItem> feedItems = DBReader.getFeedItemList(
                            feed, unplayedFilter, SortOrder.DATE_NEW_OLD, 0, Integer.MAX_VALUE);
                    boolean downloadedOnly = isDownloadedOnlyEnabled(appContext);
                    for (FeedItem item : feedItems) {
                        item.setFeed(feed);
                        boolean availableOffline = feed.isLocalFeed()
                                || (item.getMedia() != null && item.getMedia().isDownloaded());
                        if ((!downloadedOnly || availableOffline) && item.hasMedia()) {
                            allCandidates.add(item);
                        }
                    }

                }

                // 3. Evaluate BeyondPod SmartPlay rules in order
                for (SmartPlayRule rule : rules) {
                    List<FeedItem> matching = new ArrayList<>();
                    for (FeedItem item : allCandidates) {
                        if (addedIds.contains(item.getId())) {
                            continue;
                        }
                        if ("ALL".equals(rule.sourceType)) {
                            matching.add(item);
                        } else if ("TAG".equals(rule.sourceType)) {
                            if (item.getFeed() != null && item.getFeed().getPreferences() != null
                                    && item.getFeed().getPreferences().getTags().contains(rule.sourceValue)) {
                                matching.add(item);
                            }
                        } else if ("FEED".equals(rule.sourceType)) {
                            if (item.getFeed() != null
                                    && String.valueOf(item.getFeed().getId()).equals(rule.sourceValue)) {
                                matching.add(item);
                            }
                        }
                    }

                    if ("OLDEST".equals(rule.order)) {
                        Collections.sort(matching, (a, b) -> a.getPubDate().compareTo(b.getPubDate()));
                    } else if ("NEWEST".equals(rule.order)) {
                        Collections.sort(matching, (a, b) -> b.getPubDate().compareTo(a.getPubDate()));
                    } else if ("RANDOM".equals(rule.order)) {

                        Collections.shuffle(matching);
                    }

                    int limit = (rule.count <= 0) ? matching.size() : Math.min(rule.count, matching.size());
                    for (int i = 0; i < limit; i++) {
                        FeedItem chosen = matching.get(i);
                        chosen.addTag(FeedItem.TAG_QUEUE);
                        newQueue.add(chosen);
                        addedIds.add(chosen.getId());
                    }
                }

                // 4. Write new queue to database & refresh UI
                PodDBAdapter adapter = PodDBAdapter.getInstance();
                adapter.open();
                adapter.setQueue(newQueue);
                adapter.close();

                EventBus.getDefault().post(QueueEvent.setQueue(newQueue));
                EventBus.getDefault().post(new FeedItemEvent(newQueue, false));
                Log.i(TAG, "SmartPlay rebuilt queue with " + newQueue.size() + " episodes");
            } catch (Exception e) {
                Log.e(TAG, "SmartPlay rebuild failed", e);
            }
    }
}
