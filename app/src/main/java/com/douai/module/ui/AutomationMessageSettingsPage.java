package com.douai.module.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.AutomationHistoryStore;
import com.douai.module.config.AutomationMessageSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.SettingsStore;
import com.douai.module.feature.AutomationScopePolicy;
import com.douai.module.feature.ContentScopePolicy;
import com.douai.module.feature.FollowListSnapshot;
import com.douai.module.feature.GroupReplyPolicy;
import com.douai.module.feature.NativeFollowLists;
import com.douai.module.feature.NativeMessagingHooks;
import com.douai.module.feature.ProfileListEntry;
import com.douai.module.feature.ReplyScopePolicy;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import com.douai.module.ui.miuix.MiuixExtraControls;
import com.douai.module.ui.miuix.MiuixFormControls;
import com.douai.module.ui.miuix.MiuixSettingsViews;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** Owns reply/comment forms and their editing session; the parent owns dialog navigation. */
final class AutomationMessageSettingsPage implements AutoCloseable {
    private static final int TEXT = Color.rgb(31, 39, 45);
    private static final int MUTED = Color.rgb(105, 115, 122);
    private static final int LINE = Color.rgb(226, 231, 232);
    private final Context context;
    private final AutomationMessageSettings repository;
    private final boolean useMiuix;
    private final Context rootRowContext;
    private final Context dialogRowContext;
    private final LinearLayout root;
    private final java.util.Map<LinearLayout, Dialog> panels = new java.util.IdentityHashMap<>();
    private final BiConsumer<Dialog, Runnable> presenter;
    private final Runnable showModels;
    private boolean closed;

    AutomationMessageSettingsPage(Context context, SettingsStore store, boolean useMiuix,
                                  Context rootRowContext, Context dialogRowContext, LinearLayout root,
                                  String kind, BiConsumer<Dialog, Runnable> presenter,
                                  Runnable showModels, Runnable changed) {
        this.context = ModuleTheme.themedContext(context);
        this.repository = new AutomationMessageSettings(store, changed);
        this.useMiuix = useMiuix;
        this.rootRowContext = rootRowContext;
        this.dialogRowContext = dialogRowContext;
        this.root = root;
        this.presenter = presenter;
        this.showModels = showModels;
        if ("reply".equals(kind)) buildAutoReplyControls(root);
        else if ("comment".equals(kind)) buildAutoCommentControls(root);
        else throw new IllegalArgumentException("未知自动化类型");
    }

    @Override public void close() { closed = true; repository.close(); panels.clear(); }

    private boolean isActive(LinearLayout panel) {
        if (closed) return false;
        if (panel == root) return true;
        Dialog owner = panels.get(panel);
        return owner != null && owner.isShowing();
    }

    private Context rowContext(LinearLayout panel) {
        return useMiuix ? panel == root ? rootRowContext : dialogRowContext : null;
    }

    private Context rowContext() { return useMiuix ? dialogRowContext : null; }

    private void rebuildAutomationPanel(LinearLayout panel, Runnable build) {
        if (closed) return;
        panel.removeAllViews();
        build.run();
    }

    private void showAutomationPanel(String title, LinearLayout panel, Runnable build) {
        rebuildAutomationPanel(panel, build);
        Dialog owner = new MiuixDialogBuilder(context, useMiuix).setTitle(title)
                .setView(scrolling(panel)).setNegativeButton("返回", null).create();
        panels.put(panel, owner);
        showOptionDialog(owner, () -> panels.remove(panel));
    }

    private void showOptionDialog(Dialog dialog) { showOptionDialog(dialog, null); }

    private void showOptionDialog(Dialog dialog, Runnable onDismiss) {
        if (closed) { if (onDismiss != null) onDismiss.run(); return; }
        presenter.accept(dialog, onDismiss);
    }

    private void buildAutoReplyControls(LinearLayout panel) {
        ToggleControl[] commentReplies = new ToggleControl[1];
        OptionRow[] media = new OptionRow[1];
        addSwitch(panel, "开启自动回复", "",
                repository.getBoolean("native_auto_reply", false), true, value -> {
                    repository.setEnabled("reply", value);
                    if (commentReplies[0] != null) commentReplies[0].setSummary(commentReplySummary());
                });
        addChoiceOption(panel, "回复模式", "reply_mode", new String[]{"固定内容", "AI 模型", "关键词规则", "随机内容"},
                new String[]{"fixed", "ai", "keyword", "random"}, "fixed", () -> {
                    media[0].setValue(replyMediaPreview());
                    media[0].setEnabled("ai".equals(repository.getString("reply_mode", "fixed")));
                });
        addOption(panel, "回复内容", "", null, true, this::showReplyContentSettings);
        media[0] = addOption(panel, "素材识别", "", replyMediaPreview(),
                "ai".equals(repository.getString("reply_mode", "fixed")), () -> showReplyMediaSettings(media[0]));
        addSwitch(panel, "语音回复", "", repository.getBoolean(LocalOptions.REPLY_VOICE, false), true,
                value -> repository.putBoolean(LocalOptions.REPLY_VOICE, value));
        OptionRow[] privateRange = new OptionRow[1];
        addSwitch(panel, "接收私聊", "", repository.getBoolean("reply_private_chats", true), true,
                value -> { repository.putBoolean("reply_private_chats", value); privateRange[0].setValue(privateReplyPreview()); });
        privateRange[0] = addOption(panel, "私聊范围", "", privateReplyPreview(), true, () -> showPrivateReplySettings(privateRange[0]));
        OptionRow[] groupRange = new OptionRow[1];
        addSwitch(panel, "接收群聊", "", repository.getBoolean("reply_group_chats", false), true,
                value -> { repository.putBoolean("reply_group_chats", value); groupRange[0].setValue(groupReplyPreview()); });
        groupRange[0] = addOption(panel, "群聊范围", "", groupReplyPreview(), true, () -> showGroupReplySettings(groupRange[0]));
        commentReplies[0] = addSwitch(panel, "接收评论回复", commentReplySummary(),
                repository.getBoolean(LocalOptions.COMMENT_REPLY_ENABLED, false), true, value -> {
                    repository.setEnabled("comment_reply", value);
                    commentReplies[0].setSummary(commentReplySummary());
                });
        addAutomationLimitOption(panel, "每日最大回复数", "", "reply_daily_limit");
        addAutomationLimitOption(panel, "同一用户回复间隔（分钟）", "", "reply_user_cooldown_minutes");
        addOption(panel, "发送与记录", "", null, true, this::showReplyDeliverySettings);
    }

    private String commentReplySummary() {
        return repository.getBoolean(LocalOptions.COMMENT_REPLY_ENABLED, false) && !repository.getBoolean("native_auto_reply", false)
                ? "已暂停：自动回复未开启" : "";
    }

    private String replyMediaPreview() {
        if (!"ai".equals(repository.getString("reply_mode", "fixed"))) return "未启用";
        boolean images = repository.getBoolean(LocalOptions.REPLY_RECOGNIZE_IMAGES, true);
        boolean video = repository.getBoolean(LocalOptions.REPLY_RECOGNIZE_VIDEO, false);
        return images && video ? "图片、视频" : images ? "图片" : video ? "视频" : "不识别";
    }

    private void showReplyMediaSettings(OptionRow preview) {
        LinearLayout panel = form();
        showAutomationPanel("素材识别", panel, () -> {
            addSwitch(panel, "识别图片", "",
                    repository.getBoolean(LocalOptions.REPLY_RECOGNIZE_IMAGES, true), true,
                    value -> { repository.putBoolean(LocalOptions.REPLY_RECOGNIZE_IMAGES, value); preview.setValue(replyMediaPreview()); });
            addSwitch(panel, "识别视频", "",
                    repository.getBoolean(LocalOptions.REPLY_RECOGNIZE_VIDEO, false), true,
                    value -> { repository.putBoolean(LocalOptions.REPLY_RECOGNIZE_VIDEO, value); preview.setValue(replyMediaPreview()); });
        });
    }

    private void addAutomationLimitOption(LinearLayout panel, String title, String summary, String key) {
        addEditableOption(panel, title, summary, repository.getString(key, "0"), true, true, value -> {
            repository.putString(key, value);
        });
    }

    private static String scopeLabel(String mode) {
        return "whitelist".equals(mode) ? "白名单" : "blacklist".equals(mode) ? "黑名单" : "全部";
    }

    private String privateReplyPreview() {
        return repository.getBoolean("reply_private_chats", true)
                ? scopeLabel(ReplyScopePolicy.scopeMode(repository.scope("reply"))) : "未启用";
    }

    private String groupReplyPreview() {
        return repository.getBoolean("reply_group_chats", false)
                ? scopeLabel(repository.getString("reply_group_scope_mode", "all")) : "未启用";
    }

    private void showReplyContentSettings() {
        String mode = repository.getString("reply_mode", "fixed");
        if ("keyword".equals(mode) || "random".equals(mode)) { showReplyPool("keyword".equals(mode)); return; }
        LinearLayout panel = form();
        showAutomationPanel("回复内容", panel, () -> {
            if ("ai".equals(mode)) {
                addAutomationModelOption(panel, "reply");
                addPromptOption(panel, "二级提示词", repository.getString("reply_prompt", "自然简洁地回复对方，只输出回复正文。"),
                        value -> repository.putString("reply_prompt", value));
            } else {
                addEditableOption(panel, "固定回复内容", "", repository.getString("auto_message_text", ""), false, true,
                        value -> repository.putString("auto_message_text", value));
            }
        });
    }

    private void showPrivateReplySettings(OptionRow preview) {
        repository.prepareContentFilter("reply");
        LinearLayout panel = form();
        Runnable[] build = new Runnable[1];
        build[0] = () -> {
            LocalOptions scopeOptions = repository.scope("reply");
            String scope = ReplyScopePolicy.scopeMode(scopeOptions);
            addChoiceOption(panel, "用户范围", "reply_scope_mode", new String[]{"全部", "白名单", "黑名单"},
                    new String[]{"all", "whitelist", "blacklist"}, scope, () -> {
                        preview.setValue(privateReplyPreview());
                        rebuildAutomationPanel(panel, build[0]);
                    });
            if (!"all".equals(scope)) {
                addMultiChoiceCsvOption(panel, "关注关系", "reply_scope_relations", new String[]{"我关注的", "关注我的", "陌生人"},
                        new String[]{AutomationScopePolicy.FOLLOWING, AutomationScopePolicy.FOLLOWERS, AutomationScopePolicy.STRANGERS});
                addOption(panel, "指定用户", "", repository.getString("reply_targets", ""), true,
                        () -> showTargetSelection("指定用户", "reply_targets"));
            }
            addFollowListsOption(panel);
            String filter = ReplyScopePolicy.filterMode(scopeOptions);
            addChoiceOption(panel, "消息筛选", "reply_filter_mode", new String[]{"不限", "包含关键词", "排除关键词"},
                    new String[]{"off", "include", "exclude"}, filter, () -> rebuildAutomationPanel(panel, build[0]));
            if (!ReplyScopePolicy.OFF.equals(filter)) {
                addEditableOption(panel, "筛选关键词", "", ReplyScopePolicy.keywords(scopeOptions), false, true,
                        value -> repository.putString("reply_filter_keywords", value));
                addMultiChoiceCsvOption(panel, "匹配对象", "reply_filter_fields", new String[]{"发送者", "消息内容"},
                        new String[]{AutomationScopePolicy.AUTHOR, AutomationScopePolicy.CAPTION});
            }
        };
        showAutomationPanel("私聊范围", panel, build[0]);
    }

    private void showGroupReplySettings(OptionRow preview) {
        LinearLayout panel = form();
        Runnable[] build = new Runnable[1];
        build[0] = () -> {
            String scope = repository.getString("reply_group_scope_mode", "all");
            addChoiceOption(panel, "群聊作用域", "reply_group_scope_mode",
                    new String[]{"全部", "白名单", "黑名单"}, new String[]{"all", "whitelist", "blacklist"}, "all", () -> {
                        preview.setValue(groupReplyPreview());
                        rebuildAutomationPanel(panel, build[0]);
                    });
            if (!"all".equals(scope)) {
                OptionRow[] groups = new OptionRow[1];
                groups[0] = addOption(panel, "选择群聊", "", selectedGroupPreview(), true, () -> showGroupSelection(groups[0]));
            }
            addChoiceOption(panel, "触发条件", "reply_group_mode", new String[]{"全员", "指定成员", "仅 @我"},
                    new String[]{"all", "selected", "mentions"}, "all", () -> rebuildAutomationPanel(panel, build[0]));
            if ("selected".equals(repository.getString("reply_group_mode", "all"))) {
                addOption(panel, "指定成员", "", repository.getString("reply_group_members", ""), true, this::showGroupMemberSelection);
            }
        };
        showAutomationPanel("群聊范围", panel, build[0]);
    }

    private void showReplyDeliverySettings() {
        showDeliverySettings(AutomationHistoryStore.REPLY);
    }

    private void showDeliverySettings(String kind) {
        boolean reply = AutomationHistoryStore.REPLY.equals(kind);
        LinearLayout panel = form();
        Runnable[] build = new Runnable[1];
        build[0] = () -> {
            addEditableOption(panel, reply ? "延迟回复" : "延迟评论", "", repository.getString(kind + "_delay_seconds", reply ? "2" : "3"), true, true, value -> {
                repository.putString(kind + "_delay_seconds", value);
            });
            addSwitch(panel, "悬浮提示", "", repository.getBoolean(kind + "_tip", false), true, value -> {
                repository.putBoolean(kind + "_tip", value);
                rebuildAutomationPanel(panel, build[0]);
            });
            if (repository.getBoolean(kind + "_tip", false)) {
                addEditableOption(panel, "提示超时（秒）", "", repository.getString(kind + "_notice_seconds",
                        Integer.toString(AutomationMessageSettings.DEFAULT_NOTICE_SECONDS)), true, true, value -> {
                    repository.putString(kind + "_notice_seconds", value);
                });
            }
            addLocalSwitch(panel, "保存历史记录", "", kind + "_save_history");
            addOption(panel, reply ? "回复历史记录" : "评论历史记录", "", null, true, () -> {
                AutomationHistoryDialog history = new AutomationHistoryDialog(context, kind, useMiuix);
                showOptionDialog(history.dialog(), history::closeDetails);
            });
        };
        showAutomationPanel("发送与记录", panel, build[0]);
    }

    private String selectedGroupPreview() {
        int selected = GroupReplyPolicy.entries(repository.getString("reply_group_targets", "")).size();
        return selected == 0 ? "未选择" : "已选择 " + selected + " 个群聊";
    }

    private void showGroupSelection(OptionRow preview) {
        withNativeContacts(false, () -> showGroupSelectionLoaded(preview));
    }

    private void showGroupSelectionLoaded(OptionRow preview) {
        java.util.Map<String, String> groups = NativeMessagingHooks.availableGroups();
        java.util.Set<String> selected = GroupReplyPolicy.entries(repository.getString("reply_group_targets", ""));
        if (groups.isEmpty() && selected.isEmpty()) {
            showOptionDialog(new MiuixDialogBuilder(context, useMiuix).setTitle("选择群聊")
                    .setMessage("暂无可用群会话。")
                    .setNeutralButton("填写会话 ID", (dialog, which) ->
                            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> showGroupConversationIds(preview)))
                    .setNegativeButton("返回", null).create());
            return;
        }
        showProfileSelection("选择群聊", NativeMessagingHooks.availableGroupProfiles(), selected, next -> {
                    repository.putString("reply_group_targets", String.join(",", next));
                    preview.setValue(selectedGroupPreview());
                }, () -> showGroupConversationIds(preview));
    }

    private void showGroupConversationIds(OptionRow preview) {
        LinearLayout form = form();
        EditText input = formInput(form, "群会话 ID，以逗号分隔", repository.getString("reply_group_targets", ""), false);
        showOptionDialog(new MiuixDialogBuilder(context, useMiuix).setTitle("填写会话 ID").setView(form)
                .setNegativeButton("取消", null).setPositiveButton("保存", (dialog, which) -> {
                    if (closed) return;
                    repository.putString("reply_group_targets", String.join(",", GroupReplyPolicy.entries(input.getText().toString())));
                    preview.setValue(selectedGroupPreview());
                }).create());
    }

    private void showGroupMemberSelection() {
        withNativeContacts(true, () -> showGroupMemberSelectionLoaded());
    }

    private void showGroupMemberSelectionLoaded() {
        java.util.Map<String, ProfileListEntry> members = NativeMessagingHooks.availableGroupMembers();
        java.util.Set<String> selected = new java.util.LinkedHashSet<>(GroupReplyPolicy.entries(
                repository.getString("reply_group_members", "")));
        java.util.Set<String> configured = new java.util.LinkedHashSet<>(selected);
        for (ProfileListEntry entry : members.values()) {
            if (entry.name.isEmpty()) continue;
            for (String name : configured) if (name.equalsIgnoreCase(entry.name)) {
                selected.remove(name);
                selected.add(entry.uid);
            }
        }
        showProfileSelection("选择群成员", members, selected, next ->
                repository.putString("reply_group_members", String.join(",", next)), this::showGroupMemberIds);
    }

    private void showGroupMemberIds() {
        LinearLayout form = form();
        EditText input = formInput(form, "成员 UID 或昵称", repository.getString("reply_group_members", ""), false);
        showOptionDialog(new MiuixDialogBuilder(context, useMiuix).setTitle("指定成员").setView(form)
                .setNegativeButton("取消", null).setPositiveButton("保存", (dialog, which) ->
                        repository.putString("reply_group_members", String.join(",", GroupReplyPolicy.entries(input.getText().toString())))).create());
    }

    private void showProfileSelection(String title, java.util.Map<String, ProfileListEntry> available,
                                      java.util.Set<String> selected, java.util.function.Consumer<java.util.Set<String>> saved,
                                      Runnable manual) {
        java.util.Map<String, ProfileListEntry> entries = new java.util.LinkedHashMap<>(available);
        for (String uid : selected) if (!entries.containsKey(uid)) {
            entries.put(uid, "选择群成员".equals(title) && !uid.matches("[1-9][0-9]{0,19}")
                    ? ProfileListEntry.named(uid) : new ProfileListEntry(uid, "", ""));
        }
        MemberListView list = new MemberListView(useMiuix ? rowContext() : context, useMiuix,
                new ArrayList<>(entries.values()), selected, true);
        MiuixDialogBuilder builder = new MiuixDialogBuilder(context, useMiuix);
        builder.setTitle(title).setView(list).setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> saved.accept(list.selected()));
        if (manual != null) builder.setNeutralButton("选择群聊".equals(title) ? "填写会话 ID" : "填写 UID", (dialog, which) ->
                new android.os.Handler(android.os.Looper.getMainLooper()).post(manual));
        showOptionDialog(builder.create(), list::close);
    }

    private void buildAutoCommentControls(LinearLayout panel) {
        addSwitch(panel, "开启自动评论", "", repository.getBoolean("native_auto_comment", false), true,
                value -> repository.setEnabled("comment", value));
        OptionRow[] media = new OptionRow[1];
        addChoiceOption(panel, "评论模式", "comment_mode", new String[]{"AI 模型", "固定内容"}, new String[]{"ai", "fixed"}, "ai", () -> {
            boolean ai = "ai".equals(repository.getString("comment_mode", "ai"));
            media[0].setValue(commentMediaPreview());
            media[0].setEnabled(ai);
        });
        addOption(panel, "评论内容", "", null, true, this::showCommentContentSettings);
        addSwitch(panel, "语音评论", "AI 语音评论尽量简洁，最终使用完整正文，不截断音频；实际上传失败仍遵循原回退与防重。",
                repository.getBoolean(LocalOptions.COMMENT_VOICE, false), true,
                value -> repository.putBoolean(LocalOptions.COMMENT_VOICE, value));
        OptionRow[] scope = new OptionRow[1];
        scope[0] = addOption(panel, "作品范围", "", scopeLabel(ContentScopePolicy.scopeMode("comment", repository.scope("comment"))),
                true, () -> showCommentScopeSettings(scope[0]));
        boolean ai = "ai".equals(repository.getString("comment_mode", "ai"));
        media[0] = addOption(panel, "素材识别", "", commentMediaPreview(), ai, () -> showCommentMediaSettings(media[0]));
        addAutomationLimitOption(panel, "每日最大评论数", "", "comment_daily_limit");
        addAutomationLimitOption(panel, "同一作者评论间隔（分钟）", "", "comment_user_cooldown_minutes");
        addOption(panel, "发送与记录", "", null, true, () -> showDeliverySettings(AutomationHistoryStore.COMMENT));
    }

    private void showCommentContentSettings() {
        boolean ai = "ai".equals(repository.getString("comment_mode", "ai"));
        LinearLayout panel = form();
        showAutomationPanel("评论内容", panel, () -> {
            if (ai) {
                addAutomationModelOption(panel, "comment");
                addPromptOption(panel, "二级提示词", repository.getString("comment_prompt", "根据当前作品自然地写一条简短评论，只输出评论正文。"),
                        value -> repository.putString("comment_prompt", value));
            } else {
                addEditableOption(panel, "固定评论内容", "", repository.getString("auto_comment_text", ""), false, true,
                        value -> repository.putString("auto_comment_text", value));
            }
        });
    }

    private void showCommentScopeSettings(OptionRow preview) {
        repository.prepareContentFilter("comment");
        LinearLayout panel = form();
        Runnable[] build = new Runnable[1];
        build[0] = () -> {
            LocalOptions scopeOptions = repository.scope("comment");
            String mode = ContentScopePolicy.scopeMode("comment", scopeOptions);
            addChoiceOption(panel, "作者范围", "comment_scope_mode", new String[]{"全部", "白名单", "黑名单"},
                    new String[]{"all", "whitelist", "blacklist"}, mode, () -> {
                        preview.setValue(scopeLabel(repository.getString("comment_scope_mode", "all")));
                        rebuildAutomationPanel(panel, build[0]);
                    });
            if (!"all".equals(mode)) {
                addMultiChoiceCsvOption(panel, "关注关系", "comment_scope_relations", new String[]{"我关注的", "关注我的", "陌生人"},
                        new String[]{AutomationScopePolicy.FOLLOWING, AutomationScopePolicy.FOLLOWERS, AutomationScopePolicy.STRANGERS});
                addOption(panel, "指定作者", "", repository.getString("comment_scope_users", ""), true, this::showCommentAuthorSelection);
            }
            addFollowListsOption(panel);
            String filter = ContentScopePolicy.filterMode("comment", scopeOptions);
            addChoiceOption(panel, "作品筛选", "comment_filter_mode", new String[]{"不限", "包含关键词", "排除关键词"},
                    new String[]{"off", "include", "exclude"}, filter, () -> rebuildAutomationPanel(panel, build[0]));
            if (!ContentScopePolicy.OFF.equals(filter)) {
                addEditableOption(panel, "筛选关键词", "", ContentScopePolicy.keywords("comment", scopeOptions),
                        false, true, value -> repository.putString("comment_filter_keywords", value));
                addMultiChoiceCsvOption(panel, "匹配对象", "comment_filter_fields", new String[]{"作者", "文案", "标题"},
                        new String[]{AutomationScopePolicy.AUTHOR, AutomationScopePolicy.CAPTION, AutomationScopePolicy.TITLE});
            }
        };
        showAutomationPanel("作品范围", panel, build[0]);
    }

    private void showCommentAuthorSelection() {
        withNativeContacts(false, () -> showCommentAuthorSelectionLoaded());
    }

    private void showCommentAuthorSelectionLoaded() {
        java.util.Map<String, ProfileListEntry> authors = new java.util.LinkedHashMap<>(NativeMessagingHooks.availableContactProfiles());
        authors.putAll(NativeFollowLists.memberDetails(FollowListSnapshot.Kind.FOLLOWING));
        authors.putAll(NativeFollowLists.memberDetails(FollowListSnapshot.Kind.FOLLOWERS));
        showProfileSelection("指定作者", authors, AutomationScopePolicy.targets(repository.getString("comment_scope_users", "")),
                next -> repository.putString("comment_scope_users", String.join(",", next)),
                () -> showTargetIds("指定作者 UID", "comment_scope_users"));
    }

    private String commentMediaPreview() {
        if (!"ai".equals(repository.getString("comment_mode", "ai"))) return "未启用";
        boolean images = repository.getBoolean("comment_recognize_images", true);
        boolean video = repository.getBoolean("comment_recognize_video", false);
        return images && video ? "图片、视频" : images ? "图片" : video ? "视频" : "不识别";
    }

    private void showCommentMediaSettings(OptionRow preview) {
        LinearLayout panel = form();
        showAutomationPanel("素材识别", panel, () -> {
            addSwitch(panel, "识别图片", "", repository.getBoolean("comment_recognize_images", true), true,
                    value -> { repository.putBoolean("comment_recognize_images", value); preview.setValue(commentMediaPreview()); });
            addSwitch(panel, "识别视频", "", repository.getBoolean("comment_recognize_video", false), true,
                    value -> { repository.putBoolean("comment_recognize_video", value); preview.setValue(commentMediaPreview()); });
        });
    }

    private void addFollowListsOption(LinearLayout panel) {
        OptionRow[] preview = new OptionRow[1];
        preview[0] = addOption(panel, "关注与粉丝名单", "", NativeFollowLists.statusText(), true,
                () -> showFollowLists(preview[0]));
    }

    private void showFollowLists(OptionRow preview) {
        LinearLayout form = form();
        Button following = formButton();
        following.setText("我关注的");
        following.setTag("settings.followLists.following");
        following.setOnClickListener(ignored -> showFollowListMembers(FollowListSnapshot.Kind.FOLLOWING));
        form.addView(MiuixFormControls.viewFor(following), matchWrap());
        Button followers = formButton();
        followers.setText("关注我的");
        followers.setTag("settings.followLists.followers");
        followers.setOnClickListener(ignored -> showFollowListMembers(FollowListSnapshot.Kind.FOLLOWERS));
        LinearLayout.LayoutParams followersParams = matchWrap();
        followersParams.topMargin = dp(8);
        form.addView(MiuixFormControls.viewFor(followers), followersParams);
        AlertDialog dialog = new MiuixDialogBuilder(context, useMiuix)
                .setTitle("关注与粉丝名单").setMessage(NativeFollowLists.statusText()).setView(form)
                .setNegativeButton("返回", null).setPositiveButton("刷新名单", null).create();
        showOptionDialog(dialog);
        Button refresh = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        refresh.setOnClickListener(ignored -> {
            if (closed || !dialog.isShowing()) return;
            refresh.setEnabled(false);
            boolean started = NativeFollowLists.refresh(() -> {
                if (!dialog.isShowing()) return;
                String status = NativeFollowLists.statusText();
                dialog.setMessage(status);
                preview.setValue(status);
                refresh.setEnabled(true);
            });
            if (started) {
                dialog.setMessage(NativeFollowLists.statusText());
            } else {
                repository.putString(NativeFollowLists.REFRESH_KEY, Long.toString(System.currentTimeMillis()));
                String status = "已请求刷新。请打开抖音内的模块设置查看实际名单。";
                dialog.setMessage(status);
                preview.setValue(status);
                refresh.setEnabled(true);
            }
        });
    }

    private void showFollowListMembers(FollowListSnapshot.Kind kind) {
        java.util.Map<String, String> members = NativeFollowLists.members(kind);
        FollowListSnapshot.State snapshot = FollowListSnapshot.current();
        String title = kind == FollowListSnapshot.Kind.FOLLOWING ? "我关注的" : "关注我的";
        boolean known = kind == FollowListSnapshot.Kind.FOLLOWING
                ? snapshot.followingKnown : snapshot.followersKnown;
        java.util.Set<String> ids = kind == FollowListSnapshot.Kind.FOLLOWING
                ? snapshot.following : snapshot.followers;
        MiuixDialogBuilder builder = new MiuixDialogBuilder(context, useMiuix);
        builder.setTitle(known ? title + "（" + ids.size() + "人）" : title);
        if (!snapshot.active) {
            builder.setMessage(repository.isInHost() ? "尚未读取登录账号。" : "请在抖音内查看实际名单。");
        } else if (!known) {
            builder.setMessage("名单尚未加载。\n" + NativeFollowLists.statusText());
        } else if (ids.isEmpty()) {
            builder.setMessage("列表为空。");
        } else {
            MemberListView list = new MemberListView(useMiuix ? rowContext() : context, useMiuix,
                    new ArrayList<>(NativeFollowLists.memberDetails(kind).values()), java.util.Collections.emptySet(), false);
            builder.setView(list).setNegativeButton("返回", null);
            showOptionDialog(builder.create(), list::close);
            return;
        }
        builder.setNegativeButton("返回", null);
        showOptionDialog(builder.create());
    }

    private void addPromptOption(LinearLayout panel, String title, String value, ValueTextListener listener) {
        String[] current = {value};
        OptionRow[] preview = new OptionRow[1];
        OptionRow row = addOption(panel, title, "", promptPreview(value), true, () -> showOptionDialog(
                new PromptEditorDialog(context, title, current[0], candidate -> {
                    listener.onValue(candidate);
                    current[0] = candidate;
                    preview[0].setValue(promptPreview(candidate));
                }, useMiuix)));
        preview[0] = row;
    }

    private static String promptPreview(String value) {
        if (value.isEmpty()) return "未设置";
        int points = value.codePointCount(0, value.length());
        return value.substring(0, value.offsetByCodePoints(0, Math.min(240, points)));
    }

    private void showReplyPool(boolean keyword) {
        String key = keyword ? "reply_keywords" : "reply_random";
        JSONArray saved;
        try { saved = new JSONArray(repository.getString(key, "[]")); }
        catch (org.json.JSONException ignored) { saved = new JSONArray(); }
        LinearLayout form = form();
        List<EditText[]> editors = new ArrayList<>();
        Runnable add = () -> {
            if (editors.size() >= 50) return;
            LinearLayout entry = form();
            EditText match = keyword ? formInput(entry, "匹配文字", "", false) : null;
            EditText response = formInput(entry, "回复内容", "", false);
            ImageButton remove = new ImageButton(context);
            remove.setImageResource(android.R.drawable.ic_menu_delete);
            remove.setContentDescription("删除回复");
            remove.setTooltipText("删除回复");
            EditText[] values = {match, response};
            remove.setOnClickListener(ignored -> { editors.remove(values); form.removeView(entry); });
            entry.addView(!useMiuix ? remove : MiuixExtraControls.iconAction(rowContext(),
                    remove, android.R.drawable.ic_menu_delete), new LinearLayout.LayoutParams(dp(48), dp(48)));
            editors.add(values);
            form.addView(entry, matchWrap());
        };
        for (int index = 0; index < saved.length(); index++) {
            add.run();
            EditText[] entry = editors.get(editors.size() - 1);
            if (keyword) {
                JSONObject pair = saved.optJSONObject(index);
                if (pair != null) { entry[0].setText(pair.optString("keyword")); entry[1].setText(pair.optString("reply")); }
            } else entry[1].setText(saved.optString(index));
        }
        ImageButton addButton = new ImageButton(context);
        addButton.setImageResource(android.R.drawable.ic_input_add);
        addButton.setContentDescription("添加回复");
        addButton.setTooltipText("添加回复");
        addButton.setOnClickListener(ignored -> add.run());
        form.addView(!useMiuix ? addButton : MiuixExtraControls.iconAction(rowContext(),
                addButton, android.R.drawable.ic_input_add), new LinearLayout.LayoutParams(dp(48), dp(48)));
        showOptionDialog(new MiuixDialogBuilder(context, useMiuix).setTitle(keyword ? "关键词规则" : "随机内容")
                .setView(scrolling(form)).setNegativeButton("取消", null).setPositiveButton("保存", (dialog, which) -> {
                    JSONArray entries = new JSONArray();
                    for (EditText[] item : editors) {
                        String reply = item[1].getText().toString().trim();
                        if (reply.isEmpty()) continue;
                        try {
                            if (keyword) {
                                String match = item[0].getText().toString().trim();
                                if (!match.isEmpty()) entries.put(new JSONObject().put("keyword", match).put("reply", reply));
                            } else entries.put(reply);
                        } catch (org.json.JSONException ignored) { }
                    }
                    repository.putString(key, entries.toString());
                }).create());
    }

    private void addChoiceOption(LinearLayout panel, String title, String key, String[] labels, String[] values, String fallback) {
        addChoiceOption(panel, title, key, labels, values, fallback, null);
    }

    private void addAutomationModelOption(LinearLayout panel, String kind) {
        OptionRow[] preview = new OptionRow[1];
        preview[0] = addOption(panel, "模型配置", "", automationModelPreview(kind), true, () -> {
            AiModelSettings settings;
            try { settings = AiModelSettings.read(repository); }
            catch (RuntimeException failure) { toast(safeError(failure)); return; }
            String key = AiModelSettings.automationModelKey(kind);
            List<String> ids = new ArrayList<>(), labels = new ArrayList<>();
            ids.add("");
            labels.add("跟随全局模型");
            for (AiModelConfig config : settings.configs) {
                ids.add(config.id);
                labels.add(automationModelLabel(config));
            }
            int selected = ids.indexOf(repository.getString(key, ""));
            showOptionDialog(new MiuixDialogBuilder(context, useMiuix)
                    .setTitle("reply".equals(kind) ? "自动回复使用的模型" : "自动评论使用的模型")
                    .setSingleChoiceItems(labels.toArray(new String[0]), selected, (dialog, which) -> {
                        if (isActive(panel)) {
                            repository.putString(key, ids.get(which));
                            preview[0].setValue(automationModelPreview(kind));
                        }
                        dialog.dismiss();
                    }).setNeutralButton("管理模型", (dialog, which) ->
                            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                                if (isActive(panel)) showModels.run();
                            }))
                    .setNegativeButton("取消", null).create());
        });
    }

    private String automationModelPreview(String kind) {
        String id = repository.getString(AiModelSettings.automationModelKey(kind), "");
        try {
            AiModelConfig selected = AiModelSettings.readForAutomation(repository, kind).selected();
            return (id.isEmpty() ? "跟随全局 · " : "") + automationModelLabel(selected);
        } catch (RuntimeException ignored) {
            return id.isEmpty() ? "跟随全局（未选择模型）" : "所选配置不可用，请重新选择";
        }
    }

    private static String automationModelLabel(AiModelConfig config) {
        return config.name + " · " + config.modelName + " · " + (config.streaming() ? "流式" : "非流式");
    }

    private void addChoiceOption(LinearLayout panel, String title, String key, String[] labels, String[] values, String fallback, Runnable changed) {
        String selected = repository.getString(key, fallback);
        int initial = java.util.Arrays.asList(values).indexOf(selected);
        int selectedIndex = Math.max(0, initial);
        OptionRow[] preview = new OptionRow[1];
        OptionRow row = addOption(panel, title, "", labels[selectedIndex], true, () -> {
            int current = Math.max(0, java.util.Arrays.asList(values).indexOf(repository.getString(key, fallback)));
            showOptionDialog(new MiuixDialogBuilder(context, useMiuix).setTitle(title)
                    .setSingleChoiceItems(labels, current, (dialog, which) -> {
                        repository.putString(key, values[which]);
                        preview[0].setValue(labels[which]);
                        dialog.dismiss();
                        if (changed != null) changed.run();
                    }).setNegativeButton("取消", null).create());
        });
        preview[0] = row;
    }

    private void addMultiChoiceCsvOption(LinearLayout panel, String title, String key, String[] labels, String[] values) {
        OptionRow[] preview = new OptionRow[1];
        preview[0] = addOption(panel, title, "", scopeLabels(labels, values, repository.getString(key, "")), true, () -> {
            java.util.Set<String> selected = AutomationScopePolicy.targets(repository.getString(key, ""));
            boolean[] checked = new boolean[values.length];
            for (int index = 0; index < values.length; index++) checked[index] = selected.contains(values[index]);
            showOptionDialog(new MiuixDialogBuilder(context, useMiuix).setTitle(title)
                    .setMultiChoiceItems(labels, checked, (dialog, which, value) -> checked[which] = value)
                    .setNegativeButton("取消", null).setPositiveButton("保存", (dialog, which) -> {
                        List<String> next = new ArrayList<>();
                        for (int index = 0; index < values.length; index++) if (checked[index]) next.add(values[index]);
                        String saved = String.join(",", next);
                        repository.putString(key, saved);
                        preview[0].setValue(scopeLabels(labels, values, saved));
                    }).create());
        });
    }

    private static String scopeLabels(String[] labels, String[] values, String configured) {
        java.util.Set<String> selected = AutomationScopePolicy.targets(configured);
        List<String> result = new ArrayList<>();
        for (int index = 0; index < values.length; index++) if (selected.contains(values[index])) result.add(labels[index]);
        return result.isEmpty() ? "未选择" : String.join("、", result);
    }

    private void withNativeContacts(boolean includeMembers, Runnable showSelection) {
        if (closed) return;
        java.util.concurrent.atomic.AtomicBoolean waiting = new java.util.concurrent.atomic.AtomicBoolean(true);
        AutoCloseable[] request = new AutoCloseable[1];
        AlertDialog progress = new MiuixDialogBuilder(context, useMiuix).setTitle("读取会话名单")
                .setMessage(includeMembers ? "正在读取群成员，请稍候…" : "正在读取会话，请稍候…")
                .setNegativeButton("取消", null).create();
        showOptionDialog(progress, () -> {
            if (waiting.getAndSet(false) && request[0] != null) {
                try { request[0].close(); } catch (Exception ignored) { }
            }
        });
        request[0] = NativeMessagingHooks.loadContacts(includeMembers, success -> {
            if (!waiting.getAndSet(false) || closed) return;
            progress.dismiss();
            if (success) showSelection.run();
            else showOptionDialog(new MiuixDialogBuilder(context, useMiuix).setTitle("会话名单暂不可用")
                    .setMessage("读取失败或登录账号已变化，请稍后重新打开。也可以继续填写目标 ID。")
                    .setPositiveButton("继续选择", (dialog, which) -> { if (!closed) showSelection.run(); })
                    .setNegativeButton("返回", null).create());
        });
    }

    private void showTargetSelection(String title, String key) {
        withNativeContacts(false, () -> showTargetSelectionLoaded(title, key));
    }

    private void showTargetSelectionLoaded(String title, String key) {
        java.util.Map<String, String> contacts = NativeMessagingHooks.availableContacts();
        if (contacts.isEmpty()) {
            showTargetIds(title, key);
            return;
        }
        java.util.Set<String> selected = com.douai.module.feature.ReplyPolicy.targets(repository.getString(key, ""));
        showProfileSelection(title, NativeMessagingHooks.availableContactProfiles(), selected,
                next -> repository.putString(key, String.join(",", next)), () -> showTargetIds(title, key));
    }

    private void showTargetIds(String title, String key) {
        LinearLayout form = form();
        EditText input = formInput(form, "用户 UID，以逗号分隔", repository.getString(key, ""), false);
        showOptionDialog(new MiuixDialogBuilder(context, useMiuix).setTitle(title).setView(form)
                .setNegativeButton("取消", null).setPositiveButton("保存", (dialog, which) ->
                        repository.putString(key, String.join(",", com.douai.module.feature.ReplyPolicy.targets(input.getText().toString())))).create());
    }

    private EditText formInput(LinearLayout parent, String label, String value, boolean secret) {
        TextView heading = text(label, 14, TEXT, false);
        LinearLayout.LayoutParams headingParams = matchWrap();
        headingParams.topMargin = dp(10);
        parent.addView(heading, headingParams);
        EditText input = !useMiuix ? new EditText(context)
                : MiuixFormControls.input(context, label, value, secret);
        input.setContentDescription(label);
        input.setText(value);
        input.setTextSize(14);
        input.setMaxLines(secret ? 1 : 4);
        input.setInputType(InputType.TYPE_CLASS_TEXT | (secret
                ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_MULTI_LINE));
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4000)});
        parent.addView(MiuixFormControls.viewFor(input), matchWrap());
        return input;
    }

    private LinearLayout form() {
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(4), dp(20), dp(8));
        return form;
    }

    private ScrollView scrolling(View child) {
        ScrollView scroll = new ScrollView(context);
        scroll.addView(child);
        return scroll;
    }

    private Button formButton() {
        return !useMiuix ? new Button(context) : MiuixFormControls.button(context);
    }

    private View addEditableOption(LinearLayout panel, String title, String summary, String value, boolean numeric,
                                   boolean enabled, ValueTextListener listener) {
        String[] current = {value};
        OptionRow[] preview = new OptionRow[1];
        OptionRow row = addOption(panel, title, summary, value, enabled, () -> {
            EditText input = !useMiuix ? new EditText(context)
                    : MiuixFormControls.input(context, title, current[0], false);
            input.setContentDescription(title);
            input.setTag("settings.input." + title);
            input.setText(current[0]);
            input.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                    : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            input.setMaxLines(numeric ? 1 : 6);
            input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(400)});
            LinearLayout form = new LinearLayout(context);
            form.setPadding(dp(20), dp(8), dp(20), 0);
            form.addView(MiuixFormControls.viewFor(input), matchWrap());
            AlertDialog editor = new MiuixDialogBuilder(context, useMiuix).setTitle(title).setView(form)
                    .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
            showOptionDialog(editor);
            editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
                if (closed || !editor.isShowing()) return;
                String candidate = input.getText().toString().trim();
                try {
                    listener.onValue(candidate);
                    current[0] = candidate;
                    preview[0].setValue(candidate.isEmpty() ? "未设置" : candidate);
                    editor.dismiss();
                } catch (RuntimeException failure) { toast(safeError(failure)); }
            });
        });
        preview[0] = row;

        return row.view;
    }

    private OptionRow addOption(LinearLayout panel, String title, String summary, String value, boolean enabled, Runnable action) {
        Runnable guarded = action == null ? null : () -> { if (isActive(panel)) action.run(); };
        Context rowContext = rowContext(panel);
        if (rowContext != null) {
            MiuixSettingsViews.Option option = new MiuixSettingsViews.Option(
                    rowContext, title, summary, value, enabled, guarded);
            panel.addView(option.getView(), matchWrap());
            return new OptionRow(option.getView(), option::setValue, option::setEnabled, option::setColor);
        }
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        row.setMinimumHeight(dp(58));
        row.setTag("settings.option." + title);
        row.setContentDescription(title);
        row.setEnabled(enabled);
        row.setFocusable(action != null);
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 15, TEXT, false), matchWrap());
        if (summary != null && !summary.isEmpty()) {
            TextView detail = text(summary, 12, MUTED, false);
            LinearLayout.LayoutParams params = matchWrap();
            params.topMargin = dp(3);
            labels.addView(detail, params);
        }
        TextView preview = null;
        if (value != null) {
            preview = text(value.isEmpty() ? "未设置" : value, 13, MUTED, false);
            preview.setTag("settings.value." + title);
            preview.setMaxLines(3);
            preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams params = matchWrap();
            params.topMargin = dp(5);
            labels.addView(preview, params);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (guarded != null) {
            TextView arrow = text("›", 24, MUTED, false);
            arrow.setGravity(Gravity.CENTER);
            row.addView(arrow, new LinearLayout.LayoutParams(dp(32), dp(32)));
            row.setOnClickListener(view -> { if (view.isEnabled()) guarded.run(); });
        }
        panel.addView(row, matchWrap());
        addDividerTo(panel);
        TextView valuePreview = preview;
        View[] colorSwatch = new View[1];
        return new OptionRow(row, newValue -> {
            if (valuePreview != null) valuePreview.setText(newValue);
        }, row::setEnabled, color -> {
            if (colorSwatch[0] == null) {
                colorSwatch[0] = new View(context);
                labels.addView(colorSwatch[0], new LinearLayout.LayoutParams(dp(24), dp(24)));
            }
            colorSwatch[0].setBackground(makeCircle(color));
        });
    }

    private ToggleControl addSwitch(LinearLayout panel, String title, String summary, boolean checked, boolean enabled, ValueListener listener) {
        ValueListener guarded = value -> { if (isActive(panel)) listener.onValue(value); };
        Context rowContext = rowContext(panel);
        if (rowContext != null) {
            MiuixSettingsViews.Toggle toggle = new MiuixSettingsViews.Toggle(
                    rowContext, title, summary, checked, enabled, guarded::onValue);
            ToggleControl control = new ToggleControl(toggle::setChecked, toggle::setEnabled,
                    toggle::setOnLongClickListener, toggle::setSummary);
            panel.addView(toggle.getView(), matchWrap());

            return control;
        }
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(title, 15, TEXT, false);
        labels.addView(name, matchWrap());
        TextView description = text(summary == null ? "" : summary, 12, MUTED, false);
        description.setVisibility(summary == null || summary.isEmpty() ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams descriptionParams = matchWrap();
        descriptionParams.topMargin = dp(3);
        labels.addView(description, descriptionParams);
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Switch toggle = new Switch(context);
        toggle.setChecked(checked);
        toggle.setEnabled(enabled);
        toggle.setTag("settings.toggle." + title);
        toggle.setContentDescription(title);

        toggle.setOnCheckedChangeListener((button, value) -> guarded.onValue(value));
        row.addView(toggle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(row, matchWrap());
        addDividerTo(panel);
        return new ToggleControl(toggle::setChecked, toggle::setEnabled, toggle::setOnLongClickListener, value -> {
            description.setText(value);
            description.setVisibility(value == null || value.isEmpty() ? View.GONE : View.VISIBLE);
        });
    }

    private void addLocalSwitch(LinearLayout panel, String title, String summary, String key) {
        addSwitch(panel, title, summary, repository.getBoolean(key, false), true,
                value -> repository.putBoolean(key, value));
    }

    private void addDividerTo(LinearLayout parent) {
        View divider = new View(context);
        divider.setBackgroundColor(LINE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        parent.addView(divider, p);
    }

    private GradientDrawable makeCircle(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setShape(GradientDrawable.OVAL);
        return drawable;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        android.widget.Toast.makeText(context, message == null ? "操作失败" : message,
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private static String safeError(Throwable failure) {
        String message = failure.getMessage();
        if (message == null) return failure.getClass().getSimpleName();
        return message.length() > 220 ? message.substring(0, 220) : message;
    }

    private interface EnabledControl { void setEnabled(boolean enabled); }

    private static final class ToggleControl implements EnabledControl {
        private final java.util.function.Consumer<Boolean> checked;
        private final java.util.function.Consumer<Boolean> enabled;
        private final java.util.function.Consumer<View.OnLongClickListener> longClick;
        private final java.util.function.Consumer<String> summary;

        ToggleControl(java.util.function.Consumer<Boolean> checked,
                      java.util.function.Consumer<Boolean> enabled,
                      java.util.function.Consumer<View.OnLongClickListener> longClick,
                      java.util.function.Consumer<String> summary) {
            this.checked = checked;
            this.enabled = enabled;
            this.longClick = longClick;
            this.summary = summary;
        }

        void setChecked(boolean value) { checked.accept(value); }
        @Override public void setEnabled(boolean value) { enabled.accept(value); }
        void setOnLongClickListener(View.OnLongClickListener listener) { longClick.accept(listener); }
        void setSummary(String value) { summary.accept(value); }
    }

    private static final class OptionRow implements EnabledControl {
        final View view;
        private final java.util.function.Consumer<String> value;
        private final java.util.function.Consumer<Boolean> enabled;
        private final java.util.function.IntConsumer color;

        OptionRow(View view, java.util.function.Consumer<String> value,
                  java.util.function.Consumer<Boolean> enabled, java.util.function.IntConsumer color) {
            this.view = view;
            this.value = value;
            this.enabled = enabled;
            this.color = color;
        }

        void setValue(String text) { value.accept(text); }
        void setColor(int newColor) { color.accept(newColor); }
        @Override public void setEnabled(boolean newEnabled) { enabled.accept(newEnabled); }
    }

    private interface ValueListener { void onValue(boolean value); }
    private interface ValueTextListener { void onValue(String value); }
}
