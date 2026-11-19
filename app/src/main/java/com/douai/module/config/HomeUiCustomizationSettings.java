package com.douai.module.config;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.douai.module.feature.MediaMemoryBudget;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.TreeSet;
import java.util.Set;

/** Bounded, content-checked static WebP overrides. Invalid edits throw before publication. */
public final class HomeUiCustomizationSettings {
    public static final String ICONS = "theme_icon_overrides";
    public static final String HIDE_LABELS = "theme_nav_hide_labels";
    public static final String HIDDEN_NAVIGATION = "theme_hidden_navigation";
    public static final int MAX_ICON_BYTES = 32768;
    // The 44 supplied images plus the previous 256 KiB allowance fit below the 2 MiB backup bound.
    public static final int MAX_TOTAL_BASE64 = 1536 * 1024;
    public static final int MAX_ICONS = 32;
    public static final int MAX_JSON_LENGTH = MAX_TOTAL_BASE64 + MAX_ICONS * 400;
    private static volatile ValidatedIcons lastValidatedIcons;

    /** One immutable text-only result; no bitmap, window or context is retained here. */
    private static final class ValidatedIcons {
        final String json;
        final Map<String,Map<IconState,String>> icons;
        ValidatedIcons(String json,Map<String,Map<IconState,String>> icons){this.json=json;this.icons=icons;}
    }

    public enum IconState {
        DAY_NORMAL("day_normal","日间静态",false,false),
        DAY_SELECTED("day_selected","日间选中",false,true),
        NIGHT_NORMAL("night_normal","夜间静态",true,false),
        NIGHT_SELECTED("night_selected","夜间选中",true,true);
        public final String key,title;
        public final boolean night,selected;
        IconState(String key,String title,boolean night,boolean selected){this.key=key;this.title=title;this.night=night;this.selected=selected;}
        public static IconState of(boolean night,boolean selected){return night?(selected?NIGHT_SELECTED:NIGHT_NORMAL):(selected?DAY_SELECTED:DAY_NORMAL);}
    }

    private HomeUiCustomizationSettings() { }

    /** Visibility is navigation-only and independent of all four persisted artwork states. */
    public static Set<String> readHiddenNavigation(String json) {
        if(json==null || "[]".equals(json))return Collections.emptySet();
        if(json.length()>8192)throw invalid("隐藏导航配置过大");
        if(json.trim().isEmpty())return Collections.emptySet();
        try {
            Object value=ConfigJson.parse(json,8192,MAX_ICONS);
            if(!(value instanceof JSONArray))throw invalid("隐藏导航配置必须是 JSON 数组");
            JSONArray entries=(JSONArray)value;Set<String> keys=new TreeSet<>();
            for(int i=0;i<entries.length();i++) {
                Object entry=entries.get(i);
                if(!(entry instanceof String))throw invalid("请选择有效的导航位置");
                String key=canonicalNavigationKey((String)entry);requireNavigationKey(key);
                if(!keys.add(key))throw invalid("隐藏导航位置重复");
            }
            return Collections.unmodifiableSet(keys);
        }catch(IllegalArgumentException failure){throw failure;}
        catch(Exception failure){throw invalid("无法读取隐藏导航配置");}
    }

    public static String setNavigationHidden(String json,String key,boolean hidden) {
        key=canonicalNavigationKey(key);requireNavigationKey(key);
        Set<String> keys=new TreeSet<>(readHiddenNavigation(json));
        if(hidden)keys.add(key);else keys.remove(key);
        if(keys.size()>MAX_ICONS)throw invalid("最多隐藏 32 个导航位置");
        return keys.isEmpty()?"[]":new JSONArray(keys).toString();
    }

    public static String normalizeHiddenNavigation(String json) {
        Set<String> keys=readHiddenNavigation(json);
        return keys.isEmpty()?"[]":new JSONArray(keys).toString();
    }

    private static void requireNavigationKey(String key) {
        requireKey(key);
        if(!key.startsWith("nav_top:") && !key.startsWith("nav_bottom:"))throw invalid("请选择上导航或下导航位置");
    }

    public static String putIcon(String json, String key, byte[] bytes) {
        requireKey(key);
        key=canonicalNavigationKey(key);
        validateIcon(bytes);
        TreeMap<String,Entry> icons = parse(json);
        icons.put(key, new Entry(Base64.getEncoder().encodeToString(bytes),true));
        icons.remove(nativeAlias(key));
        return encode(icons);
    }

    public static String putIcon(String json,String key,IconState state,byte[] bytes) {
        if(bytes==null)throw invalid("请选择静态 WebP 图标");
        return putIcons(json,key,Collections.singletonMap(requireState(state),bytes));
    }

    /** Every variant is validated before the complete replacement is returned to the caller. */
    public static String putIcons(String json,String key,Map<IconState,byte[]> variants) {
        requireKey(key);
        key=canonicalNavigationKey(key);
        if(variants==null || variants.isEmpty())throw invalid("请选择图标状态");
        TreeMap<String,Entry> icons=parse(json);
        Entry previous=preferredEntry(icons,key),next=previous==null?new Entry(null,false):previous.copy();
        next.legacy=false;
        for(Map.Entry<IconState,byte[]> variant:variants.entrySet()) {
            IconState state=requireState(variant.getKey());byte[] bytes=variant.getValue();
            if(bytes!=null)validateIcon(bytes);
            next.states.put(state,bytes==null?null:Base64.getEncoder().encodeToString(bytes));
        }
        icons.put(key,next);icons.remove(nativeAlias(key));return encode(icons);
    }

    public static String removeIcon(String json, String key) {
        requireKey(key);
        key=canonicalNavigationKey(key);
        TreeMap<String,Entry> icons = parse(json);
        icons.remove(key);
        icons.remove(nativeAlias(key));
        if(key.equals("feed_like") || key.equals("feed_collect"))icons.remove(key+"_selected");
        return encode(icons);
    }

    /** Explicit native artwork blocks legacy default/selected fallback for this state. */
    public static String removeIcon(String json,String key,IconState state) {
        return putIcons(json,key,Collections.singletonMap(requireState(state),null));
    }

    public static String normalize(String json) { return encode(parse(json)); }

    public static byte[] readIcon(String json, String key) {
        requireKey(key);
        Entry entry=preferredEntry(parse(json),canonicalNavigationKey(key));
        String encoded=entry==null?null:entry.value(IconState.DAY_NORMAL);
        return encoded == null ? null : Base64.getDecoder().decode(encoded);
    }

    public static byte[] readIcon(String json,String key,IconState state) {
        requireKey(key);requireState(state);
        Map<IconState,String> icons=readEncodedIcons(json).get(baseKey(key));
        String encoded=icons==null?null:icons.get(state);
        return encoded==null?null:Base64.getDecoder().decode(encoded);
    }

    /** One bounded parser and one fallback policy serve the settings preview and the runtime. */
    public static Map<String,Map<IconState,String>> readEncodedIcons(String json) {
        ValidatedIcons cached=lastValidatedIcons;
        if(cached!=null && java.util.Objects.equals(cached.json,json))return cached.icons;
        TreeMap<String,Entry> parsed=parse(json);Map<String,Map<IconState,String>> result=new LinkedHashMap<>();
        for(String key:logicalKeys(parsed)) {
            Entry entry=preferredEntry(parsed,key),selected=parsed.get(selectedAlias(key));
            EnumMap<IconState,String> states=new EnumMap<>(IconState.class);
            for(IconState state:IconState.values()) {
                String value;
                if(entry!=null && entry.states.containsKey(state))value=entry.states.get(state);
                else if(state.selected && selected!=null && (selected.states.containsKey(state) || selected.fallback!=null))value=selected.value(state);
                else value=entry==null?null:entry.fallback;
                states.put(state,value);
            }
            result.put(key,Collections.unmodifiableMap(states));
        }
        Map<String,Map<IconState,String>> resolved=Collections.unmodifiableMap(result);
        lastValidatedIcons=new ValidatedIcons(json,resolved);
        return resolved;
    }

    private static TreeMap<String,Entry> parse(String json) {
        TreeMap<String,Entry> result = new TreeMap<>();
        if (json == null) return result;
        if (json.length() > MAX_JSON_LENGTH) throw invalid("图标配置过大");
        if (json.trim().isEmpty()) return result;
        try {
            Object value = ConfigJson.parse(json, MAX_JSON_LENGTH, MAX_ICONS);
            if (!(value instanceof JSONObject)) throw invalid("图标配置必须是 JSON 对象");
            JSONObject object = (JSONObject) value;
            // Four native bottom aliases and two legacy Feed selections can share logical positions.
            if (object.length() > MAX_ICONS+6) throw invalid("最多保存 32 个图标位置");
            int[] total={0};
            for (Iterator<String> keys = object.keys(); keys.hasNext();) {
                String key = keys.next();
                requireKey(key);
                Object raw = object.get(key);
                Entry entry;
                if(raw instanceof String)entry=new Entry(checked((String)raw,total),true);
                else if(raw instanceof JSONObject) {
                    JSONObject fields=(JSONObject)raw;if(fields.length()>5)throw invalid("未知图标状态");
                    entry=new Entry(null,false);
                    for(Iterator<String> names=fields.keys();names.hasNext();) {
                        String name=names.next();Object field=fields.get(name);
                        if(name.equals("default")) {
                            if(!(field instanceof String))throw invalid("通用图标必须是 Base64 字符串");
                            entry.fallback=checked((String)field,total);
                        }else {
                            IconState state=state(name);
                            if(field!=JSONObject.NULL && !(field instanceof String))throw invalid("图标状态必须是 Base64 或 null");
                            entry.states.put(state,field==JSONObject.NULL?null:checked((String)field,total));
                        }
                    }
                }else throw invalid("图标内容必须是 Base64 字符串或状态对象");
                result.put(key,entry);
            }
            requireCount(result);
            return result;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw invalid("无法读取图标配置");
        }
    }

    private static String encode(TreeMap<String,Entry> icons) {
        requireCount(icons);
        int total = 0;
        JSONObject object = new JSONObject();
        try {
            for (Map.Entry<String,Entry> icon : icons.entrySet()) {
                Entry entry=icon.getValue();
                if(entry.fallback!=null)total+=entry.fallback.length();
                for(String encoded:entry.states.values())if(encoded!=null)total+=encoded.length();
                if(total>MAX_TOTAL_BASE64)throw invalid("图标配置超过容量限制");
                if(entry.legacy)object.put(icon.getKey(),entry.fallback);
                else {
                    JSONObject fields=new JSONObject();if(entry.fallback!=null)fields.put("default",entry.fallback);
                    for(IconState state:IconState.values())if(entry.states.containsKey(state))fields.put(state.key,entry.states.get(state)==null?JSONObject.NULL:entry.states.get(state));
                    object.put(icon.getKey(),fields);
                }
            }
            return object.toString();
        } catch (org.json.JSONException error) {
            throw invalid("无法保存图标配置");
        }
    }

    private static final class Entry {
        String fallback;boolean legacy;
        final EnumMap<IconState,String> states=new EnumMap<>(IconState.class);
        Entry(String fallback,boolean legacy){this.fallback=fallback;this.legacy=legacy;}
        Entry copy(){Entry copy=new Entry(fallback,legacy);copy.states.putAll(states);return copy;}
        String value(IconState state){return states.containsKey(state)?states.get(state):fallback;}
    }
    private static String checked(String encoded,int[] total) {
        if(encoded.length()>((MAX_ICON_BYTES+2)/3)*4)throw invalid("图标配置超过容量限制");
        byte[] bytes=Base64.getDecoder().decode(encoded);validateIcon(bytes);
        String canonical=Base64.getEncoder().encodeToString(bytes);
        if((total[0]+=canonical.length())>MAX_TOTAL_BASE64)throw invalid("图标配置超过容量限制");
        return canonical;
    }
    private static IconState requireState(IconState state){if(state==null)throw invalid("未知图标状态");return state;}
    private static IconState state(String key){for(IconState state:IconState.values())if(state.key.equals(key))return state;throw invalid("未知图标状态");}
    /** Only verified 41 bottom aliases change; top and unknown dynamic IDs retain their identity. */
    public static String canonicalNavigationKey(String key) {
        if(key==null)return null;
        switch(key) {
            case "nav_bottom:HOME":return "nav_bottom:homepage_home";
            case "nav_bottom:FAMILIAR":return "nav_bottom:homepage_familiar";
            case "nav_bottom:NOTIFICATION":return "nav_bottom:homepage_notification";
            case "nav_bottom:USER":return "nav_bottom:homepage_profile";
            default:return key;
        }
    }
    private static String nativeAlias(String key) {
        switch(key) {
            case "nav_bottom:homepage_home":return "nav_bottom:HOME";
            case "nav_bottom:homepage_familiar":return "nav_bottom:FAMILIAR";
            case "nav_bottom:homepage_notification":return "nav_bottom:NOTIFICATION";
            case "nav_bottom:homepage_profile":return "nav_bottom:USER";
            default:return "";
        }
    }
    private static Entry preferredEntry(Map<String,Entry> icons,String key) {
        Entry canonical=icons.get(key);
        // Presence selects the entire canonical entry, including its explicit nulls and missing states.
        return canonical!=null?canonical:icons.get(nativeAlias(key));
    }
    private static String baseKey(String key){return canonicalNavigationKey(key.equals("feed_like_selected")?"feed_like":key.equals("feed_collect_selected")?"feed_collect":key);}
    private static String selectedAlias(String key){return key.equals("feed_like") || key.equals("feed_collect")?key+"_selected":"";}
    private static TreeSet<String> logicalKeys(Map<String,Entry> icons){TreeSet<String> keys=new TreeSet<>();for(String key:icons.keySet())keys.add(baseKey(key));return keys;}
    private static void requireCount(Map<String,Entry> icons){if(logicalKeys(icons).size()>MAX_ICONS)throw invalid("最多保存 32 个图标位置");}

    private static void requireKey(String key) {
        if (key == null || key.length() > 160) throw invalid("未知图标位置");
        switch (key) {
            case "feed_like": case "feed_like_selected": case "feed_comment":
            case "feed_collect": case "feed_collect_selected": case "feed_share":
            case "feed_follow": case "feed_mute": return;
            default:
                if (key.matches("nav_(?:top|bottom):[A-Za-z0-9_.:-]{1,128}")) return;
                throw invalid("未知图标位置");
        }
    }

    private static void validateIcon(byte[] bytes) {
        if (bytes == null || bytes.length < 20 || bytes.length > MAX_ICON_BYTES
                || !tag(bytes, 0, "RIFF") || !tag(bytes, 8, "WEBP")
                || u32(bytes, 4) != bytes.length - 8L) throw invalid("请选择不超过 32 KiB 的静态 WebP");
        boolean pixels = false;
        int offset = 12;
        while (offset < bytes.length) {
            if (bytes.length - offset < 8) throw invalid("WebP 数据已截断");
            long size = u32(bytes, offset + 4);
            long end = offset + 8L + size + (size & 1);
            if (end > bytes.length) throw invalid("WebP 数据已截断");
            if (tag(bytes, offset, "ANIM") || tag(bytes, offset, "ANMF")
                    || tag(bytes, offset, "VP8X") && (size < 10 || (bytes[offset + 8] & 2) != 0)) {
                throw invalid("目前仅支持静态 WebP 图标");
            }
            pixels |= tag(bytes, offset, "VP8 ") || tag(bytes, offset, "VP8L");
            offset = (int) end;
        }
        if (!pixels) throw invalid("WebP 缺少图像数据");
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 512 || bounds.outHeight > 512) {
            throw invalid("图标尺寸须在 1 至 512 像素之间");
        }
        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = 1;
        while ((bounds.outWidth + decode.inSampleSize - 1) / decode.inSampleSize > 256
                || (bounds.outHeight + decode.inSampleSize - 1) / decode.inSampleSize > 256) decode.inSampleSize *= 2;
        decode.inPreferredConfig = Bitmap.Config.ARGB_8888;
        long memory = bytes.length + 4L * ((bounds.outWidth + decode.inSampleSize - 1) / decode.inSampleSize)
                * ((bounds.outHeight + decode.inSampleSize - 1) / decode.inSampleSize);
        try (MediaMemoryBudget.Reservation reservation = MediaMemoryBudget.tryAcquire(memory)) {
            if (reservation == null) throw invalid("图片内存繁忙，请稍后重试");
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, decode);
            if (bitmap == null) throw invalid("WebP 图像无法解码");
            boolean valid=bitmap.getWidth()>0 && bitmap.getHeight()>0 && bitmap.getWidth()<=256 && bitmap.getHeight()<=256;
            bitmap.recycle();
            if(!valid)throw invalid("WebP 解码尺寸超出限制");
        } catch (OutOfMemoryError error) {
            throw invalid("图片内存不足");
        }
    }

    private static boolean tag(byte[] bytes, int offset, String text) {
        if (offset < 0 || bytes.length - offset < 4) return false;
        for (int i = 0; i < 4; i++) if ((bytes[offset + i] & 255) != text.charAt(i)) return false;
        return true;
    }
    private static long u32(byte[] bytes, int offset) {
        return (bytes[offset] & 255L) | (bytes[offset + 1] & 255L) << 8
                | (bytes[offset + 2] & 255L) << 16 | (bytes[offset + 3] & 255L) << 24;
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
