package com.douai.module.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;
import com.douai.module.config.AutomationHistoryStore;
import com.douai.module.config.AutomationHistoryStore.Entry;
import com.douai.module.config.AutomationHistoryStore.Location;
import java.lang.reflect.Method;

/** Viewing-only routes built from bounded IDs, never from recorded message text or arbitrary URLs. */
final class AutomationHistoryNavigation {
    private static final String HOST = "com.ss.android.ugc.aweme";
    static final class Target {
        final String label, hint;
        final Uri uri;
        Target(String label, String hint, Uri uri) { this.label = label; this.hint = hint; this.uri = uri; }
        boolean enabled() { return uri != null; }
    }

    static Target target(Entry entry, boolean inHost, String account) {
        Location where = entry.location;
        String expected = where.accountId;
        if (inHost && !expected.isEmpty() && !expected.equals(account)) {
            return unavailable(account == null || account.isEmpty() ? "无法确认当前抖音账号，请登录后重试" : "请切换到记录所属的抖音账号后查看");
        }
        if (AutomationHistoryStore.REPLY.equals(entry.kind)) {
            if (!inHost) return unavailable("私信记录请从抖音内的模块历史入口打开");
            if (expected.isEmpty()) return unavailable("旧记录未保存账号与会话类型，无法安全定位私信会话");
            if (!conversation(where.conversationId) || where.conversationType < 0 || where.conversationType > 1) {
                return unavailable("记录缺少可用会话信息，无法定位私信会话");
            }
            Uri.Builder route = new Uri.Builder().scheme("aweme").authority("chat").appendPath("message")
                    .appendQueryParameter("type", Integer.toString(where.conversationType))
                    .appendQueryParameter("version", "v2").appendQueryParameter("cid", where.conversationId);
            if (where.conversationType == 0 && numeric(where.peerUid)) route.appendQueryParameter("uid", where.peerUid);
            return new Target("打开会话", "将打开原私信会话；未定位到自动发送的新消息", route.build());
        }
        String aid = numeric(where.awemeId) ? where.awemeId : entry.targetId;
        if (!numeric(aid)) return unavailable("记录未保存可用作品标识，无法跳转");
        boolean reply = AutomationHistoryStore.COMMENT_REPLY.equals(entry.kind);
        String cid = numeric(where.commentId) ? where.commentId : numeric(where.rootCommentId) ? where.rootCommentId : "";
        Uri.Builder route = new Uri.Builder().scheme("aweme").authority("aweme").appendPath("detail").appendPath(aid);
        if (!cid.isEmpty()) {
            route.appendQueryParameter("cid", cid);
            boolean sentLocation = "sent".equals(entry.status) && numeric(where.commentId) && cid.equals(where.commentId)
                    && !(reply && cid.equals(where.messageId));
            String label = sentLocation ? reply ? "查看回复" : "查看评论" : "查看原评论";
            return new Target(label, sentLocation ? "作品或评论已删除、不可见时，抖音可能无法定位" : "尚无发送成功的定位回执，将打开原评论", route.build());
        }
        return new Target("查看作品", "此记录未保存评论定位，只能打开作品", route.build());
    }

    static Target target(Context context, Entry entry) {
        boolean inHost = HOST.equals(context.getPackageName());
        return target(entry, inHost, inHost ? account(context) : "");
    }

    /** Local SDK lookup only; call off the UI thread when enriching older private-message entries. */
    static Entry resolveLegacyConversation(Context context, Entry entry) {
        if (!HOST.equals(context.getPackageName()) || !AutomationHistoryStore.REPLY.equals(entry.kind)
                || entry.location.conversationType >= 0) return entry;
        String cid = entry.location.conversationId.isEmpty() ? entry.targetId : entry.location.conversationId;
        if (!conversation(cid)) return entry;
        String owner = entry.location.accountId;
        if (owner.isEmpty()) {
            int colon = entry.id.indexOf(':');
            if (colon < 1) return entry;
            owner = entry.id.substring(0, colon);
            String prefix = owner + ":" + cid + ":";
            if (!numeric(owner) || !entry.id.startsWith(prefix) || !numeric(entry.id.substring(prefix.length()))) return entry;
        }
        if (!owner.equals(account(context))) return entry;
        try {
            Context app = context.getApplicationContext();
            ClassLoader loader = (app == null ? context : app).getClassLoader();
            Class<?> model = Class.forName("com.bytedance.ies.im.core.api.client.ConversationListModel", false, loader);
            Object companion = model.getField("Companion").get(null);
            Method instance = companion.getClass().getMethod("inst");
            Object service = instance.invoke(companion);
            Object value = instance.getReturnType().getMethod("getConversation", String.class).invoke(service, cid);
            if (value == null || !cid.equals(value.getClass().getMethod("getConversationId").invoke(value))) return entry;
            Object single = value.getClass().getMethod("isSingleChat").invoke(value);
            if (!(single instanceof Boolean)) return entry;
            return entry.withLocation(new Location(owner, "", "", "", cid, entry.location.messageId,
                    (Boolean) single ? 0 : 1, entry.location.peerUid));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return entry; }
    }

    static boolean open(Context context, Entry entry) {
        Target destination = target(context, entry);
        if (!destination.enabled()) { toast(context, destination.hint); return false; }
        try {
            if (HOST.equals(context.getPackageName())) {
                Context app = context.getApplicationContext();
                ClassLoader loader = (app == null ? context : app).getClassLoader();
                Class<?> router = Class.forName("com.bytedance.router.SmartRouter", false, loader);
                Object route = router.getMethod("buildRoute", Context.class, String.class)
                        .invoke(null, context, destination.uri.toString());
                route.getClass().getMethod("open").invoke(route);
            } else {
                Intent intent = new Intent(Intent.ACTION_VIEW, destination.uri).setPackage(HOST);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            }
            toast(context, destination.hint);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            toast(context, "暂时无法打开，请确认抖音可用且目标内容仍可查看");
            return false;
        }
    }

    private static String account(Context context) {
        try {
            Context app = context.getApplicationContext();
            ClassLoader loader = (app == null ? context : app).getClassLoader();
            Class<?> proxy = Class.forName("com.ss.android.ugc.aweme.account.AccountProxyService", false, loader);
            Method getter = proxy.getMethod("userService");
            Object service = getter.invoke(null);
            Object value = getter.getReturnType().getMethod("getCurUserId").invoke(service);
            return value instanceof String && numeric((String) value) ? (String) value : "";
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return ""; }
    }
    private static boolean numeric(String value) { return value != null && value.matches("[1-9][0-9]{0,79}"); }
    private static boolean conversation(String value) { return value != null && !value.isEmpty() && value.length() <= 512 && value.matches("[A-Za-z0-9_:\\-]+"); }
    private static Target unavailable(String hint) { return new Target("无法跳转", hint, null); }
    private static void toast(Context context, String message) { Toast.makeText(context, message, Toast.LENGTH_LONG).show(); }
}
