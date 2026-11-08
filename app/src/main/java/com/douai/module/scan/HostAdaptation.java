package com.douai.module.scan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Static APK evidence; matching this profile does not establish runtime feature success. */
public final class HostAdaptation {
    public static final String TARGET_PACKAGE = "com.ss.android.ugc.aweme";
    public static final String RULES_ASSET = "douyin-host-rules.tsv";
    public static final String VERIFIED_VERSION_NAME = "40.8.0";
    public static final long VERIFIED_VERSION_CODE = 400801L;
    public static final long VERIFIED_UPDATE_VERSION_CODE = 40809900L;
    public static final String VERIFIED_RULES_FINGERPRINT =
            "7b1cdd08e2b976b8a2fa7a7896df67072d18c577743d9f3088e2d3b1ae3653d1";
    private static final Map<Integer, String> FEED_RESOURCES = feedResources();

    private HostAdaptation() { }

    public static final Profile PROFILE_406 = new Profile("40.6.0", 400601L, -1L,
            "douyin-host-rules-40.6.0.tsv",
            "ecda2db82b867d00f65adf5ac588a45217bc3e9313dbc3713b8ae38a52fcb9f0", legacyFeedResources());
    public static final Profile PROFILE_408 = new Profile("40.8.0", 400801L, 40809900L,
            RULES_ASSET, VERIFIED_RULES_FINGERPRINT, FEED_RESOURCES);
    public static final Profile PROFILE_410 = new Profile("41.0.0", 410001L, 41009900L,
            "douyin-host-rules-41.0.0.tsv",
            "98d04bab47391942113cd56b8d187bb2ec27b083126a3b83c349b6bf9923ae55", modernFeedResources());
    private static volatile Profile currentProfile;

    public static final class Profile {
        public final String versionName;
        public final long versionCode;
        public final long updateVersionCode;
        public final String rulesAsset;
        public final String fingerprint;
        private final Map<Integer, String> feedResources;

        private Profile(String versionName, long versionCode, long updateVersionCode, String rulesAsset,
                        String fingerprint, Map<Integer, String> feedResources) {
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.updateVersionCode = updateVersionCode;
            this.rulesAsset = rulesAsset;
            this.fingerprint = fingerprint;
            this.feedResources = feedResources;
        }

        public Map<Integer, String> getFeedResources() { return feedResources; }
        public boolean matchesRules(String value) { return fingerprint.equals(value); }
    }

    public static Profile forVersion(String packageName, String versionName, long versionCode) {
        return TARGET_PACKAGE.equals(packageName) ? forVersion(versionName, versionCode) : null;
    }

    public static Profile forVersion(String versionName, long versionCode) {
        for (Profile profile : new Profile[]{PROFILE_406, PROFILE_408, PROFILE_410}) {
            if (profile.versionName.equals(versionName) && profile.versionCode == versionCode) return profile;
        }
        return null;
    }

    public static Profile currentProfile() { return currentProfile; }
    public static void setCurrentProfile(Profile profile) { currentProfile = profile; }

    public static boolean matchesVersion(String packageName, String versionName, long versionCode) {
        return forVersion(packageName, versionName, versionCode) != null;
    }

    public static boolean matchesRules(String fingerprint) {
        return PROFILE_406.matchesRules(fingerprint) || PROFILE_408.matchesRules(fingerprint)
                || PROFILE_410.matchesRules(fingerprint);
    }

    /** Canonical 40.8.0 resources for source/API callers; runtime uses the real host identity. */
    public static Map<Integer, String> getFeedResources() { return FEED_RESOURCES; }

    private static Map<Integer, String> legacyFeedResources() {
        Map<Integer, String> values = new LinkedHashMap<>();
        values.put(2131362267, "i6");
        values.put(2131366221, "b1g");
        values.put(2131367345, "cmm");
        values.put(2131369826, "d5s");
        values.put(2131370690, "em6");
        values.put(2131370900, "esb");
        values.put(2131370902, "esd");
        values.put(2131371071, "ewk");
        values.put(2131371282, "comment_container");
        values.put(2131371297, "e17");
        values.put(2131371374, "e32");
        values.put(2131371412, "comment_layout");
        values.put(2131371600, "e9l");
        values.put(2131371905, "fc7");
        values.put(2131374336, "gw+");
        values.put(2131374348, "gxl");
        values.put(2131374349, "gxm");
        values.put(2131374356, "gxt");
        values.put(2131374369, "gx6");
        values.put(2131378120, "i=8");
        values.put(2131378145, "i_t");
        values.put(2131378315, "jb1");
        values.put(2131378359, "jc5");
        values.put(2131378361, "jc7");
        values.put(2131379745, "j=+");
        values.put(2131379757, "j_l");
        values.put(2131380047, "ket");
        values.put(2131380048, "keu");
        values.put(2131386250, "n80");
        values.put(2131388599, "pm6");
        values.put(2131393288, "si1");
        values.put(2131393289, "si2");
        values.put(2131394023, "s1c");
        values.put(2131394026, "s1f");
        values.put(2131394225, "s6v");
        values.put(2131394230, "s60");
        values.put(2131394663, "tdx");
        values.put(2131398470, "title");
        values.put(2131400515, "w05");
        values.put(2131401779, "xr6");
        values.put(2131401927, "share_container");
        values.put(2131402155, "x1c");
        values.put(2131405160, "zu2");
        values.put(2131405374, "z0a");
        values.put(2131410329, "22i");
        values.put(2131415061, "5zd");
        values.put(2131415074, "5zq");
        values.put(2131415727, "user_avatar");
        values.put(2131415983, "6h0");
        values.put(2131416121, "6ld");
        values.put(2131416122, "6le");
        return Collections.unmodifiableMap(values);
    }

    private static Map<Integer, String> feedResources() {
        Map<Integer, String> values = new LinkedHashMap<>();
        values.put(2131366240, "b1z");
        values.put(2131367357, "cmx");
        values.put(2131362267, "i6");
        values.put(2131370692, "em5");
        values.put(2131374327, "gw1");
        values.put(2131374339, "gw+");
        values.put(2131374340, "gxa");
        values.put(2131374360, "gxu");
        values.put(2131374347, "gxh");
        values.put(2131379772, "j_x");
        values.put(2131379761, "j_m");
        values.put(2131371285, "comment_container");
        values.put(2131371299, "e16");
        values.put(2131371376, "e31");
        values.put(2131371605, "e9n");
        values.put(2131371414, "comment_layout");
        values.put(2131371073, "ewj");
        values.put(2131370902, "esa");
        values.put(2131370904, "esc");
        values.put(2131371909, "fc8");
        values.put(2131378332, "jcb");
        values.put(2131378377, "jdf");
        values.put(2131378379, "jdh");
        values.put(2131378139, "i_k");
        values.put(2131369833, "d5w");
        values.put(2131378163, "i_8");
        values.put(2131380062, "ke6");
        values.put(2131380063, "ke7");
        values.put(2131386238, "n8l");
        values.put(2131388590, "pmu");
        values.put(2131393278, "sin");
        values.put(2131393279, "sio");
        values.put(2131394011, "s01");
        values.put(2131394015, "s05");
        values.put(2131394653, "tdk");
        values.put(2131394215, "s6i");
        values.put(2131394220, "s6n");
        values.put(2131398470, "title");
        values.put(2131400515, "w01");
        values.put(2131401927, "share_container");
        values.put(2131405175, "zu+");
        values.put(2131405391, "z0n");
        values.put(2131402182, "x1y");
        values.put(2131401805, "xso");
        values.put(2131410333, "22h");
        values.put(2131415055, "5y5");
        values.put(2131415033, "5yj");
        values.put(2131415727, "user_avatar");
        values.put(2131415966, "6hd");
        values.put(2131416104, "6ku");
        values.put(2131416105, "6kv");
        return Collections.unmodifiableMap(values);
    }
    private static Map<Integer, String> modernFeedResources() {
        Map<Integer, String> values = new LinkedHashMap<>();
        values.put(2131366203, "b02");
        values.put(2131367320, "cl2");
        values.put(2131362267, "i6");
        values.put(2131370655, "el8");
        values.put(2131374282, "gvw");
        values.put(2131374294, "gv8");
        values.put(2131374295, "gv9");
        values.put(2131374315, "gwp");
        values.put(2131374302, "gwc");
        values.put(2131379753, "j_e");
        values.put(2131379741, "j=6");
        values.put(2131371246, "comment_container");
        values.put(2131371261, "e09");
        values.put(2131371342, "e29");
        values.put(2131371570, "e8t");
        values.put(2131371378, "comment_layout");
        values.put(2131371037, "evo");
        values.put(2131370865, "ere");
        values.put(2131370867, "erg");
        values.put(2131371873, "fb-");
        values.put(2131378309, "jbs");
        values.put(2131378353, "jcw");
        values.put(2131378355, "jcy");
        values.put(2131378098, "i=j");
        values.put(2131369788, "d4s");
        values.put(2131378122, "i=7");
        values.put(2131380038, "kei");
        values.put(2131380039, "kej");
        values.put(2131386199, "n7p");
        values.put(2131388552, "plv");
        values.put(2131393246, "shw");
        values.put(2131393247, "shx");
        values.put(2131393975, "sz5");
        values.put(2131393978, "sz8");
        values.put(2131394615, "tco");
        values.put(2131394179, "s5l");
        values.put(2131394184, "s5q");
        values.put(2131398470, "title");
        values.put(2131400515, "w05");
        values.put(2131401927, "share_container");
        values.put(2131405215, "zwb");
        values.put(2131405431, "z1p");
        values.put(2131402240, "x3g");
        values.put(2131401855, "xt2");
        values.put(2131410353, "223");
        values.put(2131415074, "5zm");
        values.put(2131415061, "5y+");
        values.put(2131415727, "user_avatar");
        values.put(2131415971, "6hk");
        values.put(2131416106, "6ky");
        values.put(2131416107, "6kz");
        return Collections.unmodifiableMap(values);
    }
}
