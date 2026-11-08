package com.douai.module.feature;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public final class CommentAiVideoPageStateTest {
    private static DouyinSearch.Result page(long offset, boolean more) {
        return new DouyinSearch.Result("手势舞", List.of(), List.of(), offset, more, "search-1");
    }
    @Test public void pagingUsesActualCursorAndKeepsTheSamePageAfterFailure() {
        CommentAiVideoPageState state = new CommentAiVideoPageState();
        state.seed(page(17, true));
        CommentAiVideoPageState.Next first = state.begin();
        assertEquals(17, first.offset);
        assertEquals("search-1", first.searchId);
        assertNull(state.begin());
        state.failed(first);
        CommentAiVideoPageState.Next retry = state.begin();
        assertEquals(17, retry.offset);
        assertTrue(state.complete(retry, page(29, true), 5));
        assertEquals(29, state.begin().offset);
    }
    @Test public void staleResultsAndNonAdvancingCursorsCannotContinueOrChangeTheActivePage() {
        CommentAiVideoPageState state = new CommentAiVideoPageState();
        state.seed(page(5, true));
        CommentAiVideoPageState.Next old = state.begin();
        state.failed(old);
        CommentAiVideoPageState.Next current = state.begin();
        assertFalse(state.complete(old, page(10, true), 5));
        assertTrue(state.complete(current, page(5, true), 5));
        assertNull(state.begin());
    }
    @Test public void twoEmptyPagesStopButOneFilteredPageCanContinue() {
        CommentAiVideoPageState state = new CommentAiVideoPageState();
        state.seed(page(5, true));
        assertTrue(state.complete(state.begin(), page(10, true), 0));
        assertTrue(state.hasMore());
        assertTrue(state.complete(state.begin(), page(15, true), 0));
        assertFalse(state.hasMore());
        assertNull(state.begin());
    }
    @Test public void cancelledOrExhaustedPagesNeverRestart() {
        CommentAiVideoPageState state = new CommentAiVideoPageState();
        state.seed(page(5, false));
        assertNull(state.begin());
        state.seed(page(5, true));
        CommentAiVideoPageState.Next pending = state.begin();
        state.close();
        assertFalse(state.complete(pending, page(10, true), 5));
        state.seed(page(15, true));
        assertNull(state.begin());
    }
}
