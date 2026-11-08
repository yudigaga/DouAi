package com.douai.module.feature;

/** A display-only cursor; model tool budgets and conversation history remain independent. */
final class CommentAiVideoPageState implements AutoCloseable {
    static final class Next {
        final String query, searchId;
        final long offset;
        Next(String query, long offset, String searchId) { this.query = query; this.offset = offset; this.searchId = searchId; }
    }
    private String query = "", searchId = "";
    private long offset;
    private boolean more, closed;
    private int emptyPages;
    private Next active;

    synchronized void seed(DouyinSearch.Result page) {
        if (closed || active != null || page == null) return;
        query = page.query; offset = page.nextOffset; searchId = page.searchId;
        more = page.hasMore && offset > 0 && !query.isEmpty() && !searchId.isEmpty();
        emptyPages = 0;
    }
    synchronized Next begin() {
        if (closed || !more || active != null) return null;
        return active = new Next(query, offset, searchId);
    }
    synchronized boolean complete(Next request, DouyinSearch.Result page, int inserted) {
        if (closed || request == null || active != request) return false;
        active = null;
        emptyPages = inserted > 0 ? 0 : emptyPages + 1;
        more = page != null && query.equals(page.query) && page.hasMore && page.nextOffset > request.offset
                && !page.searchId.isEmpty() && emptyPages < 2;
        if (page != null && page.nextOffset > request.offset) {
            offset = page.nextOffset;
            searchId = page.searchId;
        }
        return true;
    }
    synchronized void failed(Next request) { if (active == request) active = null; }
    synchronized boolean hasMore() { return !closed && more; }
    @Override public synchronized void close() { closed = true; more = false; active = null; }
}
