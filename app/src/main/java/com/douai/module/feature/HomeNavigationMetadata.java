package com.douai.module.feature;

import com.douai.module.config.HomeUiCustomizationSettings;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.regex.Pattern;

/** Process-local recent host observations. Only bounded strings and quality values are retained. */
final class HomeNavigationMetadata {
    static final int CAPACITY=64;
    private static final int MAX_LABEL_CODE_POINTS=40;
    private static final Pattern NAVIGATION_KEY=Pattern.compile("nav_(?:top|bottom):[A-Za-z0-9_.:-]{1,128}");
    private static final Map<String,String> DEFAULTS=defaults();
    private final LinkedHashMap<String,Observation> observed=new LinkedHashMap<>(CAPACITY,.75f,true);

    synchronized void observe(String key,CharSequence label,boolean visible) {
        key=HomeUiCustomizationSettings.canonicalNavigationKey(key);
        if(!navigationKey(key))return;
        String name=usableLabel(key,label);int quality=name.isEmpty()?0:visible?2:1;
        Observation previous=observed.get(key);
        if(previous==null || quality>=previous.quality && (!name.equals(previous.name) || quality!=previous.quality))
            observed.put(key,new Observation(name,quality));
        while(observed.size()>CAPACITY){var eldest=observed.entrySet().iterator();eldest.next();eldest.remove();}
    }

    /** Defaults and saved keys do not become host observations or change observation recency. */
    synchronized Map<String,String> titles(Collection<String> savedKeys) {
        Map<String,String> result=new LinkedHashMap<>(DEFAULTS);
        var saved=new LinkedHashSet<String>();
        if(savedKeys!=null)for(String key:savedKeys) {
            key=HomeUiCustomizationSettings.canonicalNavigationKey(key);
            if(navigationKey(key))saved.add(key);
            // Artwork and visibility each have their own bounded saved-position set.
            if(saved.size()>=HomeUiCustomizationSettings.MAX_ICONS*2)break;
        }
        for(String key:saved)result.putIfAbsent(key,displayTitle(key,""));
        for(var entry:observed.entrySet())result.put(entry.getKey(),displayTitle(entry.getKey(),entry.getValue().name));
        Map<String,Integer> counts=new HashMap<>();for(String title:result.values())counts.merge(title,1,Integer::sum);
        for(var entry:result.entrySet())if(counts.get(entry.getValue())>1 && !DEFAULTS.containsKey(entry.getKey()))
            entry.setValue(entry.getValue()+"（"+id(entry.getKey())+"）");
        return Collections.unmodifiableMap(result);
    }

    static String usableLabel(String key,CharSequence label) {
        if(label==null)return "";
        String value=label.toString().replace('\n',' ').replace('\r',' ').replace('\t',' ').trim();
        if(value.isEmpty() || value.equals(key) || value.equals(id(key)))return "";
        if(value.codePointCount(0,value.length())>MAX_LABEL_CODE_POINTS)
            value=value.substring(0,value.offsetByCodePoints(0,MAX_LABEL_CODE_POINTS-1))+"…";
        return value;
    }

    private static String displayTitle(String key,String name) {
        String known=DEFAULTS.get(key);if(known!=null)return known;
        boolean top=key.startsWith("nav_top:");
        return (top?"上导航 · ":"下导航 · ")+(name.isEmpty()?(top?"未命名频道":"未命名导航")+"（"+id(key)+"）":name);
    }
    private static String id(String key){return key.substring(key.indexOf(':')+1);}
    private static boolean navigationKey(String key){return key!=null && NAVIGATION_KEY.matcher(key).matches();}
    private static Map<String,String> defaults() {
        Map<String,String> values=new LinkedHashMap<>();
        values.put("nav_bottom:homepage_home","下导航 · 首页");
        values.put("nav_bottom:homepage_familiar","下导航 · 朋友");
        values.put("nav_bottom:homepage_notification","下导航 · 消息");
        values.put("nav_bottom:homepage_profile","下导航 · 我");
        return Collections.unmodifiableMap(values);
    }
    private static final class Observation {
        final String name;final int quality;
        Observation(String name,int quality){this.name=name;this.quality=quality;}
    }
}
