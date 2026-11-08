package com.douai.module.feature;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

public final class HostCommentAiVideosTest {
    @Test public void additionalPagesAppendAfterExistingVideoModelsWithoutMovingTheCurrentItems() {
        Object oldAnswer = new Object(), suggestions = new Object(), firstVideo = new Object();
        Object nextVideo = new Object(), anotherVideo = new Object();
        List<Object> data = new ArrayList<>(List.of(oldAnswer, suggestions, firstVideo));
        List<Object> page = new ArrayList<>(List.of(nextVideo, anotherVideo));

        assertEquals(2, HostCommentAiVideos.appendAtRoundEnd(data, 0, 2, page));

        assertEquals(List.of(oldAnswer, suggestions, firstVideo, nextVideo, anotherVideo), data);
        assertEquals(List.of(nextVideo, anotherVideo), page);
        assertSame(firstVideo, data.get(2));
    }

    @Test public void invalidRoundBoundsCannotMutateTheNativeList() {
        Object existing = new Object(), next = new Object();
        for (int[] invalid : new int[][]{{-1, 0}, {0, -1}, {2, 0}, {1, 1}, {Integer.MAX_VALUE, 1}}) {
            List<Object> data = new ArrayList<>(List.of(existing));
            assertEquals(0, HostCommentAiVideos.appendAtRoundEnd(data, invalid[0], invalid[1], List.of(next)));
            assertEquals(List.of(existing), data);
        }
        List<Object> data = new ArrayList<>(List.of(existing));
        assertEquals(0, HostCommentAiVideos.appendAtRoundEnd(data, 0, 1, Collections.emptyList()));
        assertEquals(List.of(existing), data);
    }

    @Test public void selectionKeepsRealObjectsAndSkipsCurrentExistingMissingAndDuplicateWorks() throws Exception {
        Object first = new Work("a"), duplicate = new Work("a"), second = new Work("b");
        List<Object> input = new ArrayList<>(List.of(new Work("current"), new Work("existing"),
                new Work(""), first, duplicate, new Object(), second));
        List<Object> selected = HostCommentAiVideos.select(input, "current", Set.of("existing"),
                item -> item instanceof Work ? ((Work) item).id : "");
        assertEquals(2, selected.size());
        assertSame(first, selected.get(0));
        assertSame(second, selected.get(1));
        assertEquals(7, input.size());
        assertThrows(UnsupportedOperationException.class, () -> selected.clear());
    }

    @Test public void selectionCapsNativeCardsAndDoesNotInspectUnselectedLaterWorks() throws Exception {
        List<Object> input = new ArrayList<>();
        for (int index = 0; index < 5; index++) input.add(new Work(Integer.toString(index)));
        input.add(new Object());
        List<Object> selected = HostCommentAiVideos.select(input, "source", Collections.emptySet(), item -> {
            if (!(item instanceof Work)) throw new AssertionError("Beyond native card limit");
            return ((Work) item).id;
        });
        assertEquals(5, selected.size());
    }

    @Test public void sessionGateRejectsChangedConversationOrRankAndInvalidRequests() {
        assertTrue(HostCommentAiVideos.matchesSession("c", 2, "c", 2));
        assertFalse(HostCommentAiVideos.matchesSession("c", 3, "c", 2));
        assertFalse(HostCommentAiVideos.matchesSession("other", 2, "c", 2));
        assertFalse(HostCommentAiVideos.matchesSession(null, 2, "c", 2));
        assertFalse(HostCommentAiVideos.matchesSession("", 2, "", 2));
        assertFalse(HostCommentAiVideos.matchesSession("c", 0, "c", 0));
    }

    @Test public void unknownHostContractCannotCreateTheAdapter() {
        assertThrows(ClassNotFoundException.class,
                () -> HostCommentAiVideos.create(new ClassLoader(null) { }));
    }

    private static final class Work {
        final String id;
        Work(String id) { this.id = id; }
    }
}
