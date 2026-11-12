package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.douai.module.config.ComponentHideSettings;
import com.douai.module.config.ComponentHideSettings.Component;
import com.douai.module.config.CustomViewLocator;
import com.douai.module.config.SettingsStore;
import com.douai.module.ui.miuix.MiuixDialogBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/** The component controls share the same store in the module and host settings. */
public final class ComponentHideSettingsPage {
    private static final int TEXT = Color.rgb(31, 39, 45);
    private static final int MUTED = Color.rgb(105, 115, 122);
    private static final int ACCENT = Color.rgb(0, 133, 225);
    private static final int LINE = Color.rgb(226, 231, 232);

    private final Context context;
    private final SettingsStore store;
    private Dialog dialog;
    private Dialog customDetails;
    private Dialog alphaInput;
    private LinearLayout content;
    private LinearLayout customSection;
    private Switch uniformToggle;
    private final Map<String, View> controlRows = new LinkedHashMap<>();
    private final Map<String, View> controlDividers = new LinkedHashMap<>();
    private final List<View> customAlphaRows = new ArrayList<>();
    private final List<String> customViewIdentities = new ArrayList<>();

    public ComponentHideSettingsPage(Context context, SettingsStore store) {
        this.context = ModuleTheme.themedContext(context);
        this.store = store;
    }

    public void show() {
        if (isShowing()) return;
        Activity activity = ownerActivity(context);
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        Dialog next = new Dialog(context, ModuleTheme.dialogTheme(context));
        next.setOwnerActivity(activity);
        next.setCanceledOnTouchOutside(true);
        next.setOnDismissListener(ignored -> {
            dismissCustomDetails();
            dismissAlphaInput();
            if (dialog == next) dialog = null;
        });
        next.setContentView(createView());
        Window window = next.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.45f;
            window.setAttributes(attributes);
        }
        dialog = next;
        try {
            next.show();
            if (window != null) {
                DisplayMetrics metrics = context.getResources().getDisplayMetrics();
                View decor = activity.getWindow().getDecorView();
                int availableWidth = decor.getWidth() > 0 ? decor.getWidth() : metrics.widthPixels;
                int availableHeight = decor.getHeight() > 0 ? decor.getHeight() : metrics.heightPixels;
                int width = Math.min(dp(560), Math.max(1, availableWidth - dp(24)));
                int height = Math.max(1, Math.round(availableHeight * 0.82f));
                window.setLayout(width, height);
            }
        } catch (RuntimeException failure) {
            dismiss();
        }
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    public void dismiss() {
        dismissCustomDetails();
        dismissAlphaInput();
        Dialog current = dialog;
        dialog = null;
        if (current != null) current.dismiss();
    }

    View createView() {
        ComponentHideSettings settings = ComponentHideSettings.readFrom(store);
        controlRows.clear();
        controlDividers.clear();
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), 0, dp(12), dp(12));
        panel.setBackground(rounded(Color.rgb(245, 246, 249), dp(8)));
        panel.addView(createHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackground(rounded(Color.WHITE, dp(8)));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        addToggle("隐藏总开关", ComponentHideSettings.ENABLED, settings.enabled);
        addToggle("统一透明度开关", ComponentHideSettings.UNIFORM_ENABLED, settings.uniformEnabled);
        addAlpha("统一透明度", null, ComponentHideSettings.UNIFORM_ALPHA, settings.uniformAlpha);
        addToggle("切换视频时,自动执行隐藏View", ComponentHideSettings.HIDE_ON_SWITCH,
                settings.hideOnSwitch);
        addToggle("暂停播放时,显示View", ComponentHideSettings.SHOW_ON_PAUSE,
                settings.showOnPause);
        addToggle("暂停显示时打开评论", ComponentHideSettings.OPEN_COMMENTS_ON_SHOW,
                settings.openCommentsOnShow);
        for (Component component : Component.values()) {
            addAlpha(component.title, null,
                    component.key, settings.individualAlpha(component));
        }
        addCustomViewsSection(settings.customViews);
        updateControlVisibility();
        ModuleTheme.bind(panel);
        return panel;
    }

    private void addCustomViewsSection(List<CustomViewLocator> locators) {
        customSection = new LinearLayout(context);
        customSection.setOrientation(LinearLayout.VERTICAL);
        customSection.setTag("component.custom.section");
        content.addView(customSection, matchWrap());
        renderCustomViews(locators);
    }

    private void renderCustomViews(List<CustomViewLocator> locators) {
        if (customSection == null) return;
        customSection.removeAllViews();
        customAlphaRows.clear();
        customViewIdentities.clear();
        addDividerTo(customSection);
        LinearLayout heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setBaselineAligned(false);
        heading.setPadding(dp(12), dp(14), dp(12), dp(12));
        TextView header = text("自定义视图", 15, TEXT, true);
        heading.addView(header, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView count = text(locators.size() + " 个", 13, MUTED, false);
        count.setTag("component.custom.count");
        heading.addView(count, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        customSection.addView(heading, matchWrap());
        if (locators.isEmpty()) {
            TextView empty = text("暂无自定义视图", 13, MUTED, false);
            empty.setPadding(dp(12), dp(4), dp(12), dp(16));
            customSection.addView(empty, matchWrap());
            return;
        }
        for (CustomViewLocator locator : locators) addCustomViewRow(locator);
    }

    private void addCustomViewRow(CustomViewLocator locator) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(8), dp(8));
        row.setMinimumHeight(dp(58));
        row.setTag("component.custom." + locator.identityKey());
        row.setFocusable(true);
        row.setOnClickListener(ignored -> {
            dismissCustomDetails();
            customDetails = showCustomViewDetails(context, locator, false);
        });
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(locator.label, 14, TEXT, true), matchWrap());
        labels.addView(text(locator.scopeDescription() + " · " + locator.matchingDescription(),
                12, MUTED, false), matchWrap());
        labels.addView(text(locator.viewClassName, 11, MUTED, false), matchWrap());
        row.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton remove = new ImageButton(context);
        remove.setImageResource(android.R.drawable.ic_menu_delete);
        remove.setBackgroundColor(Color.TRANSPARENT);
        remove.setContentDescription("删除自定义视图");
        remove.setTooltipText("删除自定义视图");
        remove.setTag("component.custom.delete." + locator.identityKey());
        remove.setOnClickListener(ignored -> confirmDelete(locator));
        row.addView(remove, new LinearLayout.LayoutParams(dp(48), dp(48)));
        customSection.addView(row, matchWrap());
        String alphaKey = "component.custom.alpha." + locator.identityKey();
        View alphaRow = createAlphaRow("透明度", null, alphaKey, locator.alpha,
                alpha -> saveCustomAlpha(locator, alpha));
        alphaRow.findViewWithTag(alphaKey).setContentDescription(locator.label + "透明度");
        alphaRow.findViewWithTag(alphaKey + ".value").setContentDescription(
                locator.label + "，点击输入透明度");
        customSection.addView(alphaRow, matchWrap());
        customAlphaRows.add(alphaRow);
        customViewIdentities.add(locator.identityKey());
        alphaRow.setVisibility(ComponentHideSettings.readFrom(store).enabled ? View.VISIBLE : View.GONE);
        addDividerTo(customSection);
    }

    private void saveCustomAlpha(CustomViewLocator locator, int alpha) {
        List<CustomViewLocator> updated = new ArrayList<>(ComponentHideSettings.readFrom(store).customViews);
        for (int index = 0; index < updated.size(); index++) {
            CustomViewLocator current = updated.get(index);
            if (!current.identityKey().equals(locator.identityKey())) continue;
            updated.set(index, current.withAlpha(alpha));
            store.putString(CustomViewLocator.SETTINGS_KEY, CustomViewLocator.serialize(updated));
            break;
        }
        List<String> identities = new ArrayList<>(updated.size());
        for (CustomViewLocator item : updated) identities.add(item.identityKey());
        if (!customViewIdentities.equals(identities)) renderCustomViews(updated);
    }

    /** Shared by the native and Miuix component lists; this reads locator metadata only. */
    public static AlertDialog showCustomViewDetails(Context source, CustomViewLocator locator,
                                                   boolean useMiuix) {
        Context themed = ModuleTheme.themedContext(source);
        Activity activity = ownerActivity(themed);
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        String information = "作用域：" + locator.scopeDescription()
                + "\n定位方式：" + locator.matchingDescription()
                + "\n完整类名：" + locator.viewClassName
                + "\n资源 ID：" + (locator.resourceName.isEmpty() ? "无资源 ID" : locator.resourceName)
                + "\n根类名：" + locator.rootClassName
                + "\n定位路径：\n" + locator.pathDescription();
        TextView detail = new TextView(themed);
        detail.setText(information);
        detail.setTextSize(13);
        detail.setTextIsSelectable(true);
        detail.setTag("component.custom.details");
        int padding = Math.round(16 * themed.getResources().getDisplayMetrics().density);
        detail.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(themed);
        scroll.addView(detail);
        ModuleTheme.bind(scroll);
        AlertDialog next = new MiuixDialogBuilder(themed, useMiuix)
                .setTitle("自定义视图详情").setView(scroll)
                .setNegativeButton("关闭", null)
                .setPositiveButton("复制规则信息", (dialog, which) -> {
                    try {
                        ClipboardManager clipboard = (ClipboardManager) themed.getSystemService(
                                Context.CLIPBOARD_SERVICE);
                        if (clipboard == null) throw new IllegalStateException("Clipboard unavailable");
                        clipboard.setPrimaryClip(ClipData.newPlainText("DouAi 自定义视图", information));
                        Toast.makeText(themed, "已复制规则信息", Toast.LENGTH_SHORT).show();
                    } catch (RuntimeException unavailable) {
                        Toast.makeText(themed, "复制失败", Toast.LENGTH_SHORT).show();
                    }
                }).create();
        next.setOwnerActivity(activity);
        // MiuixDialogBuilder owns its inset listener and Compose height limit.
        if (!useMiuix) next.setOnShowListener(ignored -> HostDialogBounds.install(activity, next, false));
        try {
            next.show();
            return next;
        } catch (RuntimeException unavailable) {
            next.dismiss();
            return null;
        }
    }

    private void dismissCustomDetails() {
        Dialog current = customDetails;
        customDetails = null;
        if (current != null) current.dismiss();
    }

    private void dismissAlphaInput() {
        Dialog current = alphaInput;
        alphaInput = null;
        if (current != null) current.dismiss();
    }

    private void confirmDelete(CustomViewLocator locator) {
        new MiuixDialogBuilder(context, false).setTitle("删除自定义视图")
                .setMessage("删除后会恢复该视图。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    List<CustomViewLocator> updated = new ArrayList<>(
                            ComponentHideSettings.readFrom(store).customViews);
                    updated.removeIf(item -> item.identityKey().equals(locator.identityKey()));
                    store.putString(CustomViewLocator.SETTINGS_KEY, CustomViewLocator.serialize(updated));
                    renderCustomViews(updated);
                }).show();
    }

    private View createHeader() {
        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("‹ 根目录", 16, ACCENT, false);
        back.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        back.setContentDescription("返回根目录");
        back.setFocusable(true);
        back.setOnClickListener(view -> dismiss());
        header.addView(back, new LinearLayout.LayoutParams(dp(90), dp(48)));
        TextView title = text("组件隐藏", 20, TEXT, true);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(1);
        title.setAutoSizeTextTypeUniformWithConfiguration(14, 20, 1,
                TypedValue.COMPLEX_UNIT_SP);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        header.addView(new View(context), new LinearLayout.LayoutParams(dp(90), 1));
        return header;
    }

    private void addToggle(String title, String key, boolean checked) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setMinimumHeight(dp(58));
        TextView label = text(title, 15, TEXT, false);
        row.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch toggle = new Switch(context);
        if (ComponentHideSettings.UNIFORM_ENABLED.equals(key)) uniformToggle = toggle;
        toggle.setChecked(checked);
        toggle.setContentDescription(title);
        toggle.setTag(key);
        toggle.setMinimumWidth(dp(52));
        toggle.setOnCheckedChangeListener((button, value) -> {
            initializeMaster();
            store.putBoolean(key, value);
            updateControlVisibility();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(12);
        row.addView(toggle, params);
        addControlRow(key, row);
    }

    private void addAlpha(String title, String subtitle, String key, int initialValue) {
        View row = createAlphaRow(title, subtitle, key, initialValue, alpha -> {
            initializeMaster();
            if (!ComponentHideSettings.UNIFORM_ALPHA.equals(key)) {
                store.putBoolean(ComponentHideSettings.UNIFORM_ENABLED, false);
                if (uniformToggle != null) uniformToggle.setChecked(false);
            }
            store.putString(key, Integer.toString(alpha));
            updateControlVisibility();
        });
        addControlRow(key, row);
    }

    private View createAlphaRow(String title, String subtitle, String key, int initialValue,
                                IntConsumer save) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(14), dp(12), dp(10));
        LinearLayout heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 15, TEXT, false), matchWrap());
        if (subtitle != null) labels.addView(text(subtitle, 12, MUTED, false), matchWrap());
        heading.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView value = text("透明度 " + initialValue, 14, TEXT, false);
        value.setTag(key + ".value");
        value.setFocusable(true);
        value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        value.setMaxLines(1);
        value.setAutoSizeTextTypeUniformWithConfiguration(11, 14, 1,
                TypedValue.COMPLEX_UNIT_SP);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(dp(98), dp(48));
        valueParams.leftMargin = dp(6);
        heading.addView(value, valueParams);
        row.addView(heading, matchWrap());

        SeekBar slider = new SeekBar(context) {
            private final int touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            private float downX;
            private float downY;
            private boolean dragging;

            @Override public boolean onTouchEvent(MotionEvent event) {
                if (!isEnabled()) return false;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getX();
                        downY = event.getY();
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!dragging && Math.abs(event.getX() - downX) > touchSlop
                                && Math.abs(event.getX() - downX) >= Math.abs(event.getY() - downY)) {
                            dragging = true;
                            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                            MotionEvent down = MotionEvent.obtain(event);
                            down.setAction(MotionEvent.ACTION_DOWN);
                            down.setLocation(downX, downY);
                            super.onTouchEvent(down);
                            down.recycle();
                        }
                        return !dragging || super.onTouchEvent(event);
                    case MotionEvent.ACTION_UP:
                        if (!dragging) return performClick();
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        if (!dragging) return true;
                        break;
                }
                boolean handled = super.onTouchEvent(event);
                if (event.getActionMasked() == MotionEvent.ACTION_UP
                        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) dragging = false;
                return handled;
            }

            @Override public boolean performClick() {
                return !dragging && super.performClick();
            }
        };
        slider.setMax(257);
        slider.setProgress(initialValue + 2);
        slider.setTag(key);
        slider.setContentDescription(title);
        setAlphaStateDescription(slider, initialValue);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private boolean tracking;
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int alpha = progress - 2;
                value.setText("透明度 " + alpha);
                setAlphaStateDescription(seekBar, alpha);
                if (fromUser && !tracking) save.accept(alpha);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { tracking = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                tracking = false;
                save.accept(seekBar.getProgress() - 2);
            }
        });
        slider.setOnClickListener(ignored -> showAlphaInput(slider.getContentDescription().toString(), slider, save));
        value.setOnClickListener(ignored -> showAlphaInput(slider.getContentDescription().toString(), slider, save));
        value.setContentDescription(title + "，点击输入透明度");
        LinearLayout.LayoutParams sliderParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        sliderParams.topMargin = dp(8);
        row.addView(slider, sliderParams);
        return row;
    }

    private void showAlphaInput(String title, SeekBar slider, IntConsumer save) {
        Activity activity = ownerActivity(context);
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        dismissAlphaInput();
        EditText input = new EditText(context);
        input.setTag("component.alpha.input");
        input.setContentDescription(title);
        input.setSingleLine(true);
        // Keep pasted text intact so invalid decimals cannot silently become another integer.
        input.setRawInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setText(String.format(java.util.Locale.ROOT, "%d", slider.getProgress() - 2));
        input.selectAll();
        LinearLayout body = new LinearLayout(context);
        body.setPadding(dp(20), dp(4), dp(20), dp(4));
        body.addView(input, matchWrap());
        ModuleTheme.bind(body);
        AlertDialog next = new MiuixDialogBuilder(context, false).setTitle(title)
                .setView(body).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        next.setOwnerActivity(activity);
        next.setOnDismissListener(ignored -> { if (alphaInput == next) alphaInput = null; });
        next.setOnShowListener(ignored -> HostDialogBounds.install(activity, next, false));
        alphaInput = next;
        try {
            next.show();
            next.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
                if (alphaInput != next || !next.isShowing()) return;
                final int alpha;
                try {
                    String raw = input.getText().toString().trim();
                    if (!raw.matches("-?[0-9]+")) throw new NumberFormatException();
                    alpha = Integer.parseInt(raw);
                    if (alpha < -2 || alpha > 255) throw new NumberFormatException();
                } catch (NumberFormatException invalid) {
                    input.setError("请输入 -2 到 255 的整数");
                    return;
                }
                slider.setProgress(alpha + 2);
                save.accept(alpha);
                next.dismiss();
            });
            input.requestFocus();
            if (next.getWindow() != null) next.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        } catch (RuntimeException unavailable) {
            dismissAlphaInput();
        }
    }

    private void addControlRow(String key, View row) {
        content.addView(row, matchWrap());
        controlRows.put(key, row);
        controlDividers.put(key, addDivider());
    }

    private void updateControlVisibility() {
        ComponentHideSettings settings = ComponentHideSettings.readFrom(store);
        for (Map.Entry<String, View> entry : controlRows.entrySet()) {
            String key = entry.getKey();
            boolean visible = ComponentHideSettings.ENABLED.equals(key) || settings.enabled;
            if (ComponentHideSettings.UNIFORM_ALPHA.equals(key)) visible &= settings.uniformEnabled;
            else if (ComponentHideSettings.isAlphaKey(key)) visible &= !settings.uniformEnabled;
            else if (ComponentHideSettings.OPEN_COMMENTS_ON_SHOW.equals(key)) visible &= settings.showOnPause;
            int visibility = visible ? View.VISIBLE : View.GONE;
            entry.getValue().setVisibility(visibility);
            controlDividers.get(key).setVisibility(visibility);
        }
        for (View row : customAlphaRows) row.setVisibility(settings.enabled ? View.VISIBLE : View.GONE);
    }

    private void initializeMaster() {
        ComponentHideSettings.initialize(store);
    }

    private static void setAlphaStateDescription(SeekBar slider, int alpha) {
        if (Build.VERSION.SDK_INT >= 30) slider.setStateDescription("透明度 " + alpha);
    }

    private View addDivider() {
        return addDividerTo(content);
    }

    private View addDividerTo(LinearLayout target) {
        View divider = new View(context);
        divider.setBackgroundColor(LINE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        params.leftMargin = dp(12);
        params.rightMargin = dp(12);
        target.addView(divider, params);
        return divider;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable rounded(int fill, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static Activity ownerActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return null;
    }
}
