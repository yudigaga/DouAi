package com.douai.module.scan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Stable semantic resource keys and UI aliases for the three verified original host builds. */
public final class HostResourceProfile {
    private static final Map<Integer, Integer> LEGACY_IDS = legacyIds();
    private static final Map<Integer, Integer> CANONICAL_IDS = canonicalIds(LEGACY_IDS);
    private static final Map<Integer, Integer> MODERN_IDS = modernIds();
    private static final Map<Integer, Integer> MODERN_CANONICAL_IDS = canonicalIds(MODERN_IDS);

    private HostResourceProfile() { }

    public static boolean legacy(HostAdaptation.Profile profile) {
        if (profile == null) throw new IllegalArgumentException("Host profile is required");
        return profile.versionCode == 400601L;
    }

    public static int id(HostAdaptation.Profile profile, int canonicalId) {
        if (legacy(profile)) return LEGACY_IDS.getOrDefault(canonicalId, canonicalId);
        return profile == HostAdaptation.PROFILE_410 ? MODERN_IDS.getOrDefault(canonicalId, canonicalId) : canonicalId;
    }

    public static int canonicalId(HostAdaptation.Profile profile, int actualId) {
        if (legacy(profile)) return CANONICAL_IDS.getOrDefault(actualId, actualId);
        return profile == HostAdaptation.PROFILE_410 ? MODERN_CANONICAL_IDS.getOrDefault(actualId, actualId) : actualId;
    }

    public static String className(HostAdaptation.Profile profile, String latestName) {
        boolean previous = legacy(profile);
        if (profile == HostAdaptation.PROFILE_410) {
            switch (latestName) {
                case "X.0YVw": return "X.0v6A";
                case "X.0vJL": return "X.0uqo";
                case "X.0YVa": return "X.0Vk2";
                case "X.0vMA": return "X.1iQa";
                case "X.0uuG": return "X.0uHP";
                case "X.0vK4": return "X.18tv";
                case "X.0Yjd": return "X.0udp";
                case "X.0XZk": return "X.19La";
                case "X.0uok": return "X.0umH";
                case "X.0vM9": return "X.1hJq";
                case "X.1UOy": return "X.1cMN";
                case "X.1U1O": return "X.1c8i";
                case "X.1U1r": return "X.1blt";
                case "X.1U1z": return "X.1bmi";
                case "X.1U2g": return "X.1bmG";
                case "X.0vUA": return "X.0v2P";
                case "X.0vTt": return "X.0v4Q";
                case "X.1UWt": return "X.05JR";
                default: return latestName;
            }
        }
        // The keys stay stable for callers; original targets remain isolated by profile.
        switch (latestName) {
            case "X.0YVw": return previous ? "X.0s7o" : "X.0TzN";
            case "X.0vJL": return previous ? "X.0sBR" : "X.1Czy";
            case "X.0YVa": return previous ? "X.0ryb" : "X.0Tqi";
            case "X.0vMA": return previous ? "X.1bWb" : "X.1hFv";
            case "X.0uuG": return previous ? "X.0qn3" : "X.0URm";
            case "X.0vK4": return previous ? "X.0sNm" : "X.14XH";
            case "X.0Yjd": return previous ? "X.0sMT" : "X.0UGJ";
            case "X.0XZk": return previous ? "X.0sWV" : "X.0TQx";
            case "X.0uok": return previous ? "X.0sSQ" : "X.1Cni";
            case "X.0vM9": return previous ? "X.1aLM" : "X.1fyy";
            case "X.1UOy": return previous ? "X.131D" : "X.1Cd0";
            case "X.1U1O": return previous ? "X.133H" : "X.1Cue";
            case "X.1U1r": return previous ? "X.133I" : "X.1CuX";
            case "X.1U1z": return previous ? "X.134V" : "X.1CuH";
            case "X.1U2g": return previous ? "X.134d" : "X.1Cur";
            case "X.0vUA": return previous ? "X.12w3" : "X.1Ccz";
            case "X.0vTt": return previous ? "X.12vu" : "X.1Ct9";
            case "X.1UWt": return previous ? "X.06Dh" : "X.1Czx";
            default: return latestName;
        }
    }

    public static String viewFactory(HostAdaptation.Profile profile) {
        if (legacy(profile)) return "LJJIIJ";
        return profile == HostAdaptation.PROFILE_410 ? "LJJ" : "LJJIIJZLJL";
    }

    public static String avatarLoader(HostAdaptation.Profile profile) {
        return legacy(profile) ? "LJIIIZ" : "LJIIJ";
    }

    public static String mediaSourceGetter(HostAdaptation.Profile profile) {
        legacy(profile);
        return profile == HostAdaptation.PROFILE_410 ? "LJIILIIL" : "LJIILJJIL";
    }

    public static String ripsLogicProvider(HostAdaptation.Profile profile) {
        legacy(profile);
        return profile == HostAdaptation.PROFILE_410 ? "LJJJI" : "LJJJJJ";
    }

    public static String downloadAwemeGetter(HostAdaptation.Profile profile) {
        legacy(profile);
        return profile == HostAdaptation.PROFILE_410 ? "LJIIJ" : "LJIIJJI";
    }

    public static String diggViewModelGetter(HostAdaptation.Profile profile) {
        legacy(profile);
        return profile == HostAdaptation.PROFILE_410 ? "LLLJ" : "LLLILZ";
    }

    public static String feedItemParamsField(HostAdaptation.Profile profile) {
        legacy(profile);
        return profile == HostAdaptation.PROFILE_410 ? "B2" : "A2";
    }

    public static String textBindMethod(HostAdaptation.Profile profile) {
        legacy(profile);
        return profile == HostAdaptation.PROFILE_410 ? "LJJIIZI" : "LJJIIZ";
    }

    public static String commentSaveClick(HostAdaptation.Profile profile) {
        legacy(profile);
        return profile == HostAdaptation.PROFILE_410 ? "LJJIIJ" : "LJJI";
    }

    public static String chatMethod(HostAdaptation.Profile profile, String existingName) {
        legacy(profile);
        if (profile == HostAdaptation.PROFILE_410) {
            switch (existingName) {
                case "LJLJJLL": return "LJLILLLLZI";
                case "LJLIIL": return "LJJZZIII";
                case "LJJJIL": return "LJJJ";
                case "LJJJJL": return "LJJJJI";
                default: return existingName;
            }
        }
        return existingName;
    }

    private static Map<Integer, Integer> legacyIds() {
        Map<Integer, Integer> values = new LinkedHashMap<>();
        values.put(0x7f0d1b57, 0x7f0d1b61);
        values.put(0x7f0a577c, 0x7f0a5774);
        values.put(0x7f0a6a1e, 0x7f0a6a27);
        values.put(0x7f0a9dcc, 0x7f0a9daf);
        values.put(0x7f083b0a, 0x7f083ab1);
        values.put(0x7f083b0d, 0x7f083ab4);
        values.put(0x7f08415e, 0x7f084101);
        values.put(0x7f08430a, 0x7f0842a7);
        values.put(0x7f08439b, 0x7f084338);
        values.put(0x7f084486, 0x7f084422);
        values.put(0x7f0844a9, 0x7f084445);
        values.put(0x7f0844b1, 0x7f08444d);
        values.put(0x7f0845cd, 0x7f08456a);
        values.put(0x7f0845d2, 0x7f08456f);
        values.put(0x7f0a0f00, 0x7f0a0ef3);
        values.put(0x7f0a10e6, 0x7f0a10d9);
        values.put(0x7f0a1160, 0x7f0a114d);
        values.put(0x7f0a15bd, 0x7f0a15b1);
        values.put(0x7f0a1f69, 0x7f0a1f62);
        values.put(0x7f0a202d, 0x7f0a2026);
        values.put(0x7f0a2042, 0x7f0a203b);
        values.put(0x7f0a205b, 0x7f0a2054);
        values.put(0x7f0a2063, 0x7f0a205c);
        values.put(0x7f0a22c4, 0x7f0a22c2);
        values.put(0x7f0a2396, 0x7f0a2394);
        values.put(0x7f0a2398, 0x7f0a2396);
        values.put(0x7f0a2441, 0x7f0a243f);
        values.put(0x7f0a2515, 0x7f0a2512);
        values.put(0x7f0a2523, 0x7f0a2521);
        values.put(0x7f0a2570, 0x7f0a256e);
        values.put(0x7f0a2596, 0x7f0a2594);
        values.put(0x7f0a2655, 0x7f0a2650);
        values.put(0x7f0a2785, 0x7f0a2781);
        values.put(0x7f0a30f7, 0x7f0a3100);
        values.put(0x7f0a3103, 0x7f0a310c);
        values.put(0x7f0a3104, 0x7f0a310d);
        values.put(0x7f0a310b, 0x7f0a3114);
        values.put(0x7f0a3118, 0x7f0a3121);
        values.put(0x7f0a3fd6, 0x7f0a3fc5);
        values.put(0x7f0a3fdb, 0x7f0a3fc8);
        values.put(0x7f0a3ff3, 0x7f0a3fe1);
        values.put(0x7f0a409c, 0x7f0a408b);
        values.put(0x7f0a40c9, 0x7f0a40b7);
        values.put(0x7f0a40cb, 0x7f0a40b9);
        values.put(0x7f0a4258, 0x7f0a4246);
        values.put(0x7f0a4631, 0x7f0a4621);
        values.put(0x7f0a463c, 0x7f0a462d);
        values.put(0x7f0a475e, 0x7f0a474f);
        values.put(0x7f0a475f, 0x7f0a4750);
        values.put(0x7f0a5146, 0x7f0a5158);
        values.put(0x7f0a5f7e, 0x7f0a5f8a);
        values.put(0x7f0a68ae, 0x7f0a68b7);
        values.put(0x7f0a6a13, 0x7f0a6a1c);
        values.put(0x7f0a7afe, 0x7f0a7b08);
        values.put(0x7f0a7aff, 0x7f0a7b09);
        values.put(0x7f0a7d27, 0x7f0a7d32);
        values.put(0x7f0a7ddb, 0x7f0a7de7);
        values.put(0x7f0a7ddf, 0x7f0a7dea);
        values.put(0x7f0a7ea7, 0x7f0a7eb1);
        values.put(0x7f0a7eac, 0x7f0a7eb6);
        values.put(0x7f0a805d, 0x7f0a8067);
        values.put(0x7f0a8189, 0x7f0a8192);
        values.put(0x7f0a9c4d, 0x7f0a9c33);
        values.put(0x7f0a9dbe, 0x7f0a9da1);
        values.put(0x7f0a9dc6, 0x7f0a9dab);
        values.put(0x7f0aa977, 0x7f0aa968);
        values.put(0x7f0aaa4f, 0x7f0aaa3e);
        values.put(0x7f0ab9a8, 0x7f0ab99c);
        values.put(0x7f0abd9d, 0x7f0abd99);
        values.put(0x7f0acff9, 0x7f0ad015);
        values.put(0x7f0ad00f, 0x7f0ad022);
        values.put(0x7f0ad39e, 0x7f0ad3af);
        values.put(0x7f0ad428, 0x7f0ad439);
        values.put(0x7f0ad429, 0x7f0ad43a);
        values.put(0x7f0d194a, 0x7f0d1954);
        values.put(0x7f0d194b, 0x7f0d1955);
        values.put(0x7f0d194c, 0x7f0d1956);
        return Collections.unmodifiableMap(values);
    }

    private static Map<Integer, Integer> modernIds() {
        Map<Integer, Integer> values = new LinkedHashMap<>();
        values.put(0x7f083b0a, 0x7f083b55);
        values.put(0x7f083b0d, 0x7f083b58);
        values.put(0x7f08415e, 0x7f0841b4);
        values.put(0x7f08430a, 0x7f084360);
        values.put(0x7f08439b, 0x7f0843f2);
        values.put(0x7f084486, 0x7f0844d8);
        values.put(0x7f0844a9, 0x7f0844fb);
        values.put(0x7f0844b1, 0x7f084503);
        values.put(0x7f0845cd, 0x7f08461f);
        values.put(0x7f0845d2, 0x7f084624);
        values.put(0x7f0a0f00, 0x7f0a0ee2);
        values.put(0x7f0a10e6, 0x7f0a10c6);
        values.put(0x7f0a1160, 0x7f0a113b);
        values.put(0x7f0a15bd, 0x7f0a1598);
        values.put(0x7f0a1f69, 0x7f0a1f3c);
        values.put(0x7f0a202d, 0x7f0a2000);
        values.put(0x7f0a2042, 0x7f0a2017);
        values.put(0x7f0a205b, 0x7f0a2034);
        values.put(0x7f0a2063, 0x7f0a203c);
        values.put(0x7f0a22c4, 0x7f0a229f);
        values.put(0x7f0a2396, 0x7f0a2371);
        values.put(0x7f0a2398, 0x7f0a2373);
        values.put(0x7f0a2441, 0x7f0a241d);
        values.put(0x7f0a2515, 0x7f0a24ee);
        values.put(0x7f0a2523, 0x7f0a24fd);
        values.put(0x7f0a2570, 0x7f0a254e);
        values.put(0x7f0a2596, 0x7f0a2572);
        values.put(0x7f0a2655, 0x7f0a2632);
        values.put(0x7f0a2785, 0x7f0a2761);
        values.put(0x7f0a30f7, 0x7f0a30ca);
        values.put(0x7f0a3103, 0x7f0a30d6);
        values.put(0x7f0a3104, 0x7f0a30d7);
        values.put(0x7f0a310b, 0x7f0a30de);
        values.put(0x7f0a3118, 0x7f0a30eb);
        values.put(0x7f0a3fd6, 0x7f0a3fad);
        values.put(0x7f0a3fdb, 0x7f0a3fb2);
        values.put(0x7f0a3ff3, 0x7f0a3fca);
        values.put(0x7f0a409c, 0x7f0a4085);
        values.put(0x7f0a40c9, 0x7f0a40b1);
        values.put(0x7f0a40cb, 0x7f0a40b3);
        values.put(0x7f0a4258, 0x7f0a424a);
        values.put(0x7f0a4631, 0x7f0a461d);
        values.put(0x7f0a463c, 0x7f0a4629);
        values.put(0x7f0a475e, 0x7f0a4746);
        values.put(0x7f0a475f, 0x7f0a4747);
        values.put(0x7f0a5146, 0x7f0a5132);
        values.put(0x7f0a577c, 0x7f0a5756);
        values.put(0x7f0a5f7e, 0x7f0a5f57);
        values.put(0x7f0a68ae, 0x7f0a6888);
        values.put(0x7f0a6a13, 0x7f0a69ee);
        values.put(0x7f0a6a1e, 0x7f0a69f9);
        values.put(0x7f0a7afe, 0x7f0a7ade);
        values.put(0x7f0a7aff, 0x7f0a7adf);
        values.put(0x7f0a7d27, 0x7f0a7d00);
        values.put(0x7f0a7ddb, 0x7f0a7db7);
        values.put(0x7f0a7ddf, 0x7f0a7dba);
        values.put(0x7f0a7ea7, 0x7f0a7e83);
        values.put(0x7f0a7eac, 0x7f0a7e88);
        values.put(0x7f0a805d, 0x7f0a8037);
        values.put(0x7f0a8189, 0x7f0a8163);
        values.put(0x7f0a9c4d, 0x7f0a9c7f);
        values.put(0x7f0a9dbe, 0x7f0a9df7);
        values.put(0x7f0a9dc6, 0x7f0a9e00);
        values.put(0x7f0a9dcc, 0x7f0a9e04);
        values.put(0x7f0aa977, 0x7f0aa99f);
        values.put(0x7f0aaa4f, 0x7f0aaa77);
        values.put(0x7f0ab9a8, 0x7f0ab9bc);
        values.put(0x7f0abd9d, 0x7f0abdb1);
        values.put(0x7f0acff9, 0x7f0ad015);
        values.put(0x7f0ad00f, 0x7f0ad022);
        values.put(0x7f0ad39e, 0x7f0ad3a3);
        values.put(0x7f0ad428, 0x7f0ad42a);
        values.put(0x7f0ad429, 0x7f0ad42b);
        values.put(0x7f0d194a, 0x7f0d1959);
        values.put(0x7f0d194b, 0x7f0d195a);
        values.put(0x7f0d194c, 0x7f0d195b);
        values.put(0x7f0d1b57, 0x7f0d1b6a);
        return Collections.unmodifiableMap(values);
    }

    private static Map<Integer, Integer> canonicalIds(Map<Integer, Integer> actualIds) {
        Map<Integer, Integer> values = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> entry : actualIds.entrySet()) {
            if (values.put(entry.getValue(), entry.getKey()) != null) {
                throw new IllegalStateException("Duplicate host resource identifier");
            }
        }
        return Collections.unmodifiableMap(values);
    }
}
