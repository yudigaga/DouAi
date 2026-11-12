package com.douai.module.ui;

import com.douai.module.diagnostics.DouAiLog;

import android.app.Dialog;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Build;
import android.os.Looper;
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
import android.widget.EditText;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import com.douai.module.feature.DouyinSearch;
import com.douai.module.feature.ChatCompletionClient;
import com.douai.module.feature.ChatImage;
import com.douai.module.feature.ChatImageLoader;
import com.douai.module.feature.ChatMediaLoader;
import com.douai.module.feature.TestChatMemory;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import com.douai.module.ui.miuix.MiuixChatEditorSurface;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;

/** Model test conversation using a fixed profile snapshot and optional bounded private history. */
final class AiTestChatDialog extends Dialog {
    interface Session {
        String reply(String message, List<ChatImage> images) throws Exception;
        void cancel();
        default String reply(String message, List<ChatImage> images, String document) throws Exception {
            return reply(message, images);
        }
        default String replyStreaming(String message, List<ChatImage> images, String document,
                                      Consumer<String> onPartial) throws Exception {
            return reply(message, images, document);
        }
        default void clear() { }
        default List<TestChatMemory.Turn> savedTurns() { return Collections.emptyList(); }
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
    private final LinearLayout attachments;
    private final HorizontalScrollView attachmentScroll;
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
    private Runnable contactEditAction;
    private HostSpeechInput speechInput;
    private HostAccountAvatar accountAvatar;
    private ImageView profileAvatar;
    private HorizontalScrollView quickReplies;
    private final ArrayDeque<List<ChatImage>> visibleImages = new ArrayDeque<>();
    private int visibleImageBytes;
    private Future<?> restoreLoad;
    private Future<?> historyClear;
    private HostImagePicker picker;
    private HostMediaPicker mediaPicker;
    private Session session;
    private Future<?> request;
    private RequestTurn pending;
    private int generation;
    private int restoreGeneration;
    private int clearGeneration;
    private int thumbnailGeneration;
    private boolean busy;
    private boolean disposed;
    private MiuixChatEditorSurface miuixSurface;
    private final HostNativeChatViews nativeViews;
    private final AiSearchVideoCards searchVideoCards;
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
                private final List<DouyinSearch.Result> found = new ArrayList<>();
                private long searchEpoch;
                private synchronized ChatCompletionClient client() {
                    if (client == null) client = new ChatCompletionClient(messageContact
                            ? TestChatMemory.messageConversation(context, config) : new TestChatMemory(context, config),
                            diagnostic -> DouAiLog.legacy(4, messageContact ? "DouAi.AiContact" : "DouAi.ModelTest", diagnostic, null));
                    return client;
                }
                @Override public String reply(String message, List<ChatImage> images) throws Exception {
                    return client().reply(messageContact ? "message-contact" : "model-test", message, images, snapshot);
                }
                @Override public void cancel() {
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
                @Override public List<TestChatMemory.Turn> savedTurns() { return client().savedTurns(); }
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
            else sendMessage(input.getText().toString().trim(), new ArrayList<>(draftImages),
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
        restoreConversation();
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
        nativeViews.messageContainer.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        nativeViews.root.setFitsSystemWindows(Build.VERSION.SDK_INT < 30);
        // Theme only module-owned status/attachment content. Host controls retain their native palette.
        ModuleTheme.bind(state);
        ModuleTheme.bind(attachmentScroll);
    }

    private static void move(View view, ViewGroup parent, ViewGroup.LayoutParams params) {
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
        parent.addView(view, params);
    }

    @Override protected void onStart() {
        super.onStart();
        if (miuixSurface != null) miuixSurface.bindBackNavigation(this);
        Window window = getWindow();
        if (window != null) sizeWindow(window.getDecorView().getRootWindowInsets());
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
        List<PendingAttachment> selected = new ArrayList<>(retryAnswer == null ? draftPending : retryAnswer.retryPending);
        if (disposed || busy
                || (prompt.isEmpty() && images.isEmpty() && documents.isEmpty() && selected.isEmpty())) {
            DouAiLog.event("ai_chat", "eligibility", disposed ? "closed" : busy ? "busy" : "empty_input", ""); return;
        }
        cancelRestore();
        ChatMessage answer = retryAnswer;
        if (answer == null) {
            ChatMessage user = addMessage("我", prompt, true, images);
            for (Document document : documents) user.documentLabels.add(document.name);
            for (PendingAttachment attachment : selected) user.documentLabels.add(attachment.name);
            visibleImages.addLast(user.images);
            visibleImageBytes += imageBytes(user.images);
            trimVisibleHistory();
            answer = addMessage(assistantName, "正在回复…", false, Collections.emptyList());
            answer.sourceUser = user;
        }
        answer.text = selected.isEmpty() ? "正在回复…" : "正在读取附件…";
        if (answer.videos != null) { answer.videos.close(); answer.videos = null; }
        answer.retry = false;
        messageRows.notifyDataSetChanged();
        if (prompt.equals(input.getText().toString().trim())) input.setText("");
        if (draftImages.equals(images)) draftImages.clear();
        if (draftDocuments.equals(documents)) draftDocuments.clear();
        draftPending.removeAll(selected);
        renderAttachments();
        int ticket = ++generation;
        Session active = session;
        Future<?> clearBarrier = historyClear;
        RequestTurn turn = new RequestTurn(ticket, prompt, images, documents, selected, answer, com.douai.module.analytics.LocalAnalytics.get(getContext()).beginOperation(null,"ai"));
        pending = turn;
        busy = true;
        state.setText(answer.text);
        updateSend();
        scrollToBottom();
        turn.trace.stage("queued", "input_chars=" + prompt.length() + ";images=" + images.size() + ";documents=" + documents.size() + ";attachments=" + selected.size());
        request = worker.submit(turn.trace.wrap(() -> {
            String reply = null;
            Exception error = null;
            try {
                synchronized (turn) {
                    if (turn.stopRequested) throw new java.util.concurrent.CancellationException();
                    turn.started = true;
                }
                if (clearBarrier != null) clearBarrier.get();
                turn.checkStopped();
                if (!selected.isEmpty()) {
                    turn.trace.stage("media_read", "attachments=" + selected.size());
                    turn.reading = true;
                    prepareMedia(turn);
                    turn.reading = false;
                    main.post(() -> showPreparedMedia(turn));
                }
                turn.checkStopped();
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
            catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                error = cancelled;
            }
            catch (Exception failure) { error = failure; }
            catch (OutOfMemoryError failure) { error = new IllegalArgumentException("附件过大，无法读取"); }
            String result = reply;
            Exception failure = error;
            main.post(turn.trace.wrap(() -> finishRequest(turn, result, failure)));
        }));
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
        while (visibleImages.size() > 1 && (visibleImages.size() > MAX_VISIBLE_TURNS
                || visibleImageBytes > MAX_VISIBLE_IMAGE_BYTES)) {
            List<ChatImage> removed = visibleImages.removeFirst();
            visibleImageBytes -= imageBytes(removed);
            ChatMessage first = transcript.remove(0);
            ChatMessage second = transcript.remove(0);
            if (first.videos != null) first.videos.close();
            if (second.videos != null) second.videos.close();
        }
        evictUnusedThumbnails();
    }

    private void finishRequest(RequestTurn turn, String reply, Exception failure) {
        // A cancelled/closed conversation must not receive a late response.
        if (disposed || turn.ticket != generation) return;
        busy = false;
        request = null;
        pending = null;
        ChatMessage answer = turn.answer;
        if (failure == null) {
            answer.text = reply;
            answer.color = TEXT;
            answer.videos = turn.stopRequested ? null : searchVideoCards.group(turn.searchResults, turn.searchBridge, turn.analytics);
            state.setText("");
        } else {
            answer.text = turn.stopRequested ? "已停止" : turn.reading
                    ? "附件读取失败：" + failureDetail(failure) : errorMessage(failure);
            if (!turn.reading && !turn.latestPartial.isEmpty()) {
                answer.text = turn.latestPartial + "\n\n（" + answer.text + "，回复未完成）";
            }
            answer.color = turn.stopRequested ? MUTED : ERROR;
            state.setText(turn.stopRequested ? "已停止" : turn.reading ? "附件读取失败" : "发送失败");
            restoreDraft(turn.prompt, turn.images, turn.documents);
            addRetry(answer, turn.prompt, turn.images, turn.documents);
            if (draftPending.isEmpty()) draftPending.addAll(turn.attachments);
            answer.retryPending = new ArrayList<>(turn.attachments);
            renderAttachments();
        }
        messageRows.notifyDataSetChanged();
        updateSend();
        scrollToBottom();
        if (failure != null) turn.trace.error(turn.reading ? "media_read" : "provider_request", failure);
        turn.trace.finish(turn.stopRequested ? DouAiLog.Outcome.CANCELLED : failure == null ? DouAiLog.Outcome.SUCCESS
                        : failure instanceof java.net.SocketTimeoutException ? DouAiLog.Outcome.TIMED_OUT : DouAiLog.Outcome.FAILED,
                turn.stopRequested ? "user_cancelled" : failure == null ? "response_rendered" : "request_failed",
                "response_chars=" + (reply == null ? 0 : reply.length()) + ";view_notified=true;remote_delivery=not_applicable");
    }

    private void stopRequest() {
        if (!busy || pending.stopRequested) return;
        synchronized (pending) {
            pending.stopRequested = true;
            pending.trace.finish(DouAiLog.Outcome.CANCELLED, "user_cancelled", "provider_result=unknown");
            if (pending.started && request != null) request.cancel(true);
        }
        session.cancel();
        searchVideoCards.cancelPages();
        state.setText("正在停止…");
        updateSend();
    }

    private void clearConversation() {
        if (disposed) return;
        cancelRequest();
        closePickers();
        cancelRestore();
        cancelThumbnails();
        searchVideoCards.clear();
        Session previous = session;
        session = sessions.create();
        transcript.clear();
        messageRows.notifyDataSetChanged();
        visibleImages.clear();
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
            try { previous.clear(); }
            catch (RuntimeException failure) { error = failure; }
            RuntimeException failure = error;
            main.post(() -> {
                if (disposed || ticket != clearGeneration) return;
                if (!busy) state.setText(failure == null ? "" : "无法清空已保存的对话");
                updateSend();
            });
        });
    }

    private void cancelRequest() {
        if (pending != null) pending.trace.finish(DouAiLog.Outcome.CANCELLED, "conversation_closed_or_cleared", "provider_result=unknown");
        ++generation;
        if (request != null) request.cancel(true);
        session.cancel();
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
        messageRows.notifyDataSetChanged();
        return message;
    }

    private void updateSend() {
        state.setVisibility(TextUtils.isEmpty(state.getText()) ? View.GONE : View.VISIBLE);
        boolean hasContent = !input.getText().toString().trim().isEmpty() || !draftImages.isEmpty()
                || !draftDocuments.isEmpty() || !draftPending.isEmpty();
        if (nativeViews == null) send.setImageResource(busy ? android.R.drawable.ic_menu_close_clear_cancel : android.R.drawable.ic_menu_send);
        else nativeViews.updateComposer(busy, hasContent);
        send.setContentDescription(busy ? "停止请求" : "发送");
        send.setTooltipText(busy ? "停止请求" : "发送");
        send.setEnabled(!disposed && (busy ? !pending.stopRequested
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
        if (miuixSurface != null) miuixSurface.refresh();
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

    private void showLocalTools() {
        if (disposed) return;
        if (localMenu != null) localMenu.dismiss();
        localMenu = new MiuixDialogBuilder(getContext(), useMiuix).setTitle("聊天工具")
                .setItems(new String[]{"添加附件", "联系人设置"}, (child, which) -> {
                    if (which == 0) chooseAttachment();
                    else showConversationActions();
                }).setNegativeButton("取消", null).create();
        localMenu.show();
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

    private boolean retains(ChatImage image) {
        if (draftImages.contains(image)) return true;
        for (List<ChatImage> images : visibleImages) if (images.contains(image)) return true;
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
        final DouAiLog.Operation trace = DouAiLog.begin("ai_chat", "send_or_retry");
        final com.douai.module.analytics.LocalAnalytics.FlowOperation analytics;
        final int ticket;
        final String prompt;
        final List<ChatImage> images;
        final List<Document> documents;
        final List<PendingAttachment> attachments;
        final List<ChatImage> preparedImages;
        final List<Document> preparedDocuments;
        final ChatMessage answer;
        volatile boolean stopRequested;
        volatile String latestPartial = "";
        boolean partialQueued;
        boolean started;
        boolean reading;
        List<DouyinSearch.Result> searchResults = Collections.emptyList();
        DouyinSearch searchBridge;

        RequestTurn(int ticket, String prompt, List<ChatImage> images, List<Document> documents,
                    List<PendingAttachment> attachments, ChatMessage answer, com.douai.module.analytics.LocalAnalytics.FlowOperation analytics) {
            this.analytics=analytics;
            this.ticket = ticket;
            this.prompt = prompt;
            this.images = images;
            this.documents = documents;
            this.attachments = attachments;
            this.preparedImages = new ArrayList<>(images);
            this.preparedDocuments = new ArrayList<>(documents);
            this.answer = answer;
        }

        void checkStopped() throws InterruptedException {
            if (stopRequested || Thread.currentThread().isInterrupted()) throw new InterruptedException("已停止");
        }
    }

    private static final class ChatMessage {
        final String role;
        final boolean user;
        final List<ChatImage> images;
        String text;
        final List<String> documentLabels = new ArrayList<>();
        int color = TEXT;
        boolean retry;
        String retryPrompt;
        List<ChatImage> retryImages = Collections.emptyList();
        List<Document> retryDocuments = Collections.emptyList();
        List<PendingAttachment> retryPending = Collections.emptyList();
        ChatMessage sourceUser;
        AiSearchVideoCards.Group videos;

        ChatMessage(String role, String text, boolean user, List<ChatImage> images) {
            this.role = role;
            this.text = text;
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
        private final ImageButton retry;
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
            body.setTextIsSelectable(true);
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
            videos = new LinearLayout(context);
            videos.setOrientation(VERTICAL);
            videos.setTag("ai.chat.message.videos");
            bubble.addView(videos, new LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT));
            retry = icon("重新发送", "ai.chat.retry", android.R.drawable.ic_menu_rotate);
            bubble.addView(retry, square());
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
            }
            document.setVisibility(message.documentLabels.isEmpty() ? GONE : VISIBLE);
            document.setText(message.documentLabels.isEmpty() ? ""
                    : "已保存的附件：" + TextUtils.join("、", message.documentLabels));
            images.removeAllViews();
            for (ChatImage image : message.images) {
                images.addView(preview(image), new LayoutParams(dp(128), dp(96)));
            }
            int rowWidthForVideos = messages.getWidth() > 0 ? messages.getWidth()
                    : getContext().getResources().getDisplayMetrics().widthPixels;
            int reservedForVideos = messages.getPaddingLeft() + messages.getPaddingRight() + dp(48)
                    + (messageAvatar == null ? 0 : messageAvatar.getLayoutParams().width);
            videos.getLayoutParams().width = Math.min(dp(240), Math.max(dp(120), rowWidthForVideos - reservedForVideos));
            searchVideoCards.bind(videos, message.user ? null : message.videos);
            retry.setVisibility(message.retry ? VISIBLE : GONE);
            retry.setOnClickListener(message.retry ? ignored -> sendMessage(message.retryPrompt,
                    message.retryImages, message.retryDocuments, message) : null);
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
        restoreLoad = historyWorker.submit(() -> {
            List<TestChatMemory.Turn> saved;
            try { saved = active.savedTurns(); }
            catch (RuntimeException failure) { saved = Collections.emptyList(); }
            List<TestChatMemory.Turn> retained = new ArrayList<>();
            Map<ChatImage, Bitmap> decoded = new IdentityHashMap<>();
            int bytes = 0;
            for (int index = saved.size() - 1; index >= 0 && retained.size() < MAX_VISIBLE_TURNS; index--) {
                if (Thread.currentThread().isInterrupted()) { recycle(decoded); return; }
                TestChatMemory.Turn turn = saved.get(index);
                int imageBytes = imageBytes(turn.images);
                if (bytes + imageBytes > MAX_VISIBLE_IMAGE_BYTES) continue;
                bytes += imageBytes;
                retained.add(0, turn);
                for (ChatImage image : turn.images) decoded.put(image, thumbnail(image));
            }
            main.post(() -> {
                if (disposed || ticket != restoreGeneration) { recycle(decoded); return; }
                restoreLoad = null;
                for (Map.Entry<ChatImage, Bitmap> item : decoded.entrySet()) {
                    if (item.getValue() != null) thumbnails.put(item.getKey(), item.getValue());
                }
                for (TestChatMemory.Turn turn : retained) {
                    visibleImages.addLast(turn.images);
                    visibleImageBytes += imageBytes(turn.images);
                    ChatMessage user = addMessage("我", turn.prompt, true, turn.images);
                    if (!turn.document.isEmpty()) {
                        user.documentLabels.add("文本");
                    }
                    addMessage(assistantName, turn.answer, false, Collections.emptyList());
                }
                scrollToBottom();
                updateSend();
            });
        });
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
