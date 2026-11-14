package com.douai.module.feature;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** A single serial worker owns disk I/O. A response only reads the published account snapshot. */
final class SeenWorkStore {
    static final int LIMIT = 4096;
    private static final int RECORD_BYTES = 40; // SHA-256 + last visible time
    private static final long DAY = 86_400_000L;
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private final File directory;
    private final Executor worker;
    private final LongSupplier clock;
    private boolean enabled;
    private int days = 3;
    private String account = "";
    private long generation = 1;
    private Map<String,Long> entries; // null means cold/error; never mutated after publication.

    SeenWorkStore(File directory, Executor serial, LongSupplier clock) {
        this.directory = directory; this.worker = serial; this.clock = clock;
    }

    synchronized void configure(boolean on, int window) {
        int d = window == 1 || window == 7 ? window : 3;
        if (enabled == on && days == d) return;
        enabled = on; days = d; account = ""; generation++; entries = null;
    }

    synchronized long token(String uid) {
        if (!enabled) return 0;
        if (uid == null || uid.length() > 160) uid = "";
        if (!account.equals(uid)) {
            account = uid; entries = null; long token = ++generation;
            if (!uid.isEmpty()) {
                String key = uid; int window = days;
                worker.execute(() -> load(token, key, window));
            }
        }
        return uid.isEmpty() ? 0 : generation;
    }

    synchronized void visible(long token, String id) {
        if (!current(token) || !validId(id)) return;
        String uid = account; int window = days;
        long ts = clock.getAsLong();
        worker.execute(() -> record(token, uid, id, ts, window));
    }

    <T> List<T> filter(long token, List<T> input, Function<T,String> reader, boolean allowEmpty) {
        Map<String,Long> snapshot; long cutoff; long now; String uid;
        synchronized (this) {
            if (!current(token) || entries == null || entries.isEmpty()) return input;
            snapshot = entries; uid = account; now = clock.getAsLong(); cutoff = now - days * DAY;
        }
        List<T> result = FeedResponseFilter.filter(input, true, item -> {
            String id = reader.apply(item);
            if (!validId(id)) return false;
            Long at = snapshot.get(hex(digest(uid + ":" + id)));
            return at != null && at > cutoff && at <= now;
        }, allowEmpty);
        synchronized (this) { return current(token) ? result : input; }
    }

    synchronized void clear(String uid, Consumer<Boolean> complete) {
        if (uid == null || uid.isEmpty() || uid.length() > 160) { complete.accept(false); return; }
        if (uid.equals(account)) { ++generation; account = ""; entries = null; }
        worker.execute(() -> {
            boolean ok;
            try {
                File file = file(uid);
                Files.deleteIfExists(file.toPath()); Files.deleteIfExists(temp(file).toPath()); ok = true;
            } catch (IOException | RuntimeException e) { ok = false; }
            complete.accept(ok);
        });
    }

    private synchronized boolean current(long token) {
        return token != 0 && token == generation && enabled && !account.isEmpty();
    }
    private static boolean validId(String id) { return id != null && !id.isEmpty() && id.length() <= 160; }
    private File file(String uid) { return new File(directory, hex(digest(uid)) + ".seen"); }
    private static File temp(File file) { return new File(file.getParentFile(), file.getName() + ".tmp"); }
    private void load(long epoch, String uid, int window) {
        if (!current(epoch)) return;
        try {
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) return;
            File file = file(uid);
            LinkedHashMap<String,Long> map = new LinkedHashMap<>();
            long now = clock.getAsLong();
            if (file.exists()) {
                long bytes = file.length();
                if (bytes % RECORD_BYTES != 0 || bytes > (LIMIT + 1024L) * RECORD_BYTES) return;
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
                    byte[] key = new byte[32];
                    for (long i = 0; i < bytes; i += RECORD_BYTES) {
                        in.readFully(key); long ts = in.readLong();
                        if (ts > now - window * DAY && ts <= now) put(map, hex(key), ts);
                    }
                }
                // Purge old records on opening even if this account doesn't watch another work.
                if (map.size() * (long) RECORD_BYTES < bytes) compact(file, map);
            }
            synchronized (this) { if (current(epoch)) entries = map; }
        } catch (IOException | RuntimeException failure) { /* unknown snapshot => keep the host page */ }
    }

    private void record(long epoch, String uid, String id, long ts, int window) {
        Map<String,Long> old;
        synchronized (this) { if (!current(epoch) || entries == null) return; old = entries; }
        byte[] key = digest(uid + ":" + id); String hash = hex(key);
        Long last = old.get(hash);
        if (last != null && ts >= last && ts - last < 1500) return;
        LinkedHashMap<String,Long> map = new LinkedHashMap<>(old);
        boolean expired = map.values().removeIf(time -> time <= ts - window * DAY || time > ts);
        put(map, hash, ts);
        try {
            File file = file(uid);
            if (file.length() >= (LIMIT + 512L) * RECORD_BYTES || expired) compact(file, map);
            else try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file, true)))) {
                out.write(key); out.writeLong(ts);
            }
            synchronized (this) { if (current(epoch)) entries = map; }
        } catch (IOException | RuntimeException failure) {
            synchronized (this) { if (current(epoch)) entries = null; }
        }
    }

    private static void put(LinkedHashMap<String,Long> map, String key, long ts) {
        map.remove(key); map.put(key, ts);
        if (map.size() > LIMIT) map.remove(map.keySet().iterator().next());
    }

    private static void compact(File file, Map<String,Long> map) throws IOException {
        File tmp = temp(file);
        try {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
                for (Map.Entry<String,Long> e : map.entrySet()) {
                    String h = e.getKey();
                    for (int k=0;k<h.length();k+=2) out.writeByte(Character.digit(h.charAt(k),16)*16 + Character.digit(h.charAt(k+1),16));
                    out.writeLong(e.getValue());
                }
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(tmp.toPath()); }
    }
    private static byte[] digest(String text) {
        try { return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static String hex(byte[] bytes) {
        char[] s = new char[bytes.length * 2];
        for (int i=0; i<bytes.length; i++) {
            s[i*2] = HEX[(bytes[i]&255) >>> 4]; s[i*2+1] = HEX[bytes[i]&15];
        }
        return new String(s);
    }
}
