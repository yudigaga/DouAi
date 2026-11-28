package com.douai.module.ui;

import com.douai.module.diagnostics.DouAiLog;

import android.app.Dialog;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Build;
import android.os.Looper;
import android.os.PersistableBundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;

import com.douai.module.R;
import com.douai.module.ai.mcp.McpClient;
import com.douai.module.ai.mcp.McpRegistry;
import com.douai.module.ai.skills.AiSkills;
import com.douai.module.ai.skills.AiToolPackages;
import com.douai.module.ai.skills.MarkdownSkillStore;
import com.douai.module.ai.mcp.McpRegistry;
import com.douai.module.ai.skills.AiSubAgents;
import com.douai.module.ai.skills.SkillAuthoring;
import com.douai.module.ai.skills.SkillAuthoringPatch;
import com.douai.module.ai.skills.SkillDocumentationClient;
import com.douai.module.ai.skills.SkillOpenApiVideo;
import com.douai.module.ai.skills.SkillVideoDocumentation;
import com.douai.module.ai.skills.SkillCapabilityRegistry;
import com.douai.module.ai.skills.SkillCustomService;
import com.douai.module.ai.skills.SkillHttpDeclaration;
import com.douai.module.ai.skills.SkillManifest;
import com.douai.module.ai.skills.SkillResult;
import com.douai.module.ai.skills.SkillSelection;
import com.douai.module.ai.skills.SkillServices;
import com.douai.module.ai.skills.SkillStore;
import com.douai.module.ai.skills.SkillTable;
import com.douai.module.ai.skills.SkillVideoFacts;
import com.douai.module.ai.skills.SkillVideoEdit;
import com.douai.module.ai.skills.SkillVideoJobs;
import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.SettingsStore;
import com.douai.module.feature.DouyinSearch;
import com.douai.module.feature.ChatCompletionClient;
import com.douai.module.feature.ChatImage;
import com.douai.module.feature.ChatImageLoader;
import com.douai.module.feature.ChatMediaLoader;
import com.douai.module.feature.TestChatMemory;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import com.douai.module.ui.miuix.MiuixChatEditorSurface;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Model test conversation using a fixed profile snapshot and optional bounded private history. */
final class AiTestChatDialog extends Dialog {
    interface Session {
        String reply(String message, List<ChatImage> images) throws Exception;
        void cancel();
        default String skillModel(String system, String input) throws Exception {
            throw new IllegalStateException("当前模型不支持技能请求");
        }
        default String packageModel(String system, String input) throws Exception { return skillModel(system, input); }
        default String readSkillDocumentation(String endpoint, long deadlineNanos) throws Exception {
            return SkillDocumentationClient.read(endpoint, deadlineNanos);
        }
        default String reply(String message, List<ChatImage> images, String document) throws Exception {
            return reply(message, images);
        }
        default String replyStreaming(String message, List<ChatImage> images, String document,
                                      Consumer<String> onPartial) throws Exception {
            return reply(message, images, document);
        }
        default void clear() { }
        default List<TestChatMemory.Turn> savedTurns() { return Collections.emptyList(); }
        default TestChatMemory transcriptMemory() { return null; }
        default List<DouyinSearch.Result> searchResults() { return Collections.emptyList(); }
        default DouyinSearch searchBridge() { return null; }
    }

    interface SessionFactory { Session create(); }

    interface ThumbnailLoader { Bitmap load(ChatImage image); }

    interface MediaLoader {
        LoadedMedia load(Context context, android.net.Uri uri, AttachmentKind kind, int imageBudget) throws Exception;
    }

    private static final int TEXT = Color.rgb(31, 39, 45);
    private static final int MUTED = Color.rgb(105, 115, 122);
    private static final int ACCENT = Color.rgb(0, 151, 136);
    private static final int SURFACE = Color.rgb(255, 255, 255);
    private static final int CANVAS = Color.rgb(246, 248, 248);
    private static final int LINE = Color.rgb(226, 232, 231);
    private static final int ERROR = Color.rgb(174, 49, 57);
    private static final int MAX_VISIBLE_TURNS = 100;
    private static final int MAX_VISIBLE_IMAGE_BYTES = 16 * 1024 * 1024;
    private static final String DRAFT_CONTINUATION = "\n可直接说想改什么，或发送“预览草稿”“帮我内置”“取消编写”。";
    private static final String SAVING_HISTORY = "正在保存历史…";
    private static final String RESTORING_CHAT = "正在恢复对话和草稿，请稍后发送。";
    private static final int VIDEO_REFERENCE_MESSAGES = 12;
    private static final Pattern EDIT_CREDENTIAL = Pattern.compile("(?i)(?:密钥|密码|凭据|令牌|api[ _-]*key|authorization"
            + "|token|bearer|cookie)\\s*(?:改成|改为|调整为|修改为|换成|设置为|设为|填成|填写为)");
    private static final Pattern NAMED_DRAFT_FIELD = Pattern.compile("^(.*?(?:把|将)\\s*)(?:技能\\s*)?/"
            + "([\\p{L}\\p{N}_-]{1,24})\\s*的\\s*");
    private static final Pattern FIELD_CHANGE = Pattern.compile("改成|改为|调整为|修改为|换成|设置为|设为|填成|填写为");
    private static final String HISTORY_UNCONFIRMED = "技能已完成，但未能确认历史已保存。结果保留在当前会话。";
    private static final ExecutorService VIDEO_IO = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), task -> {
        Thread thread = new Thread(task, "DouAi-video-jobs");
        thread.setDaemon(true);
        return thread;
    });
    private static final ExecutorService HISTORY_IO = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "DouAi-chat-history");
            thread.setDaemon(true);
            return thread;
        }
    });

    private final Handler main = new Handler(Looper.getMainLooper());
    private final SessionFactory sessions;
    private final ExecutorService worker;
    private final ExecutorService historyWorker;
    private final ThumbnailLoader thumbnailLoader;
    private final MediaLoader mediaLoader;
    private final Map<ChatImage, Bitmap> thumbnails = new IdentityHashMap<>();
    private final List<Future<?>> thumbnailLoads = new ArrayList<>();
    private final ListView messages;
    private final List<ChatMessage> transcript = new ArrayList<>();
    private final TranscriptRows messageRows = new TranscriptRows();
    private final EditText input;
    private final ImageView send;
    private final ImageButton attach;
    private final TextView state;
    private final TextView skillEditorStatus;
    private final LinearLayout skillEditorBar;
    private final Button skillDraftSettings;
    private final LinearLayout attachments;
    private final HorizontalScrollView attachmentScroll;
    private final HorizontalScrollView skillMenu;
    private final LinearLayout skillCommands;
    private List<AiSkills.Command> visibleCommands = Collections.emptyList();
    private AiSkills.Entry skillEntry;
    private SkillStore skillStore;
    private AutoCloseable skillDeletionObserver;
    private SkillStore.DraftPreview skillDraft;
    private SkillVideoFacts skillDraftFacts;
    private String skillAuthoringId = "";
    private String pendingSkillDraftId = "";
    private String skillEditorSourceProof = "";
    private boolean skillEditorReplacesVideo;
    private TestChatMemory.SkillDraft storedSkillDraft;
    private SettingsStore subAgentSettings;
    private final List<ChatImage> draftImages = new ArrayList<>();
    private final List<Document> draftDocuments = new ArrayList<>();
    private final List<PendingAttachment> draftPending = new ArrayList<>();
    private final boolean useMiuix;
    private final String assistantName;
    private ImageButton contactEdit;
    private ImageView contactAvatar;
    private Bitmap contactAvatarBitmap;
    private Dialog attachmentMenu;
    private Dialog memoryDialog;
    private Dialog localMenu;
    private int packageDiscoveryGeneration;
    private Dialog skillDetails;
    private AlertDialog customReviewDialog;
    private AiChatImagePreview imagePreview;
    private Runnable contactEditAction;
    private HostSpeechInput speechInput;
    private HostAccountAvatar accountAvatar;
    private ImageView profileAvatar;
    private HorizontalScrollView quickReplies;
    private int visibleImageBytes;
    private Future<?> restoreLoad;
    private Future<?> historyClear;
    private HostImagePicker picker;
    private HostMediaPicker mediaPicker;
    private HostConfigFilePicker csvPicker;
    private Session session;
    private Future<?> request;
    private RequestTurn pending;
    private int generation;
    private int restoreGeneration;
    private int clearGeneration;
    private int thumbnailGeneration;
    private boolean restoreStarted;
    private boolean busy;
    private Future<?> videoDocumentTask;
    private boolean disposed;
    private MiuixChatEditorSurface miuixSurface;
    private final HostNativeChatViews nativeViews;
    private final AiSearchVideoCards searchVideoCards;
    private final SkillVideoJobs skillVideoJobs;
    private final AiSkillVideoCards skillVideoCards;
    private SkillVideoJobs.Scope videoScope;
    private int[] nativePadding;
    private boolean nativeFailure;

    AiTestChatDialog(Context context, AiModelConfig config) {
        this(context, config, true);
    }

    AiTestChatDialog(Context context, AiModelConfig config, boolean useMiuix) {
        this(context, config, useMiuix, false);
    }

    AiTestChatDialog(Context context, AiModelConfig config, boolean useMiuix, boolean messageContact) {
        this(context, config, useMiuix, messageContact, null);
    }

    AiTestChatDialog(Context context, AiModelConfig config, boolean useMiuix, boolean messageContact,
                     HostNativeChatViews nativeViews) {
        this(context, config, useMiuix, messageContact, nativeViews, null);
    }

    AiTestChatDialog(Context context, AiModelConfig config, boolean useMiuix, boolean messageContact,
                     HostNativeChatViews nativeViews, DouyinSearch search) {
        this(context, config, () -> {
            AiModelSettings snapshot = new AiModelSettings(Collections.singletonList(config), config.id);
            return new Session() {
                private volatile ChatCompletionClient client;
                private TestChatMemory memory;
                private volatile ChatCompletionClient.IsolatedTextRequest activeSkillRequest;
                private final List<DouyinSearch.Result> found = new ArrayList<>();
                private long searchEpoch;
                private synchronized ChatCompletionClient client() {
                    if (client == null) client = new ChatCompletionClient(transcriptMemory(),
                            diagnostic -> DouAiLog.legacy(4, messageContact ? "DouAi.AiContact" : "DouAi.ModelTest", diagnostic, null));
                    return client;
                }
                @Override public synchronized TestChatMemory transcriptMemory() {
                    if (memory == null) memory = messageContact ? TestChatMemory.messageConversation(context, config)
                            : new TestChatMemory(context, config);
                    return memory;
                }
                @Override public String reply(String message, List<ChatImage> images) throws Exception {
                    return client().reply(messageContact ? "message-contact" : "model-test", message, images, snapshot);
                }
                @Override public String skillModel(String system, String input) throws Exception {
                    return askIsolated(system, input, 4000);
                }
                @Override public String packageModel(String system, String input) throws Exception {
                    return askIsolated(system, input, AiToolPackages.MAX_MODEL_INPUT);
                }
                private String askIsolated(String system, String input, int maxInput) throws Exception {
                    ChatCompletionClient.IsolatedTextRequest isolated = new ChatCompletionClient.IsolatedTextRequest(true);
                    activeSkillRequest = isolated;
                    try {
                        return isolated.execute(snapshot, config.id, system, input,
                                System.nanoTime() + TimeUnit.SECONDS.toNanos(45), 2048, maxInput);
                    } finally {
                        activeSkillRequest = null;
                    }
                }
                @Override public void cancel() {
                    ChatCompletionClient.IsolatedTextRequest isolated = activeSkillRequest;
                    if (isolated != null) { isolated.cancel(); return; }
                    synchronized (found) { searchEpoch++; found.clear(); }
                    ChatCompletionClient active = client;
                    if (active != null) active.cancelCurrentRequest();
                }
                @Override public String reply(String message, List<ChatImage> images, String document) throws Exception {
                    return client().reply(messageContact ? "message-contact" : "model-test", message, images, snapshot, "", true, document);
                }
                @Override public String replyStreaming(String message, List<ChatImage> images, String document,
                                                       Consumer<String> onPartial) throws Exception {
                    long ticket;
                    synchronized (found) { found.clear(); ticket = ++searchEpoch; }
                    DouyinSearch observed = search == null ? null : (query, limit) -> {
                        DouyinSearch.Request original = search.preparePage(query, limit, 0, "");
                        return new DouyinSearch.Request() {
                            @Override public DouyinSearch.Result run(long deadline) throws Exception {
                                DouyinSearch.Result result = original.run(deadline);
                                synchronized (found) {
                                    if (ticket == searchEpoch && found.size() < 2) {
                                        found.addAll(AiSearchVideoCards.snapshot(Collections.singletonList(result)));
                                    }
                                }
                                return result;
                            }
                            @Override public void cancel() { original.cancel(); }
                        };
                    };
                    if (search != null) return client().replyWithSearch(
                            messageContact ? "message-contact" : "model-test", message, images,
                            snapshot, "", true, document, observed, onPartial);
                    return client().replyStreaming(messageContact ? "message-contact" : "model-test",
                            message, images, snapshot, "", true, document, onPartial);
                }
                @Override public List<DouyinSearch.Result> searchResults() {
                    synchronized (found) {
                        List<DouyinSearch.Result> results = new ArrayList<>(found);
                        found.clear();
                        return results;
                    }
                }
                @Override public DouyinSearch searchBridge() { return search; }
                @Override public void clear() {
                    synchronized (found) { searchEpoch++; found.clear(); }
                    client().clearHistory();
                }
            };
        }, Executors.newSingleThreadExecutor(), HISTORY_IO, useMiuix, ChatImageLoader::thumbnail,
                messageContact, nativeViews);
    }

    AiTestChatDialog(Context context, AiModelConfig config, SessionFactory sessions,
                     ExecutorService worker) {
        this(context, config, sessions, worker, HISTORY_IO, false);
    }

    AiTestChatDialog(Context context, AiModelConfig config, SessionFactory sessions,
                     ExecutorService worker, boolean useMiuix) {
        this(context, config, sessions, worker, HISTORY_IO, useMiuix);
    }

    AiTestChatDialog(Context context, AiModelConfig config, SessionFactory sessions,
                     ExecutorService worker, ExecutorService historyWorker) {
        this(context, config, sessions, worker, historyWorker, false);
    }

    AiTestChatDialog(Context context, AiModelConfig config, SessionFactory sessions,
                     ExecutorService worker, ExecutorService historyWorker, boolean useMiuix) {
        this(context, config, sessions, worker, historyWorker, useMiuix, ChatImageLoader::thumbnail);
    }

    AiTestChatDialog(Context baseContext, AiModelConfig config, SessionFactory sessions,
                     ExecutorService worker, ExecutorService historyWorker, boolean useMiuix,
                     ThumbnailLoader thumbnailLoader) {
        this(baseContext, config, sessions, worker, historyWorker, useMiuix, thumbnailLoader, false);
    }

    AiTestChatDialog(Context baseContext, AiModelConfig config, SessionFactory sessions,
                     ExecutorService worker, ExecutorService historyWorker, boolean useMiuix,
                     ThumbnailLoader thumbnailLoader, boolean messageContact) {
        this(baseContext, config, sessions, worker, historyWorker, useMiuix, thumbnailLoader,
                messageContact, null);
    }

    AiTestChatDialog(Context baseContext, AiModelConfig config, SessionFactory sessions,
                     ExecutorService worker, ExecutorService historyWorker, boolean useMiuix,
                     ThumbnailLoader thumbnailLoader, boolean messageContact, HostNativeChatViews nativeViews) {
        this(baseContext, config, sessions, worker, historyWorker, useMiuix, thumbnailLoader,
                messageContact, nativeViews, AiTestChatDialog::readMedia);
    }

    AiTestChatDialog(Context baseContext, AiModelConfig config, SessionFactory sessions,
                     ExecutorService worker, ExecutorService historyWorker, boolean useMiuix,
                     ThumbnailLoader thumbnailLoader, boolean messageContact, HostNativeChatViews nativeViews,
                     MediaLoader mediaLoader) {
        this(baseContext, config, sessions, worker, historyWorker, useMiuix, thumbnailLoader, messageContact,
                nativeViews, mediaLoader, SkillVideoJobs.forContext(baseContext));
    }

    AiTestChatDialog(Context baseContext, AiModelConfig config, SessionFactory sessions, ExecutorService worker,
                     ExecutorService historyWorker, ThumbnailLoader thumbnailLoader, SkillVideoJobs videoJobs) {
        this(baseContext, config, sessions, worker, historyWorker, false, thumbnailLoader, false, null,
                AiTestChatDialog::readMedia, videoJobs);
    }

    private AiTestChatDialog(Context baseContext, AiModelConfig config, SessionFactory sessions,
                             ExecutorService worker, ExecutorService historyWorker, boolean useMiuix,
                             ThumbnailLoader thumbnailLoader, boolean messageContact, HostNativeChatViews nativeViews,
                             MediaLoader mediaLoader, SkillVideoJobs videoJobs) {
        super(nativeViews == null ? ModuleTheme.themedContext(baseContext) : baseContext,
                nativeViews == null ? ModuleTheme.dialogTheme(baseContext) : android.R.style.Theme_DeviceDefault_NoActionBar);
        this.mediaLoader = mediaLoader;
        Context context = getContext();
        config.validate(true);
        this.sessions = sessions;
        this.worker = worker;
        this.historyWorker = historyWorker;
        this.thumbnailLoader = thumbnailLoader;
        this.useMiuix = useMiuix;
        this.nativeViews = nativeViews;
        this.searchVideoCards = new AiSearchVideoCards(context);
        this.skillVideoJobs = videoJobs;
        this.skillVideoCards = new AiSkillVideoCards(context, main, VIDEO_IO, skillVideoJobs, () -> skillStore,
                this::ownerActivity, messageRows::notifyDataSetChanged, this::showVideoStatus);
        assistantName = messageContact ? config.messageContactDisplayName() : "AI";
        session = sessions.create();
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setTitle(messageContact ? assistantName : "测试对话");

        LinearLayout root = new LinearLayout(context);
        root.setTag("ai.chat");
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(background(SURFACE, dp(20), LINE));
        root.setElevation(dp(4));
        root.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        if (messageContact) {
            ImageView avatar = new ImageView(context);
            contactAvatar = avatar;
            avatar.setTag("ai.chat.contact_avatar");
            avatar.setContentDescription(assistantName + "头像");
            Bitmap bitmap = AiContactAvatar.load(config.messageContactAvatar);
            contactAvatarBitmap = bitmap;
            if (bitmap == null) avatar.setImageResource(android.R.drawable.sym_def_app_icon);
            else avatar.setImageBitmap(bitmap);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(36), dp(36));
            avatarParams.rightMargin = dp(8);
            header.addView(avatar, avatarParams);
        }
        TextView title = label(messageContact ? assistantName : "测试对话", 18, TEXT);
        title.setTag("ai.chat.title");
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (messageContact) {
            contactEdit = icon("修改联系人", "ai.chat.contact_edit", android.R.drawable.ic_menu_edit);
            contactEdit.setVisibility(View.GONE);
            header.addView(contactEdit, square());
        }
        ImageButton memory = icon("查看一级提示词", "ai.chat.memory", android.R.drawable.ic_menu_info_details);
        if (messageContact) memory.setVisibility(View.GONE);
        memory.setOnClickListener(ignored -> {
            TextView background = label(config.systemPrompt.isEmpty() ? "未设置" : config.systemPrompt, 14, TEXT);
            background.setTextIsSelectable(true);
            background.setPadding(dp(16), dp(12), dp(16), dp(12));
            background.setTag("ai.chat.memory.text");
            ScrollView body = new ScrollView(getContext());
            body.addView(background);
            memoryDialog = new MiuixDialogBuilder(getContext(), useMiuix).setTitle("一级提示词")
                    .setView(body).setNegativeButton("关闭", null).create();
            memoryDialog.show();
            if (ownerActivity() != null) HostDialogBounds.install(ownerActivity(), memoryDialog, false);
        });
        header.addView(memory, square());
        ImageButton clear = icon("清空对话", "ai.chat.clear", android.R.drawable.ic_menu_delete);
        clear.setOnClickListener(ignored -> clearConversation());
        header.addView(clear, square());
        ImageButton close = icon("关闭", "ai.chat.close", android.R.drawable.ic_menu_close_clear_cancel);
        close.setOnClickListener(ignored -> dismiss());
        header.addView(close, square());
        root.addView(header, wrap());

        TextView model = label(config.name + " · " + config.modelName, 13, MUTED);
        model.setTag("ai.chat.model");
        model.setMaxLines(2);
        model.setPadding(0, dp(2), 0, dp(8));
        root.addView(model, wrap());
        state = label("", 12, MUTED);
        state.setTag("ai.chat.state");
        state.setMinHeight(dp(28));
        state.setGravity(Gravity.CENTER_VERTICAL);
        state.setPadding(dp(10), 0, dp(10), 0);
        state.setBackground(background(CANVAS, dp(8), LINE));
        root.addView(state, wrap());
        skillEditorStatus = label("", 12, MUTED);
        skillEditorStatus.setTag("ai.chat.skill_editor");
        skillEditorStatus.setPadding(dp(10), dp(5), dp(10), dp(5));
        skillEditorBar = new LinearLayout(context);
        skillEditorBar.setGravity(Gravity.CENTER_VERTICAL);
        skillEditorBar.setVisibility(View.GONE);
        skillEditorBar.addView(skillEditorStatus, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        skillDraftSettings = new Button(context);
        skillDraftSettings.setAllCaps(false);
        skillDraftSettings.setText("查看草稿配置");
        skillDraftSettings.setOnClickListener(ignored -> showCurrentDraftDetails());
        skillEditorBar.addView(skillDraftSettings, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(skillEditorBar, wrap());

        messages = new ListView(context);
        messages.setTag("ai.chat.messages");
        messages.setDividerHeight(0);
        messages.setClipToPadding(false);
        messages.setPadding(dp(8), dp(4), dp(8), dp(4));
        messages.setBackground(background(CANVAS, dp(12), LINE));
        if (nativeViews != null) {
            try {
                HostNativeChatViews.ProfileHeader profile = nativeViews.createProfileHeader(messages);
                profileAvatar = profile.avatar;
                profile.avatar.setTag("ai.chat.profile_avatar");
                profile.avatar.setContentDescription(assistantName + "头像");
                if (contactAvatarBitmap == null) profile.avatar.setImageResource(android.R.drawable.sym_def_app_icon);
                else profile.avatar.setImageBitmap(contactAvatarBitmap);
                profile.name.setText(assistantName);
                profile.name.setTag("ai.chat.profile_name");
                profile.view.setTag("ai.chat.profile");
                messages.addHeaderView(profile.view, null, false);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("原生联系人资料区域不可用", failure);
            }
        }
        messages.setAdapter(messageRows);
        LinearLayout.LayoutParams transcriptParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        transcriptParams.topMargin = dp(10);
        root.addView(messages, transcriptParams);

        attachmentScroll = new HorizontalScrollView(context);
        attachmentScroll.setTag("ai.chat.attachments");
        attachmentScroll.setPadding(dp(6), dp(4), dp(6), dp(4));
        attachmentScroll.setBackground(background(CANVAS, dp(12), LINE));
        attachments = new LinearLayout(context);
        attachments.setOrientation(LinearLayout.HORIZONTAL);
        attachmentScroll.addView(attachments);
        attachmentScroll.setVisibility(View.GONE);
        LinearLayout.LayoutParams attachmentParams = wrap();
        attachmentParams.topMargin = dp(8);
        root.addView(attachmentScroll, attachmentParams);

        skillMenu = new HorizontalScrollView(context);
        skillMenu.setTag("ai.chat.skills");
        skillMenu.setHorizontalScrollBarEnabled(false);
        skillMenu.setVisibility(View.GONE);
        skillCommands = new LinearLayout(context);
        skillMenu.addView(skillCommands);
        root.addView(skillMenu, wrap());

        LinearLayout composer = new LinearLayout(context);
        composer.setGravity(Gravity.BOTTOM);
        composer.setPadding(0, dp(10), 0, 0);
        attach = icon("添加附件", "ai.chat.attach", android.R.drawable.ic_menu_gallery);
        attach.setOnClickListener(ignored -> chooseAttachment());
        composer.addView(attach, square());
        input = nativeViews == null ? new EditText(context) : nativeViews.input;
        input.setTag("ai.chat.input");
        input.setContentDescription("消息");
        input.setHint("输入消息");
        if (nativeViews == null) {
            input.setTextColor(TEXT);
            input.setTextSize(15);
        }
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(1);
        if (nativeViews == null) {
            input.setPadding(dp(12), dp(10), dp(12), dp(10));
            input.setBackground(background(CANVAS, dp(14), LINE));
        }
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        input.setMaxLines(metrics.heightPixels / metrics.density < 480 ? 2 : 4);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4000)});
        input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        if (nativeViews == null) composer.addView(input,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        send = nativeViews == null ? icon("发送", "ai.chat.send", android.R.drawable.ic_menu_send) : nativeViews.send;
        send.setTag("ai.chat.send");
        send.setOnClickListener(ignored -> {
            if (busy) stopRequest();
            else sendMessage(input.getText().toString(), new ArrayList<>(draftImages),
                    new ArrayList<>(draftDocuments), null);
        });
        if (nativeViews == null) composer.addView(send, square());
        LinearLayout.LayoutParams composerParams = wrap();
        composerParams.topMargin = dp(8);
        root.addView(composer, composerParams);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateSend(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        Activity activity = ownerActivity();
        if (nativeViews != null) {
            bindNativeConversation();
            setContentView(nativeViews.root);
        } else if (useMiuix && activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
            ModuleTheme.bind(root);
            setOwnerActivity(activity);
            miuixSurface = new MiuixChatEditorSurface(activity, root, this::dismiss);
            setContentView(miuixSurface.getView());
        } else {
            ModuleTheme.bind(root);
            setContentView(root);
        }
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            if (nativeViews == null) window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = nativeViews == null ? 0.42f : 0;
            window.setAttributes(attributes);
            window.setGravity(Gravity.CENTER);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            if (nativeViews != null && Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false);
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                        | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            }
            if (nativeViews != null && nativeViews.root.getBackground() instanceof ColorDrawable) {
                int color = ((ColorDrawable) nativeViews.root.getBackground()).getColor();
                boolean light = Color.red(color) * 0.2126 + Color.green(color) * 0.7152
                        + Color.blue(color) * 0.0722 > 128;
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                window.setStatusBarColor(Build.VERSION.SDK_INT >= 30 ? Color.TRANSPARENT : color);
                window.setNavigationBarColor(Build.VERSION.SDK_INT >= 30 ? Color.TRANSPARENT : color);
                if (Build.VERSION.SDK_INT >= 30 && window.getInsetsController() != null) {
                    int appearance = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    window.getInsetsController().setSystemBarsAppearance(light ? appearance : 0, appearance);
                } else {
                    window.getDecorView().setSystemUiVisibility(light ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                            | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
                }
            }
            window.getDecorView().setOnApplyWindowInsetsListener((view, insets) -> {
                sizeWindow(insets);
                return insets;
            });
        }
        updateSend();
    }

    /** Host factories create views only; the existing AI session owns every send and history update. */
    private void bindNativeConversation() {
        nativePadding = new int[]{nativeViews.root.getPaddingLeft(), nativeViews.root.getPaddingTop(),
                nativeViews.root.getPaddingRight(), nativeViews.root.getPaddingBottom()};
        nativeViews.root.setTag("ai.chat");
        nativeViews.title.setText(assistantName);
        nativeViews.title.setTag("ai.chat.title");
        nativeViews.back.setContentDescription("返回消息");
        nativeViews.back.setTag("ai.chat.close");
        nativeViews.back.setOnClickListener(ignored -> dismiss());
        if (nativeViews.avatar != null) {
            nativeViews.avatar.setTag("ai.chat.contact_avatar");
            nativeViews.avatar.setContentDescription(assistantName + "头像");
            if (contactAvatarBitmap == null) nativeViews.avatar.setImageResource(android.R.drawable.sym_def_app_icon);
            else nativeViews.avatar.setImageBitmap(contactAvatarBitmap);
            nativeViews.avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        }
        accountAvatar = HostAccountAvatar.read(nativeViews.root.getContext());
        nativeViews.more.setTag("ai.chat.more");
        nativeViews.more.setOnClickListener(ignored -> showConversationActions());
        nativeViews.tools.setTag("ai.chat.tools");
        nativeViews.tools.setOnClickListener(ignored -> showLocalTools());
        nativeViews.plus.setTag("ai.chat.attach");
        nativeViews.plus.setOnClickListener(ignored -> chooseAttachment());
        nativeViews.emoji.setTag("ai.chat.emoji");
        nativeViews.emoji.setOnClickListener(ignored -> showEmojiChoices());
        nativeViews.voice.setTag("ai.chat.voice");
        nativeViews.voice.setOnClickListener(ignored -> startSpeechInput());
        LinearLayout content = new LinearLayout(nativeViews.root.getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        messages.setBackgroundColor(Color.TRANSPARENT);
        messages.setPadding(dp(12), 0, dp(12), dp(8));
        move(state, content, wrap());
        move(skillEditorBar, content, wrap());
        move(messages, content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        move(attachmentScroll, content, wrap());
        quickReplies = new HorizontalScrollView(nativeViews.root.getContext());
        quickReplies.setTag("ai.chat.quick_replies");
        quickReplies.setHorizontalScrollBarEnabled(false);
        quickReplies.setClipToPadding(false);
        quickReplies.setPadding(dp(8), 0, dp(8), dp(8));
        LinearLayout quickRow = new LinearLayout(nativeViews.root.getContext());
        quickRow.setGravity(Gravity.CENTER_VERTICAL);
        String[] quickLabels = {"🌞 早上好", "😘 比心", "😴 困", "👍 赞", "🤦 捂脸"};
        String[] quickText = {"早上好 ☀️", "比心 ❤️", "😴", "👍", "🤦"};
        for (int index = 0; index < quickLabels.length; index++) {
            int selected = index;
            try {
                View action = nativeViews.createQuickAction(quickRow, quickLabels[index]);
                action.setTag("ai.chat.quick." + index);
                action.setContentDescription(quickLabels[index]);
                action.setOnClickListener(ignored -> insertDraft(quickText[selected]));
                ViewGroup.LayoutParams nativeParams = action.getLayoutParams();
                LinearLayout.LayoutParams params = nativeParams instanceof ViewGroup.MarginLayoutParams
                        ? new LinearLayout.LayoutParams((ViewGroup.MarginLayoutParams) nativeParams)
                        : nativeParams != null ? new LinearLayout.LayoutParams(nativeParams)
                        : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
                params.setMarginEnd(dp(8));
                quickRow.addView(action, params);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("原生快捷回复不可用", failure);
            }
        }
        quickReplies.addView(quickRow);
        content.addView(quickReplies, wrap());
        move(skillMenu, content, wrap());
        nativeViews.messageContainer.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        nativeViews.root.setFitsSystemWindows(Build.VERSION.SDK_INT < 30);
        // Theme only module-owned status/attachment content. Host controls retain their native palette.
        ModuleTheme.bind(state);
        ModuleTheme.bind(skillEditorBar);
        ModuleTheme.bind(attachmentScroll);
        ModuleTheme.bind(skillMenu);
    }

    private static void move(View view, ViewGroup parent, ViewGroup.LayoutParams params) {
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
        parent.addView(view, params);
    }

    @Override protected void onStart() {
        super.onStart();
        if (!restoreStarted && !disposed) {
            restoreStarted = true;
            restoreConversation();
        }
        if (miuixSurface != null) miuixSurface.bindBackNavigation(this);
        Window window = getWindow();
        if (window != null) sizeWindow(window.getDecorView().getRootWindowInsets());
    }

    void enableSkills(AiSkills.Entry entry) {
        enableSkills(entry, null);
    }

    void enableSkills(AiSkills.Entry entry, SettingsStore settings) {
        enableSkills(entry, settings, entry == null ? null : SkillStore.forContext(getContext()));
    }

    void enableSkills(AiSkills.Entry entry, SettingsStore settings, SkillStore store) {
        if (disposed) return;
        closeSkillDeletionObserver();
        subAgentSettings = entry == AiSkills.Entry.WORK ? settings : null;
        skillEntry = entry;
        skillStore = entry == null ? null : store;
        if (skillStore != null) {
            SkillStore observed = skillStore;
            skillDeletionObserver = observed.observeDeletions(() -> main.post(() -> {
                if (!disposed && skillStore == observed) updateSkillMenu();
            }));
        }
        boolean enabled = false;
        try { enabled = skillStore != null && skillStore.enabled(); }
        catch (IllegalStateException unavailable) { }
        if (enabled) input.setHint("输入消息，或输入 / 选择技能");
        updateSkillMenu();
    }

    private void closeSkillDeletionObserver() {
        AutoCloseable observer = skillDeletionObserver;
        skillDeletionObserver = null;
        if (observer == null) return;
        try { observer.close(); }
        catch (Exception ignored) { }
    }

    void setContactEditAction(Runnable action) {
        contactEditAction = action;
        if (nativeViews != null) return;
        if (contactEdit == null) return;
        contactEdit.setVisibility(action == null ? View.GONE : View.VISIBLE);
        contactEdit.setOnClickListener(action == null ? null : ignored -> action.run());
        if (miuixSurface != null) miuixSurface.refresh();
    }

    private void sizeWindow(WindowInsets insets) {
        Window window = getWindow();
        if (window == null) return;
        android.util.DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        int hostWidth = metrics.widthPixels;
        int hostHeight = metrics.heightPixels;
        Activity owner = ownerActivity();
        if (Build.VERSION.SDK_INT >= 30 && owner != null) {
            Rect bounds = owner.getWindowManager().getCurrentWindowMetrics().getBounds();
            hostWidth = bounds.width();
            hostHeight = bounds.height();
        }
        if (nativeViews != null) {
            if (insets != null && Build.VERSION.SDK_INT >= 30) {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                        | WindowInsets.Type.ime());
                nativeViews.root.setPadding(nativePadding[0] + safe.left, nativePadding[1] + safe.top,
                        nativePadding[2] + safe.right, nativePadding[3] + safe.bottom);
            }
            window.setGravity(Gravity.FILL);
            WindowManager.LayoutParams attributes = window.getAttributes();
            if (attributes.width != hostWidth || attributes.height != hostHeight) window.setLayout(hostWidth, hostHeight);
            return;
        }
        int horizontal = 0;
        int vertical = 0;
        if (insets != null) {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                        | WindowInsets.Type.ime());
                horizontal = safe.left + safe.right;
                vertical = safe.top + safe.bottom;
            } else {
                horizontal = insets.getSystemWindowInsetLeft() + insets.getSystemWindowInsetRight();
                vertical = insets.getSystemWindowInsetTop() + insets.getSystemWindowInsetBottom();
            }
        }
        int width = Math.max(1, Math.min(dp(560), hostWidth - horizontal - dp(24)));
        int height = Math.max(1, Math.min(dp(720), Math.min(Math.round(hostHeight * 0.84f),
                hostHeight - vertical - dp(32))));
        window.setGravity(Gravity.CENTER);
        WindowManager.LayoutParams attributes = window.getAttributes();
        if (attributes.width != width || attributes.height != height) window.setLayout(width, height);
    }

    private void sendMessage(String prompt, List<ChatImage> images, List<Document> documents, ChatMessage retryAnswer) {
        sendMessage(prompt, images, documents, retryAnswer, null, null, false);
    }

    private void sendMessage(String prompt, List<ChatImage> images, List<Document> documents, ChatMessage retryAnswer,
                             SkillStore.CustomCall approvedService, SkillSelection.Choice choice, boolean selectionDone) {
        sendMessage(prompt, images, documents, retryAnswer, approvedService, choice, selectionDone, null);
    }

    private record AuthoringMaterial(SkillAuthoring.Action action, SkillVideoFacts facts,
                                     SkillAuthoringPatch.Request fieldPatch) {
        AuthoringMaterial(SkillAuthoring.Action action, SkillVideoFacts facts) { this(action, facts, null); }
    }

    private void sendMessage(String prompt, List<ChatImage> images, List<Document> documents, ChatMessage retryAnswer,
                             SkillStore.CustomCall approvedService, SkillSelection.Choice choice, boolean selectionDone,
                             AuthoringMaterial selectedMaterial) {
        sendMessage(prompt, images, documents, retryAnswer, approvedService, choice, selectionDone, selectedMaterial, null);
    }

    private void sendMessage(String prompt, List<ChatImage> images, List<Document> documents, ChatMessage retryAnswer,
                             SkillStore.CustomCall approvedService, SkillSelection.Choice choice, boolean selectionDone,
                             AuthoringMaterial selectedMaterial, AiSkills.Invocation selectedPackage) {
        String commandText = prompt.trim();
        List<PendingAttachment> selected = new ArrayList<>(retryAnswer == null ? draftPending : retryAnswer.retryPending);
        if (disposed || busy
                || (commandText.isEmpty() && images.isEmpty() && documents.isEmpty() && selected.isEmpty())) {
            DouAiLog.event("ai_chat", "eligibility", disposed ? "closed" : busy ? "busy" : "empty_input", ""); return;
        }
        if (videoDocumentTask != null) return;
        if (skillEntry != null && restoreLoad != null) {
            try {
                TestChatMemory memory = session.transcriptMemory();
                if (memory != null && memory.historyEnabled()) {
                    showAuthoringStatus(RESTORING_CHAT);
                    return;
                }
            } catch (RuntimeException unavailable) { /* A session without a local transcript can still reply. */ }
        }
        if (selectedPackage == null && selectedMaterial == null && choice == null && retryAnswer == null && skillEntry != null) {
            String supplement = videoDocumentationCommand(commandText);
            if (supplement != null) {
                if (!previewingVideoDraft() || skillStore == null || !skillStore.enabled()) {
                    showAuthoringStatus("请先在当前会话打开一份视频技能草稿");
                } else if (images.isEmpty() && documents.isEmpty() && selected.isEmpty()) {
                    confirmOpenApiVideo(prompt, new SkillAuthoring.Action(SkillAuthoring.Mode.SUPPLEMENT, "", false),
                            supplement);
                } else {
                    showAuthoringStatus("请移除附件，再读取接口文档");
                }
                return;
            }
            String change = commandText.startsWith("/技能") ? commandText.substring("/技能".length()).trim() : commandText;
            if (previewingVideoDraft() && change.matches("(?i)^(?:请|帮我)?(?:改用|换用|换成|切换到)\\s*new[\\s-]*api[。！! ]*$")
                    && images.isEmpty() && documents.isEmpty() && selected.isEmpty()) {
                if (pendingSkillDraftId.isEmpty() && skillEditorSourceProof.isEmpty()) {
                    showAuthoringStatus("请先用 /技能 编辑 /技能名 打开要更换接口的已保存技能。");
                } else {
                    SkillVideoFacts documented = SkillVideoDocumentation.knownFacts(change);
                    if (!documented.isComplete()) showAuthoringStatus("未找到完整的接口资料，请提供服务文档。");
                    else sendAuthoringMaterial(prompt,
                            new SkillAuthoring.Action(SkillAuthoring.Mode.REPLACE_VIDEO_SOURCE, "", false), documented);
                }
                return;
            }
            String markdownRequirement = AiToolPackages.parseWrite(commandText);
            if (markdownRequirement != null && !existingPackageCommand(commandText)) {
                showAuthoringStatus("请用 /技能 描述需求，或用 /技能 编辑 /技能名 打开已有技能。");
                return;
            }
            AiToolPackages.Use use = AiToolPackages.parseUse(commandText);
            if (use != null && !existingUnqualifiedInvocation(commandText, use)) {
                if (!images.isEmpty() || !documents.isEmpty() || !selected.isEmpty()) {
                    showAuthoringStatus("请先移除附件，再选用工具包。");
                } else {
                    usePackage(prompt, use);
                }
                return;
            }
        }
        boolean installReference = SkillAuthoring.isReferencedVideoInstall(commandText);
        boolean currentVideoOnly = SkillAuthoring.fillsCurrentVideoDraftOnly(commandText);
        if (currentVideoOnly && retryAnswer == null && selectedMaterial == null && !previewingVideoDraft()) {
            showAuthoringStatus("当前没有正在编写的视频草稿。");
            return;
        }
        boolean naturalFill = currentVideoOnly || skillDraft != null && SkillAuthoring.naturallyFillsSkillFields(commandText);
        boolean usesReference = SkillAuthoring.usesPreviousVideoMaterial(commandText);
        if (skillEntry != null && retryAnswer == null && selectedMaterial == null && skillDraft != null
                && !previewingVideoDraft() && !installReference && (naturalFill || usesReference)) {
            if (!images.isEmpty() || !documents.isEmpty() || !selected.isEmpty()) {
                showAuthoringStatus("请移除附件，再引用上面的字段资料。");
            } else {
                chooseDraftFieldSource(prompt, true);
            }
            return;
        }
        if (skillEntry != null && retryAnswer == null && selectedMaterial == null
                && (installReference || naturalFill || usesReference)) {
            if (!images.isEmpty() || !documents.isEmpty() || !selected.isEmpty()) {
                showAuthoringStatus("请移除附件，再引用上面的接口资料");
            } else {
                referenceVideoDraft(prompt, installReference, naturalFill);
            }
            return;
        }
        final AiSkills.Invocation skill;
        final SkillAuthoring.Action authoring;
        final SkillAuthoring.VideoSource videoSource;
        final SkillVideoFacts authoredFacts;
        final SkillAuthoringPatch.Request fieldPatch;
        SkillStore.DraftPreview previousDraft = skillDraft;
        SettingsStore selectedSettings = subAgentSettings;
        AiSubAgents.Selection subAgent = AiSubAgents.read(selectedSettings);
        SkillSelection.Choice resolved = choice;
        try {
            videoSource = skillStore != null && retryAnswer == null && selectedPackage == null
                    ? SkillAuthoring.videoSource(commandText) : null;
            boolean editing = skillEntry != null && retryAnswer == null && previousDraft != null && approvedService == null
                    && selectedPackage == null;
            SkillVideoFacts material = selectedMaterial == null ? null : selectedMaterial.facts();
            if (editing && material == null) {
                try { SkillAuthoring.rejectEditorCredential(commandText); }
                catch (IllegalArgumentException credential) {
                    if (prompt.equals(input.getText().toString())) input.setText("");
                    showAuthoringStatus("请移除密钥，并在技能设置填写。");
                    return;
                }
            }
            if (editing && material == null && previousDraft.outputType() == SkillResult.Type.VIDEO && videoSource == null
                    && SkillAuthoring.canUsePastedVideoMaterial(commandText)) {
                try {
                    String fragment = SkillAuthoring.videoMaterialFragment(commandText);
                    SkillVideoFacts parsed = fragment == null ? SkillVideoFacts.parseReference(commandText)
                            : SkillVideoFacts.parseSupplement(fragment);
                    if (fragment != null && parsed.isEmpty()) {
                        showAuthoringStatus("请贴接口文档中的一段，或提供对应的请求、响应示例。");
                        return;
                    }
                    if (!parsed.isEmpty()) material = parsed;
                } catch (IllegalArgumentException unsafe) {
                    showAuthoringStatus("接口资料无法安全识别，请核对文档并移除其中的密钥或命令变量");
                    return;
                }
            }
            SkillAuthoring.Action requested = skillStore == null || retryAnswer != null || selectedPackage != null ? null
                    : selectedMaterial != null ? selectedMaterial.action()
                    : material != null ? new SkillAuthoring.Action(SkillAuthoring.Mode.SUPPLEMENT, "", false)
                    : videoSource == null ? SkillAuthoring.match(commandText, editing) : videoSource.action();
            SkillVideoFacts targetedFacts = null;
            SkillAuthoringPatch.Request proposedPatch = selectedMaterial == null ? null : selectedMaterial.fieldPatch();
            if (editing && material == null && videoSource == null && previousDraft.outputType() == SkillResult.Type.VIDEO
                    && (requested == null || requested.mode() != SkillAuthoring.Mode.OPEN)) {
                String editText = requested != null && requested.mode() == SkillAuthoring.Mode.REVISE ? requested.request()
                        : SkillAuthoring.isCreationCommand(commandText) ? commandText.substring("/技能".length()).trim() : commandText;
                SkillVideoEdit.Edit edit = SkillVideoEdit.parse(editText);
                if (edit != null) {
                    if (edit.mode() == SkillVideoEdit.Mode.SENSITIVE) {
                        if (prompt.equals(input.getText().toString())) input.setText("");
                        showAuthoringStatus("请移除密钥，并在技能设置填写。");
                        return;
                    }
                    if (edit.mode() == SkillVideoEdit.Mode.CLARIFY) {
                        showAuthoringStatus("请确认要填写的接口字段和值，例如「任务 ID 是 data.id」。");
                        return;
                    }
                    if (edit.mode() == SkillVideoEdit.Mode.NEEDS_ENCODING_EVIDENCE && edit.fields().isEmpty()
                            && edit.stateMappings().isEmpty() && edit.queryMode().isEmpty()) {
                        showAuthoringStatus("请贴提交请求的文档或示例，核对它是否支持 application/json。");
                        return;
                    }
                    try {
                        SkillVideoFacts prior = skillDraftFacts == null ? SkillVideoFacts.empty() : skillDraftFacts;
                        targetedFacts = applyNaturalVideoEdit(prior, edit);
                    } catch (IllegalArgumentException conflict) {
                        showAuthoringStatus("接口字段与已填写的值冲突，或格式不符。请明确要改的字段和新值。");
                        return;
                    }
                    requested = new SkillAuthoring.Action(SkillAuthoring.Mode.SUPPLEMENT, editText,
                            requested != null && requested.saveAfter());
                }
            }
            if (editing && requested == null) {
                if (SkillAuthoring.needsEditClarification(commandText)) {
                    showAuthoringStatus("想修改 /" + previousDraft.name() + " 的哪一项？");
                    return;
                }
                requested = SkillAuthoring.continuation(commandText);
            }
            if (editing && material == null && targetedFacts == null && proposedPatch == null && videoSource == null
                    && (requested == null || requested.mode() == SkillAuthoring.Mode.REVISE)) {
                String editText = requested == null ? commandText : requested.request();
                if (!otherDraftTarget(editText) && !editText.matches("(?s).*(?:[?？]|[吗么][。！!]*)$")) {
                    SkillManifest manifest = authoringManifest(previousDraft);
                    Set<String> targets = manifest == null ? Collections.emptySet() : SkillAuthoringPatch.targets(manifest, editText);
                    if (!targets.isEmpty()) {
                        proposedPatch = SkillAuthoringPatch.request(manifest, targets, editText, "", false);
                        requested = new SkillAuthoring.Action(SkillAuthoring.Mode.FIELD_PATCH, editText,
                                requested != null && requested.saveAfter());
                    }
                }
            }
            if (requested != null && requested.mode() == SkillAuthoring.Mode.REVISE) {
                String revision = editing ? localDraftField(requested.request(), previousDraft.name()) : requested.request();
                SkillAuthoring.FieldEdit change = SkillAuthoring.fieldEdit(revision);
                if (EDIT_CREDENTIAL.matcher(revision).find()
                        || (change != null && change.label().matches(
                        "(?i).*(密钥|密码|凭据|令牌|api[ _-]*key|authorization|token|bearer|cookie).*"))) {
                    if (prompt.equals(input.getText().toString())) input.setText("");
                    showAuthoringStatus("请移除密钥，并在技能设置填写。");
                    return;
                }
                if (!editing || otherDraftTarget(revision)) {
                    showAuthoringStatus(editing ? "当前正在编写 /" + previousDraft.name() + "。请确认要修改哪一份草稿。"
                            : "请先在当前会话创建或打开要修改的草稿。");
                    return;
                }
                Matcher fields = FIELD_CHANGE.matcher(revision);
                if (SkillAuthoring.needsEditClarification(revision)
                        || (change == null && fields.find() && fields.find())) {
                    showAuthoringStatus("请说清要修改 /" + previousDraft.name() + " 的哪个字段和新值。");
                    return;
                }
                if (change != null && previousDraft.outputType() == SkillResult.Type.VIDEO) {
                    if (change.label().matches("(?i)提交(?:请求)?(?:格式|编码)|content[ _-]*type|submitencoding")) {
                        showAuthoringStatus("请贴服务文档中的提交编码示例，以便核对接口实际支持的格式。");
                        return;
                    }
                    String field = SkillVideoFacts.chatEditableField(change.label());
                    if (!field.isEmpty()) {
                        SkillVideoFacts prior = skillDraftFacts == null ? SkillVideoFacts.empty() : skillDraftFacts;
                        try {
                            targetedFacts = prior.withForm(Map.of(field, change.value()));
                        } catch (IllegalArgumentException invalid) {
                            showAuthoringStatus("此字段的值不符合接口格式，当前草稿已保留。");
                            return;
                        }
                        requested = new SkillAuthoring.Action(SkillAuthoring.Mode.SUPPLEMENT,
                                requested.request(), requested.saveAfter());
                    }
                }
            }
            int pollSeconds = 0;
            if (editing && previousDraft.outputType() == SkillResult.Type.VIDEO && requested != null
                    && requested.mode() == SkillAuthoring.Mode.REVISE) {
                try {
                    pollSeconds = SkillAuthoring.minimumPollSeconds(requested.request());
                } catch (IllegalArgumentException invalid) {
                    showAuthoringStatus("请明确一个 3 至 60 秒的最短轮询间隔");
                    return;
                }
            }
            boolean creationCommand = skillEntry != null && retryAnswer == null && selectedPackage == null
                    && SkillAuthoring.isCreationCommand(commandText);
            boolean canAuthor = (creationCommand || requested != null) && skillStore != null && skillStore.enabled();
            if ((creationCommand || videoSource != null) && (!canAuthor || requested == null
                    || requested.mode() == SkillAuthoring.Mode.DESIGN && requested.request().isEmpty())) {
                state.setText(!canAuthor ? "请先启用 AI Skills"
                        : requested == null ? "请在 /技能 后填写不超过 400 字的单行需求"
                        : "请在 /技能 后填写技能需求");
                updateSend();
                return;
            }
            if (canAuthor && selectedMaterial == null && requested.mode() == SkillAuthoring.Mode.DESIGN
                    && SkillAuthoring.explicitOutput(requested.request()) == SkillResult.Type.VIDEO
                    && images.isEmpty() && documents.isEmpty() && selected.isEmpty()) {
                if (videoSource == null) {
                    String documentLink;
                    try { documentLink = SkillVideoDocumentation.openApiLink(requested.request()); }
                    catch (IllegalArgumentException invalid) {
                        showAuthoringStatus(invalid.getMessage());
                        return;
                    }
                    if (!documentLink.isEmpty()) {
                        var action = new SkillAuthoring.Action(SkillAuthoring.Mode.DESIGN,
                                requested.request().replace(documentLink, "").trim(), requested.saveAfter());
                        confirmOpenApiVideo(prompt, action, documentLink);
                        return;
                    }
                    SkillVideoFacts documented = SkillVideoDocumentation.knownFacts(requested.request());
                    if (!documented.isEmpty()) {
                        sendAuthoringMaterial(prompt, requested, documented);
                        return;
                    }
                }
                if (videoSource != null && videoSource.visibleMaterial()) {
                    chooseVisibleVideoSource(prompt, requested);
                    return;
                }
                if (videoSource == null) {
                    List<VideoReferenceMaterial> supplied = visibleUserVideoMaterials();
                    if (!supplied.isEmpty()) {
                        chooseGroundedVideoSource(prompt, requested, supplied);
                        return;
                    }
                }
            }
            authoring = canAuthor ? requested : null;
            fieldPatch = canAuthor ? proposedPatch : null;
            if (targetedFacts != null) {
                authoredFacts = targetedFacts;
            } else if (material != null) {
                authoredFacts = authoring.mode() == SkillAuthoring.Mode.DESIGN
                        || authoring.mode() == SkillAuthoring.Mode.REPLACE_VIDEO_SOURCE || skillDraftFacts == null
                        ? material : skillDraftFacts.fillMissingFrom(material);
            } else if (videoSource != null) {
                SkillVideoFacts supplied = SkillVideoFacts.parse(videoSource.notes());
                if (videoSource.relatedSkill().isEmpty()) authoredFacts = supplied;
                else {
                    SkillVideoFacts related;
                    try { related = skillStore.videoFactsForSkill(videoSource.relatedSkill()); }
                    catch (Exception unavailable) {
                        state.setText("找不到已启用且已配置的对应视频技能");
                        updateSend();
                        return;
                    }
                    authoredFacts = related.merge(supplied);
                }
            } else if (authoring != null && authoring.mode() != SkillAuthoring.Mode.DESIGN
                    && (skillDraftFacts != null || pollSeconds != 0)) {
                SkillVideoFacts facts = skillDraftFacts == null ? SkillVideoFacts.empty() : skillDraftFacts;
                authoredFacts = pollSeconds == 0 ? facts : facts.withMinimumPollSeconds(pollSeconds);
            } else {
                authoredFacts = SkillVideoFacts.empty();
            }
            AiSkills.Invocation slash = selectedPackage != null ? selectedPackage
                    : authoring != null || skillEntry == null || retryAnswer != null
                    ? null : AiSkills.match(skillEntry, commandText, skillStore, subAgent.enabled());
            boolean named = SkillSelection.couldBeNamed(commandText);
            if (!selectionDone && approvedService == null && resolved == null && authoring == null && slash == null
                    && retryAnswer == null && skillEntry != null && skillStore != null
                    && images.isEmpty() && documents.isEmpty() && selected.isEmpty()
                    && (named || SkillSelection.looksLikeTask(commandText)) && skillStore.enabled()) {
                List<SkillSelection.Candidate> catalog = new ArrayList<>(skillStore.selectionCatalog());
                catalog.addAll(toolPackages().modelCandidates());
                if (named) {
                    SkillSelection.Named local = SkillSelection.named(commandText, catalog, skillStore.unavailableSelectionNames());
                    if (local.kind() == SkillSelection.NamedKind.SELECTED) {
                        resolved = local.choice();
                    } else if (local.kind() != SkillSelection.NamedKind.NONE) {
                        state.setText(switch (local.kind()) {
                            case AMBIGUOUS -> "匹配到多个技能，请使用斜杠命令指定一个技能";
                            case UNAVAILABLE -> "此技能不可用，请在技能设置中核对配置";
                            default -> "请输入此技能所需的参数";
                        });
                        updateSend();
                        return;
                    }
                }
                if (resolved == null) {
                    SkillSelection.Request selection = SkillSelection.request(commandText, catalog);
                    if (selection != null) {
                        startSkillSelection(prompt, selection);
                        return;
                    }
                }
            }
            if (resolved != null && resolved.candidate().packageItem() != null
                    && resolved.candidate().packageItem().kind() == AiToolPackages.Kind.MCP) {
                confirmMcpActivation(resolved.candidate().packageItem(), resolved.argument());
                return;
            }
            skill = resolved == null ? slash : rematchSkillChoice(resolved);
            if (resolved != null && skill == null) {
                state.setText("技能已变化，请重新选择");
                updateSend();
                return;
            }
        } catch (AiSkills.CommandUnavailable unavailable) {
            state.setText(unavailable.getMessage());
            updateSend();
            return;
        } catch (IllegalStateException unavailable) {
            state.setText("技能目录无法访问");
            updateSend();
            return;
        } catch (IllegalArgumentException invalid) {
            state.setText("接口资料无效，请核对接口字段并移除密钥");
            updateSend();
            return;
        }
        if (authoring != null || skill != null) {
            String invalid = null;
            if (!images.isEmpty() || !documents.isEmpty() || !selected.isEmpty()) {
                invalid = authoring != null ? "技能设计只接收输入框里的文字，请先移除附件"
                        : "此技能只接收文本，请先移除附件";
            } else if (authoring != null && authoring.mode() == SkillAuthoring.Mode.SAVE && previousDraft == null) {
                invalid = "当前没有可保存的技能草案";
            } else if (skill != null && skill.argument().isEmpty()) {
                invalid = "请在 /" + skill.command().name() + " 后输入参数";
            }
            if (invalid != null) {
                state.setText(invalid);
                updateSend();
                return;
            }
        }
        SkillResult.Type explicitOutput = authoring == null ? null : SkillAuthoring.explicitOutput(authoring.request());
        boolean revising = authoring != null && authoring.mode() == SkillAuthoring.Mode.REVISE && previousDraft != null;
        boolean modelAuthoring = authoring != null && (revising || authoring.mode() == SkillAuthoring.Mode.DESIGN);
        SkillResult.Type requiredOutput = revising ? previousDraft.outputType() : explicitOutput;
        boolean customRequired = authoring != null && requiredOutput != SkillResult.Type.VIDEO
                && (revising ? SkillCapabilityRegistry.Capability.CUSTOM_SERVICE.id().equals(previousDraft.capabilityId())
                : SkillAuthoring.explicitlyCustomService(authoring.request()));
        final String authoringInput;
        try {
            if ((authoring != null && authoring.mode() != SkillAuthoring.Mode.SUPPLEMENT) || skill != null) {
                SkillAuthoring.rejectCredential(videoSource == null ? prompt : videoSource.command());
            }
            String prior = revising
                    ? previousDraft.outputType() == SkillResult.Type.VIDEO
                    ? SkillStore.prepareAuthoredVideoDraft(previousDraft.definition(), SkillVideoFacts.empty()).definition()
                    : SkillStore.sanitizeModelAuthoringDefinition(previousDraft.definition()) : null;
            authoringInput = modelAuthoring
                    ? SkillAuthoring.draftInput(authoring.request(), prior, requiredOutput, customRequired) : null;
        } catch (IllegalArgumentException invalid) {
            state.setText("技能需求包含不支持的内容或超过长度");
            updateSend();
            return;
        }
        if (skill != null && skill.command().requiresReview() && approvedService == null) {
            showCustomServiceReview(skill, prompt, resolved, selectedPackage);
            return;
        }
        if (approvedService != null && (skill == null || !skill.command().requiresReview())) {
            state.setText("技能配置已变化，请重新确认");
            return;
        }
        cancelRestore();
        String displayedPrompt = authoring != null && authoring.mode() == SkillAuthoring.Mode.SUPPLEMENT
                && authoring.request().isEmpty() && selectedMaterial == null
                ? "补充视频接口资料" : videoSource == null ? prompt : videoSource.command();
        ChatMessage answer = retryAnswer;
        if (answer == null) {
            ChatMessage user = addMessage("我", displayedPrompt, true, images);
            for (Document document : documents) user.documentLabels.add(document.name);
            for (PendingAttachment attachment : selected) user.documentLabels.add(attachment.name);
            answer = addMessage(assistantName, "正在回复…", false, Collections.emptyList());
            answer.sourceUser = user;
            trimVisibleHistory();
        }
        answer.text = authoring != null ? authoringProgress(authoring) : skill != null ? "正在运行技能…"
                : selected.isEmpty() ? "正在回复…" : "正在读取附件…";
        answer.copyText = null;
        if (answer.videos != null) { answer.videos.close(); answer.videos = null; }
        answer.retry = false;
        messageRows.notifyDataSetChanged();
        if (prompt.equals(input.getText().toString())) input.setText("");
        if (draftImages.equals(images)) draftImages.clear();
        if (draftDocuments.equals(documents)) draftDocuments.clear();
        draftPending.removeAll(selected);
        renderAttachments();
        int ticket = ++generation;
        Session active = session;
        SkillStore activeSkillStore = skillStore;
        long delegationDeadline = skill != null && skill.delegated()
                ? System.nanoTime() + AiSubAgents.TIME_LIMIT_NANOS : 0;
        Future<?> clearBarrier = historyClear;
        RequestTurn turn = new RequestTurn(ticket, displayedPrompt, images, documents, selected, answer, skill, authoring,
                com.douai.module.analytics.LocalAnalytics.get(getContext()).beginOperation(null, "ai"));
        turn.videoFacts = authoredFacts;
        turn.entry = skillEntry;
        pending = turn;
        busy = true;
        state.setText(answer.text);
        updateSend();
        scrollToBottom();
        turn.trace.stage("queued", "input_chars=" + prompt.length() + ";images=" + images.size() + ";documents=" + documents.size() + ";attachments=" + selected.size());
        request = worker.submit(turn.trace.wrap(() -> {
            String reply = null;
            ChatImage generatedImage = null;
            SkillResult artifact = null;
            SkillStore.DraftPreview authored = null;
            Exception error = null;
            try {
                synchronized (turn) {
                    if (turn.stopRequested) throw new java.util.concurrent.CancellationException();
                    turn.started = true;
                }
                if (clearBarrier != null) clearBarrier.get();
                turn.checkStopped();
                if (turn.manualSkill()) {
                    try {
                        TestChatMemory memory = active.transcriptMemory();
                        if (memory != null) {
                            turn.transcriptEpoch = memory.epoch();
                            turn.transcriptMemory = memory;
                        }
                    } catch (RuntimeException unavailable) {
                        turn.transcriptUnavailable = true;
                        turn.trace.stage("local_skill_history", "outcome=unavailable");
                    }
                }
                if (!selected.isEmpty()) {
                    turn.trace.stage("media_read", "attachments=" + selected.size());
                    turn.reading = true;
                    prepareMedia(turn);
                    turn.reading = false;
                    main.post(() -> showPreparedMedia(turn));
                }
                turn.checkStopped();
                if (authoring != null) {
                    turn.trace.stage("skill_authoring", "input_chars=" + prompt.length());
                    if (!activeSkillStore.enabled()) throw new IllegalStateException("技能已停用");
                    if (authoring.mode() == SkillAuthoring.Mode.OPEN) {
                        turn.openedSkill = activeSkillStore.openConversationDraft(authoring.request());
                        authored = turn.openedSkill.preview();
                    } else if (fieldPatch != null) {
                        String raw = askSkillModel(turn, active, SkillAuthoringPatch.INSTRUCTION, fieldPatch.input());
                        turn.checkStopped();
                        Map<String, Object> updates = fieldPatch.read(raw);
                        if (updates.isEmpty()) throw new IllegalArgumentException("没有可核对的字段变更");
                        authored = SkillStore.applyConversationFields(previousDraft, updates);
                    } else if (authoring.mode() == SkillAuthoring.Mode.SUPPLEMENT
                            || authoring.mode() == SkillAuthoring.Mode.REPLACE_VIDEO_SOURCE) {
                        String definition = authoring.mode() == SkillAuthoring.Mode.REPLACE_VIDEO_SOURCE
                                ? SkillStore.sanitizeModelAuthoringDefinition(previousDraft.definition())
                                : previousDraft.definition();
                        authored = SkillStore.prepareAuthoredVideoDraft(definition, authoredFacts);
                    } else if (!modelAuthoring) {
                        authored = previousDraft;
                    } else {
                        String raw = SkillStore.sanitizeModelAuthoringDefinition(
                                askSkillModel(turn, active, SkillAuthoring.DESIGN_INSTRUCTION, authoringInput));
                        turn.checkStopped();
                        SkillStore.DraftPreview validated = requiredOutput == SkillResult.Type.VIDEO
                                ? SkillStore.prepareAuthoredVideoDraft(raw, authoredFacts) : SkillStore.validateAuthoredDraft(raw);
                        if (validated.outputType() == SkillResult.Type.VIDEO && requiredOutput != SkillResult.Type.VIDEO) {
                            validated = SkillStore.prepareAuthoredVideoDraft(raw, authoredFacts);
                        }
                        if (requiredOutput != null && validated.outputType() != requiredOutput) {
                            throw new OutputMismatch(requiredOutput, validated.outputType());
                        }
                        if (customRequired && !SkillCapabilityRegistry.Capability.CUSTOM_SERVICE.id().equals(validated.capabilityId())) {
                            throw new ServiceCapabilityMismatch();
                        }
                        if (revising && !previousDraft.name().equals(validated.name())) {
                            throw new IllegalArgumentException("修订必须保留当前技能命令名");
                        }
                        authored = revising && requiredOutput == SkillResult.Type.VIDEO
                                ? applyFocusedVideoRevision(previousDraft, validated, authoring.request(), authoredFacts)
                                : validated;
                        if (revising && requiredOutput != SkillResult.Type.VIDEO && !previousDraft.publicFields().isEmpty()) {
                            authored = SkillStore.applyConversationFields(authored, previousDraft.publicFields());
                        }
                    }
                    turn.checkStopped();
                } else if (turn.skill != null) {
                    turn.trace.stage("skill_execute", "input_chars=" + turn.skill.argument().length());
                    if (turn.skill.delegated()) {
                        if (!activeSkillStore.enabled() || !subAgent.equals(AiSubAgents.read(selectedSettings))) {
                            throw new IllegalStateException("子代理设置已变化");
                        }
                        reply = AiSubAgents.run(AiModelSettings.read(selectedSettings), subAgent,
                                turn.skill.argument(), delegationDeadline);
                    } else {
                        SkillResult result;
                        if (turn.skill.command().requiresReview()) {
                            SkillStore.VideoTurn video = turn.skill.command().outputType() == SkillResult.Type.VIDEO
                                    ? prepareVideoTurn(turn) : null;
                            result = activeSkillStore.executeCustomService(turn.skill, approvedService,
                                    System.nanoTime() + TimeUnit.SECONDS.toNanos(30), video);
                        } else result = AiSkills.executeResult(turn.skill, activeSkillStore, new AiSkills.TextExecutor() {
                            @Override public String run(String instruction, String input) throws Exception {
                                return runConfigured(instruction, input, Map.of());
                            }

                            @Override public String runConfigured(String instruction, String input,
                                                                  Map<String, Object> settings) throws Exception {
                                String system = turn.skill.command().outputType() == SkillResult.Type.TABLE
                                        ? SkillAuthoring.TABLE_INSTRUCTION : SkillAuthoring.TEXT_INSTRUCTION;
                                return askSkillModel(turn, active, system,
                                        SkillAuthoring.textInput(instruction, input, settings));
                            }

                            @Override public String runPackage(String instruction, String input) throws Exception {
                                synchronized (turn) {
                                    turn.checkStopped();
                                    turn.usingSkillModel = true;
                                }
                                try { return active.packageModel(instruction, input); }
                                finally { turn.usingSkillModel = false; }
                            }
                        });
                        if (result instanceof SkillResult.Image image) {
                            generatedImage = image.image();
                            reply = "";
                        } else if (result instanceof SkillResult.Text text) {
                            reply = text.text();
                        } else if (result instanceof SkillResult.Table || result instanceof SkillResult.Video) {
                            artifact = result;
                            reply = "";
                        } else {
                            throw new IllegalStateException("技能返回了未知的结果类型");
                        }
                    }
                    turn.checkStopped();
                } else {
                    turn.trace.stage("provider_request", "images=" + turn.preparedImages.size() + ";documents=" + turn.preparedDocuments.size());
                    reply = active.replyStreaming(prompt, turn.preparedImages,
                            documentText(turn.preparedDocuments, turn.preparedImages), text -> queuePartialReply(turn, text));
                    turn.checkStopped();
                    try {
                        turn.searchResults = AiSearchVideoCards.snapshot(active.searchResults());
                        turn.searchBridge = active.searchBridge();
                    } catch (RuntimeException unavailable) {
                        turn.searchResults = Collections.emptyList();
                        turn.searchBridge = null;
                    }
                }
            }
            catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                error = cancelled;
            }
            catch (Exception failure) { error = failure; }
            catch (OutOfMemoryError failure) { error = new IllegalArgumentException("附件过大，无法读取"); }
            String result = reply;
            ChatImage image = generatedImage;
            SkillResult localArtifact = artifact;
            SkillStore.DraftPreview draft = authored;
            Exception failure = error;
            main.post(turn.trace.wrap(() -> finishRequest(turn, result, image, localArtifact, draft, failure)));
        }));
    }

    private AiSkills.Invocation rematchSkillChoice(SkillSelection.Choice choice) {
        if (skillEntry == null || skillStore == null) return null;
        SkillSelection.Candidate candidate = choice.candidate();
        if (candidate.packageItem() != null && candidate.packageItem().kind() == AiToolPackages.Kind.MARKDOWN) {
            return toolPackages().markdownInvocation(candidate.packageItem(), choice.argument());
        }
        AiSkills.Invocation current;
        try {
            current = AiSkills.match(skillEntry, "/" + candidate.name() + " " + choice.argument(), skillStore);
        } catch (AiSkills.CommandUnavailable unavailable) {
            return null;
        }
        return current != null && current.version() == candidate.version() && candidate.revisionId().equals(current.revisionId())
                && current.command().name().equals(candidate.name()) && current.argument().equals(choice.argument()) ? current : null;
    }

    private void startSkillSelection(String prompt, SkillSelection.Request selection) {
        SkillStore catalog = skillStore;
        AiSkills.Entry entry = skillEntry;
        Session active = session;
        RequestTurn turn = new RequestTurn(++generation, prompt, Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), null, null, null, null);
        pending = turn;
        busy = true;
        state.setText("正在选择技能…");
        updateSend();
        turn.trace.stage("skill_selection", "input_chars=" + prompt.length());
        request = worker.submit(turn.trace.wrap(() -> {
            SkillSelection.Choice selected = null;
            try {
                synchronized (turn) {
                    turn.checkStopped();
                    turn.started = true;
                }
                selected = selection.read(askSkillModel(turn, active, SkillSelection.INSTRUCTION, selection.input()));
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
            } catch (Exception unavailable) {
                turn.trace.stage("skill_selection", "outcome=unavailable");
            }
            SkillSelection.Choice result = selected;
            main.post(turn.trace.wrap(() -> finishSkillSelection(turn, catalog, entry, result)));
        }));
    }

    private void finishSkillSelection(RequestTurn turn, SkillStore catalog, AiSkills.Entry entry, SkillSelection.Choice choice) {
        if (!isCurrentTurn(turn)) return;
        pending = null;
        busy = false;
        request = null;
        if (turn.stopRequested || skillStore != catalog || skillEntry != entry) {
            turn.trace.finish(DouAiLog.Outcome.CANCELLED, "skill_selection_cancelled", "");
            state.setText(turn.stopRequested ? "已停止" : "技能入口已变化，请重新选择");
            updateSend();
            return;
        }
        turn.trace.finish(DouAiLog.Outcome.SUCCESS, choice == null ? "skill_selection_none" : "skill_selection_selected", "");
        state.setText("");
        updateSend();
        sendMessage(turn.prompt, Collections.emptyList(), Collections.emptyList(), null, null, choice, true);
    }

    private static String authoringProgress(SkillAuthoring.Action action) {
        switch (action.mode()) {
            case OPEN: return "正在打开技能…";
            case SAVE: return "正在保存技能…";
            case REVISE: return "正在修改技能草案…";
            case FIELD_PATCH: return "正在回填草稿字段…";
            default: return "正在设计技能草案…";
        }
    }

    private static String askSkillModel(RequestTurn turn, Session active, String system, String input) throws Exception {
        synchronized (turn) {
            turn.checkStopped();
            turn.usingSkillModel = true;
        }
        try { return active.skillModel(system, input); }
        finally { turn.usingSkillModel = false; }
    }

    private static SkillStore.DraftPreview applyFocusedVideoRevision(
            SkillStore.DraftPreview previous, SkillStore.DraftPreview proposal, String request, SkillVideoFacts facts)
            throws org.json.JSONException {
        SkillAuthoring.FieldEdit change = SkillAuthoring.fieldEdit(localDraftField(request, previous.name()));
        String property = change == null ? "instructions" : switch (change.label().trim()) {
            case "简介", "技能简介" -> "description";
            case "使用说明", "文档" -> "documentation";
            case "显示名称", "显示名" -> "displayName";
            case "输入", "输入限制", "输入长度" -> "inputs";
            default -> "instructions";
        };
        org.json.JSONObject original = new org.json.JSONObject(previous.definition());
        org.json.JSONObject edited = new org.json.JSONObject(proposal.definition());
        if (edited.has(property)) original.put(property, edited.get(property));
        return SkillStore.prepareAuthoredVideoDraft(original.toString(), facts);
    }

    private void queuePartialReply(RequestTurn turn, String text) {
        if (text == null || text.isEmpty()) return;
        synchronized (turn) {
            if (turn.stopRequested) return;
            turn.latestPartial = text;
            if (turn.partialQueued) return;
            turn.partialQueued = true;
        }
        main.post(() -> {
            String latest;
            synchronized (turn) {
                latest = turn.latestPartial;
                turn.partialQueued = false;
            }
            if (disposed || !busy || pending != turn || turn.ticket != generation || turn.stopRequested) return;
            if (latest.equals(turn.answer.text)) return;
            boolean follow = messages.getLastVisiblePosition() >= messages.getCount() - 2;
            turn.answer.text = latest;
            state.setText("正在回复…");
            messageRows.notifyDataSetChanged();
            if (follow) scrollToBottom();
        });
    }

    private void prepareMedia(RequestTurn turn) throws Exception {
        List<LoadedMedia> loaded = new ArrayList<>();
        boolean[] reserveSlots = new boolean[turn.attachments.size()];
        for (int index = 0; index < reserveSlots.length; index++) {
            turn.checkStopped();
            reserveSlots[index] = turn.attachments.get(index).mayNeedImages(getContext());
        }
        for (int index = 0; index < turn.attachments.size(); index++) {
            turn.checkStopped();
            PendingAttachment attachment = turn.attachments.get(index);
            int remaining = ChatCompletionClient.MAX_IMAGES_PER_MESSAGE - turn.preparedImages.size();
            int reserve = 0;
            for (int next = index + 1; next < turn.attachments.size(); next++) {
                if (reserveSlots[next]) reserve++;
            }
            int budget = Math.max(0, remaining - reserve);
            if (attachment.needsImages() && budget == 0)
                throw new IllegalArgumentException("每条消息最多包含 4 张图片或视频抽样画面，请减少附件");
            LoadedMedia media = attachment.prepared;
            // A restored draft can gain another image after a provider failure.
            // Re-read only when the cached sampling no longer fits its new budget.
            if (media != null && media.images.size() > budget) media = null;
            if (media == null) {
                long started = android.os.SystemClock.elapsedRealtime();
                boolean completed = false;
                DouAiLog.legacy(4, "DouAi.ChatAttachment", "read_started kind=" + attachment.kind + " imageBudget=" + budget, null);
                try {
                    media = mediaLoader.load(getContext(), attachment.uri, attachment.kind, budget);
                    completed = media != null;
                } finally {
                    DouAiLog.legacy(4, "DouAi.ChatAttachment", "read_finished kind=" + attachment.kind
                            + " completed=" + completed + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - started), null);
                }
            }
            turn.checkStopped();
            if (media == null) throw new IllegalArgumentException("附件读取结果为空");
            if (media.images.size() > budget)
                throw new IllegalArgumentException("每条消息最多包含 4 张图片或视频抽样画面，请减少附件");
            turn.preparedImages.addAll(media.images);
            if (media.text != null && !media.text.isEmpty()) {
                if (turn.preparedDocuments.size() >= 4)
                    throw new IllegalArgumentException("每条消息最多添加 4 个文件或视频说明");
                turn.preparedDocuments.add(new Document(media.name, media.text, media.images));
            }
            if (documentText(turn.preparedDocuments, turn.preparedImages).length() > 32768)
                throw new IllegalArgumentException("附件文本总计不能超过 32768 个字符");
            loaded.add(media);
        }
        // Cache only a complete preparation. Provider retries can reuse it without another video read.
        for (int index = 0; index < loaded.size(); index++) turn.attachments.get(index).prepared = loaded.get(index);
    }

    private void showPreparedMedia(RequestTurn turn) {
        if (disposed || turn.ticket != generation) {
            turn.trace.finish(DouAiLog.Outcome.CANCELLED, "conversation_invalidated", "late_response=true"); return;
        }
        ChatMessage user = turn.answer.sourceUser;
        if (user != null && transcript.contains(user)) {
            visibleImageBytes -= imageBytes(user.images);
            user.images.clear();
            user.images.addAll(turn.preparedImages);
            visibleImageBytes += imageBytes(user.images);
            user.documentLabels.clear();
            for (Document document : turn.preparedDocuments) user.documentLabels.add(document.name);
            // Image selections have no text document, so their preview is the sent attachment.
            trimVisibleHistory();
        }
        if (busy && pending == turn && !turn.stopRequested) {
            turn.answer.text = "正在回复…";
            state.setText("正在回复…");
        }
        messageRows.notifyDataSetChanged();
        updateSend();
    }

    private void trimVisibleHistory() {
        while (transcript.size() > 2 && (transcript.size() / 2 > MAX_VISIBLE_TURNS
                || visibleImageBytes > MAX_VISIBLE_IMAGE_BYTES)) {
            ChatMessage first = transcript.remove(0);
            ChatMessage second = transcript.remove(0);
            visibleImageBytes -= imageBytes(first.images) + imageBytes(second.images);
            if (first.videos != null) first.videos.close();
            if (second.videos != null) second.videos.close();
        }
        evictUnusedThumbnails();
    }

    private void finishRequest(RequestTurn turn, String reply, ChatImage generatedImage, SkillResult artifact,
                               SkillStore.DraftPreview draft, Exception failure) {
        if (!isCurrentTurn(turn)) return;
        if (turn.authoring != null && !turn.stopRequested && failure == null) {
            try {
                reply = completeSkillAuthoring(turn, draft);
                if (turn.authoring.mode() != SkillAuthoring.Mode.CANCEL) artifact = declarationArtifact(draft);
            } catch (Exception cannotComplete) {
                failure = cannotComplete;
            }
        }
        boolean clearedMismatchedEditor = !turn.stopRequested && failure instanceof OutputMismatch mismatch
                && turn.authoring != null && turn.authoring.mode() == SkillAuthoring.Mode.DESIGN
                && skillDraft != null && skillDraft.outputType() != mismatch.expected;
        if (clearedMismatchedEditor) clearAuthoringDraft(turn);
        HistorySave history = HistorySave.NOT_REQUESTED;
        if (turn.manualSkill() && !turn.stopRequested && (failure == null || clearedMismatchedEditor)) {
            if (turn.transcriptMemory != null && turn.transcriptMemory.historyEnabled()
                    && (failure == null || storedSkillDraft != null)) {
                saveSkillTranscript(turn, reply, generatedImage, artifact, failure);
                return;
            }
            if (turn.transcriptUnavailable) history = HistorySave.UNCONFIRMED;
            else if (turn.transcriptMemory != null) history = HistorySave.DISABLED;
        }
        showRequestResult(turn, reply, generatedImage, artifact, failure, history);
    }

    private boolean isCurrentTurn(RequestTurn turn) {
        return !disposed && pending == turn && turn.ticket == generation;
    }

    private static SkillResult.Declaration declarationArtifact(SkillStore.DraftPreview draft) {
        try { return new SkillResult.Declaration(draft.definition()); }
        catch (IllegalArgumentException invalid) { return null; }
    }

    private enum HistorySave { NOT_REQUESTED, DISABLED, SAVED, UNCONFIRMED }
    private static final class VideoHistoryRequired extends IllegalStateException { }

    private SkillStore.VideoTurn prepareVideoTurn(RequestTurn turn) throws Exception {
        SkillVideoJobs.Scope scope = videoScope(turn.transcriptMemory, turn.entry);
        if (scope == null) throw new VideoHistoryRequired();
        long epoch = skillVideoJobs.generation(scope);
        turn.checkStopped();
        if (turn.discardHistory) throw new InterruptedException("会话已清空");
        turn.videoScope = scope;
        return new SkillStore.VideoTurn(skillVideoJobs, scope, epoch, UUID.randomUUID());
    }

    private static SkillVideoJobs.Scope videoScope(TestChatMemory memory, AiSkills.Entry entry) {
        return entry == null || memory == null || !memory.historyEnabled() ? null
                : new SkillVideoJobs.Scope(entry, memory.conversationKey());
    }

    private void watchVideoCards() {
        if (videoScope == null) return;
        List<UUID> visible = new ArrayList<>();
        for (ChatMessage message : transcript) {
            if (message.artifact instanceof SkillResult.Video video) visible.add(video.requestId());
        }
        skillVideoCards.watch(videoScope, visible);
    }

    private void showVideoStatus(String message) { if (!disposed && !busy) state.setText(message); }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        skillVideoCards.setForeground(focused && !disposed);
    }

    private void showRequestResult(RequestTurn turn, String reply, ChatImage generatedImage, SkillResult artifact,
                                   Exception failure, HistorySave history) {
        if (!isCurrentTurn(turn)) return;
        busy = false;
        request = null;
        pending = null;
        ChatMessage answer = turn.answer;
        if (failure == null && (!turn.manualSkill() || !turn.stopRequested)) {
            answer.text = reply;
            answer.copyText = turn.authoring == null ? reply : null;
            answer.color = TEXT;
            answer.artifact = artifact;
            if (artifact instanceof SkillResult.Video) videoScope = turn.videoScope;
            if (turn.manualSkill()) answer.settingsSkillName = turn.settingsSkillName;
            if (generatedImage != null) {
                answer.images.add(generatedImage);
                visibleImageBytes += generatedImage.sizeBytes();
                trimVisibleHistory();
            }
            if (!turn.manualSkill() && !turn.stopRequested) {
                answer.videos = searchVideoCards.group(turn.searchResults, turn.searchBridge, turn.analytics);
            }
            state.setText(switch (history) {
                case UNCONFIRMED -> artifact instanceof SkillResult.Video
                        ? "视频任务已记录，聊天历史暂未写入。重开会话后可恢复任务。" : HISTORY_UNCONFIRMED;
                case DISABLED -> "聊天历史已关闭，结果仅保留在当前会话。";
                default -> "";
            });
        } else if (turn.manualSkill()) {
            answer.text = turn.authoring != null && !turn.stopRequested ? authoringFailure(turn.authoring, failure)
                    : skillFailure(turn.stopRequested, failure);
            if (!turn.stopRequested && turn.authoring != null && skillDraft != null) {
                answer.text += "\n仍在编写 /" + skillDraft.name();
            }
            answer.color = turn.stopRequested ? MUTED : ERROR;
            state.setText(answer.text);
            if (turn.skill != null && turn.skill.localBuiltIn()) {
                restoreDraft(turn.prompt, Collections.emptyList(), Collections.emptyList());
            }
            if (failure != null) {
                String code = failure instanceof SkillVideoJobs.Failure job ? ";code=" + job.code().name()
                        : failure instanceof SkillCustomService.Failure service ? ";code=" + service.code().name() : "";
                turn.trace.stage(turn.authoring != null ? "skill_authoring" : "skill_execute",
                        "error_type=" + failure.getClass().getSimpleName() + code);
            }
        } else {
            answer.text = turn.stopRequested ? "已停止" : turn.reading
                    ? "附件读取失败：" + failureDetail(failure) : errorMessage(failure);
            if (!turn.reading && !turn.latestPartial.isEmpty()) {
                answer.text = turn.latestPartial + "\n\n（" + answer.text + "，回复未完成）";
                answer.copyText = turn.latestPartial;
            }
            answer.color = turn.stopRequested ? MUTED : ERROR;
            state.setText(turn.stopRequested ? "已停止" : turn.reading ? "附件读取失败" : "发送失败");
            restoreDraft(turn.prompt, turn.images, turn.documents);
            addRetry(answer, turn.prompt, turn.images, turn.documents);
            if (draftPending.isEmpty()) draftPending.addAll(turn.attachments);
            answer.retryPending = new ArrayList<>(turn.attachments);
            renderAttachments();
            turn.trace.error(turn.reading ? "media_read" : "provider_request", failure);
        }
        messageRows.notifyDataSetChanged();
        if (artifact instanceof SkillResult.Video) watchVideoCards();
        updateSend();
        scrollToBottom();
        turn.trace.finish(turn.stopRequested ? DouAiLog.Outcome.CANCELLED : failure == null ? DouAiLog.Outcome.SUCCESS
                        : failure instanceof java.net.SocketTimeoutException ? DouAiLog.Outcome.TIMED_OUT : DouAiLog.Outcome.FAILED,
                turn.stopRequested ? "user_cancelled" : failure == null ? "response_rendered" : "request_failed",
                "response_chars=" + (reply == null ? 0 : reply.length()) + ";view_notified=true;remote_delivery=not_applicable"
                        + ";history=" + history.name());
    }

    private void saveSkillTranscript(RequestTurn turn, String reply, ChatImage generatedImage, SkillResult artifact,
                                     Exception failure) {
        TestChatMemory memory = turn.transcriptMemory;
        turn.savingHistory = true;
        request = null;
        turn.answer.text = SAVING_HISTORY;
        state.setText(SAVING_HISTORY);
        messageRows.notifyDataSetChanged();
        updateSend();
        String savedAnswer = reply == null ? "" : reply;
        if (turn.authoring != null && savedAnswer.endsWith(DRAFT_CONTINUATION)) {
            savedAnswer = savedAnswer.substring(0, savedAnswer.length() - DRAFT_CONTINUATION.length());
        }
        TestChatMemory.SkillDraft expectedDraft = storedSkillDraft;
        TestChatMemory.SkillDraft nextDraft = turn.editorChanged ? authoringSnapshot() : expectedDraft;
        try {
            TestChatMemory.Turn saved = failure == null ? TestChatMemory.Turn.displayedSkill(turn.prompt, savedAnswer,
                    generatedImage == null ? Collections.emptyList() : Collections.singletonList(generatedImage),
                    turn.settingsSkillName, artifact) : null;
            historyWorker.execute(() -> {
                if (turn.discardHistory) return;
                HistorySave result;
                boolean draftWritten = !turn.editorChanged;
                try {
                    if (turn.editorChanged) {
                        draftWritten = memory.writeSkillDraft(turn.transcriptEpoch, expectedDraft, nextDraft);
                    }
                    boolean written = saved == null ? draftWritten : memory.append(turn.transcriptEpoch, saved);
                    result = written ? HistorySave.SAVED : HistorySave.UNCONFIRMED;
                    if (result == HistorySave.SAVED && artifact instanceof SkillResult.Video video && turn.videoScope != null) {
                        try { skillVideoJobs.markLinked(turn.videoScope, video.requestId()); }
                        catch (SkillVideoJobs.Failure ignored) { /* Reopen can link the journal and existing row. */ }
                    }
                } catch (RuntimeException | OutOfMemoryError unavailable) {
                    result = HistorySave.UNCONFIRMED;
                }
                boolean storedDraft = draftWritten;
                boolean written = result == HistorySave.SAVED;
                DouAiLog.event("ai_chat", saved == null ? "skill_draft_write" : "skill_history_write",
                        written ? "saved" : "not_saved", "scope=local_display");
                main.post(turn.trace.wrap(() -> {
                    if (!isCurrentTurn(turn)) return;
                    if (turn.editorChanged && storedDraft) storedSkillDraft = nextDraft;
                    showRequestResult(turn, reply, generatedImage, artifact, failure, saved == null
                            ? HistorySave.NOT_REQUESTED : written ? HistorySave.SAVED : HistorySave.UNCONFIRMED);
                    if (!storedDraft) showDraftPersistenceWarning();
                }));
            });
        } catch (RuntimeException unavailable) {
            showRequestResult(turn, reply, generatedImage, artifact, failure, HistorySave.UNCONFIRMED);
            if (turn.editorChanged) showDraftPersistenceWarning();
        }
    }

    private void showDraftPersistenceWarning() {
        state.setText("技能编写进度暂未保存到本机，重开前请先核对当前草稿。");
        updateSend();
    }

    private TestChatMemory.SkillDraft authoringSnapshot() {
        if (skillDraft == null) return null;
        return new TestChatMemory.SkillDraft(skillDraft.definition(),
                skillDraftFacts == null ? "" : skillDraftFacts.toJson().toString(),
                pendingSkillDraftId.isEmpty() ? "" : skillDraft.name(), pendingSkillDraftId, skillAuthoringId,
                skillEditorSourceProof, skillEditorReplacesVideo);
    }

    private record VideoReferenceTarget(String name, SkillStore.Detail saved, SkillStore.DraftPreview preview,
                                        SkillVideoFacts previousFacts) {
        String label() { return "/" + name + (saved == null ? " · 当前聊天草案" : " · 已保存草稿"); }
    }

    private record VideoReferenceMaterial(ChatMessage message, SkillVideoFacts facts, String label) { }

    private static SkillVideoFacts applyNaturalVideoEdit(SkillVideoFacts prior, SkillVideoEdit.Edit edit) {
        if (!edit.queryMode().isEmpty() && !prior.queryMode().isEmpty() && !prior.queryMode().equals(edit.queryMode())
                && !edit.replaceQueryMode()) throw new IllegalArgumentException("查询方式已有不同的值");
        SkillVideoFacts facts = prior.applyGroundedPatch(edit.fields(), edit.stateMappings(), edit.queryMode(), edit.replacements());
        return edit.mode() == SkillVideoEdit.Mode.UNSUPPORTED_ENCODING
                ? facts.withForm(Map.of("submitEncoding", edit.submissionEncoding())) : facts;
    }

    private void showAuthoringStatus(String message) {
        state.setText(message);
        updateSend();
    }

    private void sendAuthoringMaterial(String prompt, SkillAuthoring.Action action, SkillVideoFacts facts) {
        sendMessage(prompt, List.of(), List.of(), null, null, null, false, new AuthoringMaterial(action, facts));
    }

    private static String videoDocumentationCommand(String command) {
        String prefix = "/技能";
        if (!command.startsWith(prefix)) return null;
        String argument = command.substring(prefix.length()).trim();
        String verb = "读取接口文档";
        if (argument.equals(verb)) return "";
        if (!argument.startsWith(verb + " ")) return null;
        return argument.substring(verb.length()).trim();
    }

    private void confirmOpenApiVideo(String prompt, SkillAuthoring.Action action, String link) {
        final String endpoint;
        try {
            SkillAuthoring.rejectEditorCredential(prompt);
            endpoint = SkillDocumentationClient.normalize(link);
        } catch (IllegalArgumentException invalid) {
            showAuthoringStatus("请提供公开 HTTPS OpenAPI JSON 直链，密钥请在技能设置填写");
            return;
        }
        SkillStore.DraftPreview expectedDraft = skillDraft;
        int expectedGeneration = generation;
        if (localMenu != null) localMenu.dismiss();
        localMenu = new MiuixDialogBuilder(getContext(), useMiuix).setTitle("读取接口文档")
                .setMessage(endpoint + "\n只读取公开文档，不会访问视频生成接口。")
                .setNegativeButton("取消", null).setPositiveButton("读取文档", (dialog, which) -> {
                    if (dialog == localMenu && !disposed && !busy && videoDocumentTask == null && generation == expectedGeneration
                            && skillDraft == expectedDraft) loadOpenApiVideo(prompt, action, endpoint);
                }).create();
        localMenu.show();
    }

    private void loadOpenApiVideo(String prompt, SkillAuthoring.Action action, String endpoint) {
        Session active = session;
        SkillStore.DraftPreview expectedDraft = skillDraft;
        int expectedGeneration = generation;
        videoDocumentTask = worker.submit(() -> {
            SkillVideoFacts parsed;
            try {
                String document = active.readSkillDocumentation(endpoint, System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
                parsed = SkillOpenApiVideo.read(document);
            } catch (Exception unavailable) {
                parsed = null;
            }
            SkillVideoFacts result = parsed;
            main.post(() -> {
                if (disposed || generation != expectedGeneration) return;
                videoDocumentTask = null;
                updateSend();
                if (skillDraft != expectedDraft || !skillStore.enabled()) return;
                if (result == null) showAuthoringStatus("未能读取所选文档。请核对 JSON 直链，或贴提交和查询的示例。");
                else if (result.isEmpty()) showAuthoringStatus("文档中未找到一套可核对的视频提交、状态和成片接口。请补充对应示例。");
                else sendAuthoringMaterial(prompt, action, result);
            });
        });
        showAuthoringStatus("正在读取接口文档…");
    }

    private record DraftFieldSource(String label, SkillAuthoringPatch.Request patch) { }

    private static SkillManifest authoringManifest(SkillStore.DraftPreview preview) {
        return preview.capabilityId().isEmpty() ? null : SkillManifest.parse(preview.definition());
    }

    private boolean chooseDraftFieldSource(String prompt, boolean reportMissing) {
        SkillStore.DraftPreview preview = skillDraft;
        if (preview == null || skillStore == null || !skillStore.enabled()) return false;
        SkillManifest manifest = authoringManifest(preview);
        Map<String, DraftFieldSource> available = new LinkedHashMap<>();
        int examined = 0;
        for (int index = transcript.size() - 1; index >= 0 && examined < VIDEO_REFERENCE_MESSAGES; index--) {
            ChatMessage message = transcript.get(index);
            if (message.artifact instanceof SkillResult.Declaration declaration) {
                if (!sameAuthoringThread(declaration, preview, index)) break;
                continue;
            }
            if (message.color != TEXT || TextUtils.isEmpty(message.copyText)
                    || !SkillAuthoring.canUsePastedVideoMaterial(message.copyText)) continue;
            examined++;
            Set<String> fields = new HashSet<>(SkillAuthoringPatch.targets(manifest, message.copyText));
            if (!message.user) fields.removeIf(field -> field.startsWith("service."));
            if (fields.isEmpty()) continue;
            try {
                SkillAuthoringPatch.Request patch = SkillAuthoringPatch.request(manifest, fields, prompt,
                        message.copyText, message.user);
                String label = (message.user ? "用户资料 " : "助手消息 ") + (index + 1);
                available.putIfAbsent(patch.input(), new DraftFieldSource(label, patch));
            } catch (IllegalArgumentException unsafe) { /* Only a validated source enters the chooser. */ }
        }
        if (available.isEmpty()) {
            if (reportMissing) showAuthoringStatus("上文没有可核对的字段值。请告诉我要填写的字段和值。");
            return false;
        }
        chooseVideoReference("选择要回填到当前草稿的资料", new ArrayList<>(available.values()), DraftFieldSource::label,
                generation, false, source -> {
                    if (skillDraft != preview) return;
                    var action = new SkillAuthoring.Action(SkillAuthoring.Mode.FIELD_PATCH, prompt, false);
                    sendMessage(prompt, List.of(), List.of(), null, null, null, false,
                            new AuthoringMaterial(action, null, source.patch()));
                });
        return true;
    }

    private void chooseVisibleVideoSource(String prompt, SkillAuthoring.Action action) {
        List<VideoReferenceMaterial> materials = visibleVideoMaterials(null, true);
        if (materials.isEmpty()) {
            sendAuthoringMaterial(prompt, action, SkillVideoFacts.empty());
            return;
        }
        chooseVideoReference("选择用于新视频技能的接口资料", materials, VideoReferenceMaterial::label,
                generation, false, material -> sendAuthoringMaterial(prompt, action, selectedVideoFacts(material)));
    }

    private record GroundedVideoSource(String label, SkillVideoFacts facts) { }

    private void chooseGroundedVideoSource(String prompt, SkillAuthoring.Action action,
                                           List<VideoReferenceMaterial> documents) {
        List<GroundedVideoSource> choices = new ArrayList<>();
        for (VideoReferenceMaterial document : documents) {
            choices.add(new GroundedVideoSource(document.label(), document.facts()));
        }
        if (choices.size() > 1) choices.add(new GroundedVideoSource("先创建待配置草稿", SkillVideoFacts.empty()));
        chooseVideoReference("选择视频接口资料", choices, GroundedVideoSource::label,
                generation, false, source -> sendAuthoringMaterial(prompt, action, source.facts()));
    }

    private static boolean otherDraftTarget(String request) {
        int changed = request.indexOf("改");
        String target = changed < 0 ? request : request.substring(0, changed);
        return target.matches(".*(?:另一个|另一项|另一份|上一个|上一份|其他|别的)(?:的)?(?:技能|草稿|草案).*")
                || target.matches(".*(?:技能\\s*/|(?:把|将)\\s*/)[\\p{L}\\p{N}_-]+.*");
    }

    private static String localDraftField(String request, String currentName) {
        Matcher target = NAMED_DRAFT_FIELD.matcher(request);
        return target.find() && currentName.equals(target.group(2)) ? target.group(1) + request.substring(target.end())
                : request;
    }

    private void referenceVideoDraft(String prompt, boolean install, boolean naturalFill) {
        SkillStore store = skillStore;
        if (store == null) {
            showAuthoringStatus("请先启用 AI Skills");
            return;
        }
        if (restoreLoad != null && transcript.isEmpty()) {
            showAuthoringStatus("会话正在恢复，请稍后再试");
            return;
        }
        List<VideoReferenceTarget> targets = new ArrayList<>();
        List<VideoReferenceMaterial> materials;
        try {
            if (!store.enabled()) {
                showAuthoringStatus("请先启用 AI Skills");
                return;
            }
            SkillStore.DraftPreview preview = skillDraft;
            if (install && preview == null) {
                for (SkillStore.Detail saved : store.pendingVideoDrafts()) {
                    if (!pendingSkillDraftId.equals(saved.draftId())) {
                        targets.add(new VideoReferenceTarget(saved.name(), saved, null, null));
                    }
                }
            }
            if (preview != null && preview.outputType() == SkillResult.Type.VIDEO) {
                targets.add(new VideoReferenceTarget(preview.name(), null, preview, skillDraftFacts));
            }
            if (targets.isEmpty()) {
                showAuthoringStatus("没有可补齐的视频草稿，请先用 /技能 创建");
                return;
            }
            materials = visibleVideoMaterials(preview, false, naturalFill);
        } catch (IllegalArgumentException invalid) {
            showAuthoringStatus("无法安全读取上面的接口资料，请核对资料并移除其中的密钥");
            return;
        } catch (IllegalStateException unavailable) {
            showAuthoringStatus("无法读取待配置的视频草稿");
            return;
        }
        if (materials.isEmpty()) {
            if (!install && naturalFill && !SkillAuthoring.fillsCurrentVideoDraftOnly(prompt)
                    && chooseDraftFieldSource(prompt, false)) return;
            if (!install && previewingVideoDraft()) {
                showAuthoringStatus(skillDraftFacts != null && skillDraftFacts.isComplete()
                        ? "接口字段已填写，可以查看草稿配置。"
                        : videoDraftStatus(skillDraftFacts));
            } else {
                showAuthoringStatus("没找到可引用的视频接口资料，请先提供接口示例");
            }
            return;
        }
        int expectedGeneration = generation;
        chooseVideoReference("选择要补齐的视频草稿", targets, VideoReferenceTarget::label, expectedGeneration,
                skillDraft == null,
                target -> chooseVideoReference("选择要使用的接口资料", materials, VideoReferenceMaterial::label,
                        expectedGeneration, false, material -> {
                            SkillVideoFacts facts = selectedVideoFacts(material);
                            if (install) saveVideoReference(prompt, store, target,
                                    new VideoReferenceMaterial(material.message(), facts, material.label()));
                            else {
                                sendAuthoringMaterial(prompt,
                                        new SkillAuthoring.Action(SkillAuthoring.Mode.SUPPLEMENT, "", false), facts);
                            }
                        }));
    }

    private static SkillVideoFacts selectedVideoFacts(VideoReferenceMaterial material) {
        return material.message().user ? material.facts() : material.facts().asChatSuggestion();
    }

    private List<VideoReferenceMaterial> visibleVideoMaterials(SkillStore.DraftPreview preview, boolean newDraft) {
        return visibleVideoMaterials(preview, newDraft, false);
    }

    private List<VideoReferenceMaterial> visibleVideoMaterials(SkillStore.DraftPreview preview, boolean newDraft,
                                                               boolean includeUserMaterial) {
        Map<String, VideoReferenceMaterial> materials = new LinkedHashMap<>();
        int examined = 0;
        for (int index = transcript.size() - 1; index >= 0 && examined < VIDEO_REFERENCE_MESSAGES; index--) {
            ChatMessage message = transcript.get(index);
            if (message.user && !includeUserMaterial) continue;
            if (message.artifact instanceof SkillResult.Declaration declaration) {
                if (!newDraft && (preview == null || !sameAuthoringThread(declaration, preview, index))) break;
                continue;
            }
            if (message.color != TEXT || TextUtils.isEmpty(message.copyText)
                    || message.user && !SkillAuthoring.canUsePastedVideoMaterial(message.copyText)) continue;
            examined++;
            SkillVideoFacts facts;
            try {
                facts = readVideoMaterial(message.copyText, includeUserMaterial);
            } catch (IllegalArgumentException invalid) {
                if (!newDraft && !includeUserMaterial) throw invalid;
                continue;
            }
            if (facts.isEmpty()) continue;
            String key = facts.toJson().toString();
            VideoReferenceMaterial previous = materials.get(key);
            if (previous == null || message.user && !previous.message().user) {
                materials.put(key, videoMaterial(message, facts, index));
            }
        }
        return new ArrayList<>(materials.values());
    }

    private List<VideoReferenceMaterial> visibleUserVideoMaterials() {
        Map<String, VideoReferenceMaterial> documents = new LinkedHashMap<>();
        int examined = 0;
        for (int index = transcript.size() - 1; index >= 0 && examined < VIDEO_REFERENCE_MESSAGES; index--) {
            ChatMessage message = transcript.get(index);
            if (message.artifact instanceof SkillResult.Declaration) break;
            if (!message.user || TextUtils.isEmpty(message.copyText)
                    || !SkillAuthoring.canUsePastedVideoMaterial(message.copyText)) continue;
            examined++;
            try {
                SkillVideoFacts facts = readVideoMaterial(message.copyText, true);
                if (!facts.isEmpty()) documents.putIfAbsent(facts.toJson().toString(), videoMaterial(message, facts, index));
            } catch (IllegalArgumentException invalid) { /* Do not make an unsafe message a source. */ }
        }
        return new ArrayList<>(documents.values());
    }

    private static VideoReferenceMaterial videoMaterial(ChatMessage message, SkillVideoFacts facts, int index) {
        String endpoint = facts.formFields().stream().filter(field -> "submitEndpoint".equals(field.id()))
                .map(SkillVideoFacts.FormField::value).findFirst().orElse("");
        String host = endpoint.isEmpty() ? null : Uri.parse(endpoint).getHost();
        String label = (message.user ? "用户资料 " : "助手消息 ") + (index + 1) + (host == null ? "" : " · " + host);
        return new VideoReferenceMaterial(message, facts, label);
    }

    private static SkillVideoFacts readVideoMaterial(String raw, boolean acceptsPlainFields) {
        SkillVideoFacts extracted = SkillVideoFacts.parseReference(raw);
        if (!extracted.isEmpty() || !acceptsPlainFields) return extracted;
        SkillVideoEdit.Edit edit = SkillVideoEdit.parse(raw);
        if (edit == null || edit.fields().size() < 2
                || (!edit.fields().containsKey("submitEndpoint") && !edit.fields().containsKey("submitPath"))) {
            return SkillVideoFacts.empty();
        }
        SkillVideoFacts facts = applyNaturalVideoEdit(SkillVideoFacts.empty(), edit);
        if (edit.mode() == SkillVideoEdit.Mode.NEEDS_ENCODING_EVIDENCE) {
            return facts.withForm(Map.of("submitEncoding", edit.submissionEncoding()));
        }
        return facts;
    }

    private boolean previewingVideoDraft() { return skillDraft != null && skillDraft.outputType() == SkillResult.Type.VIDEO; }

    private <T> void chooseVideoReference(String title, List<T> options, Function<T, String> label,
                                          int expectedGeneration, boolean requireSelection, Consumer<T> selected) {
        if (disposed || busy || generation != expectedGeneration) return;
        if (options.size() == 1 && !requireSelection) {
            selected.accept(options.get(0));
            return;
        }
        if (localMenu != null) localMenu.dismiss();
        String[] labels = options.stream().map(label).toArray(String[]::new);
        localMenu = new MiuixDialogBuilder(getContext(), useMiuix).setTitle(title)
                .setItems(labels, (menu, index) -> {
                    if (menu == localMenu && !disposed && !busy && generation == expectedGeneration) {
                        selected.accept(options.get(index));
                    }
                }).setNegativeButton("取消", null).create();
        localMenu.show();
    }

    private boolean sameAuthoringThread(SkillResult.Declaration declaration, SkillStore.DraftPreview preview, int index) {
        if (index == 0 || !transcript.get(index - 1).user) return false;
        String userText = transcript.get(index - 1).text;
        SkillAuthoring.Action action = SkillAuthoring.match(userText, true);
        if (action != null && (action.mode() == SkillAuthoring.Mode.DESIGN || action.mode() == SkillAuthoring.Mode.OPEN)) {
            return false;
        }
        try {
            return preview.name().equals(new org.json.JSONObject(declaration.json()).optString("name"));
        } catch (org.json.JSONException malformed) {
            return false;
        }
    }

    private void saveVideoReference(String prompt, SkillStore store, VideoReferenceTarget target,
                                    VideoReferenceMaterial material) {
        if (disposed || busy || skillStore != store || !transcript.contains(material.message())) return;
        if (target.preview() != null && skillDraft != target.preview()) {
            state.setText("视频草稿已变化，请重新选择");
            return;
        }
        Session active = session;
        String expectedPendingId = pendingSkillDraftId;
        ChatMessage answer = new ChatMessage(assistantName, "", false, List.of());
        RequestTurn turn = new RequestTurn(++generation, prompt, List.of(), List.of(), List.of(), answer,
                null, new SkillAuthoring.Action(SkillAuthoring.Mode.SAVE, "", false),
                com.douai.module.analytics.LocalAnalytics.get(getContext()).beginOperation(null, "ai"));
        turn.localDraftWrite = true;
        pending = turn;
        busy = true;
        state.setText("正在补齐视频草稿…");
        updateSend();
        request = worker.submit(turn.trace.wrap(() -> {
            try {
                synchronized (turn) {
                    turn.checkStopped();
                    turn.started = true;
                }
                SkillStore.Pending saved;
                SkillVideoFacts updatedFacts = null;
                if (target.saved() != null) {
                    SkillStore.Detail updated = store.applyVideoReference(target.saved(), material.facts());
                    saved = new SkillStore.Pending(updated.name(), updated.outputType(), updated.reason(), updated.draftId());
                } else {
                    SkillVideoFacts prior = target.previousFacts() == null ? SkillVideoFacts.empty() : target.previousFacts();
                    SkillVideoFacts incoming = material.facts();
                    boolean conflicts = !prior.submitEncoding().isEmpty() && !incoming.submitEncoding().isEmpty()
                            && !prior.submitEncoding().equals(incoming.submitEncoding());
                    if (conflicts || (prior.isComplete() && !incoming.supportsSubmissionEncoding())) {
                        throw new IllegalArgumentException("提交编码与草稿不一致");
                    }
                    updatedFacts = prior.fillMissingFrom(incoming);
                    saved = store.saveConversationDraft(target.preview(), updatedFacts, expectedPendingId).pending();
                }
                try {
                    turn.transcriptMemory = active.transcriptMemory();
                    if (turn.transcriptMemory != null) turn.transcriptEpoch = turn.transcriptMemory.epoch();
                } catch (RuntimeException unavailable) {
                    turn.transcriptUnavailable = true;
                }
                SkillVideoFacts facts = updatedFacts;
                main.post(turn.trace.wrap(() -> finishVideoReference(turn, target.preview(), facts, saved, null)));
            } catch (Exception failed) {
                String failure = material.facts().supportsSubmissionEncoding()
                        ? "视频草稿未保存，请核对当前草稿和接口资料"
                        : "视频草稿未保存。资料的提交编码是 " + material.facts().submitEncoding() + "，当前执行器只支持 JSON";
                main.post(turn.trace.wrap(() -> finishVideoReference(turn, null, null, null, failure)));
            }
        }));
    }

    private void finishVideoReference(RequestTurn turn, SkillStore.DraftPreview savedPreview, SkillVideoFacts facts,
                                      SkillStore.Pending saved, String failure) {
        if (!isCurrentTurn(turn)) return;
        if (saved == null) {
            request = null;
            pending = null;
            busy = false;
            state.setText(turn.stopRequested ? "已停止" : failure);
            updateSend();
            turn.trace.finish(turn.stopRequested ? DouAiLog.Outcome.CANCELLED : DouAiLog.Outcome.FAILED,
                    "video_reference_not_applied", "");
            return;
        }
        if (savedPreview != null && skillDraft == savedPreview) {
            skillDraft = SkillStore.prepareAuthoredVideoDraft(savedPreview.definition(), facts);
            skillDraftFacts = facts;
            if (skillAuthoringId.isEmpty()) skillAuthoringId = UUID.randomUUID().toString();
            pendingSkillDraftId = saved.draftId();
            turn.editorChanged = true;
        }
        cancelRestore();
        turn.answer.sourceUser = addMessage("我", turn.prompt, true, List.of());
        transcript.add(turn.answer);
        trimVisibleHistory();
        turn.settingsSkillName = saved.name();
        if (turn.prompt.equals(input.getText().toString())) input.setText("");
        String reply = "已保存待配置草稿 /" + saved.name() + "\n" + saved.reason()
                + (turn.editorChanged ? DRAFT_CONTINUATION : "");
        if (turn.transcriptMemory != null && turn.transcriptMemory.historyEnabled()) {
            saveSkillTranscript(turn, reply, null, null, null);
        } else {
            showRequestResult(turn, reply, null, null, null,
                    turn.transcriptUnavailable ? HistorySave.UNCONFIRMED
                            : turn.transcriptMemory == null ? HistorySave.NOT_REQUESTED : HistorySave.DISABLED);
        }
    }

    private String completeSkillAuthoring(RequestTurn turn, SkillStore.DraftPreview draft) throws Exception {
        if (!skillStore.enabled()) throw new IllegalStateException("技能已停用");
        if (draft == null) throw new IllegalStateException("没有可用的技能草案");
        SkillAuthoring.Mode mode = turn.authoring.mode();
        if (mode == SkillAuthoring.Mode.OPEN) {
            SkillStore.ConversationDraft opened = turn.openedSkill;
            if (opened == null) throw new IllegalStateException("无法打开技能");
            skillDraft = draft;
            skillDraftFacts = opened.facts();
            pendingSkillDraftId = opened.pendingId();
            skillEditorSourceProof = pendingSkillDraftId.isEmpty() ? opened.identity().proof() : "";
            skillEditorReplacesVideo = false;
            skillAuthoringId = UUID.randomUUID().toString();
            turn.editorChanged = true;
            return "已打开 /" + draft.name() + "。直接说要改什么，完成后用 /技能 帮我内置。\n"
                    + draftPreviewReply(draft, skillDraftFacts);
        }
        if (mode == SkillAuthoring.Mode.CANCEL) {
            boolean saved = !pendingSkillDraftId.isEmpty();
            clearAuthoringDraft(turn);
            return "已取消编写 /" + draft.name() + (saved ? "。技能库中已保存的草稿仍然保留。" : "。");
        }
        if (mode == SkillAuthoring.Mode.PREVIEW) return draftPreviewReply(draft, skillDraftFacts);
        if (mode != SkillAuthoring.Mode.SAVE && !turn.authoring.saveAfter()) {
            if (mode == SkillAuthoring.Mode.DESIGN) {
                pendingSkillDraftId = "";
                skillEditorSourceProof = "";
                skillEditorReplacesVideo = false;
            }
            if (mode == SkillAuthoring.Mode.REPLACE_VIDEO_SOURCE) skillEditorReplacesVideo = true;
            if (mode == SkillAuthoring.Mode.DESIGN || skillAuthoringId.isEmpty()) skillAuthoringId = UUID.randomUUID().toString();
            skillDraft = draft;
            skillDraftFacts = draft.outputType() == SkillResult.Type.VIDEO ? turn.videoFacts : null;
            turn.editorChanged = true;
            return draftPreviewReply(draft, skillDraftFacts);
        }
        String expected = mode == SkillAuthoring.Mode.DESIGN ? "" : pendingSkillDraftId;
        SkillStore.DeleteTarget editorSource = mode == SkillAuthoring.Mode.DESIGN || skillEditorSourceProof.isEmpty()
                ? null : new SkillStore.DeleteTarget(draft.name(), 1, false, skillEditorSourceProof);
        SkillStore.SaveResult result = skillStore.saveConversationDraft(draft, turn.videoFacts, expected, editorSource,
                skillEditorReplacesVideo && mode != SkillAuthoring.Mode.DESIGN);
        SkillStore.Installed installed = result.installed();
        if (installed != null) {
            clearAuthoringDraft(turn);
            turn.settingsSkillName = installed.name();
            return "已创建技能 /" + installed.name() + " · v" + installed.version()
                    + (installed.enabled() ? " · 已启用" : " · 已停用");
        }
        SkillStore.Pending pending = result.pending();
        if (mode == SkillAuthoring.Mode.DESIGN || skillAuthoringId.isEmpty()) skillAuthoringId = UUID.randomUUID().toString();
        skillDraft = draft;
        skillDraftFacts = draft.outputType() == SkillResult.Type.VIDEO ? turn.videoFacts : null;
        pendingSkillDraftId = pending.draftId();
        skillEditorSourceProof = "";
        skillEditorReplacesVideo = false;
        turn.editorChanged = true;
        turn.settingsSkillName = pending.name();
        if (draft.outputType() == SkillResult.Type.VIDEO) {
            String status = videoDraftStatus(skillDraftFacts, draft);
            if (skillDraftFacts != null && skillDraftFacts.isComplete()) {
                try {
                    SkillStore.Detail detail = skillStore.detail(pending.name());
                    if (detail.customConfiguration() != null) status = "接口已校验，已有服务设置已保留。请在技能设置核对并确认启用。";
                } catch (IllegalStateException unavailable) { /* The stored draft remains reviewable. */ }
            }
            return "已保存待配置草稿 /" + pending.name() + "\n" + status + DRAFT_CONTINUATION;
        }
        StringBuilder status = new StringBuilder("已保存待配置草稿 /")
                .append(pending.name()).append('\n').append(pending.reason());
        // Status falls back to the completed save if reading current field details fails.
        try {
            SkillStore.Detail detail = skillStore.detail(pending.name());
            if (detail.previousVersion() > 0) status.append("\n已保留当前版本 v").append(detail.previousVersion());
            for (SkillStore.Field field : detail.fields()) {
                if (!field.required()) continue;
                if (field.state() == SkillStore.FieldState.MISSING) status.append('\n').append(field.label()).append("待填写");
                else if (field.state() == SkillStore.FieldState.NEEDS_AUTHORIZATION)
                    status.append('\n').append(field.label()).append("待授权");
            }
        } catch (Exception unavailable) { }
        return status + DRAFT_CONTINUATION;
    }

    private void clearAuthoringDraft(RequestTurn turn) {
        skillDraft = null;
        skillDraftFacts = null;
        skillAuthoringId = "";
        pendingSkillDraftId = "";
        skillEditorSourceProof = "";
        skillEditorReplacesVideo = false;
        turn.editorChanged = true;
    }

    private static String draftPreviewReply(SkillStore.DraftPreview draft, SkillVideoFacts facts) {
        String instruction = TextUtils.isEmpty(draft.instruction()) ? "" : "\n指令 " + draft.instruction();
        String source = facts != null && !facts.label().isEmpty() ? "\n接口资料 " + facts.label() : "";
        return "技能草案 /" + draft.name() + " · " + draft.outputType().label() + " · 尚未保存\n"
                + draft.description() + instruction + source + previewFilledFields(draft)
                + (draft.outputType() == SkillResult.Type.VIDEO ? "\n" + videoDraftStatus(facts, draft) : "")
                + (facts != null && facts.minimumPollSeconds() != 0
                ? "\n最短轮询间隔 " + facts.minimumPollSeconds() + " 秒，长任务会逐步退避。" : "")
                + DRAFT_CONTINUATION;
    }

    private static String previewFilledFields(SkillStore.DraftPreview draft) {
        Map<String, Object> values = draft.publicFields();
        if (values.isEmpty() && draft.outputType() != SkillResult.Type.VIDEO) return "";
        StringBuilder summary = new StringBuilder();
        SkillManifest manifest = authoringManifest(draft);
        if (manifest != null) {
            for (SkillManifest.ConfigField field : manifest.configFields()) {
                Object value = values.get("config." + field.key());
                if (value == null && draft.outputType() == SkillResult.Type.VIDEO) value = field.defaultValue();
                if (value != null) summary.append('\n').append(field.label()).append(" · ").append(value);
            }
        }
        if (values.containsKey("service.model")) summary.append("\n服务模型 · ").append(values.get("service.model"));
        if (values.containsKey("service.endpoint")) {
            summary.append("\n待确认的服务地址 · ").append(values.get("service.endpoint"));
        }
        return summary.toString();
    }

    private static String videoDraftStatus(SkillVideoFacts facts) {
        if (facts == null || facts.isEmpty()) {
            return "请贴所用视频服务的 API 文档，或发一组提交和查询的请求、响应示例。";
        }
        if (!facts.supportsSubmissionEncoding()) {
            if ("multipart/form-data".equals(facts.submitEncoding())) {
                return "目前只支持 JSON 提交。这份资料使用 multipart/form-data，请提供支持 JSON 的接口资料。";
            }
            return "目前只支持 JSON 提交。这份资料没有可用的 JSON 编码声明，请贴提交部分的 Content-Type 示例。";
        }
        if (!facts.isComplete()) {
            List<String> missing = facts.missing();
            if (missing.contains("查询响应任务 ID 字段")) {
                return "还缺查询响应的任务 ID 字段。请贴含任务 ID 的查询响应示例，或告诉我该字段名。";
            }
            if (facts.needsSubmissionVerification()
                    && missing.stream().allMatch(field -> field.contains("核对聊天资料"))) {
                return "已填写聊天资料。请核对提交地址，再告诉我「提交地址是…」或「提交路径是…」。";
            }
            if (missing.stream().anyMatch(field -> field.startsWith("提交") || field.equals("提示词字段"))) {
                return "请贴提交任务的请求和返回任务 ID 的响应示例。";
            }
            if (missing.stream().anyMatch(field -> field.startsWith("查询") || field.startsWith("状态"))) {
                return "请贴查询任务的请求和响应示例，尤其是表示完成的状态值。";
            }
            return "请补充取回视频的接口说明，或贴出查询返回的成片链接示例。";
        }
        String address = facts.needsApiBase() ? "API 基础地址" : "HTTPS 接口地址";
        return "接口声明已校验。内置后在技能设置填写" + address + "和所需凭据，再确认启用。";
    }

    private static String videoDraftStatus(SkillVideoFacts facts, SkillStore.DraftPreview draft) {
        String status = videoDraftStatus(facts);
        SkillManifest manifest = facts != null && facts.isComplete() ? authoringManifest(draft) : null;
        if (manifest == null) return status;
        Map<String, Object> values = draft.publicFields();
        List<String> missing = new ArrayList<>();
        for (SkillManifest.ConfigField field : manifest.configFields()) {
            if (field.required() && field.defaultValue() == null && !values.containsKey("config." + field.key())) {
                missing.add(field.label());
            }
        }
        return missing.isEmpty() ? status : status + "\n文档未给出默认值，还需填写 " + String.join("、", missing) + "。";
    }

    private static final class OutputMismatch extends IllegalArgumentException {
        private final SkillResult.Type expected;
        private final SkillResult.Type actual;

        OutputMismatch(SkillResult.Type expected, SkillResult.Type actual) {
            this.expected = expected;
            this.actual = actual;
        }
    }

    private static final class ServiceCapabilityMismatch extends IllegalArgumentException { }

    private void showCustomServiceReview(AiSkills.Invocation invocation, String prompt, SkillSelection.Choice choice,
                                         AiSkills.Invocation selectedPackage) {
        if (customReviewDialog != null) return;
        SkillStore store = skillStore;
        SkillStore.CustomReview review;
        try { review = store.previewCustomService(invocation); }
        catch (SkillCustomService.Failure invalid) {
            state.setText(invalid.getMessage());
            return;
        } catch (Exception unavailable) {
            state.setText("无法核对技能服务配置，请打开技能详情");
            return;
        }

        String message = "POST " + review.endpoint() + "\n传输字段和值\n" + review.payload() + "\n费用由服务商决定";
        SkillHttpDeclaration.Video video = review.video();
        if (video != null) {
            message += "\n\n按同一任务 ID 查询 " + AiSkillDetailDialog.videoStatusRoute(video);
            message += video.downloadPath().isEmpty() ? "\n成片链接字段 " + video.resultUrlField()
                    : "\n下载 GET " + video.downloadPath();
            if (video.canCancel()) message += "\n取消任务时另行确认 " + video.cancelMethod() + " " + video.cancelPath();
            message += "\n本任务使用提交时确认的接口配置。";
        }
        AlertDialog child = new MiuixDialogBuilder(getContext(), useMiuix)
                .setTitle("确认本次技能服务调用").setMessage(message)
                .setNegativeButton("取消", null).setPositiveButton("确认调用", null).create();
        customReviewDialog = child;
        boolean[] approved = {false};
        child.setOnDismissListener(ignored -> {
            if (!approved[0]) store.cancelCustomService(review);
            if (customReviewDialog == child) customReviewDialog = null;
        });
        try {
            child.show();
            child.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
                try {
                    SkillStore.CustomCall confirmation = store.approveCustomService(review);
                    approved[0] = true;
                    child.dismiss();
                    sendMessage(prompt, Collections.emptyList(), Collections.emptyList(), null, confirmation, choice, true,
                            null, selectedPackage);
                } catch (Exception stale) {
                    child.dismiss();
                    state.setText("本次确认已失效，请重新核对后调用");
                }
            });
        } catch (RuntimeException unavailable) {
            store.cancelCustomService(review);
            customReviewDialog = null;
            state.setText("无法打开技能服务确认");
        }
    }

    private static String authoringFailure(SkillAuthoring.Action action, Exception failure) {
        if (failure instanceof ServiceCapabilityMismatch) return "请求需要受控服务能力，本次草案未保存";
        if (failure instanceof OutputMismatch mismatch) {
            String name = mismatch.expected == SkillResult.Type.IMAGE ? "生图" : mismatch.expected.label();
            return name + "技能需要" + mismatch.expected.label() + "输出，模型返回了" + mismatch.actual.label() + "，本次草案未保存。";
        }
        if (failure instanceof SkillManifest.Invalid invalid) return invalid.getMessage();
        if (failure instanceof java.net.SocketTimeoutException) return "技能请求超时，请稍后再试";
        if (action.mode() == SkillAuthoring.Mode.FIELD_PATCH) return "回填失败。请核对资料，或明确填写字段和值。";
        if (action.mode() == SkillAuthoring.Mode.OPEN) return "无法打开该技能，请核对命令名或检查技能配置";
        if (action.mode() == SkillAuthoring.Mode.SAVE) return "保存技能失败，原草案仍保留";
        return "技能草案处理失败，请检查技能需求与模型设置";
    }

    private static String skillFailure(boolean stopped, Exception failure) {
        if (failure instanceof SkillServices.Failure service
                && service.code() == SkillServices.Code.UNKNOWN_RESULT) return "请求结果未知，请先核实";
        if (failure instanceof McpClient.Failure mcp && mcp.resultUnknown()) return mcp.getMessage();
        if (failure instanceof McpRegistry.Failure registry && registry.resultUnknown()) return registry.getMessage();
        if (failure instanceof SkillCustomService.Failure custom && custom.resultUnknown()) return custom.getMessage();
        if (stopped) return "已停止";
        if (failure instanceof VideoHistoryRequired) return "使用视频技能前请开启本地会话历史";
        if (failure instanceof SkillServices.Failure || failure instanceof McpClient.Failure
                || failure instanceof McpRegistry.Failure || failure instanceof AiSubAgents.Failure
                || failure instanceof SkillCustomService.Failure || failure instanceof SkillVideoJobs.Failure) {
            return failure.getMessage();
        }
        if (failure instanceof java.net.SocketTimeoutException) return "技能请求超时，请先核实请求状态";
        return "技能执行失败";
    }

    private void stopRequest() {
        if (!busy || pending.stopRequested || pending.savingHistory || pending.localDraftWrite) return;
        boolean cancelSession;
        synchronized (pending) {
            pending.stopRequested = true;
            cancelSession = !pending.manualSkill() || pending.usingSkillModel;
            pending.trace.finish(DouAiLog.Outcome.CANCELLED, "user_cancelled", "provider_result=unknown");
            if (pending.started && request != null) request.cancel(true);
        }
        if (cancelSession) session.cancel();
        searchVideoCards.cancelPages();
        state.setText("正在停止…");
        updateSend();
    }

    private void clearConversation() {
        if (disposed) return;
        if (customReviewDialog != null) customReviewDialog.dismiss();
        if (localMenu != null) localMenu.dismiss();
        closeImagePreview();
        skillVideoCards.clear();
        videoScope = null;
        if (pending != null) pending.discardHistory = true;
        cancelRequest();
        skillDraft = null;
        skillDraftFacts = null;
        skillAuthoringId = "";
        pendingSkillDraftId = "";
        skillEditorSourceProof = "";
        skillEditorReplacesVideo = false;
        storedSkillDraft = null;
        closePickers();
        cancelRestore();
        cancelThumbnails();
        searchVideoCards.clear();
        Session previous = session;
        AiSkills.Entry previousEntry = skillEntry;
        session = sessions.create();
        transcript.clear();
        messageRows.notifyDataSetChanged();
        visibleImageBytes = 0;
        draftImages.clear();
        draftDocuments.clear();
        draftPending.clear();
        renderAttachments();
        int ticket = ++clearGeneration;
        state.setText("");
        updateSend();
        // Keep the clear operation ordered after any in-flight history read, including on close.
        historyClear = historyWorker.submit(() -> {
            RuntimeException error = null;
            try {
                SkillVideoJobs.Scope scope = videoScope(previous.transcriptMemory(), previousEntry);
                if (scope != null) skillVideoJobs.clear(scope);
            } catch (SkillVideoJobs.Failure | RuntimeException failure) { error = new IllegalStateException("无法清空视频任务"); }
            try {
                previous.clear();
            } catch (RuntimeException failure) { error = new IllegalStateException("无法清空已保存的对话"); }
            RuntimeException failure = error;
            main.post(() -> {
                if (disposed || ticket != clearGeneration) return;
                if (!busy) state.setText(failure == null ? "" : failure.getMessage());
                updateSend();
            });
        });
    }

    private void cancelRequest() {
        boolean savingHistory = pending != null && pending.savingHistory;
        boolean cancelSession = !savingHistory && (pending == null || !pending.manualSkill() || pending.usingSkillModel);
        if (pending != null) pending.trace.finish(DouAiLog.Outcome.CANCELLED, "conversation_closed_or_cleared",
                savingHistory ? "provider_result=completed" : "provider_result=unknown");
        ++generation;
        if (videoDocumentTask != null) {
            videoDocumentTask.cancel(true);
            videoDocumentTask = null;
        }
        if (request != null) request.cancel(true);
        if (cancelSession) session.cancel();
        searchVideoCards.cancelPending();
        request = null;
        pending = null;
        busy = false;
    }

    @Override public void dismiss() {
        dispose();
        super.dismiss();
    }

    private void dispose() {
        if (disposed) return;
        disposed = true;
        if (customReviewDialog != null) customReviewDialog.dismiss();
        closeImagePreview();
        skillVideoCards.clear();
        closeSkillDeletionObserver();
        skillDraft = null;
        skillDraftFacts = null;
        if (contactAvatar != null) contactAvatar.setImageDrawable(null);
        if (nativeViews != null && nativeViews.avatar != null) nativeViews.avatar.setImageDrawable(null);
        if (profileAvatar != null) profileAvatar.setImageDrawable(null);
        if (nativeViews != null) {
            clearMessageAvatars(nativeViews.root);
            messages.setAdapter(null);
        }
        if (contactAvatarBitmap != null) contactAvatarBitmap.recycle();
        contactAvatarBitmap = null;
        cancelRequest();
        closePickers();
        cancelRestore();
        cancelThumbnails();
        searchVideoCards.close();
        if (attachmentMenu != null) attachmentMenu.dismiss();
        if (memoryDialog != null) memoryDialog.dismiss();
        if (localMenu != null) localMenu.dismiss();
        if (skillDetails != null) skillDetails.dismiss();
        if (speechInput != null) speechInput.close();
        worker.shutdownNow();
        if (miuixSurface != null) {
            miuixSurface.close();
            miuixSurface = null;
        }
    }

    private void restoreDraft(String prompt, List<ChatImage> images, List<Document> documents) {
        if (input.getText().toString().isEmpty()) {
            input.setText(prompt);
            input.setSelection(input.length());
        }
        if (draftImages.isEmpty()) { draftImages.addAll(images); renderAttachments(); }
        if (draftDocuments.isEmpty()) { draftDocuments.addAll(documents); renderAttachments(); }
    }

    private void addRetry(ChatMessage answer, String prompt, List<ChatImage> images, List<Document> documents) {
        answer.retry = true;
        answer.retryPrompt = prompt;
        answer.retryImages = new ArrayList<>(images);
        answer.retryDocuments = new ArrayList<>(documents);
    }

    private ChatMessage addMessage(String role, String text, boolean user, List<ChatImage> images) {
        ChatMessage message = new ChatMessage(role, text, user, images);
        transcript.add(message);
        visibleImageBytes += imageBytes(message.images);
        messageRows.notifyDataSetChanged();
        return message;
    }

    private void updateSend() {
        state.setVisibility(TextUtils.isEmpty(state.getText()) ? View.GONE : View.VISIBLE);
        skillEditorBar.setVisibility(skillDraft == null ? View.GONE : View.VISIBLE);
        skillEditorStatus.setText(skillDraft == null ? "" : "正在编写 /" + skillDraft.name() + " · 可直接说想改什么");
        skillDraftSettings.setTag(skillDraft == null ? null : "ai.chat.skill.draft_settings." + skillDraft.name());
        skillDraftSettings.setEnabled(!disposed && !busy && skillDraft != null);
        boolean hasContent = !input.getText().toString().trim().isEmpty() || !draftImages.isEmpty()
                || !draftDocuments.isEmpty() || !draftPending.isEmpty();
        if (nativeViews == null) send.setImageResource(busy ? android.R.drawable.ic_menu_close_clear_cancel : android.R.drawable.ic_menu_send);
        else nativeViews.updateComposer(busy, hasContent);
        boolean savingHistory = busy && pending.savingHistory;
        boolean writingDraft = busy && pending.localDraftWrite;
        String sendLabel = savingHistory ? "正在保存历史" : writingDraft ? "正在保存草稿" : busy ? "停止请求" : "发送";
        send.setContentDescription(sendLabel);
        send.setTooltipText(sendLabel);
        send.setEnabled(!disposed && videoDocumentTask == null && (busy ? !savingHistory && !writingDraft && !pending.stopRequested
                : hasContent));
        send.setAlpha(send.isEnabled() ? 1f : 0.4f);
        attach.setEnabled(!disposed && !busy && draftPending.size() < 4
                && (draftImages.size() < ChatCompletionClient.MAX_IMAGES_PER_MESSAGE || draftDocuments.size() < 4));
        attach.setAlpha(attach.isEnabled() ? 1f : 0.4f);
        if (nativeViews != null) {
            nativeViews.plus.setEnabled(attach.isEnabled());
            nativeViews.voice.setEnabled(!disposed && !busy && speechInput == null);
            nativeViews.emoji.setEnabled(!disposed);
            if (quickReplies != null) quickReplies.setVisibility(!busy && !hasContent ? View.VISIBLE : View.GONE);
        }
        updateSkillMenu();
        if (miuixSurface != null) miuixSurface.refresh();
    }

    private void updateSkillMenu() {
        if (skillEntry == null || disposed || busy || videoDocumentTask != null || !draftImages.isEmpty()
                || !draftDocuments.isEmpty() || !draftPending.isEmpty()) {
            skillMenu.setVisibility(View.GONE);
            return;
        }
        List<AiSkills.Command> commands;
        try {
            String text = input.getText().toString();
            commands = new ArrayList<>(AiSkills.commands(skillEntry, text, skillStore,
                    AiSubAgents.read(subAgentSettings).enabled()));
            String prefix = text.trim();
            if (skillStore != null && skillStore.enabled() && prefix.startsWith("/")
                    && (("/" + AiToolPackages.COMMAND).startsWith(prefix) || "/工具包".startsWith(prefix))) {
                commands.add(new AiSkills.Command(AiToolPackages.COMMAND, "打开 Skill 与工具包目录"));
            }
        } catch (IllegalStateException unavailable) {
            commands = Collections.emptyList();
        }
        if (!commands.equals(visibleCommands)) {
            visibleCommands = commands;
            skillCommands.removeAllViews();
            for (AiSkills.Command command : commands) {
                String slash = "/" + command.name();
                Button option = new Button(getContext());
                option.setTag("ai.chat.skill." + command.name());
                option.setText(slash);
                option.setAllCaps(false);
                option.setContentDescription(slash + "，" + command.description());
                option.setTooltipText(command.description());
                option.setOnClickListener(ignored -> {
                    if (AiToolPackages.COMMAND.equals(command.name())) {
                        usePackage("", new AiToolPackages.Use("", ""));
                        return;
                    }
                    input.setText(ModuleUiStrings.get(getContext(), R.string.ai_chat_slash_command_input, "%1$s ", slash));
                    input.setSelection(input.length());
                    input.requestFocus();
                });
                skillCommands.addView(option);
            }
        }
        skillMenu.setVisibility(commands.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showSkillDetails(String skillName) {
        showSkillDetails(() -> new AiSkillDetailDialog(getContext(), useMiuix, skillName, this::updateSend));
    }

    private void showCurrentDraftDetails() {
        SkillStore.DraftPreview draft = skillDraft;
        SkillVideoFacts facts = skillDraftFacts;
        if (busy || draft == null) return;
        showSkillDetails(() -> AiSkillDetailDialog.preview(getContext(), useMiuix, draft, facts));
    }

    private void showSkillDetails(Supplier<AiSkillDetailDialog> createPage) {
        Activity owner = ownerActivity();
        if (disposed || owner == null || owner.isFinishing() || owner.isDestroyed()) return;
        if (skillDetails != null) skillDetails.dismiss();
        AiSkillDetailDialog page = null;
        try {
            page = createPage.get();
            AiSkillDetailDialog opened = page;
            Dialog child = page.dialog();
            child.setOwnerActivity(owner);
            child.setOnDismissListener(ignored -> {
                opened.close();
                if (skillDetails == child) skillDetails = null;
                if (!disposed) updateSend();
            });
            child.show();
            skillDetails = child;
        } catch (RuntimeException unavailable) {
            if (page != null) page.close();
            state.setText("无法打开此技能的设置");
            updateSend();
        }
    }

    private void showConversationActions() {
        if (disposed) return;
        boolean editable = contactEditAction != null;
        if (localMenu != null) localMenu.dismiss();
        localMenu = new MiuixDialogBuilder(getContext(), useMiuix).setTitle(assistantName)
                .setItems(editable ? new String[]{"修改联系人", "清空聊天记录"} : new String[]{"清空聊天记录"},
                        (child, which) -> {
                            if (editable && which == 0) contactEditAction.run();
                            else clearConversation();
                        }).setNegativeButton("取消", null).create();
        localMenu.show();
    }

    private boolean showMessageActions(ChatMessage message) {
        if (disposed || !transcript.contains(message)) return false;
        boolean hasMessage = copyableMessageText(message.copyText);
        String declaration = message.artifact instanceof SkillResult.Declaration data ? data.json() : null;
        if (!hasMessage && declaration == null) return false;
        List<String> choices = new ArrayList<>();
        if (hasMessage) choices.add("复制消息");
        if (declaration != null) choices.add("复制技能声明");
        String original = message.copyText;
        if (localMenu != null) localMenu.dismiss();
        localMenu = new MiuixDialogBuilder(getContext(), useMiuix).setTitle("消息操作")
                .setItems(choices.toArray(new String[0]), (menu, selected) -> {
                    if (disposed || localMenu != menu || !transcript.contains(message)) return;
                    if (hasMessage && selected == 0) copyMessageText("DouAi 消息", original);
                    else copyMessageText("DouAi 技能声明", declaration);
                }).setNegativeButton("取消", null).create();
        localMenu.show();
        return true;
    }

    private static boolean copyableMessageText(String text) {
        return text != null && TextUtils.getTrimmedLength(text) != 0;
    }

    private void copyMessageText(String label, String content) {
        if (!copyableMessageText(content)) return;
        try {
            ClipboardManager clipboard = getContext().getSystemService(ClipboardManager.class);
            if (clipboard == null) throw new IllegalStateException("Clipboard unavailable");
            ClipData clip = ClipData.newPlainText(label, content);
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(getContext(), "已复制", Toast.LENGTH_SHORT).show();
        } catch (RuntimeException unavailable) {
            Toast.makeText(getContext(), "无法访问剪贴板", Toast.LENGTH_SHORT).show();
        }
    }

    private static void bindMessageActions(View view, View.OnLongClickListener action) {
        view.setOnLongClickListener(action);
        view.setLongClickable(action != null);
        if (action == null) {
            ViewCompat.removeAccessibilityAction(view, AccessibilityActionCompat.ACTION_LONG_CLICK.getId());
        } else {
            ViewCompat.replaceAccessibilityAction(view, AccessibilityActionCompat.ACTION_LONG_CLICK,
                    "消息操作", (target, args) -> target.performLongClick());
        }
    }

    private void showLocalTools() {
        if (disposed) return;
        if (localMenu != null) localMenu.dismiss();
        String[] items = skillEntry == null ? new String[]{"添加附件", "联系人设置"}
                : new String[]{"添加附件", "联系人设置", "Skill 与工具包"};
        localMenu = new MiuixDialogBuilder(getContext(), useMiuix).setTitle("聊天工具")
                .setItems(items, (child, which) -> {
                    if (which == 0) chooseAttachment();
                    else if (which == 1) showConversationActions();
                    else usePackage("", new AiToolPackages.Use("", ""));
                }).setNegativeButton("取消", null).create();
        localMenu.show();
    }

    private AiToolPackages toolPackages() {
        return new AiToolPackages(skillStore, MarkdownSkillStore.forContext(getContext()));
    }

    private boolean existingUnqualifiedInvocation(String prompt, AiToolPackages.Use use) {
        if (skillStore == null || use.packageName().isEmpty() || use.packageName().contains(":")
                || "tools".equals(use.packageName())) return false;
        return existingPackageCommand(prompt);
    }

    private boolean existingPackageCommand(String prompt) {
        if (skillStore == null || skillEntry == null) return false;
        try {
            return AiSkills.match(skillEntry, prompt, skillStore) != null;
        } catch (AiSkills.CommandUnavailable unavailable) {
            return true; // Existing commands keep their configured-state diagnostics.
        }
    }

    private void usePackage(String prompt, AiToolPackages.Use use) {
        if (skillStore == null || !skillStore.enabled()) {
            showAuthoringStatus("请先启用 AI Skills。");
            return;
        }
        AiToolPackages packages = toolPackages();
        List<AiToolPackages.Item> available = packages.catalog();
        if (use.packageName().isEmpty()) {
            showPackageDirectory(available, "");
            return;
        }
        List<AiToolPackages.Item> matches = available.stream()
                .filter(item -> item.id().equals(use.packageName()) || item.name().equals(use.packageName()))
                .collect(java.util.stream.Collectors.toList());
        if (matches.isEmpty()) {
            showAuthoringStatus("工具包不可用。发送 /use_package 查看已启用的包。");
        } else if (matches.size() > 1) {
            showPackageDirectory(matches, use.argument());
        } else {
            selectPackage(matches.get(0), use.argument(), prompt);
        }
    }

    private void showPackageDirectory(List<AiToolPackages.Item> items, String argument) {
        if (disposed || busy) return;
        if (items.isEmpty()) {
            showAuthoringStatus("当前没有可用工具包。");
            return;
        }
        String[] labels = items.stream().map(item -> item.id() + " · " + item.name()
                + (item.description().isBlank() ? "" : "\n" + item.description())).toArray(String[]::new);
        if (localMenu != null) localMenu.dismiss();
        localMenu = new MiuixDialogBuilder(getContext(), useMiuix).setTitle("Skill 与工具包")
                .setItems(labels, (menu, position) -> {
                    if (localMenu == menu && !disposed && !busy) selectPackage(items.get(position), argument, "");
                }).setNegativeButton("关闭", null).create();
        localMenu.show();
    }

    private void selectPackage(AiToolPackages.Item item, String argument, String prompt) {
        switch (item.kind()) {
            case MARKDOWN -> {
                if (argument.isBlank()) {
                    pastePackageCommand("/" + AiToolPackages.COMMAND + " " + item.id() + " ");
                } else {
                    try {
                        AiSkills.Invocation invocation = toolPackages().markdownInvocation(item, argument);
                        sendPackageInvocation(prompt.isEmpty() ? "/" + AiToolPackages.COMMAND + " " + item.id() + " " + argument
                                : prompt, invocation);
                    } catch (IllegalArgumentException | IllegalStateException unavailable) {
                        showAuthoringStatus("文档技能不可用，或任务超过输入范围。");
                    }
                }
            }
            case STRUCTURED -> {
                String command = "/" + item.name() + " " + argument;
                if (argument.isBlank()) {
                    pastePackageCommand("/" + AiToolPackages.COMMAND + " " + item.id() + " ");
                } else {
                    try {
                        AiSkills.Invocation invocation = AiSkills.match(skillEntry, command, skillStore);
                        if (invocation == null) throw new IllegalArgumentException();
                        sendPackageInvocation(prompt.isEmpty() ? command : prompt, invocation);
                    } catch (IllegalArgumentException | IllegalStateException unavailable) {
                        showAuthoringStatus("技能不可用，或配置已变化。");
                    }
                }
            }
            case BUILTIN -> showBuiltInTools(argument);
            case MCP -> confirmMcpActivation(item, argument);
        }
    }

    private void pastePackageCommand(String command) {
        input.setText(command);
        input.setSelection(input.length());
        input.requestFocus();
    }

    private void showBuiltInTools(String argument) {
        List<AiSkills.Command> tools = AiSkills.builtInTools(skillEntry);
        String[] labels = tools.stream().map(tool -> tool.name() + " · " + tool.description()).toArray(String[]::new);
        if (localMenu != null) localMenu.dismiss();
        localMenu = new MiuixDialogBuilder(getContext(), useMiuix).setTitle("DouAi 自带工具")
                .setItems(labels, (menu, which) -> {
                    if (localMenu == menu && !disposed && !busy) {
                        pastePackageCommand("/" + tools.get(which).name() + " " + argument);
                    }
                }).setNegativeButton("取消", null).create();
        localMenu.show();
    }

    private void confirmMcpActivation(AiToolPackages.Item item, String argument) {
        if (disposed || busy) return;
        if (localMenu != null) localMenu.dismiss();
        localMenu = new MiuixDialogBuilder(getContext(), useMiuix).setTitle("加载 MCP 工具")
                .setMessage("连接已授权服务「" + item.name() + "」读取工具目录。实际调用另行确认。")
                .setPositiveButton("加载", (dialog, which) -> {
                    if (localMenu == dialog && !disposed && !busy) discoverMcpPackage(item, argument);
                }).setNegativeButton("取消", null).create();
        localMenu.show();
    }

    private void discoverMcpPackage(AiToolPackages.Item item, String argument) {
        AiToolPackages packages = toolPackages();
        SkillStore activeStore = skillStore;
        int expectedGeneration = generation;
        int discoveryTicket = ++packageDiscoveryGeneration;
        showAuthoringStatus("正在读取 MCP 工具目录…");
        worker.submit(() -> {
            List<McpRegistry.PackageTool> tools;
            try {
                tools = packages.activateMcp(item, System.nanoTime() + TimeUnit.SECONDS.toNanos(20));
            } catch (Exception unavailable) {
                tools = null;
            }
            List<McpRegistry.PackageTool> discovered = tools;
            main.post(() -> {
                if (disposed || busy || generation != expectedGeneration || discoveryTicket != packageDiscoveryGeneration
                        || activeStore != skillStore) return;
                if (discovered == null) {
                    showAuthoringStatus("MCP 服务不可用，或授权已变化。");
                } else if (discovered.isEmpty()) {
                    showAuthoringStatus("该 MCP 服务没有可用工具。");
                } else {
                    showAuthoringStatus("");
                    showMcpPackageTools(item, discovered, argument);
                }
            });
        });
    }

    private void showMcpPackageTools(AiToolPackages.Item item, List<McpRegistry.PackageTool> tools, String argument) {
        AiToolPackages.ToolRequest suggestion = argument.trim().startsWith("{") ? null
                : AiToolPackages.suggestTool(argument, tools);
        List<String> labels = new ArrayList<>();
        if (suggestion != null) labels.add("请 AI 建议工具并填写参数");
        for (McpRegistry.PackageTool tool : tools) labels.add(tool.name()
                + (tool.description().isBlank() ? "" : "\n" + tool.description()));
        if (localMenu != null) localMenu.dismiss();
        localMenu = new MiuixDialogBuilder(getContext(), useMiuix).setTitle(item.name() + " · MCP 工具")
                .setItems(labels.toArray(new String[0]), (menu, which) -> {
                    if (localMenu != menu || disposed || busy) return;
                    if (suggestion != null && which == 0) {
                        suggestMcpPackageTool(item, tools, suggestion);
                    } else {
                        showMcpToolArguments(item, tools.get(suggestion == null ? which : which - 1), argument);
                    }
                }).setNegativeButton("关闭", null).create();
        localMenu.show();
    }

    private void suggestMcpPackageTool(AiToolPackages.Item item, List<McpRegistry.PackageTool> tools,
                                       AiToolPackages.ToolRequest request) {
        SkillStore activeStore = skillStore;
        Session active = session;
        int expectedGeneration = generation;
        int ticket = ++packageDiscoveryGeneration;
        showAuthoringStatus("正在建议 MCP 调用参数…");
        worker.submit(() -> {
            AiToolPackages.ToolSuggestion answer;
            try { answer = request.read(active.skillModel(AiToolPackages.TOOL_INSTRUCTION, request.input())); }
            catch (Exception unavailable) { answer = null; }
            AiToolPackages.ToolSuggestion proposed = answer;
            main.post(() -> {
                if (disposed || busy || generation != expectedGeneration || skillStore != activeStore
                        || session != active || ticket != packageDiscoveryGeneration) return;
                if (proposed == null) {
                    showAuthoringStatus("没有可核对的模型建议，请直接选择工具。");
                    showMcpPackageTools(item, tools, "");
                } else {
                    showAuthoringStatus("");
                    confirmMcpTool(item, proposed.tool(), proposed.arguments());
                }
            });
        });
    }

    private void showMcpToolArguments(AiToolPackages.Item item, McpRegistry.PackageTool tool, String argument) {
        EditText parameters = new EditText(getContext());
        parameters.setTag("ai.chat.package.mcp.arguments");
        parameters.setHint("JSON 参数");
        parameters.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        parameters.setMinLines(3);
        parameters.setText(argument.trim().startsWith("{") ? argument : "{}");
        if (localMenu != null) localMenu.dismiss();
        localMenu = new MiuixDialogBuilder(getContext(), useMiuix).setTitle(tool.name() + " · JSON 参数")
                .setView(parameters)
                .setPositiveButton("继续", (dialog, which) -> {
                    if (localMenu == dialog && !disposed && !busy) confirmMcpTool(item, tool, parameters.getText().toString().trim());
                }).setNegativeButton("取消", null).create();
        localMenu.show();
    }

    private void confirmMcpTool(AiToolPackages.Item item, McpRegistry.PackageTool tool, String arguments) {
        try {
            if (arguments.length() > 4096) throw new IllegalArgumentException();
            SkillAuthoring.rejectEditorCredential(arguments);
            new org.json.JSONObject(arguments);
        } catch (Exception invalid) {
            showAuthoringStatus("请输入有效的 JSON 对象，参数中不要包含凭据。");
            return;
        }
        if (localMenu != null) localMenu.dismiss();
        localMenu = new MiuixDialogBuilder(getContext(), useMiuix).setTitle("确认本次 MCP 调用")
                .setMessage(item.name() + " / " + tool.name() + "\n" + arguments)
                .setPositiveButton("确认调用", (dialog, which) -> {
                    if (localMenu != dialog || disposed || busy) return;
                    try {
                        sendPackageInvocation("调用 " + item.name() + " / " + tool.name() + "\n" + arguments,
                                toolPackages().mcpInvocation(item, tool, arguments));
                    } catch (IllegalArgumentException | IllegalStateException unavailable) {
                        showAuthoringStatus("MCP 服务或工具已变化，请重新选择。");
                    }
                }).setNegativeButton("取消", null).create();
        localMenu.show();
    }

    private void sendPackageInvocation(String prompt, AiSkills.Invocation invocation) {
        sendMessage(prompt, List.of(), List.of(), null, null, null, true, null, invocation);
    }

    private void showEmojiChoices() {
        if (disposed) return;
        String[] emoji = {"😀", "😊", "😂", "❤️", "👍", "🙏", "😴", "🤦"};
        if (localMenu != null) localMenu.dismiss();
        localMenu = new MiuixDialogBuilder(getContext(), useMiuix).setTitle("表情")
                .setItems(emoji, (child, which) -> insertDraft(emoji[which]))
                .setNegativeButton("取消", null).create();
        localMenu.show();
    }

    private void insertDraft(String value) {
        int start = Math.max(0, input.getSelectionStart());
        int end = Math.max(start, input.getSelectionEnd());
        input.getText().replace(start, end, value);
        input.requestFocus();
    }

    private void startSpeechInput() {
        if (disposed || busy || speechInput != null) return;
        speechInput = HostSpeechInput.launch(ownerActivity(), text -> {
            speechInput = null;
            if (disposed) return;
            if (text != null && !text.isEmpty()) insertDraft(text);
            updateSend();
        });
        if (speechInput == null) android.widget.Toast.makeText(getContext(), "当前设备没有可用的系统语音输入",
                android.widget.Toast.LENGTH_SHORT).show();
        updateSend();
    }

    private void chooseAttachment() {
        if (disposed || busy) return;
        attachmentMenu = new MiuixDialogBuilder(getContext(), useMiuix).setTitle("添加附件")
                .setItems(new String[]{"图片", "视频", "文件"}, (dialog, which) -> {
                    attachmentMenu = null;
                    selectAttachment(AttachmentKind.values()[which]);
                }).setNegativeButton("取消", null).create();
        attachmentMenu.show();
    }

    private void selectAttachment(AttachmentKind kind) {
        if (disposed || busy) return;
        if (picker != null) picker.close();
        if (mediaPicker != null) mediaPicker.close();
        Activity activity = ownerActivity();
        if (kind != AttachmentKind.FILE && activity != null
                && "com.ss.android.ugc.aweme".equals(activity.getPackageName())) {
            mediaPicker = HostMediaPicker.launch(activity, kind == AttachmentKind.VIDEO, selected -> {
                mediaPicker = null;
                if (disposed || selected == null) return;
                for (HostMediaPicker.Selection media : selected) {
                    stageMedia(media.uri, media.video ? AttachmentKind.VIDEO : AttachmentKind.IMAGE, media.name);
                }
                updateSend();
            });
            if (mediaPicker == null) state.setText("无法打开抖音相册，请确认当前抖音版本受支持");
            updateSend();
            return;
        }
        String[] mime = kind == AttachmentKind.IMAGE ? new String[]{"image/*"}
                : kind == AttachmentKind.VIDEO ? new String[]{"video/*"}
                : new String[]{"text/*", "application/json", "application/pdf",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"};
        picker = HostImagePicker.launch(ownerActivity(), mime, uri -> {
            picker = null;
            if (uri != null && !disposed) loadMedia(uri, kind);
        });
        if (picker == null) state.setText("无法打开附件选择器");
        updateSend();
    }

    private void loadMedia(android.net.Uri uri, AttachmentKind kind) {
        stageMedia(uri, kind, null);
    }

    private void stageMedia(android.net.Uri uri, AttachmentKind kind, String name) {
        if (disposed || busy || uri == null) return;
        for (PendingAttachment attachment : draftPending) if (attachment.uri.equals(uri)) return;
        if (draftPending.size() >= 4) {
            state.setText("每条消息最多选择 4 个附件");
            updateSend();
            return;
        }
        // No resolver query, file read, thumbnail decode or video frame extraction on selection.
        draftPending.add(new PendingAttachment(uri, kind, name));
        state.setText("");
        renderAttachments();
        updateSend();
    }

    private static LoadedMedia readMedia(Context context, android.net.Uri uri, AttachmentKind kind,
                                         int imageBudget) throws Exception {
        ChatMediaLoader.Kind type = kind == AttachmentKind.IMAGE ? ChatMediaLoader.Kind.IMAGE
                : kind == AttachmentKind.VIDEO ? ChatMediaLoader.Kind.VIDEO : ChatMediaLoader.Kind.DOCUMENT;
        ChatMediaLoader.Document document = ChatMediaLoader.loadAttachment(context, uri, type, imageBudget);
        return new LoadedMedia(document.name, document.text, document.images);
    }

    void addImage(ChatImage image) {
        if (disposed || busy || draftImages.size() >= ChatCompletionClient.MAX_IMAGES_PER_MESSAGE) return;
        draftImages.add(image);
        renderAttachments();
        updateSend();
    }

    void addDocument(String name, String text) {
        if (disposed || busy || draftDocuments.size() >= 4) return;
        Document document = new Document(name, text);
        List<Document> next = new ArrayList<>(draftDocuments);
        next.add(document);
        if (documentText(next, draftImages).length() > 32768) throw new IllegalArgumentException("附件文本总计不能超过 32768 个字符");
        draftDocuments.add(document);
        renderAttachments();
        updateSend();
    }

    void addAttachment(String name, String text, List<ChatImage> images) {
        if (disposed || busy) return;
        if (draftImages.size() + images.size() > ChatCompletionClient.MAX_IMAGES_PER_MESSAGE)
            throw new IllegalArgumentException("每条消息最多包含 4 张图片或视频帧");
        if (!text.isEmpty() && draftDocuments.size() >= 4)
            throw new IllegalArgumentException("每条消息最多添加 4 个文件或视频说明");
        if (!text.isEmpty()) {
            Document document = new Document(name, text, images);
            List<Document> next = new ArrayList<>(draftDocuments);
            next.add(document);
            List<ChatImage> combined = new ArrayList<>(draftImages);
            combined.addAll(images);
            if (documentText(next, combined).length() > 32768)
                throw new IllegalArgumentException("附件文本总计不能超过 32768 个字符");
            draftDocuments.add(document);
        }
        draftImages.addAll(images);
        renderAttachments();
        updateSend();
    }

    private void renderAttachments() {
        attachments.removeAllViews();
        for (PendingAttachment attachment : draftPending) {
            LinearLayout item = new LinearLayout(getContext());
            item.setTag("ai.chat.pending_card");
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(12), dp(4), dp(4), dp(4));
            item.setBackground(background(SURFACE, dp(12), LINE));
            TextView type = label(attachment.kind == AttachmentKind.VIDEO ? "视频" :
                    attachment.kind == AttachmentKind.IMAGE ? "图片" : "文件", 12, MUTED);
            item.addView(type);
            TextView name = label(attachment.name, 13, TEXT);
            name.setTag("ai.chat.pending_name");
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setMaxWidth(dp(180));
            name.setPadding(dp(8), 0, dp(4), 0);
            item.addView(name);
            TextView remove = label("×", 18, MUTED);
            remove.setTag("ai.chat.remove_pending");
            remove.setGravity(Gravity.CENTER);
            remove.setContentDescription("移除" + attachment.name);
            remove.setOnClickListener(ignored -> {
                draftPending.remove(attachment);
                renderAttachments();
                updateSend();
            });
            item.addView(remove, new LinearLayout.LayoutParams(dp(40), dp(40)));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52));
            params.setMarginEnd(dp(8));
            attachments.addView(item, params);
        }
        for (ChatImage image : draftImages) {
            LinearLayout item = new LinearLayout(getContext());
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.addView(preview(image), new LinearLayout.LayoutParams(dp(64), dp(56)));
            ImageButton remove = icon("移除图片", "ai.chat.remove_image", android.R.drawable.ic_menu_close_clear_cancel);
            remove.setOnClickListener(ignored -> {
                Document group = null;
                for (Document document : draftDocuments) if (document.images.contains(image)) { group = document; break; }
                if (group != null) { draftImages.removeAll(group.images); draftDocuments.remove(group); }
                else draftImages.remove(image);
                renderAttachments(); updateSend();
            });
            item.addView(remove, square());
            attachments.addView(item);
        }
        for (Document document : draftDocuments) {
            LinearLayout item = new LinearLayout(getContext());
            item.setTag("ai.chat.document_attachment");
            item.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = label(document.name, 13, TEXT);
            name.setTag("ai.chat.document_name");
            name.setMaxLines(2);
            name.setMaxWidth(dp(160));
            item.addView(name);
            ImageButton remove = icon("移除文件", "ai.chat.remove_document", android.R.drawable.ic_menu_close_clear_cancel);
            remove.setOnClickListener(ignored -> {
                draftDocuments.remove(document); draftImages.removeAll(document.images);
                renderAttachments(); updateSend();
            });
            item.addView(remove, square());
            attachments.addView(item);
        }
        attachmentScroll.setVisibility(draftPending.isEmpty() && draftImages.isEmpty()
                && draftDocuments.isEmpty() ? View.GONE : View.VISIBLE);
        evictUnusedThumbnails();
    }

    private ImageView preview(ChatImage image) {
        ImageView view = new ImageView(getContext());
        view.setTag("ai.chat.image");
        view.setContentDescription("图片");
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Bitmap cached = thumbnails.get(image);
        if (cached != null) view.setImageBitmap(cached);
        else {
            thumbnailLoads.removeIf(Future::isDone);
            int ticket = thumbnailGeneration;
            WeakReference<ImageView> target = new WeakReference<>(view);
            thumbnailLoads.add(historyWorker.submit(() -> {
                Bitmap bitmap = thumbnail(image);
                main.post(() -> {
                    if (disposed || ticket != thumbnailGeneration) {
                        if (bitmap != null) bitmap.recycle();
                        return;
                    }
                    ImageView current = target.get();
                    if (bitmap == null) return;
                    if (!retains(image)) { bitmap.recycle(); return; }
                    Bitmap existing = thumbnails.get(image);
                    if (existing == null) thumbnails.put(image, bitmap);
                    else { bitmap.recycle(); }
                    if (current != null) current.setImageBitmap(thumbnails.get(image));
                    if (miuixSurface != null) miuixSurface.refresh();
                });
            }));
        }
        return view;
    }

    private void showImagePreview(ChatImage image) {
        Activity owner = ownerActivity();
        if (disposed || owner == null || owner.isFinishing() || owner.isDestroyed() || !retains(image)) return;
        closeImagePreview();
        try {
            AiChatImagePreview child = new AiChatImagePreview(owner, image, worker);
            imagePreview = child;
            child.setOnDismissListener(ignored -> { if (imagePreview == child) imagePreview = null; });
            child.show();
        } catch (RuntimeException unavailable) {
            closeImagePreview();
            state.setText("无法打开图片预览");
            updateSend();
        }
    }

    private void closeImagePreview() {
        AiChatImagePreview child = imagePreview;
        imagePreview = null;
        if (child != null) child.dismiss();
    }

    private boolean retains(ChatImage image) {
        if (draftImages.contains(image)) return true;
        for (ChatMessage message : transcript) if (message.images.contains(image)) return true;
        return false;
    }

    private void evictUnusedThumbnails() {
        java.util.Iterator<Map.Entry<ChatImage, Bitmap>> entries = thumbnails.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<ChatImage, Bitmap> entry = entries.next();
            // Other visible rows may still be attached until ListView relayouts, so let GC
            // release their bitmap rather than recycling a drawable still used by a row.
            if (!retains(entry.getKey())) entries.remove();
        }
    }

    private Bitmap thumbnail(ChatImage image) {
        if (Thread.currentThread().isInterrupted()) return null;
        try { return thumbnailLoader.load(image); }
        catch (RuntimeException | OutOfMemoryError unavailable) { return null; }
    }

    private void cancelThumbnails() {
        ++thumbnailGeneration;
        for (Future<?> load : thumbnailLoads) load.cancel(true);
        thumbnailLoads.clear();
        thumbnails.clear();
    }

    private void closePickers() {
        if (picker != null) picker.close();
        picker = null;
        if (mediaPicker != null) mediaPicker.close();
        mediaPicker = null;
        if (csvPicker != null) csvPicker.close();
        csvPicker = null;
    }

    private void bindTable(LinearLayout container, SkillTable table) {
        container.removeAllViews();
        container.setVisibility(table == null ? View.GONE : View.VISIBLE);
        if (table == null) return;
        int columns = Math.min(6, table.columns().size());
        int rows = Math.min(6, table.rows().size());
        TextView summary = label(table.rows().size() + " 行 · " + table.columns().size() + " 列", 13, MUTED);
        container.addView(summary, wrap());
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setTag("ai.chat.table.preview");
        LinearLayout grid = new LinearLayout(getContext());
        grid.setOrientation(LinearLayout.VERTICAL);
        addTableRow(grid, table.columns(), columns, true);
        for (int row = 0; row < rows; row++) addTableRow(grid, table.rows().get(row), columns, false);
        scroll.addView(grid);
        container.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        if (columns < table.columns().size() || rows < table.rows().size()) {
            container.addView(label("导出 CSV 查看完整表格", 12, MUTED), wrap());
        }
        Button export = new Button(getContext());
        export.setAllCaps(false);
        export.setText(ModuleUiStrings.get(getContext(), R.string.ai_chat_table_export_csv, "导出 CSV"));
        export.setTag("ai.chat.table.export");
        export.setOnClickListener(ignored -> exportTable(table));
        container.addView(export, wrap());
    }

    private void addTableRow(LinearLayout grid, List<String> values, int columns, boolean heading) {
        LinearLayout row = new LinearLayout(getContext());
        for (int column = 0; column < columns; column++) {
            TextView cell = label(values.get(column), 13, TEXT);
            cell.setMaxLines(2);
            cell.setEllipsize(TextUtils.TruncateAt.END);
            cell.setPadding(dp(6), dp(4), dp(6), dp(4));
            if (heading) cell.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            row.addView(cell, new LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        grid.addView(row, wrap());
    }

    private void exportTable(SkillTable table) {
        if (disposed || csvPicker != null) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/csv").putExtra(Intent.EXTRA_TITLE, "DouAi.csv")
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        csvPicker = HostConfigFilePicker.launch(ownerActivity(), intent, uri -> {
            csvPicker = null;
            if (disposed || uri == null || !"content".equals(uri.getScheme())) return;
            historyWorker.execute(() -> {
                boolean saved = false;
                try (OutputStream output = getContext().getContentResolver().openOutputStream(uri, "wt")) {
                    if (output != null) {
                        table.writeCsv(output);
                        saved = true;
                    }
                } catch (Exception unavailable) { saved = false; }
                boolean result = saved;
                main.post(() -> {
                    if (!disposed) state.setText(result ? "CSV 已导出" : "无法写入所选 CSV 文件");
                });
            });
        });
        if (csvPicker == null) {
            state.setText(ModuleUiStrings.get(getContext(), R.string.ai_chat_csv_picker_unavailable, "无法打开 CSV 文件选择器"));
        }
    }

    private Activity ownerActivity() {
        if (getOwnerActivity() != null) return getOwnerActivity();
        Context context = getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return null;
    }

    private static int imageBytes(List<ChatImage> images) {
        int bytes = 0;
        for (ChatImage image : images) bytes += image.sizeBytes();
        return bytes;
    }

    private static final class RequestTurn {
        SkillVideoFacts videoFacts;
        SkillStore.ConversationDraft openedSkill;
        final DouAiLog.Operation trace = DouAiLog.begin("ai_chat", "send_or_retry");
        final com.douai.module.analytics.LocalAnalytics.FlowOperation analytics;
        final int ticket;
        final String prompt;
        AiSkills.Entry entry;
        SkillVideoJobs.Scope videoScope;
        final List<ChatImage> images;
        final List<Document> documents;
        final List<PendingAttachment> attachments;
        final List<ChatImage> preparedImages;
        final List<Document> preparedDocuments;
        final ChatMessage answer;
        final AiSkills.Invocation skill;
        final SkillAuthoring.Action authoring;
        volatile boolean stopRequested;
        volatile boolean usingSkillModel;
        volatile String latestPartial = "";
        boolean partialQueued;
        boolean started;
        boolean reading;
        List<DouyinSearch.Result> searchResults = Collections.emptyList();
        DouyinSearch searchBridge;
        TestChatMemory transcriptMemory;
        long transcriptEpoch;
        boolean transcriptUnavailable;
        boolean localDraftWrite;
        boolean editorChanged;
        boolean savingHistory;
        volatile boolean discardHistory;
        String settingsSkillName;

        RequestTurn(int ticket, String prompt, List<ChatImage> images, List<Document> documents,
                    List<PendingAttachment> attachments, ChatMessage answer, AiSkills.Invocation skill, SkillAuthoring.Action authoring,
                    com.douai.module.analytics.LocalAnalytics.FlowOperation analytics) {
            this.analytics=analytics;
            this.ticket = ticket;
            this.prompt = prompt;
            this.images = images;
            this.documents = documents;
            this.attachments = attachments;
            this.preparedImages = new ArrayList<>(images);
            this.preparedDocuments = new ArrayList<>(documents);
            this.answer = answer;
            this.skill = skill;
            this.authoring = authoring;
        }

        boolean manualSkill() { return authoring != null || skill != null; }

        void checkStopped() throws InterruptedException {
            if (stopRequested || Thread.currentThread().isInterrupted()) throw new InterruptedException("已停止");
        }
    }

    private static final class ChatMessage {
        final String role;
        final boolean user;
        final List<ChatImage> images;
        String text;
        String copyText;
        final List<String> documentLabels = new ArrayList<>();
        int color = TEXT;
        boolean retry;
        String retryPrompt;
        List<ChatImage> retryImages = Collections.emptyList();
        List<Document> retryDocuments = Collections.emptyList();
        List<PendingAttachment> retryPending = Collections.emptyList();
        ChatMessage sourceUser;
        String settingsSkillName;
        AiSearchVideoCards.Group videos;
        SkillResult artifact;

        ChatMessage(String role, String text, boolean user, List<ChatImage> images) {
            this.role = role;
            this.text = text;
            copyText = user ? text : null;
            this.user = user;
            this.images = new ArrayList<>(images);
        }
    }

    private final class TranscriptRows extends BaseAdapter {
        @Override public int getCount() { return transcript.size(); }
        @Override public ChatMessage getItem(int position) { return transcript.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            try {
                MessageRow row = recycled instanceof MessageRow ? (MessageRow) recycled
                        : recycled != null && recycled.getTag() instanceof MessageRow ? (MessageRow) recycled.getTag()
                        : new MessageRow(getContext());
                row.bind(getItem(position));
                return row.shell;
            } catch (RuntimeException | LinkageError failure) {
                if (nativeViews == null) throw failure;
                if (!nativeFailure) {
                    nativeFailure = true;
                    DouAiLog.legacy(5, "DouAi.AiContact", "Native message unavailable", failure);
                    main.post(() -> {
                        android.widget.Toast.makeText(getContext(), "原生消息控件不可用，请查看模块适配信息",
                                android.widget.Toast.LENGTH_LONG).show();
                        dismiss();
                    });
                }
                return label("原生消息控件不可用", 14, ERROR);
            }
        }
    }

    private final class MessageRow extends LinearLayout {
        private final LinearLayout bubble;
        private final TextView role;
        private final TextView body;
        private final TextView document;
        private final LinearLayout images;
        private final LinearLayout videos;
        private final LinearLayout table;
        private final LinearLayout skillVideo;
        private final ImageButton retry;
        private final Button settingsAction;
        private final HostNativeChatViews.TextBubble nativeBubble;
        private final ImageView messageAvatar;
        private final View shell;
        private Boolean avatarUser;

        MessageRow(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            if (nativeViews != null) {
                try {
                    ViewGroup nativeShell = nativeViews.createMessageShell(this);
                    messageAvatar = nativeViews.createAvatar(this);
                    messageAvatar.setTag("ai.chat.message_avatar");
                    nativeShell.addView(this, new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    nativeShell.setTag(this);
                    shell = nativeShell;
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("原生消息行不可用", failure);
                }
            } else {
                shell = this;
                messageAvatar = null;
            }
            bubble = new LinearLayout(context);
            bubble.setOrientation(VERTICAL);
            if (nativeViews == null) bubble.setPadding(dp(12), dp(8), dp(12), dp(8));
            role = label("", 12, MUTED);
            role.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            bubble.addView(role, wrap());
            body = label("", 15, TEXT);
            body.setPadding(0, dp(4), 0, 0);
            if (nativeViews == null) {
                nativeBubble = null;
                bubble.addView(body, wrap());
            } else {
                try { nativeBubble = nativeViews.createTextBubble(bubble, false); }
                catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("原生消息控件不可用", failure);
                }
                bubble.addView(nativeBubble.view, new LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            document = label("", 13, MUTED);
            document.setTag("ai.chat.document");
            bubble.addView(document, wrap());
            images = new LinearLayout(context);
            images.setOrientation(VERTICAL);
            images.setTag("ai.chat.message.images");
            bubble.addView(images, wrap());
            table = new LinearLayout(context);
            table.setOrientation(VERTICAL);
            table.setTag("ai.chat.table");
            bubble.addView(table, new LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT));
            skillVideo = new LinearLayout(context);
            skillVideo.setOrientation(VERTICAL);
            skillVideo.setTag("ai.chat.video_task");
            bubble.addView(skillVideo, new LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT));
            videos = new LinearLayout(context);
            videos.setOrientation(VERTICAL);
            videos.setTag("ai.chat.message.videos");
            bubble.addView(videos, new LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT));
            retry = icon("重新发送", "ai.chat.retry", android.R.drawable.ic_menu_rotate);
            bubble.addView(retry, square());
            settingsAction = new Button(context);
            settingsAction.setText("打开该技能设置");
            settingsAction.setAllCaps(false);
            bubble.addView(settingsAction, wrap());
            addView(bubble, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        void bind(ChatMessage message) {
            int color = message.user ? ACCENT : MUTED;
            role.setText(message.role);
            role.setTextColor(color);
            if (nativeBubble != null) role.setVisibility(GONE);
            body.setTag(message.user ? "ai.chat.user" : "ai.chat.answer");
            body.setText(message.text);
            body.setTextColor(message.color);
            body.setVisibility(!message.user && (!message.images.isEmpty() || message.artifact != null) && TextUtils.isEmpty(message.text)
                    ? GONE : VISIBLE);
            boolean hasMessageText = copyableMessageText(message.copyText);
            View.OnLongClickListener actions = hasMessageText
                    || message.artifact instanceof SkillResult.Declaration ? ignored -> showMessageActions(message) : null;
            if (nativeBubble != null) {
                nativeBubble.view.setTag(message.user ? "ai.chat.user" : "ai.chat.answer");
                int rowWidth = messages.getWidth() > 0 ? messages.getWidth()
                        : getContext().getResources().getDisplayMetrics().widthPixels;
                int avatarWidth = messageAvatar.getLayoutParams().width;
                int available = rowWidth - messages.getPaddingLeft() - messages.getPaddingRight()
                        - avatarWidth - dp(20);
                nativeBubble.bind(message.text, message.color == TEXT ? nativeViews.messageTextColor(message.user)
                                : ModuleTheme.color(getContext(), message.color),
                        nativeViews.messageTextMaxWidth(Math.max(1, available)), message.user);
                nativeBubble.view.setVisibility(TextUtils.isEmpty(message.text) ? GONE : VISIBLE);
                document.setTextColor(nativeViews.input.getCurrentHintTextColor());
                bindMessageActions(nativeBubble.view, actions);
                bindMessageActions(nativeBubble.textView(), actions);
            } else {
                if (body.isTextSelectable() != hasMessageText) body.setTextIsSelectable(hasMessageText);
                bindMessageActions(body, actions);
            }
            document.setVisibility(message.documentLabels.isEmpty() ? GONE : VISIBLE);
            document.setText(message.documentLabels.isEmpty() ? ""
                    : "已保存的附件：" + TextUtils.join("、", message.documentLabels));
            images.removeAllViews();
            for (ChatImage image : message.images) {
                ImageView thumbnail = preview(image);
                if (!message.user) {
                    thumbnail.setContentDescription("生成的图片");
                    thumbnail.setOnClickListener(ignored -> showImagePreview(image));
                }
                images.addView(thumbnail, new LayoutParams(dp(128), dp(96)));
            }
            int rowWidthForVideos = messages.getWidth() > 0 ? messages.getWidth()
                    : getContext().getResources().getDisplayMetrics().widthPixels;
            int reservedForVideos = messages.getPaddingLeft() + messages.getPaddingRight() + dp(48)
                    + (messageAvatar == null ? 0 : messageAvatar.getLayoutParams().width);
            videos.getLayoutParams().width = Math.min(dp(240), Math.max(dp(120), rowWidthForVideos - reservedForVideos));
            table.getLayoutParams().width = videos.getLayoutParams().width;
            bindTable(table, message.artifact instanceof SkillResult.Table result ? result.table() : null);
            skillVideo.getLayoutParams().width = videos.getLayoutParams().width;
            skillVideoCards.bind(skillVideo, message.artifact instanceof SkillResult.Video captured ? captured.requestId() : null);
            searchVideoCards.bind(videos, message.user ? null : message.videos);
            retry.setVisibility(message.retry ? VISIBLE : GONE);
            retry.setOnClickListener(message.retry ? ignored -> sendMessage(message.retryPrompt,
                    message.retryImages, message.retryDocuments, message) : null);
            String skillName = message.settingsSkillName;
            settingsAction.setVisibility(skillName == null ? GONE : VISIBLE);
            settingsAction.setTag(skillName == null ? null : "ai.chat.skill.settings." + skillName);
            settingsAction.setContentDescription(skillName == null ? null : "打开 /" + skillName + " 的设置");
            settingsAction.setOnClickListener(skillName == null ? null : ignored -> showSkillDetails(skillName));
            if (nativeBubble == null) bubble.setBackground(background(
                    message.user ? Color.rgb(225, 243, 237) : SURFACE, dp(14),
                    message.user ? Color.rgb(210, 235, 227) : LINE));
            setGravity((message.user ? Gravity.END : Gravity.START) | Gravity.TOP);
            removeView(bubble);
            LayoutParams params = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (messageAvatar == null) {
                if (message.user) params.leftMargin = dp(24);
                else params.rightMargin = dp(24);
                params.bottomMargin = dp(10);
                addView(bubble, params);
            } else {
                removeView(messageAvatar);
                ViewGroup.LayoutParams nativeAvatar = messageAvatar.getLayoutParams();
                LayoutParams avatarParams = new LayoutParams(nativeAvatar.width, nativeAvatar.height);
                messageAvatar.setContentDescription(message.user ? "我的头像" : assistantName + "头像");
                if (message.user) {
                    if (avatarUser == null || !avatarUser) {
                        messageAvatar.setImageResource(android.R.drawable.sym_def_app_icon);
                        if (accountAvatar != null) accountAvatar.bind(messageAvatar);
                    }
                    avatarParams.setMarginStart(dp(8));
                    addView(bubble, params);
                    addView(messageAvatar, avatarParams);
                } else {
                    if (avatarUser == null || avatarUser) {
                        if (contactAvatarBitmap == null) messageAvatar.setImageResource(android.R.drawable.sym_def_app_icon);
                        else messageAvatar.setImageBitmap(contactAvatarBitmap);
                    }
                    avatarParams.setMarginEnd(dp(8));
                    addView(messageAvatar, avatarParams);
                    addView(bubble, params);
                }
                avatarUser = message.user;
            }
        }
    }

    private static void clearMessageAvatars(View view) {
        if (view instanceof ImageView && "ai.chat.message_avatar".equals(view.getTag())) {
            ((ImageView) view).setImageDrawable(null);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) clearMessageAvatars(group.getChildAt(index));
        }
    }

    private void restoreConversation() {
        int ticket = ++restoreGeneration;
        Session active = session;
        AiSkills.Entry entry = skillEntry;
        restoreLoad = historyWorker.submit(() -> {
            List<TestChatMemory.Turn> saved = Collections.emptyList();
            SkillVideoJobs.Scope scope = null;
            TestChatMemory.SkillDraft draft = null;
            SkillStore.DraftPreview restoredPreview = null;
            SkillVideoFacts restoredFacts = null;
            try {
                TestChatMemory memory = active.transcriptMemory();
                saved = memory == null ? active.savedTurns() : entry != null ? memory.readTranscript() : memory.read();
                if (entry != null && memory != null) draft = memory.readSkillDraft();
                scope = videoScope(memory, entry);
                if (scope != null) saved = recoverVideoRows(memory, scope, saved);
            }
            catch (SkillVideoJobs.Failure | RuntimeException unavailable) { /* Keep any readable chat rows. */ }
            if (draft != null && !draft.definition().isEmpty()) {
                try {
                    restoredPreview = SkillStore.validateAuthoredDraft(draft.definition());
                    if (!draft.pendingName().isEmpty() && !draft.pendingName().equals(restoredPreview.name())) {
                        restoredPreview = null;
                    } else if (restoredPreview.outputType() == SkillResult.Type.VIDEO) {
                        restoredFacts = draft.videoFacts().isEmpty() ? SkillVideoFacts.empty()
                                : SkillVideoFacts.fromJson(new org.json.JSONObject(draft.videoFacts()));
                        restoredPreview = SkillStore.prepareAuthoredVideoDraft(draft.definition(), restoredFacts);
                    }
                } catch (org.json.JSONException | IllegalArgumentException malformed) {
                    restoredPreview = null;
                }
            }
            TestChatMemory.SkillDraft restoredDraft = draft;
            SkillStore.DraftPreview preview = restoredPreview;
            SkillVideoFacts facts = restoredFacts;
            SkillVideoJobs.Scope restoredScope = scope;
            List<TestChatMemory.Turn> retained = new ArrayList<>();
            Map<ChatImage, Bitmap> decoded = new IdentityHashMap<>();
            int bytes = 0;
            for (int index = saved.size() - 1; index >= 0 && retained.size() < MAX_VISIBLE_TURNS; index--) {
                if (Thread.currentThread().isInterrupted()) { recycle(decoded); return; }
                TestChatMemory.Turn turn = saved.get(index);
                int imageBytes = imageBytes(turn.images) + imageBytes(turn.assistantImages);
                if (bytes + imageBytes > MAX_VISIBLE_IMAGE_BYTES) continue;
                bytes += imageBytes;
                retained.add(0, turn);
                for (ChatImage image : turn.images) decoded.put(image, thumbnail(image));
                for (ChatImage image : turn.assistantImages) decoded.put(image, thumbnail(image));
            }
            main.post(() -> {
                if (disposed || ticket != restoreGeneration) { recycle(decoded); return; }
                restoreLoad = null;
                if (RESTORING_CHAT.contentEquals(state.getText())) state.setText("");
                videoScope = restoredScope;
                storedSkillDraft = restoredDraft;
                if (preview != null && skillStore != null && skillStore.enabled()) {
                    skillDraft = preview;
                    skillDraftFacts = facts;
                    skillAuthoringId = restoredDraft.authoringId();
                    pendingSkillDraftId = restoredDraft.pendingDraftId();
                    skillEditorSourceProof = restoredDraft.editorSourceProof();
                    skillEditorReplacesVideo = restoredDraft.replacesVideoContract();
                }
                for (Map.Entry<ChatImage, Bitmap> item : decoded.entrySet()) {
                    if (item.getValue() != null) thumbnails.put(item.getKey(), item.getValue());
                }
                for (TestChatMemory.Turn turn : retained) {
                    ChatMessage user = addMessage("我", turn.prompt, true, turn.images);
                    if (!turn.document.isEmpty()) {
                        user.documentLabels.add("文本");
                    }
                    ChatMessage answer = addMessage(assistantName, turn.answer, false, turn.assistantImages);
                    answer.settingsSkillName = turn.settingsSkillName;
                    answer.artifact = turn.artifact;
                    if (!turn.displayOnly || (!(turn.artifact instanceof SkillResult.Declaration)
                            && SkillAuthoring.match(turn.prompt, true) == null
                            && SkillAuthoring.continuation(turn.prompt) == null
                            && !SkillAuthoring.isReferencedVideoInstall(turn.prompt)
                            && !SkillAuthoring.usesPreviousVideoMaterial(turn.prompt))) answer.copyText = turn.answer;
                }
                messageRows.notifyDataSetChanged();
                watchVideoCards();
                scrollToBottom();
                updateSend();
            });
        });
    }

    private List<TestChatMemory.Turn> recoverVideoRows(TestChatMemory memory, SkillVideoJobs.Scope scope,
                                                       List<TestChatMemory.Turn> saved) throws SkillVideoJobs.Failure {
        Set<UUID> known = new HashSet<>();
        for (TestChatMemory.Turn turn : saved) {
            if (turn.artifact instanceof SkillResult.Video video) known.add(video.requestId());
        }
        long epoch = memory.epoch();
        boolean changed = false;
        for (SkillVideoJobs.Job job : skillVideoJobs.list(scope)) {
            if (Thread.currentThread().isInterrupted()) break;
            if (job.linked()) continue;
            if (known.contains(job.localId())) {
                skillVideoJobs.markLinked(scope, job.localId());
            } else if (memory.append(epoch, TestChatMemory.Turn.displayedSkill("视频任务", "", List.of(), null,
                    new SkillResult.Video(job.localId())))) {
                changed = true;
                skillVideoJobs.markLinked(scope, job.localId());
            }
        }
        return changed ? memory.readTranscript() : saved;
    }

    private void cancelRestore() {
        ++restoreGeneration;
        if (restoreLoad != null) restoreLoad.cancel(true);
        restoreLoad = null;
    }

    private static void recycle(Map<ChatImage, Bitmap> images) {
        for (Bitmap bitmap : images.values()) if (bitmap != null) bitmap.recycle();
    }

    private static String documentText(List<Document> documents, List<ChatImage> images) {
        StringBuilder text = new StringBuilder();
        for (Document document : documents) {
            if (text.length() > 0) text.append("\n\n");
            text.append("[文件：").append(document.name).append("]\n");
            if (!document.images.isEmpty()) {
                List<String> positions = new ArrayList<>();
                for (ChatImage image : document.images) {
                    int position = images.indexOf(image);
                    if (position >= 0) positions.add(String.valueOf(position + 1));
                }
                text.append("对应本条消息的图片序号（从 1 开始）：")
                        .append(TextUtils.join("、", positions)).append("。\n");
            }
            text.append(document.text);
        }
        return text.toString();
    }

    private static final class Document {
        final String name;
        final String text;
        final List<ChatImage> images;
        Document(String name, String text) {
            this(name, text, Collections.emptyList());
        }
        Document(String name, String text, List<ChatImage> images) {
            this.name = name == null || name.isEmpty() ? "文件" : name;
            this.text = text == null ? "" : text;
            this.images = new ArrayList<>(images);
        }
    }

    enum AttachmentKind { IMAGE, VIDEO, FILE }

    static final class LoadedMedia {
        final String name;
        final String text;
        final List<ChatImage> images;
        LoadedMedia(String name, String text, List<ChatImage> images) {
            this.name = name;
            this.text = text;
            this.images = new ArrayList<>(images);
        }
    }

    private static final class PendingAttachment {
        final android.net.Uri uri;
        final AttachmentKind kind;
        final String name;
        volatile LoadedMedia prepared;

        PendingAttachment(android.net.Uri uri, AttachmentKind kind, String name) {
            this.uri = uri;
            this.kind = kind;
            String fallback = kind == AttachmentKind.VIDEO ? "已选择视频" : kind == AttachmentKind.IMAGE ? "已选择图片" : "已选择文件";
            String localName = "file".equalsIgnoreCase(uri.getScheme()) ? uri.getLastPathSegment() : null;
            this.name = !TextUtils.isEmpty(name) ? name.replace('\n', ' ').replace('\r', ' ')
                    : !TextUtils.isEmpty(localName) ? localName : fallback;
        }

        boolean needsImages() {
            return kind != AttachmentKind.FILE || name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf");
        }

        boolean mayNeedImages(Context context) {
            if (kind != AttachmentKind.FILE) return true;
            if (prepared != null) return !prepared.images.isEmpty();
            String mime = null;
            try { mime = context.getContentResolver().getType(uri); }
            catch (RuntimeException unavailable) { /* Reserve one slot for an opaque document. */ }
            if ("application/pdf".equalsIgnoreCase(mime)) return true;
            if (mime != null && (mime.startsWith("text/") || "application/json".equalsIgnoreCase(mime)
                    || "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equalsIgnoreCase(mime))) return false;
            String path = uri.getLastPathSegment();
            String hint = (name + " " + (path == null ? "" : path)).toLowerCase(java.util.Locale.ROOT);
            if (hint.endsWith(".txt") || hint.endsWith(".json") || hint.endsWith(".docx")) return false;
            // Unknown provider names can hide a PDF. Never let an earlier video use
            // the last image slot before that document is read on Send.
            return true;
        }
    }

    private static String failureDetail(Exception failure) {
        String message = failure.getMessage();
        return TextUtils.isEmpty(message) ? "无法读取所选附件，请重新选择" : message.substring(0, Math.min(220, message.length()));
    }

    private void scrollToBottom() {
        if (messages.getCount() > 0) messages.post(() -> messages.setSelection(messages.getCount() - 1));
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private ImageButton icon(String description, String tag, int resource) {
        ImageButton button = new ImageButton(getContext());
        button.setImageResource(resource);
        button.setImageTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.setTag(tag);
        button.setBackground(background(CANVAS, dp(14), LINE));
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        return button;
    }

    private static String errorMessage(Exception failure) {
        if (failure instanceof IllegalStateException || failure instanceof IllegalArgumentException) {
            String message = failure.getMessage();
            if (message != null) return "请求失败：" + message.substring(0, Math.min(220, message.length()));
        }
        if (failure instanceof java.net.SocketTimeoutException) return "请求超时，请稍后重试。";
        if (failure instanceof org.json.JSONException) return "无法读取模型回复，请检查接口协议。";
        return "请求失败，请检查网络和模型配置。";
    }

    private GradientDrawable background(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(radius);
        return shape;
    }

    private GradientDrawable background(int color, int radius, int strokeColor) {
        GradientDrawable shape = background(color, radius);
        shape.setStroke(dp(1), strokeColor);
        return shape;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams square() { return new LinearLayout.LayoutParams(dp(48), dp(48)); }
    private int dp(int value) { return Math.round(value * getContext().getResources().getDisplayMetrics().density); }
}
