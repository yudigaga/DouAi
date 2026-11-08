package com.douai.module.feature;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Module-owned follow-ups carried by an existing full-span C2 row, with native list recycling. */
final class CommentAiSuggestionRows implements AutoCloseable {
    final Method bind;
    private final Method viewById, wrap, lookupData, refresh, request;
    private final Constructor<?> modelConstructor, parameterConstructor;
    private final Field context, ledger, conversationState, currentRound, roundId, roundRank, start, count;
    private final Map<Object, Handle> owned = new IdentityHashMap<>();
    private static final int TEXT_ID = 0x7f0acadb;

    CommentAiSuggestionRows(ClassLoader loader) throws Exception {
        Class<?> card = type(loader, "com.ss.android.ugc.aweme.c2page.impl.newarchitecture.ai.card.C2ArchEndQueryCard");
        Class<?> model = type(loader, "com.ss.android.ugc.aweme.c2page.impl.newarchitecture.ai.model.C2QueryEndModel");
        Class<?> manager = type(loader, "X.1B3z"), param = type(loader, "X.1B3X");
        Class<?> composer = type(loader, "com.ss.android.ugc.aweme.c2page.impl.newarchitecture.C2ComposerImpl");
        Class<?> round = type(loader, "X.1B3p");
        bind = card.getDeclaredMethod("LJJJJL", Object.class);
        if (bind.getReturnType() != void.class) throw new NoSuchMethodException("Unexpected follow-up row binding");
        viewById = card.getMethod("LJIIZILJ", int.class);
        modelConstructor = model.getConstructor(String.class);
        parameterConstructor = param.getConstructor(String.class, String.class, boolean.class, String.class,
                Map.class, type(loader, "X.1BbH"), List.class, int.class);
        request = manager.getDeclaredMethod("LIZLLL", param);
        wrap = type(loader, "X.1BZ9").getDeclaredMethod("LIZ", Object.class, String.class, int.class);
        context = field(manager, "LIZIZ");
        ledger = field(manager, "LIZ");
        conversationState = field(type(loader, "X.1B4J"), "LIZIZ");
        currentRound = field(type(loader, "X.1B3a"), "LIZJ");
        roundId = field(round, "LIZ");
        roundRank = field(round, "LIZIZ");
        start = field(round, "LIZLLL");
        count = field(round, "LJ");
        lookupData = composer.getDeclaredMethod("LJIIIIZZ");
        refresh = type(loader, "X.1AxX").getDeclaredMethod("LJIIJJI");
    }

    Handle insert(Object manager, String id, int rank, List<String> questions, Consumer<String> onQuestion) throws Exception {
        mainThread();
        Object conversation = conversationState.get(ledger.get(manager));
        Object round = conversation == null ? null : currentRound.get(conversation);
        if (round == null || !HostCommentAiVideos.matchesSession((String) roundId.get(round), roundRank.getInt(round), id, rank)) {
            return null;
        }
        if (questions == null || questions.isEmpty()) return null;
        Object owner = context.get(manager);
        Object raw = lookupData.invoke(owner);
        if (!(raw instanceof List<?>)) return null;
        @SuppressWarnings("unchecked") List<Object> data = (List<Object>) raw;
        Object model = modelConstructor.newInstance("douai_followup_" + UUID.randomUUID());
        Object item = wrap.invoke(null, model, null, 3);
        int position = start.getInt(round) + count.getInt(round);
        if (position < 0 || position > data.size()) return null;
        Handle handle = new Handle(model, item, owner, round, data, questions, onQuestion);
        owned.put(model, handle);
        try {
            data.add(position, item);
            count.setInt(round, count.getInt(round) + 1);
            handle.counted = true;
            refresh(owner);
            return handle;
        } catch (Exception failure) {
            handle.close();
            throw failure;
        }
    }

    void bind(Object card, Object model) throws Exception {
        mainThread();
        Object raw = viewById.invoke(card, TEXT_ID);
        if (!(raw instanceof TextView) || !(((TextView) raw).getParent() instanceof ViewGroup)) return;
        TextView text = (TextView) raw;
        ViewGroup parent = (ViewGroup) text.getParent();
        // The host can recycle this same row for an official end marker or another owned round.
        for (Handle handle : new ArrayList<>(owned.values())) handle.unbind(parent);
        Handle handle = owned.get(model);
        if (handle == null || handle.closed) return;
        try {
            handle.rows.add(render(parent, text, handle.questions, handle.onQuestion));
        } catch (Exception failure) {
            text.setText("继续提问");
            throw failure;
        }
    }

    void ask(Object manager, String question) throws Exception {
        mainThread();
        Object parameter = parameterConstructor.newInstance(question, "douai_followup", false, null, null, null, null, 508);
        request.invoke(manager, parameter);
    }

    private void refresh(Object owner) throws Exception {
        Object holder = field(owner.getClass(), "o").get(owner);
        Object feed = field(holder.getClass(), "LIZ").get(holder);
        if (feed == null) throw new IllegalStateException("Native C2 feed is unavailable");
        refresh.invoke(feed);
    }

    @Override public void close() {
        for (Handle handle : new ArrayList<>(owned.values())) handle.close();
    }

    final class Handle implements AutoCloseable {
        private final Object model, item, owner, round;
        private final List<Object> data;
        private final List<String> questions;
        private final Consumer<String> onQuestion;
        private final List<Row> rows = new ArrayList<>();
        private boolean closed;
        private boolean counted;
        Handle(Object model, Object item, Object owner, Object round, List<Object> data,
                List<String> questions, Consumer<String> onQuestion) {
            this.model = model; this.item = item; this.owner = owner; this.round = round; this.data = data;
            this.questions = List.copyOf(questions); this.onQuestion = onQuestion;
        }
        void unbind(ViewGroup parent) {
            rows.removeIf(row -> {
                if (row.parent != parent) return false;
                row.close(); return true;
            });
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            owned.remove(model);
            for (Row row : rows) row.close();
            rows.clear();
            for (int index = 0; index < data.size(); index++) {
                if (data.get(index) != item) continue;
                try {
                    data.remove(index);
                    if (counted) count.setInt(round, Math.max(0, count.getInt(round) - 1));
                    refresh(owner);
                } catch (Exception ignored) { }
                break;
            }
        }
    }

    static Row render(ViewGroup parent, TextView template, List<String> questions, Consumer<String> selected) throws Exception {
        Context context = parent.getContext();
        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.VERTICAL);
        buttons.setPadding(0, dp(context, 10), 0, dp(context, 10));
        int textColor = template.getCurrentTextColor() | 0xFF000000;
        boolean lightText = Color.luminance(textColor) > .5;
        textColor = lightText ? 0xFFF1F1F2 : 0xFF161823;
        for (String question : questions.subList(0, Math.min(3, questions.size()))) {
            TextView button = new QuestionButton(context);
            button.setText(question);
            button.setContentDescription(question);
            button.setTextColor(textColor);
            button.setTextSize(14);
            button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            button.setGravity(Gravity.CENTER_VERTICAL);
            button.setMaxLines(3);
            button.setMinHeight(dp(context, 44));
            button.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(dp(context, 8));
            background.setColor(lightText ? 0xFF343434 : 0xFFF3F3F5);
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(lightText ? 0x33FFFFFF : 0x22000000), background, null));
            button.setOnClickListener(view -> selected.accept(question));
            button.setFocusable(true);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (buttons.getChildCount() > 0) params.topMargin = dp(context, 8);
            buttons.addView(button, params);
        }
        ViewGroup.LayoutParams params;
        if (parent.getClass().getName().equals("androidx.constraintlayout.widget.ConstraintLayout")) {
            Class<?> nativeParams = Class.forName("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams", false, parent.getClass().getClassLoader());
            params = (ViewGroup.LayoutParams) nativeParams.getConstructor(int.class, int.class)
                    .newInstance(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            for (String constraint : new String[]{"startToStart", "endToEnd", "topToTop", "bottomToBottom"}) {
                nativeParams.getField(constraint).setInt(params, 0);
            }
        } else params = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        Row row = new Row(parent, buttons);
        for (int index = 0; index < parent.getChildCount(); index++) {
            View child = parent.getChildAt(index);
            row.original.put(child, child.getVisibility());
        }
        parent.addView(buttons, params);
        for (View child : row.original.keySet()) child.setVisibility(View.GONE);
        return row;
    }

    static final class Row implements AutoCloseable {
        final ViewGroup parent;
        final LinearLayout buttons;
        final Map<View, Integer> original = new IdentityHashMap<>();
        private boolean closed;
        Row(ViewGroup parent, LinearLayout buttons) { this.parent = parent; this.buttons = buttons; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            for (int index = 0; index < buttons.getChildCount(); index++) buttons.getChildAt(index).setOnClickListener(null);
            parent.removeView(buttons);
            for (Map.Entry<View, Integer> child : original.entrySet()) {
                if (child.getKey().getParent() == parent) child.getKey().setVisibility(child.getValue());
            }
            original.clear();
        }
    }
    private static final class QuestionButton extends TextView {
        QuestionButton(Context context) { super(context); }
        @Override public CharSequence getAccessibilityClassName() { return "android.widget.Button"; }
    }
    private static int dp(Context context, int value) { return Math.round(context.getResources().getDisplayMetrics().density * value); }
    private static void mainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Native follow-ups require the main thread");
    }
    private static Class<?> type(ClassLoader loader, String name) throws ClassNotFoundException { return Class.forName(name, false, loader); }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        while (type != null) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException absent) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }
}
