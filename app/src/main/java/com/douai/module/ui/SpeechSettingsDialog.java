package com.douai.module.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.douai.module.config.LocalOptions;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.BreezeSpeechSettings;
import com.douai.module.config.FishSpeechSettings;
import com.douai.module.config.MiniMaxSpeechOptions;
import com.douai.module.config.SettingsStore;
import com.douai.module.config.SpeechSettings;
import com.douai.module.config.ThirdPartyRequestTemplate;
import com.douai.module.R;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.feature.LocalSpeechSynthesis;
import com.douai.module.feature.BreezeSpeechClient;
import com.douai.module.feature.FishSpeechClient;
import com.douai.module.feature.FishReferenceAudio;
import com.douai.module.feature.MiniMaxSpeechClient;
import com.douai.module.feature.MiniMaxSpeechModelCatalog;
import com.douai.module.feature.SpeechHttpClient;
import com.douai.module.feature.SpeechSynthesis;
import com.douai.module.feature.ThirdPartyResponse;
import com.douai.module.feature.VoiceCatalog;
import com.douai.module.feature.VoiceCatalogLabels;
import com.douai.module.feature.VoiceNameTranslationCache;
import com.douai.module.feature.VoiceNameTranslator;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import com.douai.module.ui.miuix.MiuixFormControls;
import com.douai.module.ui.miuix.MiuixExtraControls;
import com.douai.module.ui.miuix.MiuixSettingsViews;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Provider-specific settings using the host's existing dialog ownership. */
final class SpeechSettingsDialog implements AutoCloseable {
    private static final String VOICE_SAMPLE = "tts_voice_sample";
    private static final String SYNTHESIS_SAMPLE = "tts_synthesis_sample";
    private static final String BREEZE_ADVANCED = "Breeze Blue 高级设置";
    private static final String BREEZE_AUDIO = "Breeze Blue 音频输出";
    private static final String BREEZE_VOICE = "Breeze Blue 语言与表达";
    private static final String BREEZE_VOICES = "Breeze Blue 音色列表";
    private static final String FISH_ADVANCED = "Fish Audio 高级设置";
    private static final String FISH_AUDIO = "Fish Audio 音频输出";
    private static final String FISH_GENERATION = "Fish Audio 生成参数";
    private static final String FISH_LANGUAGE = "Fish Audio 语言与发音";
    private static final String FISH_VOICES = "Fish Audio 音色列表";
    private static final String FISH_VOICE = "Fish Audio 音色设置";
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(4), runnable -> {
                Thread thread = new Thread(runnable, "douai-voice-list");
                thread.setDaemon(true);
                return thread;
            });
    private static final ThreadPoolExecutor NAME_WORKER = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), runnable -> {
                Thread thread = new Thread(runnable, "douai-voice-name-translation");
                thread.setDaemon(true);
                return thread;
            });
    private final Context context;
    private final SettingsStore store;
    private final boolean miuix;
    private final BiConsumer<Dialog, Runnable> presenter;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinearLayout content;
    private final AlertDialog dialog;
    private LinearLayout advancedContent;
    private final Map<String, LinearLayout> sectionContents = new LinkedHashMap<>();
    private final Map<String, Dialog> sectionDialogs = new LinkedHashMap<>();
    private SpeechHttpClient.Request fetch;
    private Future<?> future;
    private FishCatalogSession fishCatalog;
    private BreezeCatalogSession breezeCatalog;
    private SpeechHttpClient.Request enhancement;
    private Future<?> enhancementFuture;
    private long enhancementGeneration;
    private VoiceNameTranslator.Request nameTranslation;
    private Future<?> nameTranslationFuture;
    private Object nameTranslationOwner;
    private long nameTranslationGeneration;
    private String nameTranslationModel = "";
    private int nameTranslationRequested, nameTranslationSaved;
    private AlertDialog instructionEditor;
    private final List<ReferenceEditorSession> referenceEditors = new ArrayList<>();
    private SpeechSynthesis preview;
    private DouAiLog.Operation previewTrace;
    private MediaPlayer player;
    private java.io.File previewFile;
    private long previewGeneration;
    private boolean closed;

    SpeechSettingsDialog(Context context, SettingsStore store, boolean miuix,
                         BiConsumer<Dialog, Runnable> presenter) {
        this.context = ModuleTheme.themedContext(context);
        this.store = store;
        this.miuix = miuix;
        this.presenter = presenter;
        content = form();
        render();
        dialog = builder("文本转语音设置").setView(scroll(content)).setNegativeButton("返回", null).create();
    }

    Dialog dialog() { return dialog; }

    private void render() {
        content.removeAllViews();
        SpeechSettings speech = settings();
        boolean third = speech.thirdParty;
        option("语音来源", speech.fish ? "Fish Audio" : speech.breeze ? "Breeze Blue" : speech.minimax ? "MiniMax" : third ? "第三方" : "系统引擎", () -> show(builder("语音来源")
                .setSingleChoiceItems(new String[]{"系统引擎", "第三方", "MiniMax", "Breeze Blue", "Fish Audio"}, speech.fish ? 4 : speech.breeze ? 3 : speech.minimax ? 2 : third ? 1 : 0, (choice, which) -> {
                    if (closed) return;
                    store.putString(SpeechSettings.PROVIDER, which == 4 ? SpeechSettings.FISH : which == 3 ? SpeechSettings.BREEZE : which == 2 ? SpeechSettings.MINIMAX
                            : which == 1 ? SpeechSettings.THIRD_PARTY : SpeechSettings.LOCAL);
                    stopPreview();
                    cancelFetch();
                    discardFishCatalog();
                    discardBreezeCatalog();
                    if (speech.fish && which != 4) clearCatalogSample(true);
                    if (speech.breeze && which != 3) clearCatalogSample(false);
                    stopEnhancement();
                    stopNameTranslation("音色来源已变化，已停止翻译");
                    instructionEditor = null;
                    for (ReferenceEditorSession session : new ArrayList<>(referenceEditors)) session.close();
                    choice.dismiss();
                    render();
                }).setNegativeButton("取消", null).create(), null));
        if (speech.fish) {
            FishSpeechSettings fish = speech.fishSettings;
            option("语音引擎", fish.engine, this::selectFishModel);
            option("音色", voiceLabel(), () -> section(FISH_VOICE));
            option("接口设置", fish.apiKey.isEmpty() ? "待填写 API Key" : "API Key 已设置", () -> section("接口设置"));
            option("朗读设置", readingSummary(speech), () -> section("朗读设置"));
            option(FISH_ADVANCED, "音频、生成与发音", () -> section(FISH_ADVANCED));
        } else if (speech.breeze) {
            BreezeSpeechSettings breeze = speech.breezeSettings;
            option("语音模型", BreezeSpeechSettings.AUTO_MODEL.equals(breeze.model) ? "自动按语言选择" : breeze.model, this::selectBreezeModel);
            String voice = voiceLabel();
            if (breezeCatalog != null && breezeCatalog.matches(speech) && breezeCatalog.loading) voice += " · 音色列表加载中";
            option("音色", voice, this::selectVoice);
            option("接口设置", breeze.apiKey.isEmpty() ? "待填写 API Key" : "API Key 已设置", () -> section("接口设置"));
            option("朗读设置", readingSummary(speech), () -> section("朗读设置"));
            option(BREEZE_ADVANCED, "音频、语言与语音指令", () -> section(BREEZE_ADVANCED));
        } else if (speech.minimax) {
            option("模型", speech.minimaxModel, this::selectMiniMaxModel);
            if (!MiniMaxSpeechModelCatalog.builtInModels().contains(speech.minimaxModel)) {
                TextView capability = new TextView(context);
                capability.setTag("speech.model.capability");
                capability.setText("当前模型的高级参数能力未验证，请先试听。");
                content.addView(capability, matchWrap());
            }
            option("音色", voiceLabel(), this::selectVoice);
            option("接口设置", speech.minimaxApiKey.isEmpty() ? "待填写 API Key" : "API Key 已设置", () -> section("接口设置"));
            option("朗读设置", readingSummary(speech), () -> section("朗读设置"));
            option("MiniMax 高级设置", "音频、语言、混音与效果", () -> section("MiniMax 高级设置"));
        } else if (!third) {
            option("接口设置", "系统引擎与语言", () -> section("接口设置"));
            option("朗读设置", readingSummary(speech), () -> section("朗读设置"));
        } else {
            editable("合成 GET", SpeechSettings.GET_TEMPLATE, SpeechSettings.DEFAULT_GET_TEMPLATE, false);
            option("合成参数", parameterNames(settings().getTemplate), this::parameters);
            option("接口设置", "请求头、响应与音频字段", () -> section("接口设置"));
            option("音色", voiceLabel(), this::selectVoice);
            option("音色高级设置", "列表接口、字段与样本", this::advanced);
        }
        option("试听", null, this::testSpeech);
        if (advancedContent != null) renderAdvanced(advancedContent);
        for (Map.Entry<String, LinearLayout> entry : new ArrayList<>(sectionContents.entrySet())) {
            renderSection(entry.getKey(), entry.getValue());
        }
    }

    private String readingSummary(SpeechSettings speech) {
        if (speech.fish) return "语速 " + editableValue(FishSpeechSettings.SPEED, FishSpeechSettings.defaultValue(FishSpeechSettings.SPEED))
                + " · 音量 " + editableValue(FishSpeechSettings.VOLUME_DB, FishSpeechSettings.defaultValue(FishSpeechSettings.VOLUME_DB)) + " dB";
        if (speech.breeze) return "语速 " + editableValue(BreezeSpeechSettings.SPEED, BreezeSpeechSettings.defaultValue(BreezeSpeechSettings.SPEED))
                + " · 音量 " + editableValue(BreezeSpeechSettings.VOLUME, BreezeSpeechSettings.defaultValue(BreezeSpeechSettings.VOLUME));
        return speech.minimax ? "语速 " + editableValue(SpeechSettings.MINIMAX_SPEED, "1")
                + " · 音量 " + editableValue(SpeechSettings.MINIMAX_VOLUME, "1")
                + " · 音调 " + editableValue(SpeechSettings.MINIMAX_PITCH, "0")
                : "语速 " + editableValue(SpeechSettings.RATE, "")
                + " · 音调 " + editableValue(SpeechSettings.PITCH, "");
    }

    private void section(String title) {
        if (closed) return;
        Dialog opened = sectionDialogs.get(title);
        if (opened != null && opened.isShowing()) return;
        LinearLayout form = form();
        form.setTag("speech.section." + title);
        sectionContents.put(title, form);
        renderSection(title, form);
        AlertDialog child = builder(title).setView(scroll(form)).setNegativeButton("返回", null).create();
        sectionDialogs.put(title, child);
        show(child, () -> {
            if (sectionContents.get(title) == form) sectionContents.remove(title);
            if (sectionDialogs.get(title) == child) sectionDialogs.remove(title);
        });
    }

    private void renderSection(String title, LinearLayout form) {
        form.removeAllViews();
        SpeechSettings speech = settings();
        if ("接口设置".equals(title)) {
            if (speech.fish) {
                editable(form, "API Key", FishSpeechSettings.API_KEY, "", true);
                editable(form, "API 地址", FishSpeechSettings.BASE_URL, FishSpeechSettings.DEFAULT_BASE_URL, false);
            } else if (speech.breeze) {
                editable(form, "API Key", BreezeSpeechSettings.API_KEY, "", true);
                editable(form, "API 地址", BreezeSpeechSettings.BASE_URL, BreezeSpeechSettings.DEFAULT_BASE_URL, false);
            } else if (speech.minimax) {
                editable(form, "API Key", SpeechSettings.MINIMAX_API_KEY, "", true);
                editable(form, "API 地址", SpeechSettings.MINIMAX_BASE_URL, SpeechSettings.DEFAULT_MINIMAX_BASE_URL, false);
            } else if (speech.thirdParty) {
                editable(form, "合成请求头", SpeechSettings.HEADERS, "{}", false);
                option(form, "合成响应样本", synthesisSampleLabel(), this::editSynthesisSample);
                option(form, "音频地址字段", speech.audioField.isEmpty() ? "自动识别" : speech.audioField, this::chooseAudioField);
            } else {
                editable(form, "语音引擎", "tts_engine", "", false);
                editable(form, "语音语言", "tts_language", "zh-CN", false);
            }
            return;
        }
        if ("朗读设置".equals(title)) {
            if (speech.fish) {
                editable(form, "语速", FishSpeechSettings.SPEED, FishSpeechSettings.defaultValue(FishSpeechSettings.SPEED), false);
                editable(form, "音量（dB）", FishSpeechSettings.VOLUME_DB, FishSpeechSettings.defaultValue(FishSpeechSettings.VOLUME_DB), false);
                if (!"s1".equals(speech.fishSettings.engine)) fishBoolean(form, "响度归一化", FishSpeechSettings.NORMALIZE_LOUDNESS);
                else hint(form, "speech.fish.loudness.inactive", "S1 不使用响度归一化，原开关值已保留。");
                hint(form, "speech.fish.volume.hint", "音量以 dB 调整，0 表示不增减；与其它来源的音量倍率不同。");
            } else if (speech.breeze) {
                editable(form, "语速", BreezeSpeechSettings.SPEED, BreezeSpeechSettings.defaultValue(BreezeSpeechSettings.SPEED), false);
                editable(form, "音量", BreezeSpeechSettings.VOLUME, BreezeSpeechSettings.defaultValue(BreezeSpeechSettings.VOLUME), false);
                hint(form, "speech.breeze.pitch.hint", "Breeze Blue 当前接口不提供独立音调参数。");
            } else if (speech.minimax) {
                editable(form, "语速", SpeechSettings.MINIMAX_SPEED, "1", false);
                editable(form, "音量", SpeechSettings.MINIMAX_VOLUME, "1", false);
                editable(form, "音调", SpeechSettings.MINIMAX_PITCH, "0", false);
            } else if (!speech.thirdParty) {
                editable(form, "语速", SpeechSettings.RATE, "", false);
                editable(form, "音调", SpeechSettings.PITCH, "", false);
            }
            return;
        }
        if (title.startsWith("Fish Audio ")) {
            if (!speech.fish) { hint(form, "speech.fish.inactive", "切换至 Fish Audio 后可设置。"); return; }
            renderFishSection(title, form, speech.fishSettings);
            return;
        }
        if (title.startsWith("Breeze Blue ")) {
            if (!speech.breeze) { hint(form, "speech.breeze.inactive", "切换至 Breeze Blue 后可设置。"); return; }
            if (BREEZE_ADVANCED.equals(title)) {
                option(form, "音频输出", speech.breezeSettings.outputFormat, () -> section(BREEZE_AUDIO));
                option(form, "语言与表达", "语言、语音指令与指导强度", () -> section(BREEZE_VOICE));
                option(form, "音色列表", "范围、名称与语言筛选", () -> section(BREEZE_VOICES));
                hint(form, "speech.breeze.capabilities", "音调、混音、声音效果和 URL/Hex 选项仅适用于 MiniMax。");
            } else if (BREEZE_AUDIO.equals(title)) {
                renderBreezeAudio(form);
            } else if (BREEZE_VOICE.equals(title)) {
                option(form, "语音指令", speech.breezeSettings.instructions.isEmpty() ? "未设置" : speech.breezeSettings.instructions, this::editBreezeInstruction);
                String[] languageCodes = "breeze-tts-2".equals(speech.breezeSettings.model)
                        ? new String[]{"", "zh", "en"} : BreezeSpeechSettings.LANGUAGE_CODES;
                String[] languages = languageCodes.clone();
                for (int index = 0; index < languages.length; index++) {
                    if (languages[index].isEmpty()) languages[index] = "自动识别";
                    else if ("zh".equals(languages[index])) languages[index] = "中文 · zh";
                    else if ("en".equals(languages[index])) languages[index] = "英语 · en";
                }
                breezeChoice(form, "语言代码", BreezeSpeechSettings.LANGUAGE_CODE, languageCodes, languages);
                if (!speech.breezeSettings.effectiveSpeakerBoost()) {
                    editable(form, "指导强度", BreezeSpeechSettings.GUIDANCE_SCALE, BreezeSpeechSettings.defaultValue(BreezeSpeechSettings.GUIDANCE_SCALE), false);
                } else hint(form, "speech.breeze.guidance.inactive", "说话人增强生效时，指导强度按 1 应用；原指导强度值已保留。");
                if (speech.breezeSettings.instructions.isEmpty() || speech.breezeSettings.guidanceScale <= 1) {
                    breezeChoice(form, "说话人增强", BreezeSpeechSettings.SPEAKER_BOOST,
                            new String[]{"true", "false"}, new String[]{"开启", "关闭"});
                } else hint(form, "speech.breeze.boost.inactive", "当前语音指令和指导强度会覆盖说话人增强，原开关值已保留。");
                hint(form, "speech.breeze.language.hint", "breeze-tts-2".equals(speech.breezeSettings.model)
                        ? "当前基础模型仅支持中文和英语，其它语言请先选择多语言模型。" : "语言代码需要所选模型支持。");
                hint(form, "speech.breeze.boost.hint", "有语音指令且指导强度大于 1 时，接口忽略说话人增强；否则开启增强时，指导强度按 1 应用。");
            } else if (BREEZE_VOICES.equals(title)) {
                breezeChoice(form, "音色范围", BreezeSpeechSettings.VOICE_TYPE,
                        new String[]{"all", "default", "personal"}, new String[]{"全部音色", "系统音色", "个人音色"});
                editable(form, "音色搜索", BreezeSpeechSettings.VOICE_SEARCH, "", false);
                String[] languages = catalogLanguageLabels(BreezeSpeechSettings.LANGUAGE_CODES);
                breezeChoice(form, "音色语言", BreezeSpeechSettings.VOICE_LANGUAGE, BreezeSpeechSettings.LANGUAGE_CODES, languages);
                breezeChoice(form, "音色分类", BreezeSpeechSettings.VOICE_CATEGORY, BreezeSpeechSettings.VOICE_CATEGORIES,
                        namedCatalogValues(BreezeSpeechSettings.VOICE_CATEGORIES, new String[]{"不限", "旁白", "社交媒体", "广告与品牌", "播客", "学习", "客服助手", "角色扮演", "游戏", "动画", "影视", "身心放松", "风格化"}));
                catalogMultipleChoice(form, "音色性别", false, BreezeSpeechSettings.VOICE_GENDER, BreezeSpeechSettings.VOICE_GENDERS,
                        namedCatalogValues(BreezeSpeechSettings.VOICE_GENDERS, new String[]{"不限", "男声", "女声", "中性"}));
                catalogMultipleChoice(form, "音色年龄", false, BreezeSpeechSettings.VOICE_AGE, BreezeSpeechSettings.VOICE_AGES,
                        namedCatalogValues(BreezeSpeechSettings.VOICE_AGES, new String[]{"儿童", "青年", "中年", "老年"}));
                catalogMultipleChoice(form, "音色语气", false, BreezeSpeechSettings.VOICE_TONE, BreezeSpeechSettings.VOICE_TONES,
                        namedCatalogValues(BreezeSpeechSettings.VOICE_TONES, new String[]{"不限", "温暖", "平静", "明亮", "温柔", "活力", "权威", "真诚", "疲惫", "精准", "文雅",
                                "急切", "友好", "清晰", "沉稳", "俏皮", "关怀", "沉思", "从容", "有节奏", "热情"}));
                breezeChoice(form, "音色口音", BreezeSpeechSettings.VOICE_ACCENT, BreezeSpeechSettings.VOICE_ACCENTS,
                        namedCatalogValues(BreezeSpeechSettings.VOICE_ACCENTS, new String[]{"不限", "美式", "英式", "苏格兰", "爱尔兰", "澳大利亚", "加拿大", "美国南方", "纽约", "印度", "南非", "俄罗斯", "日本", "韩国", "中文", "东北普通话", "陕西普通话", "上海普通话", "四川普通话", "河南普通话", "北京普通话", "台湾普通话", "粤语", "广东普通话", "云南普通话"}));
                breezeChoice(form, "口音标记范围", BreezeSpeechSettings.VOICE_ACCENT_MODE, BreezeSpeechSettings.VOICE_ACCENT_MODES,
                        new String[]{"全部标记状态", "仅无口音标记"});
                breezeChoice(form, "音色来源类型", BreezeSpeechSettings.VOICE_ORIGIN, BreezeSpeechSettings.VOICE_ORIGINS,
                        new String[]{"不限来源", "设计音色 · designed", "克隆音色 · cloned"});
                breezeChoice(form, "仅收藏音色", BreezeSpeechSettings.VOICE_FAVORITES_ONLY, new String[]{"false", "true"}, new String[]{"关闭", "开启"});
                String[] sorts = "default".equals(speech.breezeSettings.voiceType) ? BreezeSpeechSettings.VOICE_SORTS : new String[]{"created_at_unix", "name"};
                breezeChoice(form, "音色排序", BreezeSpeechSettings.VOICE_SORT, sorts,
                        "default".equals(speech.breezeSettings.voiceType) ? new String[]{"创建时间", "音色名称", "趋势热度"} : new String[]{"创建时间", "音色名称"});
                breezeChoice(form, "排序方向", BreezeSpeechSettings.VOICE_SORT_DIRECTION, BreezeSpeechSettings.VOICE_SORT_DIRECTIONS, new String[]{"升序", "降序"});
                option(form, "自定义标签", String.join("、", speech.breezeSettings.voiceTags), () -> editCatalogTags(false));
                option(form, "刷新音色", null, this::refreshVoices);
                hint(form, "speech.breeze.voice.filters", "先缩小范围再刷新。性别、年龄、语气可多选；自定义标签最多 5 个。仅无口音标记不能与指定口音组合；趋势排序只用于系统音色，请先换排序再切换其它范围。默认中文，每页 20 项、最多加载 200 项；筛选不改变合成语言或当前音色。");
                if (breezeCatalog != null && breezeCatalog.matches(speech)) {
                    hint(form, "speech.breeze.catalog.status", breezeCatalog.status());
                }
            }
            return;
        }
        if (!speech.minimax) {
            hint(form, "speech.minimax.inactive", "切换至 MiniMax 后可设置。");
            return;
        }
        if ("MiniMax 高级设置".equals(title)) renderMiniMaxAdvanced(form);
        else if ("音频输出".equals(title)) renderMiniMaxAudio(form);
        else if ("语言与发音".equals(title)) renderMiniMaxLanguage(form);
        else if ("混音与声音效果".equals(title)) renderMiniMaxEffects(form);
    }

    private void hint(LinearLayout form, String tag, String text) {
        TextView hint = new TextView(context);
        hint.setTag(tag);
        hint.setText(text);
        hint.setPadding(dp(4), dp(6), dp(4), dp(10));
        form.addView(hint, matchWrap());
    }

    private boolean canUseFish() { return !closed && settings().fish; }

    private void renderFishSection(String title, LinearLayout form, FishSpeechSettings fish) {
        if (FISH_ADVANCED.equals(title)) {
            option(form, "音频输出", fish.format.toUpperCase(java.util.Locale.ROOT), () -> section(FISH_AUDIO));
            option(form, "生成参数", "采样、分段与延迟", () -> section(FISH_GENERATION));
            option(form, "语言与发音", "文本归一化与发音字典", () -> section(FISH_LANGUAGE));
            option(form, "文本标签说明", "情绪、表达与说话人", this::showFishTextTags);
            hint(form, "speech.fish.capabilities", "语音引擎与音色分别选择。所有音频完整生成后再试听或发送。");
        } else if (FISH_AUDIO.equals(title)) {
            fishChoice(form, "生成音频格式（API）", FishSpeechSettings.FORMAT, FishSpeechSettings.FORMATS,
                    new String[]{"MP3", "WAV", "Ogg / Opus"});
            String[] sampleRates = "mp3".equals(fish.format) ? FishSpeechSettings.MP3_SAMPLE_RATES
                    : "opus".equals(fish.format) ? FishSpeechSettings.OPUS_SAMPLE_RATES : FishSpeechSettings.WAV_SAMPLE_RATES;
            String[] rates = new String[sampleRates.length + 1], labels = new String[rates.length];
            rates[0] = ""; labels[0] = "接口默认";
            for (int index = 0; index < sampleRates.length; index++) {
                rates[index + 1] = sampleRates[index]; labels[index + 1] = sampleRates[index] + " Hz";
            }
            fishChoice(form, "采样率", FishSpeechSettings.SAMPLE_RATE, rates, labels);
            if ("mp3".equals(fish.format)) {
                fishChoice(form, "MP3 码率", FishSpeechSettings.MP3_BITRATE, FishSpeechSettings.MP3_BITRATES,
                        java.util.Arrays.stream(FishSpeechSettings.MP3_BITRATES).map(value -> value + " kbps").toArray(String[]::new));
            } else if ("opus".equals(fish.format)) {
                fishChoice(form, "Opus 码率", FishSpeechSettings.OPUS_BITRATE, FishSpeechSettings.OPUS_BITRATES,
                        java.util.Arrays.stream(FishSpeechSettings.OPUS_BITRATES)
                                .map(value -> "-1000".equals(value) ? "接口自动" : Integer.parseInt(value) / 1000 + " kbps").toArray(String[]::new));
            }
            hint(form, "speech.fish.audio.hint", "返回方式：同步完整音频。音频保留接口原文件，不做本地转码；裸 PCM 请改选 WAV。");
        } else if (FISH_GENERATION.equals(title)) {
            editable(form, "温度", FishSpeechSettings.TEMPERATURE, FishSpeechSettings.defaultValue(FishSpeechSettings.TEMPERATURE), false);
            editable(form, "Top P", FishSpeechSettings.TOP_P, FishSpeechSettings.defaultValue(FishSpeechSettings.TOP_P), false);
            fishChoice(form, "延迟模式", FishSpeechSettings.LATENCY, FishSpeechSettings.LATENCIES,
                    new String[]{"标准 · normal", "均衡 · balanced", "低延迟 · low"});
            editable(form, "分段长度", FishSpeechSettings.CHUNK_LENGTH, FishSpeechSettings.defaultValue(FishSpeechSettings.CHUNK_LENGTH), false);
            editable(form, "最短分段长度", FishSpeechSettings.MIN_CHUNK_LENGTH, FishSpeechSettings.defaultValue(FishSpeechSettings.MIN_CHUNK_LENGTH), false);
            editable(form, "最大生成 Token", FishSpeechSettings.MAX_NEW_TOKENS, FishSpeechSettings.defaultValue(FishSpeechSettings.MAX_NEW_TOKENS), false);
            editable(form, "重复惩罚", FishSpeechSettings.REPETITION_PENALTY, FishSpeechSettings.defaultValue(FishSpeechSettings.REPETITION_PENALTY), false);
            fishBoolean(form, "关联前序分段", FishSpeechSettings.CONDITION_ON_PREVIOUS_CHUNKS);
            editable(form, "提前结束阈值", FishSpeechSettings.EARLY_STOP_THRESHOLD, FishSpeechSettings.defaultValue(FishSpeechSettings.EARLY_STOP_THRESHOLD), false);
            fishBoolean(form, "质量保护", FishSpeechSettings.QUALITY_GUARD);
        } else if (FISH_LANGUAGE.equals(title)) {
            fishBoolean(form, "文本归一化", FishSpeechSettings.NORMALIZE);
            if (fish.normalize) editable(form, "归一化语言", FishSpeechSettings.LANGUAGE, FishSpeechSettings.defaultValue(FishSpeechSettings.LANGUAGE), false);
            else hint(form, "speech.fish.normalization.inactive", "文本归一化已关闭，原语言配置已保留。");
            editable(form, "发音字典（JSON）", FishSpeechSettings.PRONUNCIATION_DICTIONARY,
                    FishSpeechSettings.defaultValue(FishSpeechSettings.PRONUNCIATION_DICTIONARY), false);
            hint(form, "speech.fish.language.hint", "语言只影响文本归一化，不改变语音模型、口音或输出语言。发音字典使用 JSON 数组，最多 3 项。");
            hint(form, "speech.fish.expression.hint", "S2 系列可使用文本中的 [风格描述] 控制表达；本页不自动改写待合成文本。");
        } else if (FISH_VOICES.equals(title)) {
            fishChoice(form, "音色范围", FishSpeechSettings.VOICE_SCOPE, FishSpeechSettings.VOICE_SCOPES,
                    new String[]{"我的音色", "公开音色"});
            editable(form, "音色搜索", FishSpeechSettings.VOICE_SEARCH, "", false);
            String[] commonLanguages = {"zh", "en", "ja", "ko", "fr", "de", "es", "ru", "pt", "it", "ar", "th", "vi", "id", "hi"};
            catalogMultipleChoice(form, "音色语言", true, FishSpeechSettings.VOICE_LANGUAGE, commonLanguages, catalogLanguageLabels(commonLanguages));
            option(form, "自定义音色语言", String.join("、", fish.voiceLanguages), () -> editCatalogLines(true, FishSpeechSettings.VOICE_LANGUAGE,
                    "音色语言（每行一个）", "填写 Fish 音色目录使用的语言标识，每行一个，可多选。例如 zh、en。保存会替换当前音色语言列表；留空表示不限。"));
            catalogMultipleChoice(form, "标题语言", true, FishSpeechSettings.VOICE_TITLE_LANGUAGE, commonLanguages, catalogLanguageLabels(commonLanguages));
            option(form, "自定义标题语言", String.join("、", fish.voiceTitleLanguages), () -> editCatalogLines(true, FishSpeechSettings.VOICE_TITLE_LANGUAGE,
                    "标题语言（每行一个）", "按 Fish 音色标题的语言筛选，每行一个语言标识。与音色语言分别保存，留空表示不限。"));
            option(form, "自定义标签", String.join("、", fish.voiceTags), () -> editCatalogTags(true));
            editable(form, "作者 ID", FishSpeechSettings.VOICE_AUTHOR_ID, "", false);
            fishChoice(form, "仅许可标记音色", FishSpeechSettings.VOICE_LICENSED, new String[]{"", "true"}, new String[]{"关闭", "开启"});
            fishChoice(form, "音色排序", FishSpeechSettings.VOICE_SORT, FishSpeechSettings.VOICE_SORTS, new String[]{"相关度", "使用任务数", "创建时间"});
            option(form, "刷新音色", null, this::refreshVoices);
            hint(form, "speech.fish.voices.hint", "音色语言与标题语言均可多选，也可逐行填写 Fish 使用的其它语言标识；标签最多 5 个。先筛选再刷新，默认中文，每页 20 项、最多加载 200 项。公开音色可直接浏览；我的音色需要 API Key。许可标记关闭表示不限，不代表无授权。音色 ID 留空可使用接口默认音色。");
            if (fishCatalog != null && fishCatalog.matches(settings())) {
                hint(form, "speech.fish.catalog.status", fishCatalog.status());
            }
        } else if (FISH_VOICE.equals(title)) {
            renderFishVoice(form, fish);
        }
    }

    private void fishBoolean(LinearLayout form, String title, String key) {
        fishChoice(form, title, key, new String[]{"true", "false"}, new String[]{"开启", "关闭"});
    }

    private void renderFishVoice(LinearLayout form, FishSpeechSettings fish) {
        String[] modes = {"单音色", "多说话人音色", "参考音频"};
        int active = java.util.Arrays.asList(FishSpeechSettings.VOICE_MODES).indexOf(fish.voiceMode);
        option(form, "音色来源", active < 0 ? "请重新选择" : modes[active], () -> show(builder("音色来源")
                .setSingleChoiceItems(modes, active, (choice, index) -> {
                    if (!canUseFish()) return;
                    choice.dismiss();
                    if (index == 1) editFishSpeakers();
                    else if (index == 2) editFishReferences();
                    else {
                        store.putString(FishSpeechSettings.VOICE_MODE, "single");
                        settingChanged(FishSpeechSettings.VOICE_MODE); render();
                    }
                }).setNegativeButton("取消", null).create(), null));
        if ("multi".equals(fish.voiceMode)) {
            option(form, "说话人列表", fish.referenceIds.size() + " 位说话人", this::editFishSpeakers);
        } else if ("references".equals(fish.voiceMode)) {
            int groups;
            try { groups = fish.referenceGroups().size(); } catch (RuntimeException invalid) { groups = 0; }
            option(form, "参考音频样本", groups + " 个说话人分组", this::editFishReferences);
        } else option(form, "单音色", singleFishVoiceLabel(fish), this::selectVoice);
        option(form, "音色库", "public".equals(fish.voiceScope) ? "公开音色" : "我的音色", () -> section(FISH_VOICES));
        option(form, "文本标签说明", "说话人编号与表达方式", this::showFishTextTags);
    }

    private String singleFishVoiceLabel(FishSpeechSettings fish) {
        return fish.referenceId.isEmpty() ? "接口默认音色" : translatedVoiceLabel(fish.referenceId, fish.referenceName);
    }

    private void showFishTextTags() {
        if (!canUseFish()) return;
        String engine = settings().fishSettings.engine;
        String expression = "s1".equals(engine)
                ? "S1 使用固定的圆括号标签，例如 (happy)你好。"
                : "drama-3".equals(engine)
                ? "Drama 3 支持正文中的自然语言指示、声音、停顿及成对标签；请按对应引擎文档撰写。"
                : "S2 系列可在正文中写自然语言方括号提示，例如 [softly]你好。";
        show(builder("文本标签说明").setMessage(expression
                + "\n\n多说话人使用编号标签，例如 <|speaker:0|>你好。<|speaker:1|>你好呀。编号对应音色列表或参考音频分组，从 0 开始。"
                + "\n\n这些标签由你写在原始正文中；模块不会向自动回复或自动评论自动插入标签。")
                .setPositiveButton("知道了", null).create(), null);
    }

    private void editFishSpeakers() {
        if (!canUseFish()) return;
        List<String> ids = new ArrayList<>(settings().fishSettings.referenceIds);
        LinearLayout form = form();
        LinearLayout rows = form(); form.addView(rows, matchWrap());
        hint(form, "speech.fish.speakers.hint", "按正文中的 speaker:0、speaker:1 顺序配置，最多 8 位（模块编辑预算）。同一音色可用于多个说话人。S1 不支持此模式。");
        AlertDialog editor = builder("多说话人音色").setView(scroll(form)).setNegativeButton("取消", null)
                .setPositiveButton("保存并使用", null).create();
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            rows.removeAllViews();
            for (int index = 0; index < ids.size(); index++) {
                int position = index;
                option(rows, "说话人 " + index, cachedFishSpeakerLabel(ids.get(index)), () -> chooseFishSpeakerId(ids.get(position), value -> {
                    if (!closed && editor.isShowing() && canUseFish()) { ids.set(position, value); refresh[0].run(); }
                }));
                android.widget.Button remove = button(rows, "删除说话人 " + index, "speech.fish.speaker.remove." + index);
                remove.setOnClickListener(ignored -> { if (editor.isShowing() && canUseFish()) { ids.remove(position); refresh[0].run(); } });
            }
        };
        android.widget.Button add = button(form, "添加说话人", "speech.fish.speaker.add");
        add.setOnClickListener(ignored -> {
            if (!editor.isShowing() || !canUseFish()) return;
            if (ids.size() >= FishSpeechSettings.MAX_REFERENCE_GROUPS) { toast("最多配置 8 位说话人"); return; }
            chooseFishSpeakerId("", value -> {
                if (editor.isShowing() && canUseFish()) { ids.add(value); refresh[0].run(); }
            });
        });
        refresh[0].run(); show(editor, null);
        editor.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            if (!editor.isShowing() || !canUseFish()) return;
            try {
                if (ids.isEmpty()) throw new IllegalArgumentException("请至少添加一位说话人的音色 ID");
                String value = FishSpeechSettings.validateValue(FishSpeechSettings.REFERENCE_IDS, new org.json.JSONArray(ids).toString());
                validateFishVoiceMode("multi", FishSpeechSettings.REFERENCE_IDS, value);
                store.putString(FishSpeechSettings.REFERENCE_IDS, value);
                store.putString(FishSpeechSettings.VOICE_MODE, "multi");
                settingChanged(FishSpeechSettings.VOICE_MODE); editor.dismiss(); render();
            } catch (IllegalArgumentException failure) { toast(error(failure)); }
        });
    }

    private void chooseFishSpeakerId(String current, Consumer<String> selected) {
        if (!canUseFish()) return;
        List<VoiceCatalog.Entry> cached = java.util.Collections.emptyList();
        try { cached = readCatalogSample(settings()); }
        catch (RuntimeException ignored) { }
        List<VoiceCatalog.Entry> entries = cached;
        String[] labels = new String[entries.size()];
        int checked = -1;
        for (int index = 0; index < entries.size(); index++) {
            labels[index] = catalogEntryLabel(entries.get(index));
            if (entries.get(index).id.equals(current)) checked = index;
        }
        AlertDialog.Builder picker = builder("选择说话人音色").setSingleChoiceItems(labels, checked, (choice, index) -> {
            if (!canUseFish()) return;
            selected.accept(entries.get(index).id); choice.dismiss();
        }).setNeutralButton("手动填写", (choice, index) -> main.post(() -> editFishSpeakerId(current, selected)))
                .setNegativeButton("取消", null);
        if (entries.isEmpty()) picker.setMessage("暂无缓存音色，可手动填写音色 ID，或先在音色库刷新。");
        show(picker.create(), null);
    }

    private void editFishSpeakerId(String current, Consumer<String> selected) {
        if (!canUseFish()) return;
        LinearLayout form = form(); EditText id = input(form, "参考音色 ID", current, false);
        AlertDialog editor = builder("说话人音色 ID").setView(form).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        show(editor, null);
        editor.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            if (!canUseFish() || !editor.isShowing()) return;
            try {
                String candidate = FishSpeechSettings.validateValue(FishSpeechSettings.REFERENCE_ID, id.getText().toString());
                if (candidate.isEmpty()) throw new IllegalArgumentException("多说话人需要显式音色 ID");
                selected.accept(candidate); editor.dismiss();
            } catch (IllegalArgumentException failure) { id.setError(error(failure)); }
        });
    }

    private void validateFishVoiceMode(String mode, String key, String value) {
        Map<String, Object> values = speechValues(); values.put(FishSpeechSettings.VOICE_MODE, mode); values.put(key, value);
        new FishSpeechSettings(new LocalOptions(values)).validateOptions();
    }

    private void editFishReferences() {
        if (!canUseFish()) return;
        ReferenceEditorSession session = new ReferenceEditorSession();
        try {
            List<List<FishSpeechSettings.ReferenceSample>> groups = settings().fishSettings.referenceGroups();
            for (int group = 0; group < groups.size(); group++) for (FishSpeechSettings.ReferenceSample sample : groups.get(group)) {
                session.samples.add(new ReferenceDraft(group, sample.path, sample.text, sample.name));
            }
        } catch (RuntimeException ignored) { }
        LinearLayout form = form(); LinearLayout rows = form(); form.addView(rows, matchWrap());
        hint(form, "speech.fish.references.hint", "导入 WAV、MP3 或 FLAC，并填写样本实际说出的原文。建议清晰的 10–30 秒人声。最多 8 个说话人分组、每组 4 个样本，文件合计 8 MiB（模块预算）。样本不随配置备份导出。使用此模式合成时，会把所选音频和对应原文随 TTS 请求发送给 Fish。");
        session.dialog = builder("参考音频样本").setView(scroll(form)).setNegativeButton("取消", null).setPositiveButton("保存并使用", null).create();
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            rows.removeAllViews();
            for (int index = 0; index < session.samples.size(); index++) {
                ReferenceDraft sample = session.samples.get(index); int position = index;
                String caption = sample.name.isEmpty() ? new java.io.File(sample.path).getName() : sample.name;
                option(rows, "样本 " + (index + 1) + " · 说话人 " + sample.group, caption + "\n" + sample.text,
                        () -> editFishReferenceSample(session, position, refresh[0]));
                android.widget.Button remove = button(rows, "删除样本 " + (index + 1), "speech.fish.reference.remove." + index);
                remove.setOnClickListener(ignored -> { if (session.active()) { session.samples.remove(position); refresh[0].run(); } });
            }
        };
        android.widget.Button add = button(form, "添加参考音频", "speech.fish.reference.add");
        add.setOnClickListener(ignored -> {
            if (session.active()) editFishReferenceSample(session, -1, refresh[0]);
        });
        referenceEditors.add(session); refresh[0].run(); show(session.dialog, session::close);
        session.dialog.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            if (!session.active()) return;
            try {
                String value = encodeReferenceDrafts(session.samples);
                validateFishVoiceMode("references", FishSpeechSettings.REFERENCE_SAMPLES, value);
                store.putString(FishSpeechSettings.REFERENCE_SAMPLES, value);
                store.putString(FishSpeechSettings.VOICE_MODE, "references");
                settingChanged(FishSpeechSettings.VOICE_MODE); session.dialog.dismiss(); render();
            } catch (Exception failure) { toast(error(failure)); }
        });
    }

    private String encodeReferenceDrafts(List<ReferenceDraft> samples) throws Exception {
        if (samples.isEmpty()) throw new IllegalArgumentException("请至少导入一个参考音频样本");
        int last = 0;
        long bytes = 0;
        for (ReferenceDraft sample : samples) {
            last = Math.max(last, sample.group);
            FishReferenceAudio.validateImported(context, sample.path);
            bytes += FishReferenceAudio.ownedFile(context, sample.path).length();
            if (bytes > FishReferenceAudio.MAX_REFERENCE_BYTES) throw new IllegalArgumentException("参考音频合计超过本模块 8 MiB 限制");
        }
        org.json.JSONArray groups = new org.json.JSONArray();
        for (int group = 0; group <= last; group++) {
            org.json.JSONArray entries = new org.json.JSONArray();
            for (ReferenceDraft sample : samples) if (sample.group == group) {
                org.json.JSONObject item = new org.json.JSONObject().put("path", sample.path).put("text", sample.text);
                if (!sample.name.isEmpty()) item.put("name", sample.name);
                entries.put(item);
            }
            if (entries.length() == 0) throw new IllegalArgumentException("说话人编号需要从 0 开始连续配置");
            groups.put(entries);
        }
        return FishSpeechSettings.validateValue(FishSpeechSettings.REFERENCE_SAMPLES, groups.toString());
    }

    private void editFishReferenceSample(ReferenceEditorSession session, int position, Runnable refresh) {
        if (!session.active()) return;
        ReferenceDraft old = position >= 0 ? session.samples.get(position) : new ReferenceDraft(0, "", "", "");
        LinearLayout form = form();
        EditText path = input(form, "音频文件", old.path, false); path.setFocusable(false);
        EditText group = input(form, "说话人编号", Integer.toString(old.group), false);
        EditText transcript = input(form, "样本原文", old.text, false);
        EditText name = input(form, "样本名称（可选）", old.name, false);
        android.widget.Button pick = button(form, "选择音频文件", "speech.fish.reference.pick");
        boolean[] importing = {false}; HostImagePicker[] picker = new HostImagePicker[1];
        AlertDialog editor = builder("参考音频").setView(scroll(form)).setNegativeButton("取消", null).setPositiveButton("保存样本", null).create();
        pick.setOnClickListener(ignored -> {
            if (!session.active() || !editor.isShowing() || importing[0]) return;
            importing[0] = true; pick.setEnabled(false); editor.getButton(Dialog.BUTTON_POSITIVE).setEnabled(false);
            picker[0] = HostAudioPicker.launch(ownerActivity(context), imported -> {
                importing[0] = false;
                if (!session.active() || !editor.isShowing()) { if (imported != null) deleteDraftImport(imported); return; }
                pick.setEnabled(true); editor.getButton(Dialog.BUTTON_POSITIVE).setEnabled(true);
                if (imported != null) {
                    try {
                        FishReferenceAudio.validateImported(context, imported);
                        session.imported.add(imported); path.setText(imported);
                    } catch (Exception failure) { deleteDraftImport(imported); toast(error(failure)); }
                }
            });
            if (picker[0] != null) session.pickers.add(picker[0]);
            else {
                importing[0] = false; pick.setEnabled(true); editor.getButton(Dialog.BUTTON_POSITIVE).setEnabled(true);
                toast("当前页面无法打开文件选择器，请返回宿主后重试");
            }
        });
        show(editor, () -> { if (picker[0] != null) { picker[0].close(); session.pickers.remove(picker[0]); } });
        editor.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            if (!session.active() || !editor.isShowing() || importing[0]) return;
            try {
                int speaker = Integer.parseInt(group.getText().toString().trim());
                if (speaker < 0 || speaker >= FishSpeechSettings.MAX_REFERENCE_GROUPS) throw new IllegalArgumentException("说话人编号应为 0 至 7");
                if (path.getText().toString().isEmpty()) throw new IllegalArgumentException("请选择参考音频文件");
                FishReferenceAudio.validateImported(context, path.getText().toString());
                if (transcript.getText().toString().trim().isEmpty()) throw new IllegalArgumentException("请填写样本实际说出的原文");
                ReferenceDraft draft = new ReferenceDraft(speaker, path.getText().toString(), transcript.getText().toString(), name.getText().toString().trim());
                List<ReferenceDraft> candidate = new ArrayList<>(session.samples);
                if (position < 0) candidate.add(draft); else candidate.set(position, draft);
                encodeReferenceDrafts(candidate);
                if (position < 0) session.samples.add(draft); else session.samples.set(position, draft);
                editor.dismiss(); refresh.run();
            } catch (Exception failure) { transcript.setError(failure instanceof NumberFormatException ? "请输入有效的说话人编号" : error(failure)); }
        });
    }

    private static Activity ownerActivity(Context context) {
        for (int depth = 0; depth < 16 && context != null; depth++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            Context next = ((ContextWrapper) context).getBaseContext(); if (next == context) return null; context = next;
        }
        return null;
    }

    private void deleteDraftImport(String path) {
        try {
            java.io.File file = new java.io.File(path).getCanonicalFile();
            java.io.File owned = new java.io.File(context.getFilesDir(), "douai-voice-source").getCanonicalFile();
            if (!owned.equals(file.getParentFile())) return;
            for (List<FishSpeechSettings.ReferenceSample> group : settings().fishSettings.referenceGroups()) {
                for (FishSpeechSettings.ReferenceSample sample : group) if (new java.io.File(sample.path).getCanonicalFile().equals(file)) return;
            }
            java.nio.file.Files.deleteIfExists(file.toPath());
        } catch (Exception ignored) { }
    }

    private static final class ReferenceDraft {
        final int group;
        final String path, text, name;
        ReferenceDraft(int group, String path, String text, String name) {
            this.group = group; this.path = path; this.text = text; this.name = name;
        }
    }

    private final class ReferenceEditorSession {
        final List<ReferenceDraft> samples = new ArrayList<>();
        final List<String> imported = new ArrayList<>();
        final List<HostImagePicker> pickers = new ArrayList<>();
        AlertDialog dialog;
        boolean open = true;
        boolean active() { return open && canUseFish() && dialog != null && dialog.isShowing(); }
        void close() {
            if (!open) return;
            open = false;
            for (HostImagePicker picker : pickers) picker.close();
            pickers.clear();
            for (String path : imported) deleteDraftImport(path);
            referenceEditors.remove(this);
        }
    }

    private void fishChoice(LinearLayout form, String title, String key, String[] choices, String[] labels) {
        String current = store.getString(key, FishSpeechSettings.defaultValue(key));
        if (FishSpeechSettings.VOICE_LICENSED.equals(key) && "false".equals(current)) current = "";
        int selected = java.util.Arrays.asList(choices).indexOf(current);
        option(form, title, selected < 0 ? "请重新选择" : labels[selected], () -> {
            if (!canUseFish()) return;
            show(builder(title)
                .setSingleChoiceItems(labels, selected, (choice, index) -> {
                    if (!canUseFish()) return;
                    try {
                        String candidate = FishSpeechSettings.validateValue(key, choices[index]);
                        validateFishChange(key, candidate);
                        store.putString(key, candidate); settingChanged(key);
                        choice.dismiss(); render();
                    } catch (IllegalArgumentException failure) { toast(error(failure)); }
                }).setNegativeButton("取消", null).create(), null);
        });
    }

    private void validateFishChange(String key, String candidate) {
        Map<String, Object> values = speechValues(); values.put(key, candidate);
        new FishSpeechSettings(new LocalOptions(values)).validateOptions();
    }

    private void selectFishModel() {
        if (!canUseFish()) return;
        String[] models = FishSpeechSettings.ENGINES;
        String[] labels = new String[models.length];
        for (int index = 0; index < models.length; index++) {
            labels[index] = models[index] + ("s2.1-pro".equals(models[index]) ? " · 推荐"
                    : "s1".equals(models[index]) ? " · 旧版，2026-12-31 停用" : "");
        }
        show(builder("语音引擎").setSingleChoiceItems(labels,
                java.util.Arrays.asList(models).indexOf(settings().fishSettings.engine), (choice, index) -> {
            if (!canUseFish()) return;
            try {
                validateFishChange(FishSpeechSettings.ENGINE, models[index]);
                store.putString(FishSpeechSettings.ENGINE, models[index]);
                settingChanged(FishSpeechSettings.ENGINE); choice.dismiss(); render();
            } catch (IllegalArgumentException failure) { toast(error(failure)); }
        }).setNegativeButton("关闭", null).create(), null);
    }

    private boolean validateFishCatalog(FishSpeechSettings settings) {
        try {
            if (!settings.hasCatalogFilter()) throw new IllegalArgumentException("请先选择语言、标签、名称或我的音色范围，再获取列表");
            if ("self".equals(settings.voiceScope) && settings.apiKey.isEmpty()) throw new IllegalArgumentException("获取我的音色前请先设置 Fish Audio API Key");
            FishSpeechSettings.validateValue(FishSpeechSettings.API_KEY, settings.apiKey);
            FishSpeechSettings.validateValue(FishSpeechSettings.BASE_URL, settings.baseUrl);
            return true;
        } catch (IllegalArgumentException failure) { toast(error(failure)); return false; }
    }

    private void selectBreezeModel() {
        if (!canUseBreeze()) return;
        List<String> models = new ArrayList<>();
        String source = store.getString(BreezeSpeechSettings.MODEL_SOURCE, "built_in");
        try {
            String sample = store.getString(BreezeSpeechSettings.MODEL_SAMPLE, "[]");
            if (sample.length() > 2 * 1024 * 1024) throw new IllegalArgumentException();
            org.json.JSONArray cached = new org.json.JSONArray(sample);
            if (cached.length() > 256) throw new IllegalArgumentException();
            for (int index = 0; index < cached.length(); index++) {
                String model = BreezeSpeechSettings.validateValue(BreezeSpeechSettings.MODEL, cached.getString(index));
                if (!models.contains(model)) models.add(model);
            }
        } catch (Exception ignored) { models.clear(); }
        if (models.isEmpty()) {
            models.addAll(java.util.Arrays.asList(BreezeSpeechSettings.BUILT_IN_MODELS));
            source = "built_in";
        }
        chooseBreezeModels(models, source);
    }

    private boolean canUseBreeze() { return !closed && settings().breeze; }

    private void chooseBreezeModels(List<String> catalog, String source) {
        if (!canUseBreeze()) return;
        List<String> models = new ArrayList<>(catalog);
        if (!models.contains(BreezeSpeechSettings.AUTO_MODEL)) models.add(0, BreezeSpeechSettings.AUTO_MODEL);
        String current = settings().breezeSettings.model;
        boolean manual = !current.isEmpty() && !models.contains(current);
        if (manual) models.add(0, current);
        String[] labels = models.toArray(new String[0]);
        for (int index = 0; index < labels.length; index++) if (BreezeSpeechSettings.AUTO_MODEL.equals(labels[index])) labels[index] = "自动按语言选择";
        if (manual) labels[0] += "（当前模型，未在列表中）";
        show(builder("语音模型 · " + ("api".equals(source) ? "接口列表" : "内置文档列表"))
                .setSingleChoiceItems(labels, models.indexOf(current), (choice, index) -> {
                    if (!canUseBreeze()) return;
                    try { validateBreezeChange(BreezeSpeechSettings.MODEL, models.get(index)); }
                    catch (IllegalArgumentException failure) { toast(error(failure)); return; }
                    store.putString(BreezeSpeechSettings.MODEL, models.get(index));
                    settingChanged(BreezeSpeechSettings.MODEL);
                    choice.dismiss();
                    render();
                }).setPositiveButton("刷新", (choice, index) -> main.post(this::refreshBreezeModels))
                .setNeutralButton("手动填写", (choice, index) -> main.post(this::manualBreezeModel))
                .setNegativeButton("关闭", null).create(), null);
    }

    private void manualBreezeModel() {
        if (!canUseBreeze()) return;
        LinearLayout form = form();
        EditText value = input(form, "语音模型", settings().breezeSettings.model, false);
        AlertDialog editor = builder("语音模型").setView(form).setNegativeButton("取消", null)
                .setPositiveButton("保存", null).create();
        show(editor, null);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            if (!canUseBreeze()) return;
            try {
                String candidate = BreezeSpeechSettings.validateValue(BreezeSpeechSettings.MODEL, value.getText().toString());
                validateBreezeChange(BreezeSpeechSettings.MODEL, candidate);
                store.putString(BreezeSpeechSettings.MODEL, candidate);
                settingChanged(BreezeSpeechSettings.MODEL);
                editor.dismiss();
                render();
            } catch (IllegalArgumentException failure) { value.setError(error(failure)); }
        });
    }

    private void refreshBreezeModels() {
        if (!canUseBreeze()) return;
        if (fetch != null) { toast("列表正在获取"); return; }
        BreezeSpeechSettings settings = settings().breezeSettings;
        if (!validateBreezeCatalog(settings)) return;
        SpeechHttpClient.Request request = new SpeechHttpClient.Request();
        fetch = request;
        toast("正在获取 Breeze Blue 接口的语音模型列表");
        try {
            future = WORKER.submit(() -> {
                try {
                    List<String> models = BreezeSpeechClient.fetchModels(request, settings);
                    if (models.isEmpty()) throw new IllegalArgumentException("Breeze Blue 模型列表为空");
                    org.json.JSONArray catalog = new org.json.JSONArray();
                    for (String model : models) catalog.put(BreezeSpeechSettings.validateValue(BreezeSpeechSettings.MODEL, model));
                    String sample = catalog.toString();
                    main.post(() -> {
                        if (closed || fetch != request) return;
                        fetch = null; future = null;
                        if (!canUseBreeze()) return;
                        store.putString(BreezeSpeechSettings.MODEL_SAMPLE, sample);
                        store.putString(BreezeSpeechSettings.MODEL_SOURCE, "api");
                        render();
                        chooseBreezeModels(models, "api");
                    });
                } catch (Exception failure) {
                    main.post(() -> {
                        if (closed || fetch != request) return;
                        fetch = null; future = null;
                        toast(error(failure) + "\n原模型列表已保留，可手动填写");
                    });
                } finally { request.close(); }
            });
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            fetch = null; request.close(); toast("模型请求队列已满");
        }
    }

    private boolean validateBreezeCatalog(BreezeSpeechSettings settings) {
        try {
            if (settings.apiKey.isEmpty()) throw new IllegalArgumentException("请先设置 Breeze Blue API Key");
            BreezeSpeechSettings.validateValue(BreezeSpeechSettings.API_KEY, settings.apiKey);
            BreezeSpeechSettings.validateValue(BreezeSpeechSettings.BASE_URL, settings.baseUrl);
            return true;
        } catch (IllegalArgumentException failure) { toast(error(failure)); return false; }
    }

    private boolean validateBreezeVoiceCatalog(BreezeSpeechSettings settings) {
        if (!settings.hasCatalogFilter()) { toast("请先选择语言、性别、语气、标签、名称或个人音色范围，再获取音色"); return false; }
        return validateBreezeCatalog(settings);
    }

    private static String[] catalogLanguageLabels(String[] codes) {
        Map<String, String> known = new LinkedHashMap<>();
        String[][] names = {{"zh", "中文"}, {"en", "英语"}, {"ja", "日语"}, {"ko", "韩语"}, {"fr", "法语"},
                {"de", "德语"}, {"es", "西班牙语"}, {"ru", "俄语"}, {"pt", "葡萄牙语"}, {"ar", "阿拉伯语"},
                {"it", "意大利语"}, {"th", "泰语"}, {"vi", "越南语"}, {"id", "印度尼西亚语"}, {"hi", "印地语"},
                {"af", "南非荷兰语"}, {"bg", "保加利亚语"}, {"bn", "孟加拉语"}, {"bs", "波斯尼亚语"}, {"ca", "加泰罗尼亚语"},
                {"cs", "捷克语"}, {"da", "丹麦语"}, {"el", "希腊语"}, {"et", "爱沙尼亚语"}, {"eu", "巴斯克语"},
                {"fa", "波斯语"}, {"fi", "芬兰语"}, {"gl", "加利西亚语"}, {"he", "希伯来语"}, {"hu", "匈牙利语"},
                {"is", "冰岛语"}, {"kn", "卡纳达语"}, {"lt", "立陶宛语"}, {"mr", "马拉地语"}, {"ms", "马来语"},
                {"ne", "尼泊尔语"}, {"nl", "荷兰语"}, {"no", "挪威语"}, {"pl", "波兰语"}, {"ro", "罗马尼亚语"},
                {"sk", "斯洛伐克语"}, {"sl", "斯洛文尼亚语"}, {"sq", "阿尔巴尼亚语"}, {"sv", "瑞典语"}, {"sw", "斯瓦希里语"},
                {"ta", "泰米尔语"}, {"te", "泰卢固语"}, {"tl", "菲律宾语"}, {"tr", "土耳其语"}, {"uk", "乌克兰语"}, {"ur", "乌尔都语"}};
        for (String[] pair : names) known.put(pair[0], pair[1]);
        String[] labels = new String[codes.length];
        for (int index = 0; index < codes.length; index++) labels[index] = codes[index].isEmpty() ? "不限语言"
                : known.containsKey(codes[index]) ? known.get(codes[index]) + " · " + codes[index] : codes[index];
        return labels;
    }

    private void editCatalogTags(boolean fish) {
        if (fish ? !canUseFish() : !canUseBreeze()) return;
        List<String> current = fish ? settings().fishSettings.voiceTags : settings().breezeSettings.voiceTags;
        LinearLayout form = form(); EditText tags = input(form, "自定义标签（每行一个）", String.join("\n", current), false);
        hint(form, "voice.catalog.tags.hint", "每行一个标签，最多 5 个、每个最多 128 字符。标签与语言等条件一起筛选音色；不同标签的含义由对应服务提供。");
        AlertDialog editor = builder("自定义标签").setView(scroll(form)).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        show(editor, null);
        editor.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            if (!editor.isShowing() || (fish ? !canUseFish() : !canUseBreeze())) return;
            try {
                org.json.JSONArray values = new org.json.JSONArray();
                for (String line : tags.getText().toString().split("\\R")) if (!line.trim().isEmpty()) values.put(line.trim());
                String key = fish ? FishSpeechSettings.VOICE_TAGS : BreezeSpeechSettings.VOICE_TAGS;
                String value = fish ? FishSpeechSettings.validateValue(key, values.toString()) : BreezeSpeechSettings.validateValue(key, values.toString());
                store.putString(key, value); settingChanged(key); editor.dismiss(); render();
            } catch (IllegalArgumentException failure) { tags.setError(error(failure)); }
        });
    }

    private void catalogMultipleChoice(LinearLayout form, String title, boolean fish, String key, String[] values, String[] labels) {
        List<String> saved = catalogSavedValues(store.getString(key, fish ? FishSpeechSettings.defaultValue(key) : BreezeSpeechSettings.defaultValue(key)));
        List<String> options = new ArrayList<>(), text = new ArrayList<>();
        for (int index = 0; index < values.length; index++) if (!values[index].isEmpty()) { options.add(values[index]); text.add(labels[index]); }
        List<String> chosenLabels = new ArrayList<>();
        for (String value : saved) { int index = options.indexOf(value); chosenLabels.add(index < 0 ? value : text.get(index)); }
        option(form, title, chosenLabels.isEmpty() ? "不限（可多选）" : String.join("、", chosenLabels), () -> {
            boolean[] checked = new boolean[options.size()];
            for (int index = 0; index < checked.length; index++) checked[index] = saved.contains(options.get(index));
            AlertDialog choice = builder(title + "（可多选）").setMultiChoiceItems(text.toArray(new String[0]), checked,
                    (dialog, index, selected) -> checked[index] = selected).setPositiveButton("保存", null)
                    .setNeutralButton("清空并保存", null).setNegativeButton("取消", null).create();
            show(choice, null);
            choice.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
                org.json.JSONArray selected = new org.json.JSONArray();
                for (int index = 0; index < checked.length; index++) if (checked[index]) selected.put(options.get(index));
                saveCatalogValue(fish, key, selected.toString(), choice, null);
            });
            choice.getButton(Dialog.BUTTON_NEUTRAL).setOnClickListener(ignored -> saveCatalogValue(fish, key, "[]", choice, null));
        });
    }

    private static List<String> catalogSavedValues(String value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.trim().isEmpty()) return result;
        try {
            if (value.trim().startsWith("[")) {
                org.json.JSONArray array = new org.json.JSONArray(value);
                for (int index = 0; index < array.length(); index++) if (array.opt(index) instanceof String && !array.getString(index).isEmpty()) result.add(array.getString(index));
            } else result.add(value.trim());
        } catch (Exception ignored) { }
        return result;
    }

    private void editCatalogLines(boolean fish, String key, String title, String guidance) {
        if (fish ? !canUseFish() : !canUseBreeze()) return;
        LinearLayout form = form();
        EditText value = input(form, title, String.join("\n", catalogSavedValues(store.getString(key,
                fish ? FishSpeechSettings.defaultValue(key) : BreezeSpeechSettings.defaultValue(key)))), false);
        hint(form, "voice.catalog.lines.hint", guidance);
        AlertDialog editor = builder(title).setView(scroll(form)).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        show(editor, null);
        editor.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            org.json.JSONArray selected = new org.json.JSONArray();
            for (String line : value.getText().toString().split("\\R")) if (!line.trim().isEmpty()) selected.put(line.trim());
            saveCatalogValue(fish, key, selected.toString(), editor, value);
        });
    }

    private void saveCatalogValue(boolean fish, String key, String candidate, AlertDialog editor, EditText input) {
        if (!editor.isShowing() || (fish ? !canUseFish() : !canUseBreeze())) return;
        try {
            String value = fish ? FishSpeechSettings.validateValue(key, candidate) : BreezeSpeechSettings.validateValue(key, candidate);
            if (fish) validateFishChange(key, value); else validateBreezeChange(key, value);
            store.putString(key, value); settingChanged(key); editor.dismiss(); render();
        } catch (IllegalArgumentException failure) { if (input != null) input.setError(error(failure)); else toast(error(failure)); }
    }

    private static String[] namedCatalogValues(String[] values, String[] names) {
        String[] labels = new String[values.length];
        for (int index = 0; index < values.length; index++) labels[index] = values[index].isEmpty() ? "不限"
                : names[index] + " · " + values[index];
        return labels;
    }

    private void breezeChoice(LinearLayout form, String title, String key, String[] choices, String[] labels) {
        String current = store.getString(key, BreezeSpeechSettings.defaultValue(key));
        int selected = java.util.Arrays.asList(choices).indexOf(current);
        option(form, title, selected < 0 ? "请重新选择" : labels[selected], () -> show(builder(title)
                .setSingleChoiceItems(labels, selected, (choice, index) -> {
                    if (!canUseBreeze()) return;
                    try {
                        String value = BreezeSpeechSettings.validateValue(key, choices[index]);
                        validateBreezeChange(key, value);
                        store.putString(key, value);
                        settingChanged(key);
                        choice.dismiss();
                        render();
                    } catch (IllegalArgumentException failure) { toast(error(failure)); }
                }).setNegativeButton("取消", null).create(), null));
    }

    private void validateBreezeChange(String key, String value) {
        Map<String, Object> values = speechValues();
        values.put(key, value);
        new BreezeSpeechSettings(new LocalOptions(values)).validateOptions();
    }

    private void editBreezeInstruction() {
        if (!canUseBreeze()) return;
        stopEnhancement();
        LinearLayout form = form();
        EditText value = input(form, "语音指令", settings().breezeSettings.instructions, false);
        hint(form, "speech.breeze.instruction.hint", "描述朗读方式，使用与待合成正文相同的语言。优化接口接受最多 1000 字符的草稿，会把它发送到 Breeze；正文不会发送到优化接口。生成后仍需保存才生效。");
        TextView status = new TextView(context); status.setTag("speech.breeze.enhance.status"); form.addView(status, matchWrap());
        android.widget.Button enhance = button(form, "优化指令", "speech.breeze.enhance");
        AlertDialog editor = builder("语音指令").setView(scroll(form)).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        instructionEditor = editor;
        value.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (enhancement != null) { stopEnhancement(); enhance.setEnabled(true); status.setText("草稿已修改，已停止本次优化"); }
            }
            public void afterTextChanged(Editable text) { }
        });
        enhance.setOnClickListener(ignored -> {
            if (!canUseBreeze() || !editor.isShowing() || instructionEditor != editor) return;
            String draft = value.getText().toString();
            if (draft.trim().isEmpty()) { value.setError("请先填写语音指令"); return; }
            BreezeSpeechSettings settings = settings().breezeSettings;
            if (!validateBreezeCatalog(settings)) return;
            stopEnhancement();
            long generation = enhancementGeneration;
            SpeechHttpClient.Request request = new SpeechHttpClient.Request(); enhancement = request;
            enhance.setEnabled(false); status.setText("正在优化指令…");
            try {
                enhancementFuture = WORKER.submit(() -> {
                    try {
                        String optimized = BreezeSpeechClient.enhanceInstruction(request, settings, draft);
                        main.post(() -> {
                            if (enhancement != request || generation != enhancementGeneration || !canUseBreeze()
                                    || !editor.isShowing() || !value.getText().toString().equals(draft)) return;
                            enhancement = null; enhancementFuture = null;
                            value.setText(optimized); enhance.setEnabled(true); status.setText("优化草稿已生成，保存后生效");
                        });
                    } catch (Exception failure) {
                        main.post(() -> {
                            if (enhancement != request || generation != enhancementGeneration || !canUseBreeze() || !editor.isShowing()) return;
                            enhancement = null; enhancementFuture = null; enhance.setEnabled(true); status.setText(error(failure));
                        });
                    } finally { request.close(); }
                });
            } catch (java.util.concurrent.RejectedExecutionException failure) {
                enhancement = null; request.close(); enhance.setEnabled(true); status.setText("请求队列已满，请稍后重试");
            }
        });
        show(editor, () -> { if (instructionEditor == editor) { stopEnhancement(); instructionEditor = null; } });
        editor.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            if (!canUseBreeze() || !editor.isShowing() || instructionEditor != editor) return;
            try {
                String candidate = BreezeSpeechSettings.validateValue(BreezeSpeechSettings.INSTRUCTIONS, value.getText().toString());
                validateBreezeChange(BreezeSpeechSettings.INSTRUCTIONS, candidate);
                store.putString(BreezeSpeechSettings.INSTRUCTIONS, candidate);
                settingChanged(BreezeSpeechSettings.INSTRUCTIONS); editor.dismiss(); render();
            } catch (IllegalArgumentException failure) { value.setError(error(failure)); }
        });
    }

    private void stopEnhancement() {
        enhancementGeneration++;
        SpeechHttpClient.Request request = enhancement; enhancement = null;
        if (request != null) request.cancel();
        if (enhancementFuture != null) enhancementFuture.cancel(true);
        enhancementFuture = null;
    }

    private void renderBreezeAudio(LinearLayout form) {
        String[] profile = settings().breezeSettings.outputFormat.split("_", -1);
        String codec = profile[0];
        breezeOutputChoice(form, "生成音频格式（API）", 0, BreezeSpeechSettings.FORMATS,
                java.util.Arrays.stream(BreezeSpeechSettings.FORMATS)
                        .map(value -> "opus".equals(value) ? "Ogg / Opus" : value.toUpperCase(java.util.Locale.ROOT)).toArray(String[]::new));
        String[] sampleRates = "opus".equals(codec) ? BreezeSpeechSettings.OPUS_SAMPLE_RATES : BreezeSpeechSettings.SAMPLE_RATES;
        String[] rates = new String[sampleRates.length + 1], rateLabels = new String[rates.length];
        rates[0] = ""; rateLabels[0] = "接口默认（24000 Hz）";
        for (int index = 0; index < sampleRates.length; index++) {
            rates[index + 1] = sampleRates[index]; rateLabels[index + 1] = sampleRates[index] + " Hz";
        }
        breezeOutputChoice(form, "采样率", 1, rates, rateLabels);
        if ("mp3".equals(codec) || "aac".equals(codec) || "opus".equals(codec)) {
            int rate = 24000;
            if (profile.length > 1) try { rate = Integer.parseInt(profile[1]); } catch (NumberFormatException ignored) { }
            String[] candidates = "mp3".equals(codec)
                    ? rate >= 32000 ? BreezeSpeechSettings.MP3_HIGH_BITRATES : BreezeSpeechSettings.MP3_LOW_BITRATES
                    : "aac".equals(codec) ? new String[]{"8", "16", "32", "64", "96", "128", "160", "192", "256", "320"}
                    : new String[]{"6", "16", "24", "32", "48", "64", "96", "128", "192", "256", "510"};
            String[] bitrates = new String[candidates.length + 1];
            String[] bitrateLabels = new String[bitrates.length];
            bitrates[0] = ""; bitrateLabels[0] = "接口默认";
            for (int index = 0; index < candidates.length; index++) {
                bitrates[index + 1] = candidates[index];
                bitrateLabels[index + 1] = candidates[index] + " kbps";
            }
            breezeOutputChoice(form, "mp3".equals(codec) ? "MP3 码率" : "aac".equals(codec) ? "AAC 码率" : "Opus 码率",
                    2, bitrates, bitrateLabels);
        }
        editable(form, "手动填写输出格式", BreezeSpeechSettings.OUTPUT_FORMAT, BreezeSpeechSettings.DEFAULT_OUTPUT_FORMAT, false);
        breezeChoice(form, "生成返回方式", BreezeSpeechSettings.DELIVERY,
                new String[]{"sync", "stream", "async"}, new String[]{"同步完整音频", "HTTP 流式接收", "后台生成"});
        hint(form, "speech.breeze.channel.hint", "声道：单声道（接口固定）\n所有方式都在完整取得音频后再试听或发送；HTTP 流式仅支持 MP3/WAV。");
        hint(form, "speech.breeze.format.hint", "音频保留接口原文件，不做本地转码。裸 PCM 无法直接试听和发送，请改选 WAV；显式码率需先设置采样率。");
    }

    private void breezeOutputChoice(LinearLayout form, String title, int part, String[] choices, String[] labels) {
        String[] current = settings().breezeSettings.outputFormat.split("_", -1);
        String value = current.length > part ? current[part] : "";
        int selected = java.util.Arrays.asList(choices).indexOf(value);
        option(form, title, selected < 0 ? value + "（手动设置）" : labels[selected], () -> show(builder(title)
                .setSingleChoiceItems(labels, selected, (choice, index) -> {
                    if (!canUseBreeze()) return;
                    String[] profile = settings().breezeSettings.outputFormat.split("_", -1);
                    String codec = part == 0 ? choices[index] : profile[0];
                    String rate = part == 1 ? choices[index] : profile.length > 1 ? profile[1] : "";
                    String bitrate = part == 2 ? choices[index] : profile.length > 2 ? profile[2] : "";
                    if (rate.isEmpty() && !bitrate.isEmpty()) { toast("显式码率需先设置采样率；如需默认采样率，请先将码率改为接口默认。"); return; }
                    String candidate = codec + (rate.isEmpty() ? "" : "_" + rate) + (bitrate.isEmpty() ? "" : "_" + bitrate);
                    try {
                        candidate = BreezeSpeechSettings.validateValue(BreezeSpeechSettings.OUTPUT_FORMAT, candidate);
                        validateBreezeChange(BreezeSpeechSettings.OUTPUT_FORMAT, candidate);
                        store.putString(BreezeSpeechSettings.OUTPUT_FORMAT, candidate);
                        settingChanged(BreezeSpeechSettings.OUTPUT_FORMAT);
                        choice.dismiss(); render();
                    } catch (IllegalArgumentException failure) { toast(error(failure)); }
                }).setNegativeButton("取消", null).create(), null));
    }

    private void selectMiniMaxModel() {
        List<String> models = new ArrayList<>();
        String source = store.getString(SpeechSettings.MINIMAX_MODEL_SOURCE, "built_in");
        try {
            String sample = store.getString(SpeechSettings.MINIMAX_MODEL_SAMPLE, "[]");
            if (sample.length() > 2 * 1024 * 1024) throw new IllegalArgumentException();
            org.json.JSONArray cached = new org.json.JSONArray(sample);
            if (cached.length() > 256) throw new IllegalArgumentException();
            for (int index = 0; index < cached.length(); index++) {
                String model = SpeechSettings.validateMiniMaxValue(SpeechSettings.MINIMAX_MODEL, cached.getString(index));
                if (!models.contains(model)) models.add(model);
            }
        } catch (Exception ignored) { models.clear(); }
        if (models.isEmpty()) {
            models.addAll(MiniMaxSpeechModelCatalog.builtInModels());
            source = "built_in";
        }
        chooseMiniMaxModels(models, source);
    }

    private void chooseMiniMaxModels(List<String> catalog, String source) {
        List<String> models = new ArrayList<>(catalog);
        String current = settings().minimaxModel;
        boolean manual = !current.isEmpty() && !models.contains(current);
        if (manual) models.add(0, current);
        String[] labels = models.toArray(new String[0]);
        for (int index = 0; index < labels.length; index++) if (BreezeSpeechSettings.AUTO_MODEL.equals(labels[index])) labels[index] = "自动按语言选择";
        if (manual) labels[0] += "（当前模型，未在列表中）";
        Map<String, Object> values = speechValues();
        for (int index = 0; index < models.size(); index++) {
            String issue = MiniMaxSpeechOptions.candidateIssue(values, SpeechSettings.MINIMAX_MODEL, models.get(index));
            if (!issue.isEmpty()) labels[index] += "\n不适用：" + issue;
        }
        String sourceLabel = "api".equals(source) ? "接口列表" : "document".equals(source) ? "官方文档列表" : "内置官方列表";
        show(builder("模型 · " + sourceLabel).setSingleChoiceItems(labels, models.indexOf(current), (choice, index) -> {
            try { validateMiniMaxChange(SpeechSettings.MINIMAX_MODEL, models.get(index)); }
            catch (IllegalArgumentException failure) { toast(error(failure)); return; }
            store.putString(SpeechSettings.MINIMAX_MODEL, models.get(index));
            settingChanged(SpeechSettings.MINIMAX_MODEL);
            choice.dismiss();
            render();
        }).setPositiveButton("刷新", (choice, index) -> main.post(this::chooseMiniMaxRefreshSource))
                .setNeutralButton("手动填写", (choice, index) -> main.post(this::manualMiniMaxModel))
                .setNegativeButton("关闭", null).create(), null);
    }

    private void chooseMiniMaxRefreshSource() {
        if (closed) return;
        show(builder("刷新模型列表").setItems(new String[]{"当前接口模型（使用 API Key）", "MiniMax 官方目录（无需 Key）"},
                (choice, index) -> refreshMiniMaxModels(index == 1)).setNegativeButton("取消", null).create(), null);
    }

    private void manualMiniMaxModel() {
        if (closed) return;
        LinearLayout form = form();
        EditText value = input(form, "模型", settings().minimaxModel, false);
        AlertDialog editor = builder("模型").setView(form).setNegativeButton("取消", null)
                .setPositiveButton("保存", null).create();
        show(editor, null);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            try {
                String candidate = SpeechSettings.validateMiniMaxValue(SpeechSettings.MINIMAX_MODEL, value.getText().toString());
                validateMiniMaxChange(SpeechSettings.MINIMAX_MODEL, candidate);
                store.putString(SpeechSettings.MINIMAX_MODEL, candidate);
                settingChanged(SpeechSettings.MINIMAX_MODEL);
                editor.dismiss();
                render();
            } catch (IllegalArgumentException failure) { value.setError(error(failure)); }
        });
    }

    private void refreshMiniMaxModels(boolean document) {
        if (fetch != null) { toast("列表正在获取"); return; }
        SpeechSettings settings = settings();
        SpeechHttpClient.Request request = new SpeechHttpClient.Request();
        fetch = request;
        toast(document ? "正在读取 MiniMax 官方文档中的语音模型列表" : "正在获取 MiniMax 接口的语音模型列表");
        try {
            future = WORKER.submit(() -> {
                try {
                    List<String> models = document ? MiniMaxSpeechClient.fetchDocumentModels(request)
                            : MiniMaxSpeechClient.fetchModels(request, settings);
                    if (models.isEmpty()) throw new IllegalArgumentException("模型列表为空");
                    org.json.JSONArray catalog = new org.json.JSONArray();
                    for (String model : models) catalog.put(SpeechSettings.validateMiniMaxValue(SpeechSettings.MINIMAX_MODEL, model));
                    String sample = catalog.toString();
                    main.post(() -> {
                        if (closed || fetch != request) return;
                        fetch = null;
                        future = null;
                        store.putString(SpeechSettings.MINIMAX_MODEL_SAMPLE, sample);
                        store.putString(SpeechSettings.MINIMAX_MODEL_SOURCE, document ? "document" : "api");
                        render();
                        chooseMiniMaxModels(models, document ? "document" : "api");
                    });
                } catch (Exception failure) {
                    main.post(() -> {
                        if (closed || fetch != request) return;
                        fetch = null;
                        future = null;
                        toast(error(failure) + (document ? "\n原列表已保留，可手动填写"
                                : "\n原列表已保留，可刷新官方列表或手动填写"));
                    });
                } finally { request.close(); }
            });
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            fetch = null;
            request.close();
            toast("模型请求队列已满");
        }
    }

    private void advanced() {
        LinearLayout form = form();
        advancedContent = form;
        renderAdvanced(form);
        AlertDialog advanced = builder("音色高级设置").setView(scroll(form)).setNegativeButton("返回", null).create();
        show(advanced, () -> {
            if (advancedContent == form) advancedContent = null;
        });
    }

    private void renderMiniMaxAdvanced(LinearLayout form) {
        MiniMaxSpeechOptions current = settings().minimaxOptions;
        option(form, "音频输出", audioFormatLabel(current.audioFormat) + " · " + current.effectiveSampleRate() + " Hz", () -> section("音频输出"));
        option(form, "语言与发音", current.languageBoost.isEmpty() ? "自动语言与发音规则" : current.languageBoost, () -> section("语言与发音"));
        option(form, "混音与声音效果", current.hasMixedVoices() ? "已启用混音" : current.hasVoiceModify() ? "已设置声音效果" : "单音色 · 原始效果", () -> section("混音与声音效果"));
    }

    private static String audioFormatLabel(String format) {
        if ("pcmu_wav".equals(format)) return "G.711 μ-law / WAV";
        if ("opus".equals(format)) return "Ogg / Opus";
        if ("aac".equals(format)) return "AAC（实验）";
        return format.toUpperCase(java.util.Locale.ROOT);
    }

    private void renderMiniMaxEffects(LinearLayout form) {
        MiniMaxSpeechOptions current = settings().minimaxOptions;
        editable(form, "混合音色（每行 voice_id/weight）", MiniMaxSpeechOptions.TIMBRE_WEIGHTS, "", false);
        hint(form, "speech.minimax.mix.hint", current.hasMixedVoices()
                ? "混音已启用：普通音色 ID 将被忽略，原单音色仍保留。最多 4 个音色，权重为 1 至 100，总和无需为 100；清空恢复单音色。"
                : "最多 4 个音色，每行 voice_id/weight（权重 1 至 100，总和无需为 100）；留空使用主页面音色。");
        boolean effectsSupported = MiniMaxSpeechOptions.supportsVoiceModify(current.audioFormat);
        if (effectsSupported) {
            editable(form, "音高调整（-100 至 100）", MiniMaxSpeechOptions.MODIFY_PITCH, "0", false);
            editable(form, "音强调整（-100 至 100）", MiniMaxSpeechOptions.MODIFY_INTENSITY, "0", false);
            editable(form, "音色调整（-100 至 100）", MiniMaxSpeechOptions.MODIFY_TIMBRE, "0", false);
            miniMaxChoice(form, "声音效果", MiniMaxSpeechOptions.SOUND_EFFECT, MiniMaxSpeechOptions.SOUND_EFFECTS,
                    new String[]{"不使用", "空旷回声", "礼堂回声", "电话音质", "机器人"});
            hint(form, "speech.minimax.effects.hint", "以上调整独立于“朗读设置”的音调，支持 MP3 / WAV / FLAC。");
        } else {
            hint(form, "speech.minimax.effects.hint", "当前格式不支持声音效果器。已存参数保留，可重置后使用，或改选 MP3 / WAV / FLAC。");
        }
        option(form, "重置音色调整", "恢复原始效果，保留混音", this::resetMiniMaxVoiceModify);
    }

    private void renderMiniMaxLanguage(LinearLayout form) {
        miniMaxChoice(form, "情绪", MiniMaxSpeechOptions.EMOTION, MiniMaxSpeechOptions.EMOTIONS,
                new String[]{"自动", "高兴", "悲伤", "愤怒", "恐惧", "厌恶", "惊讶", "平静", "流利（仅 2.6）", "低语（仅 2.6）"});
        String[] languages = MiniMaxSpeechOptions.LANGUAGES.clone();
        languages[0] = "不指定";
        for (int index = 1; index < languages.length; index++) {
            if ("Chinese".equals(languages[index])) languages[index] = "中文 · Chinese";
            else if ("Chinese,Yue".equals(languages[index])) languages[index] = "粤语 · Chinese,Yue";
            else if ("auto".equals(languages[index])) languages[index] = "自动识别 · auto";
        }
        miniMaxChoice(form, "语言增强", MiniMaxSpeechOptions.LANGUAGE_BOOST, MiniMaxSpeechOptions.LANGUAGES, languages);
        miniMaxChoice(form, "文本归一化", MiniMaxSpeechOptions.TEXT_NORMALIZATION_MODE, MiniMaxSpeechOptions.NORMALIZATION_MODES,
                new String[]{"不指定", "基础 · basic", "高质量 · quality（仅 2.8 / 2.6）"});
        editable(form, "发音字典（每行 原文/替换内容）", MiniMaxSpeechOptions.PRONUNCIATION_TONE, "", false);
        hint(form, "speech.minimax.pronunciation.hint", "每行一条，例如 处理/(chu3)(li3)。归一化可改善数字等内容的朗读。");
        miniMaxChoice(form, "LaTeX 朗读", MiniMaxSpeechOptions.LATEX_READ, MiniMaxSpeechOptions.OPTIONAL_BOOLEANS,
                new String[]{"不指定", "开启（仅中文）", "关闭"});
    }

    private void renderMiniMaxAudio(LinearLayout form) {
        MiniMaxSpeechOptions current = settings().minimaxOptions;
        miniMaxChoice(form, "生成音频格式（API）", MiniMaxSpeechOptions.AUDIO_FORMAT, MiniMaxSpeechOptions.FORMATS,
                new String[]{"MP3", "WAV", "FLAC", "Ogg / Opus", "G.711 μ-law / WAV", "AAC（实验，接口可能拒绝）"});
        if ("aac".equals(current.audioFormat)) {
            hint(form, "speech.minimax.aac.hint", "AAC 为实验请求，官方文档未列出，接口可能拒绝。生成的音频原样发送，不在本地转换。");
        }
        miniMaxChoice(form, "返回方式（传输）", MiniMaxSpeechOptions.OUTPUT_FORMAT, MiniMaxSpeechOptions.OUTPUT_FORMATS,
                new String[]{"音频 URL · url", "十六进制音频 · hex"});
        hint(form, "speech.minimax.transport.hint", "URL / Hex 只改变返回方式。Hex 受 2 MiB 文本上限约束，长音频请选择 URL（20 MiB）。");
        if (MiniMaxSpeechOptions.isMuLawFormat(current.audioFormat)) {
            hint(form, "speech.minimax.mulaw.hint", "μ-law WAV 固定 8000 Hz，保留原文件发送；切换格式后恢复原采样率。");
        } else {
            miniMaxChoice(form, "采样率", MiniMaxSpeechOptions.SAMPLE_RATE, MiniMaxSpeechOptions.SAMPLE_RATES,
                    new String[]{"8000 Hz", "16000 Hz", "22050 Hz", "24000 Hz", "32000 Hz", "44100 Hz"});
        }
        if ("mp3".equals(current.audioFormat)) {
            miniMaxChoice(form, "MP3 码率", MiniMaxSpeechOptions.BITRATE, MiniMaxSpeechOptions.BITRATES,
                    new String[]{"32 kbps", "64 kbps", "128 kbps", "256 kbps"});
        }
        miniMaxChoice(form, "声道", MiniMaxSpeechOptions.CHANNEL, MiniMaxSpeechOptions.CHANNELS,
                new String[]{"单声道", "双声道"});
        miniMaxChoice(form, "AIGC 水印", MiniMaxSpeechOptions.AIGC_WATERMARK, MiniMaxSpeechOptions.OPTIONAL_BOOLEANS,
                new String[]{"不指定", "开启", "关闭"});
    }

    private void resetMiniMaxVoiceModify() {
        show(builder("重置音色调整").setMessage("将音高、音强、音色调整设为 0，并关闭声音效果；保留音频格式、单音色、混音及其它设置。")
                .setNegativeButton("取消", null).setPositiveButton("重置", (dialog, which) -> {
                    store.putString(MiniMaxSpeechOptions.MODIFY_PITCH, "0");
                    store.putString(MiniMaxSpeechOptions.MODIFY_INTENSITY, "0");
                    store.putString(MiniMaxSpeechOptions.MODIFY_TIMBRE, "0");
                    store.putString(MiniMaxSpeechOptions.SOUND_EFFECT, "");
                    settingChanged(MiniMaxSpeechOptions.MODIFY_PITCH);
                    render();
                }).create(), null);
    }

    private void miniMaxChoice(LinearLayout form, String title, String key, String[] choices, String[] labels) {
        String value = store.getString(key, MiniMaxSpeechOptions.defaultValue(key));
        int selected = java.util.Arrays.asList(choices).indexOf(value);
        String[] candidates = labels.clone();
        Map<String, Object> values = speechValues();
        for (int index = 0; index < choices.length; index++) {
            String issue = MiniMaxSpeechOptions.candidateIssue(values, key, choices[index]);
            if (!issue.isEmpty()) candidates[index] += "\n不适用：" + issue;
        }
        option(form, title, selected < 0 ? "请重新选择" : labels[selected], () -> show(builder(title)
                .setSingleChoiceItems(candidates, selected, (choice, index) -> {
                    try {
                        String candidate = MiniMaxSpeechOptions.validateValue(key, choices[index]);
                        validateMiniMaxChange(key, candidate);
                        store.putString(key, candidate);
                        settingChanged(key);
                        choice.dismiss();
                        render();
                    } catch (IllegalArgumentException failure) { toast(error(failure)); }
                }).setNegativeButton("取消", null).create(), null));
    }

    private void validateMiniMaxChange(String key, String value) {
        String issue = MiniMaxSpeechOptions.candidateIssue(speechValues(), key, value);
        if (!issue.isEmpty()) throw new IllegalArgumentException(issue);
    }

    private void renderAdvanced(LinearLayout form) {
        form.removeAllViews();
        if (!settings().thirdParty) {
            hint(form, "speech.third.inactive", "切换至第三方来源后可设置音色列表。");
            return;
        }
        editable(form, "音色列表 GET", SpeechSettings.VOICE_GET_TEMPLATE, SpeechSettings.DEFAULT_VOICE_GET_TEMPLATE, false);
        option(form, "刷新音色", null, this::refreshVoices);
        editable(form, "音色列表请求头", SpeechSettings.VOICE_HEADERS, "{}", false);
        editable(form, "列表字段", SpeechSettings.VOICE_LIST_PATH, "", false);
        editable(form, "音色编号字段", SpeechSettings.VOICE_ID_PATH, "", false);
        editable(form, "音色名称字段", SpeechSettings.VOICE_NAME_PATH, "", false);
        option(form, "音色列表样本", store.getString(VOICE_SAMPLE, "").isEmpty() ? "未保存" : "已保存", this::editSample);
    }

    private String voiceLabel() {
        SpeechSettings settings = settings();
        if (settings.fish) {
            FishSpeechSettings fish = settings.fishSettings;
            if ("multi".equals(fish.voiceMode)) return "多说话人 · " + fish.referenceIds.size() + " 位";
            if ("references".equals(fish.voiceMode)) {
                try { return "参考音频 · " + fish.referenceGroups().size() + " 个分组"; }
                catch (RuntimeException invalid) { return "参考音频 · 待重新配置"; }
            }
            return singleFishVoiceLabel(fish);
        }
        if (settings.breeze) return translatedVoiceLabel(settings.breezeSettings.voiceId, settings.breezeSettings.voiceName);
        if (settings.minimax) return settings.minimaxVoiceName.isEmpty() ? settings.minimaxVoiceId
                : settings.minimaxVoiceName + " · " + settings.minimaxVoiceId;
        return settings.selectedVoiceName.isEmpty() ? settings.selectedVoice : settings.selectedVoiceName + " · " + settings.selectedVoice;
    }

    private String translatedVoiceLabel(String id, String name) {
        if (name.isEmpty()) return id;
        try { return VoiceNameTranslationCache.read(store).displayName(VoiceNameTranslationCache.scope(settings()),
                new VoiceCatalog.Entry(id, name)) + " · " + id; }
        catch (IllegalArgumentException invalidDraft) { return name.isEmpty() ? id : name + " · " + id; }
    }

    private String catalogEntryLabel(VoiceCatalog.Entry entry) {
        return VoiceNameTranslationCache.read(store).displayName(VoiceNameTranslationCache.scope(settings()), entry)
                + " · " + entry.id + (entry.labels.isEmpty() ? "" : "\n" + String.join(" · ", entry.labels));
    }

    private String cachedFishSpeakerLabel(String id) {
        for (VoiceCatalog.Entry entry : readCatalogSample(settings())) if (entry.id.equals(id)) return catalogEntryLabel(entry);
        return id;
    }

    private List<VoiceCatalog.Entry> readCatalogSample(SpeechSettings speech) {
        try {
            if ((speech.fish || speech.breeze) && !VoiceCatalogLabels.queryKey(speech).equals(
                    store.getString(VoiceCatalogLabels.queryStorageKey(speech), ""))) return java.util.Collections.emptyList();
            return VoiceCatalog.parse(store.getString(voiceSampleKey(speech), ""), "", "", "");
        } catch (RuntimeException invalid) { return java.util.Collections.emptyList(); }
    }

    private void saveCatalogSample(Iterable<VoiceCatalog.Entry> entries, String queryKey) throws org.json.JSONException {
        SpeechSettings speech = settings(); org.json.JSONArray sample = new org.json.JSONArray();
        if (!queryKey.equals(VoiceCatalogLabels.queryKey(speech))) throw new IllegalArgumentException("筛选条件已变化，请刷新音色");
        for (VoiceCatalog.Entry entry : entries) sample.put(VoiceCatalogLabels.cached(entry));
        store.putString(voiceSampleKey(speech), sample.toString());
        store.putString(VoiceCatalogLabels.queryStorageKey(speech), queryKey);
    }

    private void clearCatalogSample(boolean fish) {
        store.putString(fish ? FishSpeechSettings.VOICE_SAMPLE : BreezeSpeechSettings.VOICE_SAMPLE, "");
        store.putString(fish ? VoiceCatalogLabels.FISH_QUERY_KEY : VoiceCatalogLabels.BREEZE_QUERY_KEY, "");
    }

    private static String voiceSampleKey(SpeechSettings settings) {
        return settings.fish ? FishSpeechSettings.VOICE_SAMPLE : settings.breeze ? BreezeSpeechSettings.VOICE_SAMPLE
                : settings.minimax ? SpeechSettings.MINIMAX_VOICE_SAMPLE : VOICE_SAMPLE;
    }

    private static String voiceIdKey(SpeechSettings settings) {
        return settings.fish ? FishSpeechSettings.REFERENCE_ID : settings.breeze ? BreezeSpeechSettings.VOICE_ID
                : settings.minimax ? SpeechSettings.MINIMAX_VOICE_ID : SpeechSettings.SELECTED_VOICE;
    }

    private static String voiceNameKey(SpeechSettings settings) {
        return settings.fish ? FishSpeechSettings.REFERENCE_NAME : settings.breeze ? BreezeSpeechSettings.VOICE_NAME
                : settings.minimax ? SpeechSettings.MINIMAX_VOICE_NAME : SpeechSettings.SELECTED_VOICE_NAME;
    }

    private String synthesisSampleLabel() {
        String sample = store.getString(SYNTHESIS_SAMPLE, "");
        if (sample.isEmpty()) return "未保存";
        try { return audioCandidates(sample).size() + " 个链接字段"; }
        catch (Exception ignored) { return "待解析"; }
    }

    private static Map<String, String> audioCandidates(String sample) {
        Map<String, String> candidates = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, List<String>> field : ThirdPartyResponse.parse(sample).downloadFields().entrySet()) {
                List<String> urls = field.getValue();
                if (urls.size() == 1) candidates.put(field.getKey(), urls.get(0));
                else for (int index = 0; index < urls.size(); index++) {
                    candidates.put(field.getKey() + "[" + index + "]", urls.get(index));
                }
            }
        } catch (IllegalArgumentException failure) {
            // JSON scalar links are accepted by speech even when the structured parser needs an object root.
            candidates.put("", SpeechHttpClient.audioUrl(sample, ""));
        }
        if (candidates.isEmpty()) throw new IllegalArgumentException("响应样本中没有音频链接");
        return candidates;
    }

    private void editSynthesisSample() {
        LinearLayout form = form();
        EditText sample = input(form, "JSON / TXT 响应", store.getString(SYNTHESIS_SAMPLE, ""), false);
        sample.setTag("speech.synthesis.sample");
        sample.setMaxLines(12);
        TextView preview = new TextView(context);
        preview.setTag("speech.synthesis.preview");
        android.widget.Button parse = button(form, "解析预览", "speech.synthesis.parse");
        form.addView(preview, matchWrap());
        parse.setOnClickListener(ignored -> {
            try {
                StringBuilder fields = new StringBuilder();
                for (String path : audioCandidates(sample.getText().toString()).keySet()) {
                    if (fields.length() > 0) fields.append('\n');
                    fields.append(path.isEmpty() ? "直接音频链接" : path);
                }
                preview.setText(fields.toString());
                sample.setError(null);
            } catch (Exception failure) { sample.setError(error(failure)); }
        });
        AlertDialog editor = builder("合成响应样本").setView(scroll(form)).setNegativeButton("取消", null)
                .setPositiveButton("保存", null).create();
        show(editor, null);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            String value = sample.getText().toString().trim();
            try {
                if (!value.isEmpty()) audioCandidates(value);
                store.putString(SYNTHESIS_SAMPLE, value);
                editor.dismiss();
                render();
            } catch (Exception failure) { sample.setError(error(failure)); }
        });
    }

    private void chooseAudioField() {
        List<String> paths = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        paths.add(""); labels.add("自动识别");
        try {
            for (String path : audioCandidates(store.getString(SYNTHESIS_SAMPLE, "")).keySet()) {
                if (!path.isEmpty()) { paths.add(path); labels.add(path); }
            }
        } catch (Exception ignored) { }
        String current = settings().audioField;
        if (!current.isEmpty() && !paths.contains(current)) { paths.add(current); labels.add(current); }
        show(builder("音频地址字段").setSingleChoiceItems(labels.toArray(new String[0]), paths.indexOf(current), (choice, index) -> {
            store.putString(SpeechSettings.AUDIO_FIELD, paths.get(index));
            settingChanged(SpeechSettings.AUDIO_FIELD);
            choice.dismiss();
            render();
        }).setNeutralButton("手动填写", (choice, index) -> main.post(() ->
                editAudioField())).setNegativeButton("取消", null).create(), null);
    }

    private void editAudioField() {
        if (closed) return;
        LinearLayout form = form();
        EditText field = input(form, "音频地址字段", settings().audioField, false);
        AlertDialog editor = builder("音频地址字段").setView(scroll(form)).setNegativeButton("取消", null)
                .setPositiveButton("保存", null).create();
        show(editor, null);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            store.putString(SpeechSettings.AUDIO_FIELD, field.getText().toString().trim());
            settingChanged(SpeechSettings.AUDIO_FIELD);
            editor.dismiss();
            render();
        });
    }

    private void editable(String title, String key, String fallback, boolean secret) {
        editable(content, title, key, fallback, secret);
    }

    private void editable(LinearLayout target, String title, String key, String fallback, boolean secret) {
        String value = editableValue(key, fallback);
        option(target, title, secret && !value.isEmpty() ? "已设置" : value, () -> {
            if (key.startsWith("tts_breeze_") && !canUseBreeze()) return;
            if (key.startsWith("tts_fish_") && !canUseFish()) return;
            LinearLayout form = form();
            EditText input = input(form, title, editableValue(key, fallback), secret);
            AlertDialog editor = builder(title).setView(scroll(form)).setNegativeButton("取消", null)
                    .setPositiveButton("保存", null).create();
            show(editor, null);
            editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
                if (key.startsWith("tts_breeze_") && !canUseBreeze()) return;
                if (key.startsWith("tts_fish_") && !canUseFish()) return;
                String candidate = input.getText().toString();
                if (!MiniMaxSpeechOptions.TIMBRE_WEIGHTS.equals(key) && !BreezeSpeechSettings.INSTRUCTIONS.equals(key)) candidate = candidate.trim();
                try {
                    if (SpeechSettings.RATE.equals(key) || SpeechSettings.PITCH.equals(key)) {
                        candidate = SpeechSettings.validateLocalRateOrPitch(candidate);
                    }
                    if (key.startsWith("tts_minimax_")) candidate = SpeechSettings.validateMiniMaxValue(key, candidate);
                    if (key.startsWith("tts_breeze_")) {
                        candidate = BreezeSpeechSettings.validateValue(key, candidate);
                        validateBreezeChange(key, candidate);
                    }
                    if (key.startsWith("tts_fish_")) {
                        candidate = FishSpeechSettings.validateValue(key, candidate);
                        validateFishChange(key, candidate);
                    }
                    if (MiniMaxSpeechOptions.isKey(key)) validateMiniMaxChange(key, candidate);
                    if (SpeechSettings.GET_TEMPLATE.equals(key)) ThirdPartyRequestTemplate.parse(candidate, "{text}", "{voice}");
                    if (SpeechSettings.VOICE_GET_TEMPLATE.equals(key) && !candidate.isEmpty()) ThirdPartyRequestTemplate.parse(candidate, new String[0]);
                    if (SpeechSettings.HEADERS.equals(key) || SpeechSettings.VOICE_HEADERS.equals(key)) {
                        org.json.JSONObject headers = new org.json.JSONObject(candidate.isEmpty() ? "{}" : candidate);
                        java.util.Iterator<String> names = headers.keys();
                        while (names.hasNext()) if (!(headers.get(names.next()) instanceof String)) throw new IllegalArgumentException("请求头的值必须是字符串");
                    }
                    store.putString(key, candidate);
                    settingChanged(key);
                    editor.dismiss();
                    render();
                } catch (Exception failure) { input.setError(error(failure)); }
            });
        });
    }

    private String editableValue(String key, String fallback) {
        if (SpeechSettings.RATE.equals(key)) return settings().rateText();
        if (SpeechSettings.PITCH.equals(key)) return settings().pitchText();
        return store.getString(key, fallback);
    }

    private void parameters() {
        SpeechSettings settings = settings();
        final ThirdPartyRequestTemplate template;
        try { template = ThirdPartyRequestTemplate.parse(settings.getTemplate, "{text}", "{voice}"); }
        catch (Exception failure) { toast(error(failure)); return; }
        LinearLayout form = form();
        List<ParameterRow> rows = new ArrayList<>();
        int[] nextId = {0};
        LinearLayout toolbar = new LinearLayout(context);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        TextView count = new TextView(context);
        count.setTag("speech.parameters.count");
        toolbar.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        form.addView(toolbar, matchWrap());
        Runnable updateCount = () -> count.setText(rows.isEmpty() ? "无参数" : rows.size() + " 个参数");
        LinearLayout fields = form();
        form.addView(fields, matchWrap());
        Consumer<ThirdPartyRequestTemplate.Parameter> add = parameter -> {
            ParameterRow row = new ParameterRow();
            row.container = form();
            int index = nextId[0]++;
            LinearLayout heading = new LinearLayout(context);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = new TextView(context);
            label.setText("参数");
            heading.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            icon(heading, "移除参数", "speech.parameter.remove." + index, android.R.drawable.ic_menu_delete, () -> {
                rows.remove(row);
                fields.removeView(row.container);
                updateCount.run();
            });
            row.container.addView(heading, matchWrap());
            row.name = input(row.container, "参数名", parameter.name, false);
            row.name.setTag("speech.parameter.name." + index);
            row.name.setSingleLine(true);
            row.source = miuix ? MiuixFormControls.spinner(context) : new Spinner(context);
            row.source.setContentDescription("参数值来源");
            row.source.setTag("speech.parameter.source." + index);
            row.source.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item,
                    new String[]{"固定值", "待合成文本", "已选音色"}));
            row.container.addView(MiuixFormControls.viewFor(row.source), matchWrap());
            row.fixedValue = form();
            row.fixedValue.setTag("speech.parameter.fixed." + index);
            row.container.addView(row.fixedValue, matchWrap());
            boolean bound = "{text}".equals(parameter.value) || "{voice}".equals(parameter.value);
            row.value = input(row.fixedValue, "参数值", bound ? "" : parameter.value, false);
            row.value.setTag("speech.parameter.value." + index);
            row.source.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    row.fixedValue.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
                }
                @Override public void onNothingSelected(AdapterView<?> parent) { }
            });
            row.source.setSelection("{text}".equals(parameter.value) ? 1 : "{voice}".equals(parameter.value) ? 2 : 0);
            row.fixedValue.setVisibility(bound ? View.GONE : View.VISIBLE);
            rows.add(row);
            fields.addView(row.container, matchWrap());
            updateCount.run();
        };
        for (ThirdPartyRequestTemplate.Parameter parameter : template.parameters) add.accept(parameter);
        updateCount.run();
        icon(toolbar, "添加参数", "speech.parameter.add", android.R.drawable.ic_input_add,
                () -> add.accept(new ThirdPartyRequestTemplate.Parameter("", "")));
        AlertDialog editor = builder("合成参数").setView(scroll(form))
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        show(editor, null);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            List<ThirdPartyRequestTemplate.Parameter> parameters = new ArrayList<>();
            for (ParameterRow row : rows) {
                String name = row.name.getText().toString().trim();
                if (name.isEmpty()) { row.name.setError("请输入参数名"); return; }
                int source = row.source.getSelectedItemPosition();
                parameters.add(new ThirdPartyRequestTemplate.Parameter(name, source == 1 ? "{text}"
                        : source == 2 ? "{voice}" : row.value.getText().toString()));
            }
            try {
                store.putString(SpeechSettings.GET_TEMPLATE, template.withParameters(parameters));
                settingChanged(SpeechSettings.GET_TEMPLATE);
                editor.dismiss();
                render();
            } catch (Exception failure) { toast(error(failure)); }
        });
    }

    private void editSample() {
        LinearLayout form = form();
        EditText sample = input(form, "音色列表样本", store.getString(VOICE_SAMPLE, ""), false);
        AlertDialog editor = builder("音色列表样本").setView(scroll(form)).setNegativeButton("取消", null)
                .setPositiveButton("保存", null).create();
        show(editor, null);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            try {
                String value = sample.getText().toString();
                VoiceCatalog.parse(value, settings());
                store.putString(VOICE_SAMPLE, value);
                cancelFetch();
                editor.dismiss();
                render();
            } catch (Exception failure) { sample.setError(error(failure)); }
        });
    }

    private void refreshVoices() {
        if (closed) return;
        SpeechSettings settings = settings();
        if (settings.fish) { refreshFishCatalog(settings.fishSettings); return; }
        if (settings.breeze) { refreshBreezeCatalog(settings.breezeSettings); return; }
        if (fetch != null) { toast("音色列表正在获取"); return; }
        if (settings.minimax) {
            try {
                if (settings.minimaxApiKey.isEmpty()) throw new IllegalArgumentException("请先设置 MiniMax API Key");
                SpeechSettings.validateMiniMaxValue(SpeechSettings.MINIMAX_API_KEY, settings.minimaxApiKey);
                SpeechSettings.validateMiniMaxValue(SpeechSettings.MINIMAX_BASE_URL, settings.minimaxBaseUrl);
            } catch (IllegalArgumentException invalid) { toast(error(invalid)); return; }
        }
        SpeechHttpClient.Request request = new SpeechHttpClient.Request();
        fetch = request;
        toast("正在获取音色列表");
        try {
            future = WORKER.submit(() -> {
                try {
                    String body;
                    List<VoiceCatalog.Entry> entries;
                    if (settings.minimax) {
                        entries = MiniMaxSpeechClient.fetchVoices(request, settings);
                        org.json.JSONArray catalog = new org.json.JSONArray();
                        for (VoiceCatalog.Entry entry : entries) catalog.put(new org.json.JSONObject().put("id", entry.id).put("name", entry.name));
                        body = catalog.toString();
                    } else {
                        body = request.fetchText(settings.voiceGetTemplate, settings.voiceHeaders);
                        entries = VoiceCatalog.parse(body, settings);
                    }
                    String sample = body;
                    main.post(() -> {
                        if (closed || fetch != request) return;
                        fetch = null;
                        future = null;
                        if (settings.breeze && !canUseBreeze()) return;
                        if (settings.fish && !canUseFish()) return;
                        store.putString(voiceSampleKey(settings), sample);
                        render();
                        chooseVoices(entries);
                    });
                } catch (Exception failure) {
                    main.post(() -> {
                        if (closed || fetch != request) return;
                        fetch = null;
                        future = null;
                        toast(error(failure) + (settings.fish ? "\n原音色列表已保留，可缩小搜索、切换我的音色或手动填写"
                                : settings.minimax || settings.breeze ? "\n原音色列表已保留，可手动填写" : ""));
                    });
                } finally { request.close(); }
            });
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            fetch = null;
            request.close();
            toast("音色请求队列已满");
        }
    }

    private void refreshBreezeCatalog(BreezeSpeechSettings query) {
        if (!canUseBreeze() || !validateBreezeVoiceCatalog(query)) return;
        cancelFetch(); discardBreezeCatalog();
        BreezeCatalogSession session = new BreezeCatalogSession(query, VoiceCatalogLabels.queryKey(settings()));
        try {
            for (VoiceCatalog.Entry entry : readCatalogSample(settings())) {
                if (session.entries.size() >= BreezeCatalogSession.MAX_ENTRIES) break;
                session.entries.put(entry.id, entry);
            }
        } catch (RuntimeException ignored) { }
        breezeCatalog = session; loadBreezePage(session);
    }

    private void chooseBreezeVoices(List<VoiceCatalog.Entry> cached) {
        if (!canUseBreeze()) return;
        BreezeSpeechSettings query = settings().breezeSettings;
        if (breezeCatalog != null && !breezeCatalog.matches(settings())) {
            cancelFetch(); discardBreezeCatalog(); clearCatalogSample(false);
            cached = java.util.Collections.emptyList();
        }
        if (breezeCatalog == null) {
            breezeCatalog = new BreezeCatalogSession(query, VoiceCatalogLabels.queryKey(settings()));
            for (VoiceCatalog.Entry entry : cached) {
                if (breezeCatalog.entries.size() >= BreezeCatalogSession.MAX_ENTRIES) break;
                breezeCatalog.entries.put(entry.id, entry);
            }
        }
        showBreezeCatalog(breezeCatalog);
    }

    private boolean currentBreezeCatalog(BreezeCatalogSession session) {
        return canUseBreeze() && breezeCatalog == session && session.matches(settings());
    }

    private void loadBreezePage(BreezeCatalogSession session) {
        if (!currentBreezeCatalog(session) || session.loading) return;
        if (fetch != null) { toast("音色列表正在获取"); return; }
        if (session.pagesLoaded > 0 && (!session.hasMore || session.budget)) return;
        int page = session.nextPage; String token = session.nextToken;
        SpeechHttpClient.Request request = new SpeechHttpClient.Request();
        fetch = request; session.loading = true; render(); updateBreezeCatalogControls(session);
        toast(session.pagesLoaded == 0 ? "正在读取 Breeze 音色首屏" : "正在加载更多 Breeze 音色");
        try {
            future = WORKER.submit(() -> {
                try {
                    BreezeSpeechClient.VoicePage response = BreezeSpeechClient.fetchVoicePage(request, session.query, page, token);
                    main.post(() -> {
                        if (!acceptBreezePageOwner(session, request)) return;
                        fetch = null; future = null; session.loading = false;
                        try {
                            appendBreezePage(session, response, page, token);
                            saveCatalogSample(session.entries.values(), session.queryKey);
                            render(); showBreezeCatalog(session);
                        } catch (Exception failure) { breezePageFailed(session, error(failure)); }
                    });
                } catch (Exception failure) {
                    main.post(() -> {
                        if (!acceptBreezePageOwner(session, request)) return;
                        fetch = null; future = null; session.loading = false;
                        breezePageFailed(session, error(failure));
                    });
                } finally { request.close(); }
            });
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            fetch = null; request.close(); session.loading = false;
            breezePageFailed(session, "音色请求队列已满");
        }
    }

    private boolean acceptBreezePageOwner(BreezeCatalogSession session, SpeechHttpClient.Request request) {
        if (fetch != request) return false;
        if (currentBreezeCatalog(session)) return true;
        fetch = null; future = null; session.loading = false;
        if (breezeCatalog == session) { discardBreezeCatalog(); clearCatalogSample(false); }
        return false;
    }

    private void appendBreezePage(BreezeCatalogSession session, BreezeSpeechClient.VoicePage response,
                                  int requestedPage, String requestedToken) {
        if (response.page != requestedPage) throw new IllegalArgumentException("音色页码不一致，已保留当前结果");
        java.util.Set<String> added = new java.util.LinkedHashSet<>(response.sourceIds); added.removeAll(session.sourceIds);
        boolean repeatedItems = added.isEmpty() && (response.hasMore || session.pagesLoaded > 0 && response.sourceCount > 0);
        boolean repeatedCursor = response.hasMore && !response.nextPageToken.isEmpty()
                && (response.nextPageToken.equals(requestedToken) || session.tokens.contains(response.nextPageToken));
        boolean repeatedPage = response.hasMore && response.nextPageToken.isEmpty() && response.nextPage <= requestedPage;
        if (repeatedItems || repeatedCursor || repeatedPage) {
            session.hasMore = false; session.stalled = true;
            throw new IllegalArgumentException("音色分页或游标未前进，请刷新或缩小搜索范围");
        }
        if (session.pagesLoaded == 0) { session.entries.clear(); session.sourceIds.clear(); session.tokens.clear(); }
        for (VoiceCatalog.Entry entry : response.entries) {
            if (session.entries.containsKey(entry.id)) continue;
            if (session.entries.size() >= BreezeCatalogSession.MAX_ENTRIES) { session.budget = true; break; }
            session.entries.put(entry.id, entry);
        }
        session.sourceIds.addAll(response.sourceIds);
        if (!response.nextPageToken.isEmpty()) session.tokens.add(response.nextPageToken);
        session.pagesLoaded++; session.hasMore = response.hasMore;
        session.nextPage = response.nextPage; session.nextToken = response.nextPageToken;
        session.budget |= session.entries.size() >= BreezeCatalogSession.MAX_ENTRIES && session.hasMore;
        session.notice = ""; session.stalled = false;
    }

    private void breezePageFailed(BreezeCatalogSession session, String failure) {
        if (!currentBreezeCatalog(session)) return;
        session.notice = failure + "；已加载音色和当前选择已保留";
        render();
        if (session.dialog == null || !session.dialog.isShowing()) showBreezeCatalog(session);
        else updateBreezeCatalogControls(session);
        toast(session.notice);
    }

    private void showBreezeCatalog(BreezeCatalogSession session) {
        if (!currentBreezeCatalog(session)) return;
        if (session.dialog != null && session.dialog.isShowing()) {
            updateCatalogChoices(session); updateBreezeCatalogControls(session); return;
        }
        session.header = new CatalogHeader(session, session.localSearch);
        AlertDialog picker = builder("Breeze Blue 音色").setCustomTitle(session.header.view)
                .setSingleChoiceItems(new String[0], -1, (choice, index) -> { })
                .setPositiveButton(session.moreButton(), null)
                .setNeutralButton("手动填写", (choice, index) -> main.post(() -> {
                    if (currentBreezeCatalog(session)) manualVoice();
                })).setNegativeButton("关闭", null).create();
        session.dialog = picker;
        show(picker, () -> {
            if (session.dialog == picker) {
                session.dialog = null; session.header.close();
                stopNameTranslationFor(session, "音色列表已关闭，已停止翻译");
                if (session.loading) cancelFetch();
            }
        });
        picker.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            if (!currentBreezeCatalog(session) || session.loading) return;
            if (session.pagesLoaded > 0 && session.hasMore && !session.budget) loadBreezePage(session);
            else refreshBreezeCatalog(settings().breezeSettings);
        });
        updateCatalogChoices(session); updateBreezeCatalogControls(session);
    }

    private void updateBreezeCatalogControls(BreezeCatalogSession session) {
        AlertDialog picker = session.dialog;
        if (picker == null || !picker.isShowing()) return;
        updateCatalogHeader(session);
        android.widget.Button more = picker.getButton(Dialog.BUTTON_POSITIVE);
        if (more != null) { more.setText(session.loading ? "正在加载…" : session.moreButton()); more.setEnabled(!session.loading); }
    }

    private void discardBreezeCatalog() {
        BreezeCatalogSession previous = breezeCatalog; breezeCatalog = null;
        if (previous == null) return;
        stopNameTranslationFor(previous, "音色列表已变化，已停止翻译");
        if (previous.header != null) previous.header.close();
        if (previous.loading) cancelFetch();
        if (previous.dialog != null) { AlertDialog picker = previous.dialog; previous.dialog = null; picker.dismiss(); }
    }

    private static final class BreezeCatalogSession {
        static final int MAX_ENTRIES = 200;
        final BreezeSpeechSettings query;
        final String queryKey;
        final Map<String, VoiceCatalog.Entry> entries = new LinkedHashMap<>();
        final java.util.Set<String> sourceIds = new java.util.LinkedHashSet<>(), tokens = new java.util.LinkedHashSet<>();
        int pagesLoaded, nextPage = 1;
        String nextToken = "", notice = "", localSearch = "", translationStatus = "";
        CatalogHeader header;
        boolean hasMore, loading, budget, stalled;
        AlertDialog dialog;
        BreezeCatalogSession(BreezeSpeechSettings query, String queryKey) { this.query = query; this.queryKey = queryKey; }
        boolean matches(SpeechSettings current) { return queryKey.equals(VoiceCatalogLabels.queryKey(current)); }
        String moreButton() { return pagesLoaded > 0 && hasMore && !budget ? "加载更多" : "刷新"; }
        String status() {
            StringBuilder text = new StringBuilder("已加载 ").append(entries.size()).append(" 项");
            if (loading) text.append(pagesLoaded == 0 ? " · 正在读取首屏" : " · 正在加载下一页");
            else if (pagesLoaded == 0) text.append(" · 缓存结果，刷新读取首屏");
            else text.append(" · 已读 ").append(pagesLoaded).append(" 页");
            if (budget) text.append(" · 达到本机 200 项预算，请缩小筛选");
            else if (stalled) text.append(" · 分页已停止，请刷新或缩小搜索");
            else if (pagesLoaded > 0) text.append(hasMore ? " · 还有更多" : " · 当前筛选已到底");
            if (!notice.isEmpty()) text.append("\n").append(notice);
            return text.toString();
        }
        String title() {
            String scope = "personal".equals(query.voiceType) ? "个人音色" : "default".equals(query.voiceType) ? "系统音色" : "全部音色";
            return "音色 · Breeze Blue · " + scope + "\n" + status();
        }
    }

    private void refreshFishCatalog(FishSpeechSettings query) {
        if (!canUseFish() || !validateFishCatalog(query)) return;
        cancelFetch();
        discardFishCatalog();
        FishCatalogSession session = new FishCatalogSession(query, VoiceCatalogLabels.queryKey(settings()));
        try {
            for (VoiceCatalog.Entry entry : readCatalogSample(settings())) {
                if (session.entries.size() >= FishCatalogSession.MAX_ENTRIES) break;
                session.entries.put(entry.id, entry);
            }
        } catch (RuntimeException ignored) { }
        fishCatalog = session;
        loadFishPage(session);
    }

    private void chooseFishVoices(List<VoiceCatalog.Entry> cached) {
        if (!canUseFish()) return;
        FishSpeechSettings query = settings().fishSettings;
        if (fishCatalog != null && !fishCatalog.matches(settings())) {
            cancelFetch(); discardFishCatalog();
            clearCatalogSample(true);
            cached = java.util.Collections.emptyList();
        }
        if (fishCatalog == null) {
            fishCatalog = new FishCatalogSession(query, VoiceCatalogLabels.queryKey(settings()));
            for (VoiceCatalog.Entry entry : cached) {
                if (fishCatalog.entries.size() >= FishCatalogSession.MAX_ENTRIES) break;
                fishCatalog.entries.put(entry.id, entry);
            }
        }
        showFishCatalog(fishCatalog);
    }

    private boolean currentFishCatalog(FishCatalogSession session) {
        return canUseFish() && fishCatalog == session && session.matches(settings());
    }

    private void loadFishPage(FishCatalogSession session) {
        if (!currentFishCatalog(session) || session.loading) return;
        if (fetch != null) { toast("音色列表正在获取"); return; }
        if (session.page > 0 && (session.budget || !session.moreKnown || !session.hasMore)) return;
        SpeechHttpClient.Request request = new SpeechHttpClient.Request();
        fetch = request; session.loading = true; updateFishCatalogControls(session);
        int page = session.page + 1;
        toast(page == 1 ? "正在读取音色首屏" : "正在加载更多音色");
        try {
            future = WORKER.submit(() -> {
                try {
                    FishSpeechClient.VoicePage response = FishSpeechClient.fetchVoicePage(request, session.query, page);
                    main.post(() -> {
                        if (!acceptFishPageOwner(session, request)) return;
                        fetch = null; future = null; session.loading = false;
                        try {
                            appendFishPage(session, response);
                            saveCatalogSample(session.entries.values(), session.queryKey);
                            render(); showFishCatalog(session);
                        } catch (Exception failure) { fishPageFailed(session, error(failure)); }
                    });
                } catch (Exception failure) {
                    main.post(() -> {
                        if (!acceptFishPageOwner(session, request)) return;
                        fetch = null; future = null; session.loading = false;
                        fishPageFailed(session, error(failure));
                    });
                } finally { request.close(); }
            });
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            fetch = null; request.close(); session.loading = false;
            fishPageFailed(session, "音色请求队列已满");
        }
    }

    private boolean acceptFishPageOwner(FishCatalogSession session, SpeechHttpClient.Request request) {
        if (fetch != request) return false;
        if (currentFishCatalog(session)) return true;
        fetch = null; future = null; session.loading = false;
        if (fishCatalog == session) {
            discardFishCatalog(); clearCatalogSample(true);
        }
        return false;
    }

    private void appendFishPage(FishCatalogSession session, FishSpeechClient.VoicePage response) {
        if (response.page != session.page + 1) throw new IllegalArgumentException("音色页码不一致，已保留当前结果");
        java.util.Set<String> newIds = new java.util.LinkedHashSet<>(response.sourceIds);
        newIds.removeAll(session.sourceIds);
        boolean responseMore = response.moreKnown ? response.hasMore
                : response.totalExact && session.sourceCount + response.sourceCount < response.total;
        if (session.page > 0 && response.sourceCount > 0 && newIds.isEmpty()
                || response.sourceCount == 0 && responseMore) {
            session.moreKnown = false; session.hasMore = false;
            throw new IllegalArgumentException("音色分页未前进，请刷新或缩小搜索范围");
        }
        if (session.page == 0) { session.entries.clear(); session.sourceIds.clear(); session.sourceCount = 0; }
        for (VoiceCatalog.Entry entry : response.entries) {
            if (session.entries.containsKey(entry.id)) continue;
            if (session.entries.size() >= FishCatalogSession.MAX_ENTRIES) { session.budget = true; break; }
            session.entries.put(entry.id, entry);
        }
        session.sourceIds.addAll(response.sourceIds);
        session.sourceCount += response.sourceCount;
        session.page = response.page; session.total = response.total;
        session.windowLimited |= response.windowLimited; session.totalExact = response.totalExact;
        session.moreKnown = response.moreKnown || response.totalExact;
        session.hasMore = response.moreKnown ? response.hasMore : response.totalExact && session.sourceCount < response.total;
        session.budget |= session.entries.size() >= FishCatalogSession.MAX_ENTRIES && session.hasMore;
        session.notice = "";
    }

    private void fishPageFailed(FishCatalogSession session, String failure) {
        if (!currentFishCatalog(session)) return;
        session.notice = failure + "；已加载音色和当前选择已保留";
        render();
        if (session.dialog == null || !session.dialog.isShowing()) showFishCatalog(session);
        else updateFishCatalogControls(session);
        toast(session.notice);
    }

    private void showFishCatalog(FishCatalogSession session) {
        if (!currentFishCatalog(session)) return;
        if (session.dialog != null && session.dialog.isShowing()) {
            updateCatalogChoices(session); updateFishCatalogControls(session); return;
        }
        session.header = new CatalogHeader(session, session.localSearch);
        AlertDialog picker = builder("Fish Audio 音色").setCustomTitle(session.header.view)
                .setSingleChoiceItems(new String[0], -1, (choice, index) -> { })
                .setPositiveButton(session.moreButton(), null)
                .setNeutralButton("手动填写", (choice, index) -> main.post(() -> {
                    if (currentFishCatalog(session)) manualVoice();
                })).setNegativeButton("关闭", null).create();
        session.dialog = picker;
        show(picker, () -> {
            if (session.dialog == picker) {
                session.dialog = null; session.header.close();
                stopNameTranslationFor(session, "音色列表已关闭，已停止翻译");
                if (session.loading) cancelFetch();
            }
        });
        picker.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            if (!currentFishCatalog(session) || session.loading) return;
            if (session.page > 0 && session.moreKnown && session.hasMore && !session.budget) loadFishPage(session);
            else refreshFishCatalog(settings().fishSettings);
        });
        updateCatalogChoices(session); updateFishCatalogControls(session);
    }

    private void updateFishCatalogControls(FishCatalogSession session) {
        AlertDialog picker = session.dialog;
        if (picker == null || !picker.isShowing()) return;
        updateCatalogHeader(session);
        android.widget.Button more = picker.getButton(Dialog.BUTTON_POSITIVE);
        if (more != null) { more.setText(session.loading ? "正在加载…" : session.moreButton()); more.setEnabled(!session.loading); }
    }

    private void discardFishCatalog() {
        FishCatalogSession previous = fishCatalog; fishCatalog = null;
        if (previous == null) return;
        stopNameTranslationFor(previous, "音色列表已变化，已停止翻译");
        if (previous.header != null) previous.header.close();
        if (previous.loading) cancelFetch();
        if (previous.dialog != null) { AlertDialog picker = previous.dialog; previous.dialog = null; picker.dismiss(); }
    }

    private static final class FishCatalogSession {
        static final int MAX_ENTRIES = 200;
        final FishSpeechSettings query;
        final String queryKey;
        final Map<String, VoiceCatalog.Entry> entries = new LinkedHashMap<>();
        final java.util.Set<String> sourceIds = new java.util.LinkedHashSet<>();
        int page;
        long sourceCount, total;
        boolean hasMore, moreKnown, windowLimited, totalExact, loading, budget;
        String notice = "", localSearch = "", translationStatus = "";
        CatalogHeader header;
        AlertDialog dialog;
        FishCatalogSession(FishSpeechSettings query, String queryKey) { this.query = query; this.queryKey = queryKey; }
        boolean matches(SpeechSettings current) { return queryKey.equals(VoiceCatalogLabels.queryKey(current)); }
        String moreButton() { return page > 0 && moreKnown && hasMore && !budget ? "加载更多" : "刷新"; }
        String status() {
            StringBuilder text = new StringBuilder("已加载 ").append(entries.size()).append(" 项");
            if (page == 0) text.append(" · 缓存结果，刷新读取首屏");
            else {
                text.append(" · 第 ").append(page).append(" 页");
                if (windowLimited) text.append(" · 搜索窗口内结果");
                text.append(totalExact ? " · 当前查询总数 " : " · 总数估计 ").append(total);
                text.append(moreKnown ? hasMore ? " · 还有更多" : windowLimited ? " · 当前搜索窗口已到底" : " · 当前查询已到底"
                        : " · 更多状态未知，请缩小搜索或刷新");
            }
            if (budget) text.append(" · 达到本机 200 项预算，请缩小筛选");
            if (!notice.isEmpty()) text.append("\n").append(notice);
            return text.toString();
        }
        String title() { return "单说话人音色 · " + ("public".equals(query.voiceScope) ? "公开音色" : "我的音色") + "\n" + status(); }
    }

    private boolean currentCatalog(Object owner) {
        if (owner instanceof FishCatalogSession) return currentFishCatalog((FishCatalogSession) owner);
        return owner instanceof BreezeCatalogSession && currentBreezeCatalog((BreezeCatalogSession) owner);
    }

    private AlertDialog catalogDialog(Object owner) {
        return owner instanceof FishCatalogSession ? ((FishCatalogSession) owner).dialog : ((BreezeCatalogSession) owner).dialog;
    }

    private CatalogHeader catalogHeader(Object owner) {
        return owner instanceof FishCatalogSession ? ((FishCatalogSession) owner).header : ((BreezeCatalogSession) owner).header;
    }

    private boolean liveCatalog(Object owner) {
        if (!currentCatalog(owner)) return false;
        AlertDialog picker = catalogDialog(owner); CatalogHeader header = catalogHeader(owner);
        return picker != null && picker.isShowing() && header != null && !header.closed;
    }

    private List<VoiceCatalog.Entry> loadedCatalogEntries(Object owner) {
        return new ArrayList<>(owner instanceof FishCatalogSession ? ((FishCatalogSession) owner).entries.values()
                : ((BreezeCatalogSession) owner).entries.values());
    }

    private String localCatalogSearch(Object owner) {
        return owner instanceof FishCatalogSession ? ((FishCatalogSession) owner).localSearch : ((BreezeCatalogSession) owner).localSearch;
    }

    private void setLocalCatalogSearch(Object owner, String value) {
        if (owner instanceof FishCatalogSession) ((FishCatalogSession) owner).localSearch = value;
        else ((BreezeCatalogSession) owner).localSearch = value;
    }

    private String remoteCatalogSearch(Object owner) {
        return owner instanceof FishCatalogSession ? ((FishCatalogSession) owner).query.voiceSearch : ((BreezeCatalogSession) owner).query.voiceSearch;
    }

    private void setCatalogTranslationStatus(Object owner, String value) {
        if (owner instanceof FishCatalogSession) ((FishCatalogSession) owner).translationStatus = value;
        else if (owner instanceof BreezeCatalogSession) ((BreezeCatalogSession) owner).translationStatus = value;
    }

    private String catalogTranslationStatus(Object owner) {
        return owner instanceof FishCatalogSession ? ((FishCatalogSession) owner).translationStatus : ((BreezeCatalogSession) owner).translationStatus;
    }

    private void updateCatalogChoices(Object owner) {
        if (!liveCatalog(owner)) return;
        SpeechSettings speech = settings();
        String scope = VoiceNameTranslationCache.scope(speech);
        VoiceNameTranslationCache names = VoiceNameTranslationCache.read(store);
        String query = localCatalogSearch(owner);
        List<VoiceCatalog.Entry> visible = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        boolean fish = owner instanceof FishCatalogSession;
        String selected = fish ? speech.fishSettings.referenceId : speech.breezeSettings.voiceId;
        int checked = -1;
        if (fish && matchesDefaultVoice(query)) {
            visible.add(null); labels.add("接口默认音色"); if (selected.isEmpty()) checked = 0;
        }
        for (VoiceCatalog.Entry entry : loadedCatalogEntries(owner)) {
            if (!names.matches(scope, entry, query)) continue;
            if (entry.id.equals(selected)) checked = visible.size();
            visible.add(entry); labels.add(names.displayName(scope, entry) + " · " + entry.id
                    + (entry.labels.isEmpty() ? "" : "\n" + String.join(" · ", entry.labels)));
        }
        CatalogHeader header = catalogHeader(owner);
        header.matched = visible.size() - (visible.contains(null) ? 1 : 0);
        MiuixDialogBuilder.updateSingleChoiceItems(catalogDialog(owner), labels.toArray(new CharSequence[0]), checked, (choice, index) -> {
            if (!liveCatalog(owner) || choice != catalogDialog(owner) || index < 0 || index >= visible.size()) return;
            VoiceCatalog.Entry entry = visible.get(index);
            if (fish) {
                store.putString(FishSpeechSettings.REFERENCE_ID, entry == null ? "" : entry.id);
                store.putString(FishSpeechSettings.REFERENCE_NAME, entry == null ? "" : entry.name);
                store.putString(FishSpeechSettings.VOICE_MODE, "single");
            } else {
                store.putString(BreezeSpeechSettings.VOICE_ID, entry.id);
                store.putString(BreezeSpeechSettings.VOICE_NAME, entry.name);
            }
            stopPreview(); choice.dismiss(); render();
        });
        updateCatalogHeader(owner);
    }

    private static boolean matchesDefaultVoice(String query) {
        String normalized = java.text.Normalizer.normalize(query == null ? "" : query, java.text.Normalizer.Form.NFKC)
                .toLowerCase(java.util.Locale.ROOT).trim();
        for (String term : normalized.split("\\s+")) if (!"接口默认音色".contains(term)) return false;
        return true;
    }

    private void updateCatalogHeader(Object owner) {
        if (!liveCatalog(owner)) return;
        CatalogHeader header = catalogHeader(owner);
        String status;
        if (owner instanceof FishCatalogSession) {
            FishCatalogSession session = (FishCatalogSession) owner;
            header.title.setText(context.getString("public".equals(session.query.voiceScope)
                    ? R.string.fish_catalog_public_title : R.string.fish_catalog_self_title));
            status = session.status();
        } else {
            BreezeCatalogSession session = (BreezeCatalogSession) owner;
            String range = "personal".equals(session.query.voiceType) ? "个人音色" : "default".equals(session.query.voiceType) ? "系统音色" : "全部音色";
            header.title.setText(context.getString(R.string.breeze_catalog_title, range)); status = session.status();
        }
        if (!localCatalogSearch(owner).trim().isEmpty()) status += " · 本地匹配 " + header.matched + " 项";
        SpeechSettings speech = settings();
        if (!store.getString(voiceSampleKey(speech), "").isEmpty() && !VoiceCatalogLabels.queryKey(speech)
                .equals(store.getString(VoiceCatalogLabels.queryStorageKey(speech), ""))) status += " · 原缓存缺少当前筛选信息，请刷新";
        String remote = remoteCatalogSearch(owner);
        if (!remote.isEmpty()) status += " · 全部搜索：" + remote;
        header.status.setText(status);
        header.clear.setText(remote.isEmpty() ? "清空搜索" : "清空全部");
        boolean running = nameTranslation != null && nameTranslationOwner == owner;
        header.translate.setText(running ? "取消翻译" : "翻译名称");
        String progress = catalogTranslationStatus(owner);
        header.translationStatus.setText(progress.isEmpty() ? "使用当前文字 AI，每次最多 100 个名称，可能计费。" : progress);
    }

    private void searchWholeCatalog(Object owner, String text) {
        if (!liveCatalog(owner)) return;
        CatalogHeader header = catalogHeader(owner);
        try {
            if (owner instanceof FishCatalogSession) {
                String query = FishSpeechSettings.validateValue(FishSpeechSettings.VOICE_SEARCH, text);
                Map<String, Object> candidate = speechValues(); candidate.put(FishSpeechSettings.VOICE_SEARCH, query);
                if (!validateFishCatalog(new FishSpeechSettings(new LocalOptions(candidate)))) return;
                store.putString(FishSpeechSettings.VOICE_SEARCH, query); settingChanged(FishSpeechSettings.VOICE_SEARCH);
                refreshFishCatalog(settings().fishSettings);
            } else {
                String query = BreezeSpeechSettings.validateValue(BreezeSpeechSettings.VOICE_SEARCH, text);
                Map<String, Object> candidate = speechValues(); candidate.put(BreezeSpeechSettings.VOICE_SEARCH, query);
                if (!validateBreezeVoiceCatalog(new BreezeSpeechSettings(new LocalOptions(candidate)))) return;
                store.putString(BreezeSpeechSettings.VOICE_SEARCH, query); settingChanged(BreezeSpeechSettings.VOICE_SEARCH);
                refreshBreezeCatalog(settings().breezeSettings);
            }
        } catch (IllegalArgumentException failure) { header.search.setError(error(failure)); }
    }

    private void translateCatalogNames(Object owner) {
        if (!liveCatalog(owner)) return;
        if (nameTranslation != null && nameTranslationOwner == owner) {
            stopNameTranslation("已取消翻译，已缓存的译名保留"); return;
        }
        stopNameTranslation("已开始另一项音色名称翻译");
        String scope = VoiceNameTranslationCache.scope(settings());
        List<VoiceCatalog.Entry> originals = VoiceNameTranslationCache.read(store).pending(scope, loadedCatalogEntries(owner), VoiceNameTranslator.MAX_NAMES);
        if (originals.isEmpty()) { setCatalogTranslationStatus(owner, "已加载名称已有译名或无需翻译"); updateCatalogHeader(owner); return; }
        try {
            String model = store.getString(AiModelSettings.KEY, "");
            VoiceNameTranslator.Request request = VoiceNameTranslator.prepare(AiModelSettings.parse(model), originals);
            long generation = ++nameTranslationGeneration;
            nameTranslation = request; nameTranslationOwner = owner; nameTranslationModel = model;
            nameTranslationRequested = originals.size(); nameTranslationSaved = 0;
            setCatalogTranslationStatus(owner, "正在使用当前文字 AI 翻译 " + nameTranslationRequested + " 个名称…");
            updateCatalogHeader(owner);
            try {
                nameTranslationFuture = NAME_WORKER.submit(() -> {
                    try {
                        request.run(batch -> {
                            Map<String, String> copy = new LinkedHashMap<>(batch);
                            main.post(() -> {
                                if (!acceptNameTranslation(owner, request, generation, scope, model)) return;
                                VoiceNameTranslationCache.merge(store, scope, originals, copy);
                                nameTranslationSaved = originals.size() - VoiceNameTranslationCache.read(store)
                                        .pending(scope, originals, VoiceNameTranslator.MAX_NAMES).size();
                                setCatalogTranslationStatus(owner, "已缓存 " + nameTranslationSaved + " 个译名 / 本次 " + nameTranslationRequested + " 个名称");
                                updateCatalogChoices(owner); render();
                            });
                        });
                        main.post(() -> {
                            if (!acceptNameTranslation(owner, request, generation, scope, model)) return;
                            nameTranslation = null; nameTranslationFuture = null; nameTranslationOwner = null;
                            setCatalogTranslationStatus(owner, "已缓存 " + nameTranslationSaved + " 个译名，其余保留原名");
                            updateCatalogChoices(owner); updateCatalogHeader(owner);
                        });
                    } catch (Exception failure) {
                        main.post(() -> {
                            if (!acceptNameTranslation(owner, request, generation, scope, model)) return;
                            nameTranslation = null; nameTranslationFuture = null; nameTranslationOwner = null;
                            setCatalogTranslationStatus(owner, error(failure) + "；已缓存 " + nameTranslationSaved + " 个译名，未完成的可重试");
                            updateCatalogHeader(owner);
                        });
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException failure) {
                stopNameTranslation("翻译队列已满，请稍后重试");
            }
        } catch (IllegalArgumentException failure) {
            setCatalogTranslationStatus(owner, error(failure)); updateCatalogHeader(owner);
        }
    }

    private boolean acceptNameTranslation(Object owner, VoiceNameTranslator.Request request, long generation, String scope, String model) {
        if (nameTranslation != request || nameTranslationOwner != owner || nameTranslationGeneration != generation) return false;
        if (liveCatalog(owner) && scope.equals(VoiceNameTranslationCache.scope(settings()))
                && model.equals(store.getString(AiModelSettings.KEY, ""))) return true;
        stopNameTranslation("模型或音色列表已变化，已停止本次翻译"); return false;
    }

    private void stopNameTranslationFor(Object owner, String reason) {
        if (nameTranslationOwner == owner) stopNameTranslation(reason);
    }

    private void stopNameTranslation(String reason) {
        VoiceNameTranslator.Request request = nameTranslation;
        Object owner = nameTranslationOwner;
        nameTranslation = null; nameTranslationOwner = null; nameTranslationGeneration++;
        if (request != null) request.cancel();
        if (nameTranslationFuture != null) nameTranslationFuture.cancel(true);
        nameTranslationFuture = null;
        if (owner != null) { setCatalogTranslationStatus(owner, reason); updateCatalogHeader(owner); }
    }

    private final class CatalogHeader {
        final LinearLayout view = form();
        final TextView title = new TextView(context), status = new TextView(context), translationStatus = new TextView(context);
        final EditText search;
        final android.widget.Button filter, remote, clear, translate;
        final TextWatcher watcher;
        int matched;
        boolean closed;
        CatalogHeader(Object owner, String initial) {
            LinearLayout heading = new LinearLayout(context); heading.setOrientation(LinearLayout.HORIZONTAL); heading.setBaselineAligned(false); view.addView(heading, matchWrap());
            title.setTag("voice.catalog.title"); title.setTextSize(17); title.setMaxLines(2);
            heading.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            filter = miuix ? MiuixFormControls.button(context) : new android.widget.Button(context);
            filter.setText("筛选"); filter.setTag("voice.catalog.filters"); filter.setAllCaps(false); filter.setMinWidth(0);
            heading.addView(MiuixFormControls.viewFor(filter), new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
            status.setTag("voice.catalog.status"); status.setTextSize(12); status.setMaxLines(2);
            status.setEllipsize(android.text.TextUtils.TruncateAt.END); view.addView(status, matchWrap());
            search = input(view, "搜索已加载音色", initial, false);
            search.setTag("voice.catalog.search"); search.setSingleLine(true); search.setMaxLines(1);
            search.setInputType(InputType.TYPE_CLASS_TEXT);
            search.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(256)});
            LinearLayout actions = new LinearLayout(context); actions.setOrientation(LinearLayout.HORIZONTAL); actions.setBaselineAligned(false); view.addView(actions, matchWrap());
            remote = catalogHeaderButton(actions, "搜索全部", "voice.catalog.search.all");
            remote.setContentDescription("搜索全部音色（联网）");
            clear = catalogHeaderButton(actions, "清空搜索", "voice.catalog.search.clear");
            translate = catalogHeaderButton(actions, "翻译名称", "voice.catalog.translate");
            translate.setContentDescription("翻译已加载名称（每次最多100个，使用当前文字AI）");
            translationStatus.setTag("voice.catalog.translation.status"); translationStatus.setTextSize(12);
            translationStatus.setMaxLines(2); view.addView(translationStatus, matchWrap());
            watcher = new TextWatcher() {
                public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
                public void onTextChanged(CharSequence text, int start, int before, int count) {
                    if (closed || !liveCatalog(owner)) return;
                    setLocalCatalogSearch(owner, text.toString()); updateCatalogChoices(owner);
                }
                public void afterTextChanged(Editable text) { }
            };
            search.addTextChangedListener(watcher);
            filter.setOnClickListener(ignored -> {
                if (closed || !liveCatalog(owner)) return;
                catalogDialog(owner).dismiss();
                section(owner instanceof FishCatalogSession ? FISH_VOICES : BREEZE_VOICES);
            });
            remote.setOnClickListener(ignored -> { if (!closed) searchWholeCatalog(owner, search.getText().toString()); });
            clear.setOnClickListener(ignored -> {
                if (closed || !liveCatalog(owner)) return;
                if (remoteCatalogSearch(owner).isEmpty()) search.setText(""); else searchWholeCatalog(owner, "");
            });
            translate.setOnClickListener(ignored -> { if (!closed) translateCatalogNames(owner); });
        }
        void close() { closed = true; search.removeTextChangedListener(watcher); }
    }

    private android.widget.Button catalogHeaderButton(LinearLayout row, String text, String tag) {
        android.widget.Button button = miuix ? MiuixFormControls.button(context) : new android.widget.Button(context);
        button.setText(text); button.setTag(tag); button.setAllCaps(false); button.setTextSize(11); button.setMinWidth(0);
        button.setPadding(dp(4), dp(6), dp(4), dp(6));
        row.addView(MiuixFormControls.viewFor(button), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return button;
    }

    private void selectVoice() {
        if (closed) return;
        SpeechSettings settings = settings();
        if (settings.minimax || settings.breeze || settings.fish) {
            List<VoiceCatalog.Entry> entries = java.util.Collections.emptyList();
            try { entries = readCatalogSample(settings); }
            catch (Exception ignored) { }
            chooseVoices(entries);
            return;
        }
        try { chooseVoices(VoiceCatalog.parse(store.getString(VOICE_SAMPLE, ""), settings)); }
        catch (Exception ignored) {
            show(builder("音色").setItems(new String[]{"获取音色列表", "手动填写音色编号"}, (choice, index) -> {
                if (index == 0) refreshVoices(); else manualVoice();
            }).setNegativeButton("取消", null).create(), null);
        }
    }

    private void chooseVoices(List<VoiceCatalog.Entry> entries) {
        if (closed) return;
        SpeechSettings settings = settings();
        if (settings.fish) { chooseFishVoices(entries); return; }
        if (settings.breeze) { chooseBreezeVoices(entries); return; }
        boolean minimax = settings.minimax, breeze = settings.breeze, fish = settings.fish;
        List<VoiceCatalog.Entry> choices = new ArrayList<>(entries);
        int catalogOffset = fish ? 1 : 0;
        String[] labels = new String[choices.size() + catalogOffset];
        int selected = -1;
        String voiceKey = voiceIdKey(settings), nameKey = voiceNameKey(settings);
        String current = fish ? settings.fishSettings.referenceId : breeze ? settings.breezeSettings.voiceId
                : minimax ? settings.minimaxVoiceId : settings.selectedVoice;
        if (fish) {
            labels[0] = "接口默认音色";
            if (current.isEmpty()) selected = 0;
        }
        for (int i = 0; i < choices.size(); i++) {
            VoiceCatalog.Entry entry = choices.get(i);
            labels[i + catalogOffset] = entry.name + " · " + entry.id;
            if (entry.id.equals(current)) selected = i + catalogOffset;
        }
        AlertDialog.Builder picker = builder(fish ? "单说话人音色" : "音色").setSingleChoiceItems(labels, selected, (choice, index) -> {
            if (breeze && !canUseBreeze()) return;
            if (fish && !canUseFish()) return;
            if (fish && index == 0) {
                store.putString(voiceKey, "");
                store.putString(nameKey, "");
            } else {
                VoiceCatalog.Entry entry = choices.get(index - catalogOffset);
                store.putString(voiceKey, entry.id);
                store.putString(nameKey, entry.name);
            }
            if (fish) store.putString(FishSpeechSettings.VOICE_MODE, "single");
            stopPreview();
            choice.dismiss();
            render();
        }).setNeutralButton("手动填写", (choice, index) -> main.post(this::manualVoice))
                .setNegativeButton(minimax || breeze || fish ? "关闭" : "取消", null);
        if (minimax || breeze || fish) {
            picker.setPositiveButton("刷新", (choice, index) -> refreshVoices());
            if (entries.isEmpty() && !fish) picker.setMessage("尚无缓存音色，点击刷新获取当前账户的音色，或手动填写音色 ID。");
        }
        show(picker.create(), null);
    }

    private void manualVoice() {
        if (closed) return;
        LinearLayout form = form();
        SpeechSettings settings = settings();
        boolean minimax = settings.minimax, breeze = settings.breeze, fish = settings.fish;
        EditText value = input(form, "音色编号", fish ? settings.fishSettings.referenceId : breeze ? settings.breezeSettings.voiceId
                : minimax ? settings.minimaxVoiceId : settings.selectedVoice, false);
        if (fish) hint(form, "speech.fish.default.voice", "留空使用接口默认音色；这里填写参考音色 ID，不填写语音引擎名称。");
        AlertDialog editor = builder("音色编号").setView(form).setNegativeButton("取消", null)
                .setPositiveButton("保存", null).create();
        show(editor, null);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            if (breeze && !canUseBreeze()) return;
            if (fish && !canUseFish()) return;
            String candidate = value.getText().toString().trim();
            if (candidate.isEmpty() && !fish) { value.setError("请输入音色编号"); return; }
            if (minimax) {
                try { candidate = SpeechSettings.validateMiniMaxValue(SpeechSettings.MINIMAX_VOICE_ID, candidate); }
                catch (IllegalArgumentException invalid) { value.setError(error(invalid)); return; }
            }
            if (breeze) {
                try { candidate = BreezeSpeechSettings.validateValue(BreezeSpeechSettings.VOICE_ID, candidate); }
                catch (IllegalArgumentException invalid) { value.setError(error(invalid)); return; }
            }
            if (fish) {
                try { candidate = FishSpeechSettings.validateValue(FishSpeechSettings.REFERENCE_ID, candidate); }
                catch (IllegalArgumentException invalid) { value.setError(error(invalid)); return; }
            }
            store.putString(voiceIdKey(settings), candidate);
            store.putString(voiceNameKey(settings), "");
            if (fish) store.putString(FishSpeechSettings.VOICE_MODE, "single");
            stopPreview();
            editor.dismiss();
            render();
        });
    }

    private void testSpeech() {
        LinearLayout form = form();
        EditText text = input(form, "试听文本", "你好", false);
        TextView status = new TextView(context);
        status.setTag("speech.preview.status");
        form.addView(status, matchWrap());
        android.widget.Button start = button(form, "试听", "speech.preview.start");
        AlertDialog test = builder("试听").setView(form).setNegativeButton("关闭", null).create();
        start.setOnClickListener(ignored -> {
            stopPreview();
            DouAiLog.Operation trace = DouAiLog.begin("speech_preview", "user_test_speech");
            previewTrace = trace;
            long generation = previewGeneration;
            start.setEnabled(false);
            status.setText("正在生成语音");
            trace.stage("synthesis_requested", "text_chars=" + text.length() + "; playback_started=false");
            trace.wrap(() -> preview = SpeechSynthesis.synthesize(context, text.getText().toString(), options(), (result, failure) -> {
                if (closed || generation != previewGeneration || !test.isShowing()) {
                    finishPreview(trace, DouAiLog.Outcome.CANCELLED, "preview_owner_changed", "playback_started=false");
                    delete(result); return;
                }
                preview = null;
                start.setEnabled(true);
                if (result == null) {
                    finishPreview(trace, failure != null && failure.contains("超时") ? DouAiLog.Outcome.TIMED_OUT
                            : failure != null && failure.contains("已停止") ? DouAiLog.Outcome.CANCELLED : DouAiLog.Outcome.FAILED,
                            "synthesis_not_ready", "playback_started=false; diagnostic_ref=" + DouAiLog.reference(failure));
                    status.setText(failure); return;
                }
                previewFile = result.file;
                trace.stage("audio_ready", "duration_ms=" + result.durationMillis + "; playback_started=false");
                try {
                    MediaPlayer preparing = new MediaPlayer();
                    player = preparing;
                    preparing.setDataSource(result.file.getAbsolutePath());
                    preparing.setOnPreparedListener(ready -> {
                        if (closed || generation != previewGeneration || player != ready || !test.isShowing()) {
                            finishPreview(trace, DouAiLog.Outcome.CANCELLED, "late_player_prepared", "playback_started=false"); return;
                        }
                        try { ready.start(); }
                        catch (RuntimeException error) {
                            trace.error("player_start", error);
                            finishPreview(trace, DouAiLog.Outcome.FAILED, "player_start_failed", "playback_completed=false");
                            throw error;
                        }
                        trace.stage("playing", "player_start_returned=true; playback_completed=false");
                        status.setText("正在播放");
                    });
                    preparing.setOnCompletionListener(completed -> {
                        if (generation != previewGeneration || player != completed) {
                            finishPreview(trace, DouAiLog.Outcome.CANCELLED, "late_player_completion", "owner_matches=false"); return;
                        }
                        finishPreview(trace, DouAiLog.Outcome.SUCCESS, "player_completion_observed", "evidence=owned_media_player_completion_callback");
                        stopPreview();
                        status.setText("试听完成");
                    });
                    preparing.setOnErrorListener((failed, what, extra) -> {
                        if (generation == previewGeneration && player == failed) {
                            finishPreview(trace, DouAiLog.Outcome.FAILED, "player_error", "what=" + what + "; extra=" + extra + "; playback_completed=false");
                            stopPreview();
                            status.setText("试听播放失败");
                        } else trace.stage("late_player_error", "ignored=true; what=" + what + "; extra=" + extra);
                        return true;
                    });
                    status.setText("正在准备播放");
                    trace.stage("player_preparing", "prepare_async_requested=true; playback_started=false");
                    preparing.prepareAsync();
                } catch (Exception error) {
                    trace.error("player_prepare", error);
                    finishPreview(trace, DouAiLog.Outcome.FAILED, "player_prepare_failed", "playback_completed=false");
                    stopPreview(); status.setText("试听播放失败");
                }
            })).run();
        });
        show(test, this::stopPreview);
    }

    private void stopPreview() {
        DouAiLog.Operation trace = previewTrace;
        previewTrace = null;
        if (trace != null) trace.finish(DouAiLog.Outcome.CANCELLED, "preview_stopped_or_replaced", "playback_completion_unobserved=true");
        previewGeneration++;
        if (preview != null) { preview.close(); preview = null; }
        if (player != null) { player.release(); player = null; }
        if (previewFile != null) { previewFile.delete(); previewFile = null; }
    }

    private void finishPreview(DouAiLog.Operation trace, DouAiLog.Outcome outcome, String reason, String evidence) {
        if (previewTrace == trace) previewTrace = null;
        trace.finish(outcome, reason, evidence);
    }

    private void settingChanged(String key) {
        stopPreview();
        stopNameTranslation("设置已变化，已停止名称翻译");
        if (key.startsWith("tts_breeze_")) stopEnhancement();
        if (key.startsWith("tts_voice_") || key.startsWith("tts_minimax_") || key.startsWith("tts_breeze_") || key.startsWith("tts_fish_")) cancelFetch();
        if (FishSpeechSettings.API_KEY.equals(key) || FishSpeechSettings.BASE_URL.equals(key)) {
            discardFishCatalog();
            clearCatalogSample(true);
            store.putString(FishSpeechSettings.MODEL_SAMPLE, "");
            store.putString(FishSpeechSettings.MODEL_SOURCE, "");
        } else if (isCatalogSetting(true, key)) {
            discardFishCatalog();
            clearCatalogSample(true);
        }
        if (BreezeSpeechSettings.API_KEY.equals(key) || BreezeSpeechSettings.BASE_URL.equals(key)) {
            discardBreezeCatalog();
            clearCatalogSample(false);
            store.putString(BreezeSpeechSettings.MODEL_SAMPLE, "");
            store.putString(BreezeSpeechSettings.MODEL_SOURCE, "");
        } else if (isCatalogSetting(false, key)) {
            discardBreezeCatalog();
            clearCatalogSample(false);
        }
        if (SpeechSettings.MINIMAX_API_KEY.equals(key) || SpeechSettings.MINIMAX_BASE_URL.equals(key)) {
            store.putString(SpeechSettings.MINIMAX_VOICE_SAMPLE, "");
            if ("api".equals(store.getString(SpeechSettings.MINIMAX_MODEL_SOURCE, ""))) {
                store.putString(SpeechSettings.MINIMAX_MODEL_SAMPLE, "");
                store.putString(SpeechSettings.MINIMAX_MODEL_SOURCE, "");
            }
        }
    }

    private static boolean isCatalogSetting(boolean fish, String key) {
        return fish ? key.startsWith("tts_fish_voice_") && !FishSpeechSettings.VOICE_MODE.equals(key) && !FishSpeechSettings.VOICE_SAMPLE.equals(key)
                : key.startsWith("tts_breeze_voice_") && !BreezeSpeechSettings.VOICE_ID.equals(key)
                && !BreezeSpeechSettings.VOICE_NAME.equals(key) && !BreezeSpeechSettings.VOICE_SAMPLE.equals(key);
    }

    private void cancelFetch() {
        SpeechHttpClient.Request request = fetch;
        fetch = null;
        if (request != null) request.cancel();
        if (future != null) future.cancel(true);
        future = null;
        if (fishCatalog != null && fishCatalog.loading) {
            fishCatalog.loading = false; updateFishCatalogControls(fishCatalog);
        }
        if (breezeCatalog != null && breezeCatalog.loading) {
            breezeCatalog.loading = false; updateBreezeCatalogControls(breezeCatalog);
        }
    }

    private LocalOptions options() {
        return new LocalOptions(speechValues());
    }

    private Map<String, Object> speechValues() {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : new String[]{SpeechSettings.PROVIDER, SpeechSettings.GET_TEMPLATE, SpeechSettings.HEADERS,
                SpeechSettings.AUDIO_FIELD, SpeechSettings.VOICE_GET_TEMPLATE, SpeechSettings.VOICE_HEADERS,
                SpeechSettings.VOICE_LIST_PATH, SpeechSettings.VOICE_ID_PATH, SpeechSettings.VOICE_NAME_PATH,
                SpeechSettings.SELECTED_VOICE, SpeechSettings.SELECTED_VOICE_NAME, "tts_engine", "tts_language", SpeechSettings.RATE, SpeechSettings.PITCH,
                SpeechSettings.MINIMAX_API_KEY, SpeechSettings.MINIMAX_BASE_URL, SpeechSettings.MINIMAX_MODEL,
                SpeechSettings.MINIMAX_VOICE_ID, SpeechSettings.MINIMAX_VOICE_NAME, SpeechSettings.MINIMAX_SPEED,
                SpeechSettings.MINIMAX_VOLUME, SpeechSettings.MINIMAX_PITCH}) {
            String value = store.getString(key, null);
            if (value != null) values.put(key, value);
        }
        for (String key : MiniMaxSpeechOptions.KEYS) {
            String value = store.getString(key, null);
            if (value != null) values.put(key, value);
        }
        for (String key : BreezeSpeechSettings.KEYS) {
            String value = store.getString(key, null);
            if (value != null) values.put(key, value);
        }
        for (String key : FishSpeechSettings.KEYS) {
            String value = store.getString(key, null);
            if (value != null) values.put(key, value);
        }
        return values;
    }

    private SpeechSettings settings() { return new SpeechSettings(options()); }

    private static String parameterNames(String template) {
        try {
            List<String> names = new ArrayList<>();
            for (ThirdPartyRequestTemplate.Parameter parameter : ThirdPartyRequestTemplate.parse(template, "{text}", "{voice}").parameters) names.add(parameter.name);
            return names.isEmpty() ? "无参数" : String.join("、", names);
        } catch (Exception ignored) { return "未设置"; }
    }

    private void option(String title, String value, Runnable action) {
        option(content, title, value, action);
    }

    private void option(LinearLayout target, String title, String value, Runnable action) {
        if (miuix) {
            target.addView(new MiuixSettingsViews.Option(context, title, "", value, true, action).getView(), matchWrap());
        } else {
            LinearLayout row = form();
            row.setTag("settings.option." + title);
            TextView label = new TextView(context);
            label.setText(title);
            label.setTextSize(15);
            row.addView(label, matchWrap());
            if (value != null && !value.isEmpty()) {
                TextView detail = new TextView(context);
                detail.setTag("settings.value." + title);
                detail.setText(value);
                detail.setMaxLines(3);
                row.addView(detail, matchWrap());
            }
            row.setOnClickListener(ignored -> action.run());
            target.addView(row, matchWrap());
        }
    }

    private EditText input(LinearLayout form, String label, String initial, boolean secret) {
        EditText input = miuix ? MiuixFormControls.input(context, label, initial, secret) : new EditText(context);
        input.setContentDescription(label);
        input.setTag("settings.input." + label);
        input.setText(initial);
        input.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_MULTI_LINE));
        input.setMaxLines(6);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2 * 1024 * 1024)});
        form.addView(MiuixFormControls.viewFor(input), matchWrap());
        return input;
    }

    private android.widget.Button button(LinearLayout form, String text, String tag) {
        android.widget.Button button = miuix ? MiuixFormControls.button(context) : new android.widget.Button(context);
        button.setText(text);
        button.setTag(tag);
        form.addView(MiuixFormControls.viewFor(button), matchWrap());
        return button;
    }

    private void icon(LinearLayout row, String label, String tag, int resource, Runnable action) {
        ImageButton button = new ImageButton(context);
        button.setImageResource(resource);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setContentDescription(label);
        button.setTooltipText(label);
        button.setTag(tag);
        button.setOnClickListener(ignored -> action.run());
        row.addView(miuix ? MiuixExtraControls.iconAction(context, button, resource) : button,
                new LinearLayout.LayoutParams(dp(48), dp(48)));
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    private MiuixDialogBuilder builder(String title) {
        MiuixDialogBuilder builder = new MiuixDialogBuilder(context, miuix);
        builder.setTitle(title);
        return builder;
    }
    private void show(Dialog dialog, Runnable dismiss) { presenter.accept(dialog, dismiss); }
    private LinearLayout form() {
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(12, 8, 12, 8);
        return form;
    }
    private ScrollView scroll(View child) {
        ScrollView scroll = new ScrollView(context);
        scroll.addView(child);
        return scroll;
    }
    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
    private static String error(Throwable failure) { return failure.getMessage() == null ? "请求或解析失败" : failure.getMessage(); }
    private void toast(String value) { android.widget.Toast.makeText(context, value, android.widget.Toast.LENGTH_SHORT).show(); }
    private static void delete(LocalSpeechSynthesis.Result result) { if (result != null) result.file.delete(); }
    @Override public void close() {
        closed = true;
        stopNameTranslation("设置已关闭，已停止名称翻译");
        cancelFetch();
        discardFishCatalog();
        discardBreezeCatalog();
        stopPreview();
        stopEnhancement();
        instructionEditor = null;
        for (ReferenceEditorSession session : new ArrayList<>(referenceEditors)) session.close();
        for (Dialog child : new ArrayList<>(sectionDialogs.values())) child.dismiss();
        sectionDialogs.clear();
        sectionContents.clear();
        advancedContent = null;
    }
    private static final class ParameterRow {
        LinearLayout container;
        LinearLayout fixedValue;
        EditText name;
        EditText value;
        Spinner source;
    }
}
