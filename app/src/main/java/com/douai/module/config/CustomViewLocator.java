package com.douai.module.config;

import android.view.View;
import android.view.ViewGroup;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A persistent locator scoped to either the selected feed card or its Activity window. */
public final class CustomViewLocator {
    public static final String SETTINGS_KEY = "component_hide_custom_views";
    private static final int FORMAT_VERSION = 2;
    private static final int MAX_ITEMS = 128;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_SEARCH_NODES = 2000;
    private static final int MAX_NAME_LENGTH = 512;
    private static final int MAX_ENCODED_LENGTH = 1024 * 1024;

    public enum Scope { WINDOW, FEED }

    public final Scope scope;
    public final String rootClassName;
    public final String viewClassName;
    public final String resourceName;
    public final String label;
    public final int alpha;
    private final boolean uniqueId;
    private final int anchorIndex;
    private final List<Segment> path;

    private CustomViewLocator(Scope scope, String rootClassName, String viewClassName,
                              String resourceName, boolean uniqueId, int anchorIndex,
                              List<Segment> path) {
        this(scope, rootClassName, viewClassName, resourceName, uniqueId, anchorIndex, path, -2);
    }

    private CustomViewLocator(Scope scope, String rootClassName, String viewClassName,
                              String resourceName, boolean uniqueId, int anchorIndex,
                              List<Segment> path, int alpha) {
        this.scope = scope;
        this.rootClassName = rootClassName;
        this.viewClassName = viewClassName;
        this.resourceName = resourceName;
        this.label = resourceName.isEmpty() ? simpleName(viewClassName) : resourceName;
        this.alpha = alpha;
        this.uniqueId = uniqueId;
        this.anchorIndex = anchorIndex;
        this.path = Collections.unmodifiableList(new ArrayList<>(path));
    }

    public CustomViewLocator withAlpha(int alpha) {
        if (alpha < -2 || alpha > 255) throw new IllegalArgumentException("Invalid custom view alpha");
        return new CustomViewLocator(scope, rootClassName, viewClassName, resourceName,
                uniqueId, anchorIndex, path, alpha);
    }

    public static CustomViewLocator capture(View root, View target) {
        return capture(root, null, target);
    }

    public static CustomViewLocator capture(View windowRoot, View feedRoot, View target) {
        if (windowRoot == null || target == null || target == windowRoot
                || !isWithin(target, windowRoot) || hasFinderAncestor(target, windowRoot)) return null;
        boolean inFeed = feedRoot != null && isWithin(feedRoot, windowRoot) && isWithin(target, feedRoot);
        View root = inFeed ? feedRoot : windowRoot;
        if (root == target) return null;
        ArrayList<View> nodes = new ArrayList<>();
        View current = target;
        while (current != null && current != root && nodes.size() < MAX_DEPTH) {
            nodes.add(current);
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        if (current != root || nodes.isEmpty()) return null;
        Collections.reverse(nodes);

        ArrayList<Segment> segments = new ArrayList<>(nodes.size());
        View parent = root;
        for (View node : nodes) {
            if (!(parent instanceof ViewGroup)) return null;
            ViewGroup group = (ViewGroup) parent;
            int childIndex = group.indexOfChild(node);
            if (childIndex < 0) return null;
            String className = node.getClass().getName();
            String id = resourceName(node);
            int peerIndex = 0;
            int peerCount = 0;
            for (int index = 0; index < group.getChildCount(); index++) {
                if (sameSignature(group.getChildAt(index), className, id)) {
                    if (index < childIndex) peerIndex++;
                    peerCount++;
                }
            }
            segments.add(new Segment(childIndex, peerIndex, peerCount, className, id));
            parent = node;
        }
        String className = target.getClass().getName();
        String id = resourceName(target);
        boolean uniqueId = !id.isEmpty() && uniqueMatch(root, className, id) == target;
        int anchor = -1;
        if (!uniqueId) {
            // A named ancestor can move without changing an anonymous child's exact local structure.
            for (int index = nodes.size() - 2; index >= 0; index--) {
                Segment segment = segments.get(index);
                if (!segment.resourceName.isEmpty()
                        && uniqueMatch(root, segment.className, segment.resourceName) == nodes.get(index)) {
                    anchor = index;
                    break;
                }
            }
        }
        return new CustomViewLocator(inFeed ? Scope.FEED : Scope.WINDOW,
                root.getClass().getName(), className, id, uniqueId, anchor, segments);
    }

    public View resolve(View root) {
        return resolve(root, null);
    }

    public View resolve(View windowRoot, View feedRoot) {
        View root = scope == Scope.FEED ? feedRoot : windowRoot;
        if (root == null || windowRoot == null || !isWithin(root, windowRoot)
                || hasFinderAncestor(root, windowRoot)
                || !rootClassName.equals(root.getClass().getName()) || path.isEmpty()) return null;
        if (uniqueId) return uniqueMatch(root, viewClassName, resourceName);
        View current = root;
        int start = 0;
        if (anchorIndex >= 0) {
            Segment anchor = path.get(anchorIndex);
            current = uniqueMatch(root, anchor.className, anchor.resourceName);
            if (current == null) return null;
            start = anchorIndex + 1;
        }
        for (int index = start; index < path.size(); index++) {
            Segment segment = path.get(index);
            if (!(current instanceof ViewGroup)) return null;
            ViewGroup group = (ViewGroup) current;
            int peers = 0;
            int indexedPeer = -1;
            View soleMatch = null;
            for (int childIndex = 0; childIndex < group.getChildCount(); childIndex++) {
                View child = group.getChildAt(childIndex);
                if (sameSignature(child, segment.className, segment.resourceName)) {
                    if (childIndex == segment.childIndex) indexedPeer = peers;
                    soleMatch = child;
                    peers++;
                }
            }
            if (peers != segment.peerCount) return null;
            if (!segment.resourceName.isEmpty() && peers == 1) {
                current = soleMatch;
            } else {
                if (segment.childIndex >= group.getChildCount() || indexedPeer != segment.peerIndex) return null;
                current = group.getChildAt(segment.childIndex);
            }
        }
        return sameSignature(current, viewClassName, resourceName) ? current : null;
    }

    public String identityKey() {
        StringBuilder result = new StringBuilder(scope.name()).append(':').append(rootClassName);
        if (uniqueId) return result.append('/').append(viewClassName).append('#').append(resourceName).toString();
        if (anchorIndex >= 0) {
            Segment anchor = path.get(anchorIndex);
            result.append('/').append(anchor.className).append('#').append(anchor.resourceName);
        }
        for (int index = anchorIndex + 1; index < path.size(); index++) {
            Segment segment = path.get(index);
            result.append('/').append(segment.className).append('#').append(segment.resourceName);
            if (segment.resourceName.isEmpty() || segment.peerCount > 1) {
                result.append(':').append(segment.childIndex).append(':').append(segment.peerIndex)
                        .append(':').append(segment.peerCount);
            }
        }
        return result.toString();
    }

    public String scopeDescription() {
        return scope == Scope.FEED ? "当前信息流卡片" : "当前窗口";
    }

    public String matchingDescription() {
        if (uniqueId) return "唯一资源 ID";
        return anchorIndex >= 0 ? "命名祖先 + 结构路径" : "严格结构路径";
    }

    /** Describes only the metadata used by resolve(), without reading host text or descriptions. */
    public String pathDescription() {
        StringBuilder result = new StringBuilder(simpleName(rootClassName));
        if (uniqueId) {
            return result.append(" → … → ").append(simpleName(viewClassName))
                    .append(" #").append(resourceName).toString();
        }
        int start = 0;
        if (anchorIndex >= 0) {
            Segment anchor = path.get(anchorIndex);
            result.append(" → … → ").append(simpleName(anchor.className))
                    .append(" #").append(anchor.resourceName);
            start = anchorIndex + 1;
        }
        for (int index = start; index < path.size(); index++) {
            Segment segment = path.get(index);
            result.append(" → ").append(simpleName(segment.className));
            if (!segment.resourceName.isEmpty()) result.append(" #").append(segment.resourceName);
            if (segment.resourceName.isEmpty() || segment.peerCount > 1) {
                result.append(" [子节点 ").append(segment.childIndex + 1)
                        .append("，同类同 ID ").append(segment.peerIndex + 1)
                        .append('/').append(segment.peerCount).append(']');
            }
        }
        return result.toString();
    }

    public static List<CustomViewLocator> parse(String encoded) {
        if (encoded == null || encoded.isEmpty() || encoded.length() > MAX_ENCODED_LENGTH) {
            return Collections.emptyList();
        }
        try {
            JSONTokener tokener = new JSONTokener(encoded);
            Object decoded = tokener.nextValue();
            if (!(decoded instanceof JSONObject) || tokener.nextClean() != 0) return Collections.emptyList();
            JSONObject root = (JSONObject) decoded;
            if (requiredInt(root, "version") != FORMAT_VERSION) return Collections.emptyList();
            JSONArray items = root.getJSONArray("items");
            if (items.length() > MAX_ITEMS) return Collections.emptyList();
            ArrayList<CustomViewLocator> result = new ArrayList<>(items.length());
            for (int itemIndex = 0; itemIndex < items.length(); itemIndex++) {
                JSONObject item = items.getJSONObject(itemIndex);
                Scope scope = Scope.valueOf(requiredString(item, "scope", false));
                String rootClass = requiredString(item, "root", false);
                String viewClass = requiredString(item, "class", false);
                String id = requiredString(item, "id", true);
                String label = requiredString(item, "label", false);
                String mode = requiredString(item, "mode", false);
                boolean uniqueId = "id".equals(mode);
                if ((!uniqueId && !"path".equals(mode)) || (uniqueId && id.isEmpty())
                        || !label.equals(id.isEmpty() ? simpleName(viewClass) : id)) return Collections.emptyList();
                int anchor = requiredInt(item, "anchor");
                int alpha = item.has("alpha") ? requiredInt(item, "alpha") : -2;
                if (alpha < -2 || alpha > 255) return Collections.emptyList();
                JSONArray encodedPath = item.getJSONArray("path");
                if (encodedPath.length() == 0 || encodedPath.length() > MAX_DEPTH
                        || anchor < -1 || anchor >= encodedPath.length() - 1
                        || (uniqueId && anchor != -1)) return Collections.emptyList();
                ArrayList<Segment> segments = new ArrayList<>(encodedPath.length());
                for (int pathIndex = 0; pathIndex < encodedPath.length(); pathIndex++) {
                    JSONObject node = encodedPath.getJSONObject(pathIndex);
                    int index = requiredInt(node, "index");
                    int peer = requiredInt(node, "peer");
                    int count = requiredInt(node, "count");
                    if (index < 0 || peer < 0 || count <= 0 || peer >= count || peer > index) {
                        return Collections.emptyList();
                    }
                    segments.add(new Segment(index, peer, count,
                            requiredString(node, "class", false), requiredString(node, "id", true)));
                }
                Segment leaf = segments.get(segments.size() - 1);
                if (!leaf.className.equals(viewClass) || !leaf.resourceName.equals(id)
                        || (anchor >= 0 && segments.get(anchor).resourceName.isEmpty())) {
                    return Collections.emptyList();
                }
                result.add(new CustomViewLocator(scope, rootClass, viewClass, id, uniqueId, anchor, segments, alpha));
            }
            return Collections.unmodifiableList(result);
        } catch (JSONException | RuntimeException ignored) {
            return Collections.emptyList();
        }
    }

    public static String serialize(List<CustomViewLocator> locators) {
        if (locators != null && locators.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("Too many custom view locators");
        }
        try {
            JSONObject root = new JSONObject();
            JSONArray items = new JSONArray();
            if (locators != null) {
                for (CustomViewLocator locator : locators) {
                    if (locator == null) throw new IllegalArgumentException("Null custom view locator");
                    if (locator.path.isEmpty() || locator.path.size() > MAX_DEPTH
                            || locator.rootClassName.length() > MAX_NAME_LENGTH
                            || locator.viewClassName.length() > MAX_NAME_LENGTH
                            || locator.resourceName.length() > MAX_NAME_LENGTH) {
                        throw new IllegalArgumentException("Invalid custom view locator");
                    }
                    JSONObject item = new JSONObject();
                    JSONArray encodedPath = new JSONArray();
                    item.put("scope", locator.scope.name());
                    item.put("root", locator.rootClassName);
                    item.put("class", locator.viewClassName);
                    item.put("id", locator.resourceName);
                    item.put("label", locator.label);
                    item.put("mode", locator.uniqueId ? "id" : "path");
                    item.put("anchor", locator.anchorIndex);
                    item.put("alpha", locator.alpha);
                    for (Segment segment : locator.path) {
                        if (segment.className.length() > MAX_NAME_LENGTH
                                || segment.resourceName.length() > MAX_NAME_LENGTH
                                || segment.childIndex < 0 || segment.peerIndex < 0
                                || segment.peerCount <= 0 || segment.peerIndex >= segment.peerCount) {
                            throw new IllegalArgumentException("Invalid custom view path segment");
                        }
                        JSONObject node = new JSONObject();
                        node.put("index", segment.childIndex);
                        node.put("peer", segment.peerIndex);
                        node.put("count", segment.peerCount);
                        node.put("class", segment.className);
                        node.put("id", segment.resourceName);
                        encodedPath.put(node);
                    }
                    item.put("path", encodedPath);
                    items.put(item);
                }
            }
            root.put("version", FORMAT_VERSION);
            root.put("items", items);
            String encoded = root.toString();
            if (encoded.length() > MAX_ENCODED_LENGTH) {
                throw new IllegalArgumentException("Custom view settings are too large");
            }
            return encoded;
        } catch (JSONException failure) {
            throw new IllegalStateException("Cannot encode custom view locators", failure);
        }
    }

    private static int requiredInt(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new JSONException("Expected integer: " + key);
        long number = ((Number) value).longValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) throw new JSONException("Integer out of range: " + key);
        return (int) number;
    }

    private static String requiredString(JSONObject object, String key, boolean allowEmpty) throws JSONException {
        Object value = object.get(key);
        if (!(value instanceof String)) throw new JSONException("Expected string: " + key);
        String text = (String) value;
        if ((!allowEmpty && text.isEmpty()) || text.length() > MAX_NAME_LENGTH) throw new JSONException("Invalid string: " + key);
        return text;
    }

    private static View uniqueMatch(View root, String className, String id) {
        if (id.isEmpty()) return null;
        ArrayList<View> matches = new ArrayList<>(2);
        int[] visited = {0};
        boolean complete = collectMatches(root, className, id, matches, 0, visited);
        return complete && matches.size() == 1 ? matches.get(0) : null;
    }

    private static boolean collectMatches(View view, String className, String id,
                                          List<View> matches, int depth, int[] visited) {
        if (view == null || isFinderOverlay(view)) return true;
        if (depth > MAX_DEPTH || ++visited[0] > MAX_SEARCH_NODES) return false;
        if (sameSignature(view, className, id)) matches.add(view);
        if (matches.size() > 1) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                if (!collectMatches(group.getChildAt(index), className, id, matches, depth + 1, visited)) {
                    return false;
                }
                if (matches.size() > 1) return true;
            }
        }
        return true;
    }

    private static boolean isWithin(View view, View root) {
        for (View current = view; current != null; ) {
            if (current == root) return true;
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return false;
    }

    private static boolean hasFinderAncestor(View view, View root) {
        for (View current = view; current != null; ) {
            if (isFinderOverlay(current)) return true;
            if (current == root) return false;
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return true;
    }

    private static boolean isFinderOverlay(View view) {
        Object tag = view.getTag();
        return tag instanceof String && ((String) tag).startsWith("douai.view_finder");
    }

    private static boolean sameSignature(View view, String className, String id) {
        return view != null && !isFinderOverlay(view)
                && className.equals(view.getClass().getName()) && id.equals(resourceName(view));
    }

    private static String resourceName(View view) {
        if (view.getId() == View.NO_ID) return "";
        try { return view.getResources().getResourceName(view.getId()); }
        catch (RuntimeException ignored) { return ""; }
    }

    private static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    private static final class Segment {
        final int childIndex;
        final int peerIndex;
        final int peerCount;
        final String className;
        final String resourceName;

        Segment(int childIndex, int peerIndex, int peerCount, String className, String resourceName) {
            this.childIndex = childIndex;
            this.peerIndex = peerIndex;
            this.peerCount = peerCount;
            this.className = className;
            this.resourceName = resourceName;
        }
    }
}
