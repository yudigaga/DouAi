package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.douai.module.R;
import com.douai.module.config.HostSettingsStore;
import com.douai.module.config.BreezeSpeechSettings;
import com.douai.module.config.FishSpeechSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.SettingsStore;
import com.douai.module.config.SpeechSettings;
import com.douai.module.feature.VoiceCatalog;
import com.douai.module.feature.VoiceCatalogLabels;
import com.douai.module.feature.VoiceNameTranslationCache;
import com.douai.module.ui.miuix.MiuixDialogBuilder;

import java.util.Collections;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Cached provider voice picker for the feed quick-action sheet. */
public final class GestureQuickVoiceDialog {
    private static final String THIRD_PARTY_SAMPLE = "tts_voice_sample";

    private GestureQuickVoiceDialog() { }

    public static AlertDialog create(Activity activity, SettingsStore store, Runnable openSettings,
                                     Consumer<AlertDialog> show) {
        SpeechSettings speech;
        String provider;
        List<VoiceCatalog.Entry> entries = Collections.emptyList();
        try {
            Map<String, ?> values = store.snapshot();
            LocalOptions snapshot = new LocalOptions(values);
            speech = new SpeechSettings(snapshot);
            provider = snapshot.text(SpeechSettings.PROVIDER, SpeechSettings.LOCAL);
            if (speech.minimax) {
                entries = VoiceCatalog.parse(store.getString(SpeechSettings.MINIMAX_VOICE_SAMPLE, ""), "", "", "");
            } else if (speech.breeze) {
                String sample = snapshot.text(BreezeSpeechSettings.VOICE_SAMPLE, "");
                if (!sample.trim().isEmpty() && VoiceCatalogLabels.queryKey(speech).equals(
                        snapshot.text(VoiceCatalogLabels.BREEZE_QUERY_KEY, ""))) {
                    entries = VoiceCatalog.parse(sample, "", "", "");
                }
            } else if (speech.fish) {
                String sample = snapshot.text(FishSpeechSettings.VOICE_SAMPLE, "");
                if (!sample.trim().isEmpty() && VoiceCatalogLabels.queryKey(speech).equals(
                        snapshot.text(VoiceCatalogLabels.FISH_QUERY_KEY, ""))) {
                    entries = VoiceCatalog.parse(sample, "", "", "");
                }
            } else if (speech.thirdParty) {
                entries = VoiceCatalog.parse(store.getString(THIRD_PARTY_SAMPLE, ""), speech);
            }
        } catch (RuntimeException unavailable) {
            return new MiuixDialogBuilder(activity, true).setTitle("切换音色")
                    .setMessage("当前语音设置没有可用的缓存音色列表。")
                    .setNegativeButton("关闭", null)
                    .setPositiveButton("打开模块设置", (dialog, which) -> openSettings.run())
                    .create();
        }
        if (speech.local) {
            return createLocalVoicePicker(activity, store, provider, openSettings, show);
        }
        String selectedId = speech.fish ? speech.fishSettings.referenceId : speech.breeze ? speech.breezeSettings.voiceId
                : speech.minimax ? speech.minimaxVoiceId : speech.selectedVoice;
        String key = speech.fish ? FishSpeechSettings.VOICE_ID : speech.breeze ? BreezeSpeechSettings.VOICE_ID
                : speech.minimax ? SpeechSettings.MINIMAX_VOICE_ID : SpeechSettings.SELECTED_VOICE;
        String nameKey = speech.fish ? FishSpeechSettings.VOICE_NAME : speech.breeze ? BreezeSpeechSettings.VOICE_NAME
                : speech.minimax ? SpeechSettings.MINIMAX_VOICE_NAME : SpeechSettings.SELECTED_VOICE_NAME;
        List<VoiceCatalog.Entry> availableEntries = entries;
        if (entries.isEmpty() && !speech.fish) {
            return new MiuixDialogBuilder(activity, true).setTitle("切换音色")
                    .setMessage("尚无缓存音色列表，请先到文本转语音设置获取音色。")
                    .setNegativeButton("关闭", null)
                    .setNeutralButton("手动填写", (dialog, which) -> {
                        dialog.dismiss();
                        presentManualVoice(activity, store, provider, key, nameKey, selectedId, show);
                    })
                    .setPositiveButton("打开模块设置", (dialog, which) -> openSettings.run())
                    .create();
        }

        if (speech.fish || speech.breeze) {
            if (entries.size() > 200) entries = new ArrayList<>(entries.subList(0, 200));
            return new CachedProviderPicker(activity, store, speech, provider, entries,
                    key, nameKey, openSettings, show).create();
        }

        int offset = speech.fish ? 1 : 0;
        String[] labels = new String[availableEntries.size() + offset];
        int selected = speech.fish && selectedId.isEmpty() ? 0 : -1;
        if (speech.fish) labels[0] = "接口默认音色";
        for (int index = 0; index < availableEntries.size(); index++) {
            VoiceCatalog.Entry entry = availableEntries.get(index);
            labels[index + offset] = entry.name + " · " + entry.id;
            if (entry.id.equals(selectedId)) selected = index + offset;
        }
        MiuixDialogBuilder builder = new MiuixDialogBuilder(activity, true);
        builder.setTitle(speech.fish ? "切换单说话人音色" : "切换音色");
        builder.setSingleChoiceItems(labels, selected, (dialog, index) -> {
                    if (!sameProvider(store, provider)) { dialog.dismiss(); return; }
                    try {
                        VoiceCatalog.Entry entry = speech.fish && index == 0 ? null : availableEntries.get(index - offset);
                        String id = entry == null ? "" : speech.fish ? FishSpeechSettings.validateValue(key, entry.id)
                                : speech.breeze ? BreezeSpeechSettings.validateValue(key, entry.id) : entry.id;
                        String name = entry == null ? "" : speech.fish ? FishSpeechSettings.validateValue(nameKey, entry.name)
                                : speech.breeze ? BreezeSpeechSettings.validateValue(nameKey, entry.name) : entry.name;
                        store.putString(key, id);
                        store.putString(nameKey, name);
                        if (speech.fish) store.putString(FishSpeechSettings.VOICE_MODE, "single");
                        Toast.makeText(activity, "已切换到 " + (entry == null ? "接口默认音色" : name), Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    } catch (IllegalArgumentException invalid) {
                        Toast.makeText(activity, invalid.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
        builder.setNegativeButton("关闭", null);
        builder.setNeutralButton("手动填写", (dialog, which) -> {
                    dialog.dismiss();
                    presentManualVoice(activity, store, provider, key, nameKey, selectedId, show);
                });
        builder.setPositiveButton("文本转语音设置", (dialog, which) -> openSettings.run());
        return builder.create();
    }

    /** A cached-only search; the full settings screen owns directory and translation requests. */
    private static final class CachedProviderPicker {
        private final Activity activity;
        private final SettingsStore store;
        private final SpeechSettings speech;
        private final String provider, scope, queryKey, key, nameKey;
        private final List<VoiceCatalog.Entry> entries;
        private final List<VoiceCatalog.Entry> visible = new ArrayList<>();
        private final VoiceNameTranslationCache names;
        private final Runnable openSettings;
        private final Consumer<AlertDialog> show;
        private final EditText search;
        private final TextView status;
        private AlertDialog picker;

        CachedProviderPicker(Activity activity, SettingsStore store, SpeechSettings speech, String provider,
                             List<VoiceCatalog.Entry> entries, String key, String nameKey,
                             Runnable openSettings, Consumer<AlertDialog> show) {
            this.activity = activity; this.store = store; this.speech = speech; this.provider = provider;
            this.entries = entries; this.key = key; this.nameKey = nameKey;
            this.openSettings = openSettings; this.show = show;
            scope = VoiceNameTranslationCache.scope(speech);
            queryKey = VoiceCatalogLabels.queryKey(speech);
            names = VoiceNameTranslationCache.read(store);
            search = new EditText(ModuleTheme.themedContext(activity));
            search.setTag("speech.quick.voice.search");
            search.setContentDescription("搜索音色");
            search.setHint("搜索中文译名、原名或音色 ID");
            search.setSingleLine(true);
            search.setFilters(new InputFilter[]{new InputFilter.LengthFilter(256)});
            status = new TextView(ModuleTheme.themedContext(activity));
            status.setTag("speech.quick.voice.search.status");
        }

        AlertDialog create() {
            LinearLayout header = new LinearLayout(ModuleTheme.themedContext(activity));
            header.setOrientation(LinearLayout.VERTICAL);
            int padding = Math.round(16 * activity.getResources().getDisplayMetrics().density);
            header.setPadding(padding, padding, padding, 0);
            TextView title = new TextView(header.getContext());
            title.setText(speech.fish ? "切换单说话人音色" : "切换音色"); title.setTextSize(18);
            header.addView(title);
            LinearLayout row = new LinearLayout(header.getContext());
            row.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button clear = new Button(header.getContext()); clear.setText("清空");
            clear.setContentDescription("清空音色搜索"); clear.setTag("speech.quick.voice.search.clear");
            clear.setOnClickListener(ignored -> search.setText("")); row.addView(clear);
            header.addView(row); header.addView(status);
            TextView hint = new TextView(header.getContext());
            hint.setText("筛选已缓存音色；搜索全部或翻译名称请进入文本转语音设置。");
            header.addView(hint);
            MiuixDialogBuilder builder = new MiuixDialogBuilder(activity, true);
            builder.setCustomTitle(header);
            builder.setSingleChoiceItems(new String[0], -1, (dialog, index) -> choose(index));
            builder.setNegativeButton("关闭", null);
            builder.setNeutralButton("手动填写", (dialog, which) -> {
                if (current()) presentManualVoice(activity, store, provider, key, nameKey,
                        store.getString(key, ""), show);
            });
            builder.setPositiveButton("文本转语音设置", (dialog, which) -> openSettings.run());
            picker = builder.create();
            picker.setOnShowListener(ignored -> update());
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence value, int start, int before, int count) { update(); }
                @Override public void afterTextChanged(Editable value) { }
            });
            return picker;
        }

        private boolean current() {
            if (!sameProvider(store, provider)) return false;
            try {
                SpeechSettings current = new SpeechSettings(new LocalOptions(store.snapshot()));
                return scope.equals(VoiceNameTranslationCache.scope(current))
                        && queryKey.equals(VoiceCatalogLabels.queryKey(current));
            }
            catch (RuntimeException invalid) { return false; }
        }

        private void update() {
            if (picker == null || !picker.isShowing()) return;
            if (!current()) { picker.dismiss(); return; }
            String query = search.getText().toString().trim();
            visible.clear();
            if (speech.fish && (query.isEmpty() || "接口默认音色".contains(query))) visible.add(null);
            int matched = 0;
            for (VoiceCatalog.Entry entry : entries) if (names.matches(scope, entry, query)) {
                visible.add(entry); matched++;
            }
            String[] labels = new String[visible.size()]; int selected = -1;
            String selectedId = store.getString(key, "");
            for (int index = 0; index < visible.size(); index++) {
                VoiceCatalog.Entry entry = visible.get(index);
                labels[index] = entry == null ? "接口默认音色" : names.displayName(scope, entry) + " · " + entry.id
                        + (entry.labels.isEmpty() ? "" : "\n" + String.join(" · ", entry.labels));
                if (entry == null ? selectedId.isEmpty() : entry.id.equals(selectedId)) selected = index;
            }
            String emptyStatus = visible.isEmpty() ? activity.getString(R.string.voice_quick_catalog_empty) : "";
            status.setText(activity.getString(R.string.voice_quick_catalog_status, entries.size(), matched, emptyStatus));
            MiuixDialogBuilder.updateSingleChoiceItems(picker, labels, selected, (dialog, index) -> choose(index));
        }

        private void choose(int index) {
            if (!current()) { picker.dismiss(); return; }
            if (index < 0 || index >= visible.size()) return;
            VoiceCatalog.Entry entry = visible.get(index);
            try {
                String id = entry == null ? "" : speech.fish ? FishSpeechSettings.validateValue(key, entry.id)
                        : BreezeSpeechSettings.validateValue(key, entry.id);
                String original = entry == null ? "" : speech.fish ? FishSpeechSettings.validateValue(nameKey, entry.name)
                        : BreezeSpeechSettings.validateValue(nameKey, entry.name);
                store.putString(key, id); store.putString(nameKey, original);
                if (speech.fish) store.putString(FishSpeechSettings.VOICE_MODE, "single");
                Toast.makeText(activity, "已切换到 " + (entry == null ? "接口默认音色" : names.displayName(scope, entry)),
                        Toast.LENGTH_SHORT).show();
                picker.dismiss();
            } catch (IllegalArgumentException invalid) {
                Toast.makeText(activity, invalid.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private static AlertDialog createLocalVoicePicker(Activity activity, SettingsStore store,
                                                      String provider,
                                                      Runnable openSettings,
                                                      Consumer<AlertDialog> show) {
        MiuixDialogBuilder loadingBuilder = new MiuixDialogBuilder(activity, true);
        loadingBuilder.setTitle("切换音色");
        loadingBuilder.setMessage("正在读取设备已安装的离线语音音色…");
        loadingBuilder.setNegativeButton("取消", null);
        AlertDialog loading = loadingBuilder.create();
        Handler main = new Handler(Looper.getMainLooper());
        AtomicBoolean resolved = new AtomicBoolean();
        TextToSpeech[] engine = new TextToSpeech[1];
        Runnable timeout = () -> {
            if (!resolved.compareAndSet(false, true) || !loading.isShowing()) return;
            shutdown(engine);
            if (!sameProvider(store, provider)) { loading.dismiss(); return; }
            show.accept(localVoiceMessage(activity, openSettings,
                    "读取系统语音超时，请检查设备上的 TTS 引擎。"));
        };
        loading.setOnDismissListener(ignored -> {
            main.removeCallbacks(timeout);
            if (resolved.compareAndSet(false, true)) shutdown(engine);
        });
        String configuredEngine = store.getString("tts_engine", "").trim();
        try {
            engine[0] = new TextToSpeech(activity, status -> main.post(() -> {
                if (!resolved.compareAndSet(false, true) || !loading.isShowing()) {
                    shutdown(engine);
                    return;
                }
                main.removeCallbacks(timeout);
                if (!sameProvider(store, provider)) {
                    shutdown(engine);
                    loading.dismiss();
                    return;
                }
                TextToSpeech current = engine[0];
                if (status != TextToSpeech.SUCCESS || current == null) {
                    shutdown(engine);
                    show.accept(localVoiceMessage(activity, openSettings,
                            "无法读取当前系统语音引擎，请检查引擎设置。"));
                    return;
                }
                List<Voice> voices = new ArrayList<>();
                try {
                    if (current.getVoices() != null) {
                        for (Voice voice : current.getVoices()) {
                            if (voice != null && voice.getLocale() != null
                                    && !voice.isNetworkConnectionRequired()) voices.add(voice);
                        }
                    }
                } catch (RuntimeException unavailable) { }
                shutdown(engine);
                voices.sort(Comparator.comparing((Voice voice) -> voice.getLocale().toLanguageTag())
                        .thenComparing(Voice::getName, String.CASE_INSENSITIVE_ORDER));
                if (voices.isEmpty()) {
                    show.accept(localVoiceMessage(activity, openSettings,
                            "当前引擎没有已安装的离线音色，请先在系统语音设置中下载语音数据。"));
                    return;
                }

                String savedVoice = store.getString(SpeechSettings.LOCAL_VOICE_NAME, "").trim();
                Locale savedLanguage = Locale.forLanguageTag(store.getString("tts_language", "zh-CN"));
                String[] labels = new String[voices.size()];
                int selected = -1;
                for (int index = 0; index < voices.size(); index++) {
                    Voice voice = voices.get(index);
                    Locale locale = voice.getLocale();
                    labels[index] = locale.getDisplayName(Locale.getDefault()) + " · " + voice.getName();
                    if (voice.getName().equals(savedVoice)
                            && locale.toLanguageTag().equals(savedLanguage.toLanguageTag())) selected = index;
                    else if (selected < 0 && savedVoice.isEmpty() && locale.equals(savedLanguage)) selected = index;
                }
                MiuixDialogBuilder picker = new MiuixDialogBuilder(activity, true);
                picker.setTitle("选择离线音色");
                picker.setSingleChoiceItems(labels, selected, (dialog, index) -> {
                    if (!sameProvider(store, provider)) { dialog.dismiss(); return; }
                    Voice voice = voices.get(index);
                    store.putString(SpeechSettings.LOCAL_VOICE_NAME, voice.getName());
                    store.putString("tts_language", voice.getLocale().toLanguageTag());
                    Toast.makeText(activity, "已切换到 " + voice.getLocale().getDisplayName(Locale.getDefault()),
                            Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                });
                picker.setNegativeButton("关闭", null);
                picker.setPositiveButton("语音设置", (dialog, which) -> openSettings.run());
                show.accept(picker.create());
            }), configuredEngine.isEmpty() ? null : configuredEngine);
            main.postDelayed(timeout, 10_000L);
        } catch (RuntimeException | LinkageError failure) {
            resolved.set(true);
            shutdown(engine);
            show.accept(localVoiceMessage(activity, openSettings,
                    "无法初始化系统语音引擎，请检查模块语音设置。"));
        }
        return loading;
    }

    private static AlertDialog localVoiceMessage(Activity activity, Runnable openSettings, String message) {
        MiuixDialogBuilder builder = new MiuixDialogBuilder(activity, true);
        builder.setTitle("切换音色");
        builder.setMessage(message);
        builder.setNegativeButton("关闭", null);
        builder.setPositiveButton("打开语音设置", (dialog, which) -> openSettings.run());
        return builder.create();
    }

    private static void shutdown(TextToSpeech[] engine) {
        TextToSpeech current = engine[0];
        engine[0] = null;
        if (current == null) return;
        try { current.shutdown(); } catch (RuntimeException ignored) { }
    }

    private static void presentManualVoice(Activity activity, SettingsStore store, String provider,
                                           String key, String nameKey, String current,
                                           Consumer<AlertDialog> show) {
        if (!sameProvider(store, provider)) return;
        AlertDialog prompt = MiuixDialogBuilder.prompt(activity, true, "音色编号", current, value -> {
            if (!sameProvider(store, provider)) return;
            String candidate = value == null ? "" : value.trim();
            try {
                if (SpeechSettings.FISH.equals(provider)) {
                    candidate = FishSpeechSettings.validateValue(FishSpeechSettings.VOICE_ID, candidate);
                } else if (SpeechSettings.BREEZE.equals(provider)) {
                    candidate = BreezeSpeechSettings.validateValue(BreezeSpeechSettings.VOICE_ID, candidate);
                    if (candidate.isEmpty()) throw new IllegalArgumentException("请输入有效的 Breeze Blue 音色 ID");
                } else if (SpeechSettings.MINIMAX.equals(provider)) {
                    candidate = SpeechSettings.validateMiniMaxValue(SpeechSettings.MINIMAX_VOICE_ID, candidate);
                } else if (candidate.isEmpty() || candidate.length() > 256
                        || candidate.codePoints().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("请输入有效的音色编号");
                }
                store.putString(key, candidate);
                store.putString(nameKey, "");
            if (SpeechSettings.FISH.equals(provider)) store.putString(FishSpeechSettings.VOICE_MODE, "single");
            } catch (IllegalArgumentException invalid) {
                Toast.makeText(activity, invalid.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        show.accept(prompt);
    }

    private static boolean sameProvider(SettingsStore store, String provider) {
        return provider.equals(store.getString(SpeechSettings.PROVIDER, SpeechSettings.LOCAL));
    }
}
