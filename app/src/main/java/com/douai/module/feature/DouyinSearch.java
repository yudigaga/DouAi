package com.douai.module.feature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A cancellable, host-owned video search; preparing a request must not start network work. */
public interface DouyinSearch {
    Request prepare(String query, int limit);

    /** Presentation pagination is explicit; existing model-only implementations keep one-page semantics. */
    default Request preparePage(String query, int limit, long offset, String searchId) {
        if (offset != 0) throw new UnsupportedOperationException("Search pagination is unavailable");
        return prepare(query, limit);
    }

    interface Request {
        Result run(long deadlineNanos) throws Exception;
        void cancel();
    }

    final class Result {
        public final String query;
        public final List<Item> items;
        /** Session-local presentation data; never serialized into model requests or persisted. */
        final List<Object> nativeAwemes;
        public final long nextOffset;
        public final boolean hasMore;
        public final String searchId;

        public Result(String query, List<Item> items) {
            this(query, items, Collections.emptyList());
        }

        public Result(String query, List<Item> items, long nextOffset, boolean hasMore, String searchId) {
            this(query, items, Collections.emptyList(), nextOffset, hasMore, searchId);
        }

        Result(String query, List<Item> items, List<?> nativeAwemes) {
            this(query, items, nativeAwemes, 0, false, "");
        }

        Result(String query, List<Item> items, List<?> nativeAwemes, long nextOffset, boolean hasMore, String searchId) {
            this.query = query == null ? "" : query;
            this.items = Collections.unmodifiableList(new ArrayList<>(
                    items == null ? Collections.emptyList() : items));
            this.nativeAwemes = Collections.unmodifiableList(new ArrayList<>(
                    nativeAwemes == null ? Collections.emptyList() : nativeAwemes));
            this.nextOffset = Math.max(0, nextOffset);
            this.hasMore = hasMore;
            this.searchId = searchId == null ? "" : searchId;
        }
    }

    final class Item {
        public final String awemeId;
        public final String title;
        public final String authorName;
        public final String shareUrl;
        public final String summary;
        /** Display-only source; the model tool projection continues to contain only its original five fields. */
        public final String coverUrl;
        public final List<String> coverUrls;

        public Item(String awemeId, String title, String authorName, String shareUrl, String summary) {
            this(awemeId, title, authorName, shareUrl, summary, "");
        }

        public Item(String awemeId, String title, String authorName, String shareUrl, String summary, String coverUrl) {
            this(awemeId, title, authorName, shareUrl, summary, coverUrl,
                    coverUrl == null || coverUrl.isEmpty() ? Collections.emptyList() : Collections.singletonList(coverUrl));
        }

        public Item(String awemeId, String title, String authorName, String shareUrl, String summary,
                    String coverUrl, List<String> coverUrls) {
            this.awemeId = value(awemeId);
            this.title = value(title);
            this.authorName = value(authorName);
            this.shareUrl = value(shareUrl);
            this.summary = value(summary);
            this.coverUrl = value(coverUrl);
            List<String> candidates = new ArrayList<>();
            if (coverUrls != null) for (String candidate : coverUrls) {
                if (candidate != null && !candidate.isEmpty() && !candidates.contains(candidate)) candidates.add(candidate);
                if (candidates.size() == 8) break;
            }
            if (candidates.isEmpty() && !this.coverUrl.isEmpty()) candidates.add(this.coverUrl);
            this.coverUrls = Collections.unmodifiableList(candidates);
        }

        private static String value(String text) { return text == null ? "" : text; }
    }
}
