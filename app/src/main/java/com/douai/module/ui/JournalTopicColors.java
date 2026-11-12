package com.douai.module.ui;

import com.douai.module.analytics.AnalyticsSnapshot.Rank;
import com.douai.module.analytics.PortraitEvidence;
import com.douai.module.analytics.PortraitSnapshot;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** One key per displayed topic across the entire edition, including previous-only topics. */
final class JournalTopicColors {
    private static final int[] INK = {0xff744575,0xff206956,0xff285e86,0xff815524,0xff534f8c,
            0xff934460,0xff4a6338,0xffa13e20,0xff495965};
    private static final int[] BRIGHT = {0xffe4acf0,0xff80dbc2,0xff99ceff,0xffeec188,0xffc6baff,
            0xffffadca,0xffbad69b,0xffffa381,0xffbdcdd6};
    private final Map<String,Integer> slots = new HashMap<>();

    JournalTopicColors(PortraitEvidence evidence, List<Rank> tiles, PortraitSnapshot.Comparison comparison) {
        TreeSet<String> names = new TreeSet<>();
        for (Rank r : tiles) names.add(r.label);
        for (String name : names) assign(name); // Distinguish every tile before overflow elsewhere.
        if (evidence != null) for (PortraitEvidence.Topic t : evidence.topics) names.add(t.label);
        if (comparison != null) {
            for (Rank r : comparison.currentTopics) names.add(r.label);
            for (Rank r : comparison.previousTopics) names.add(r.label);
        }
        for (String name : names) assign(name);
    }
    private void assign(String name) {
        if (slots.containsKey(name)) return;
        int slot = Math.floorMod(name.hashCode(), INK.length);
        if (slots.size() < INK.length) while (slots.containsValue(slot)) slot = (slot + 1) % INK.length;
        slots.put(name, slot);
    }
    int color(String name, boolean darkSurface) {
        return (darkSurface ? BRIGHT : INK)[slots.get(name)];
    }
}
