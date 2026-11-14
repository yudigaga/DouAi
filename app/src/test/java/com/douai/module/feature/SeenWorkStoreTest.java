package com.douai.module.feature;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class SeenWorkStoreTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private long now = 20L * 86_400_000;
    private void drain() { while (!tasks.isEmpty()) tasks.remove().run(); }
    private SeenWorkStore store(File dir) {
        SeenWorkStore store = new SeenWorkStore(dir, tasks::add, () -> now);
        store.configure(true, 3);
        return store;
    }

    @Test public void onlyActuallyRecordedWorksAreHiddenAndEmptyGuardIsRespected() throws Exception {
        SeenWorkStore store = store(folder.newFolder());
        List<String> page = Arrays.asList("A","B","C");
        long token = store.token("account");
        assertSame(page, store.filter(token, page, x -> x, false)); // cold
        drain();
        assertSame(page, store.filter(token, page, x -> x, false)); // preload does not count
        store.visible(token, "A");
        drain();
        assertEquals(Arrays.asList("B","C"), store.filter(token, page, x -> x, false));
        List<String> seen = Collections.singletonList("A");
        assertSame(seen, store.filter(token, seen, x -> x, false));
        assertTrue(store.filter(token, seen, x -> x, true).isEmpty());
        assertSame(page, store.filter(store.token(""), page, x -> x, false));
    }

    @Test public void clearsQueuedObservationsAndSeparatesAccountsAndExpiry() throws Exception {
        File dir = folder.newFolder(); SeenWorkStore store = store(dir);
        long a = store.token("accountA"); drain();
        store.visible(a,"secret-video-id"); drain();
        assertFalse(new String(Files.readAllBytes(dir.listFiles()[0].toPath()), StandardCharsets.ISO_8859_1).contains("secret-video-id"));
        List<String> p = Arrays.asList("secret-video-id","new");
        long b = store.token("accountB"); drain();
        assertSame(p, store.filter(b,p,x->x,false));
        a = store.token("accountA"); drain();
        assertEquals(Collections.singletonList("new"),store.filter(a,p,x->x,false));
        now += 4L * 86_400_000;
        assertSame(p, store.filter(a,p,x->x,false));
        store.visible(a,"secret-video-id");
        store.clear("accountA", ok -> assertTrue(ok));
        store.visible(a,"secret-video-id");
        drain();
        store = store(dir); a = store.token("accountA"); drain();
        assertSame(p, store.filter(a,p,x->x,false));
    }

    @Test public void disabledOrUnreadableNeverFilters() throws Exception {
        SeenWorkStore store = store(folder.newFile());
        List<String> p = Arrays.asList("A","B");
        long a = store.token("accountA"); drain(); store.visible(a,"A"); drain();
        assertSame(p,store.filter(a,p,x->x,false));
        store.configure(false,3);
        assertEquals(0,store.token("accountA"));
    }
}
