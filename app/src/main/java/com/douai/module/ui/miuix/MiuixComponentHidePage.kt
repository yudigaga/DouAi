package com.douai.module.ui.miuix

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.douai.module.config.ComponentHideSettings
import com.douai.module.config.ComponentHideSettings.Component
import com.douai.module.config.CustomViewLocator
import com.douai.module.config.SettingsStore
import com.douai.module.ui.HostDialogBounds
import com.douai.module.ui.ModuleTheme
import com.douai.module.ui.ComponentHideSettingsPage
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.basic.Close
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/** A separate window keeps the parent settings page and its scroll position intact. */
class MiuixComponentHidePage(
    private val context: Context,
    private val store: SettingsStore,
) {
    private var dialog: Dialog? = null
    private var session: HostComposeEnvironment? = null
    private var pendingDelete by mutableStateOf<CustomViewLocator?>(null)
    private var alphaInput by mutableStateOf<AlphaInput?>(null)
    private var customDetails: Dialog? = null

    fun show() {
        if (isShowing()) return
        val activity = ownerActivity(context) ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val nextSession = HostComposeEnvironment(activity, Runnable(::requestBack))
        val view = ComposeView(nextSession.context())
        nextSession.attach(view)
        val next = Dialog(nextSession.context(), ModuleTheme.dialogTheme(nextSession.context()))
        val editor = ComponentHideEditor(store)
        next.setOwnerActivity(activity)
        next.setCanceledOnTouchOutside(true)
        next.setOnDismissListener {
            dismissCustomDetails()
            nextSession.close()
            if (dialog === next) {
                dialog = null
                session = null
                pendingDelete = null
                alphaInput = null
            }
        }
        view.setContent {
            HostSettingsTheme { Content(editor) }
        }
        next.setContentView(view)
        next.window?.apply {
            setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.45f }
        }
        dialog = next
        session = nextSession
        try {
            next.show()
            nextSession.bindBackNavigation(next)
            HostDialogBounds.install(activity, next, false)
        } catch (_: RuntimeException) {
            dismiss()
        }
    }

    fun isShowing(): Boolean = dialog?.isShowing == true

    fun dismiss() {
        dismissCustomDetails()
        val current = dialog
        dialog = null
        pendingDelete = null
        alphaInput = null
        try {
            current?.dismiss()
        } finally {
            session?.close()
            session = null
        }
    }

    private fun requestBack() {
        if (customDetails?.isShowing == true) dismissCustomDetails()
        else if (alphaInput != null) alphaInput = null
        else if (pendingDelete != null) pendingDelete = null else dismiss()
    }

    private fun dismissCustomDetails() {
        customDetails?.dismiss()
        customDetails = null
    }

    @Composable
    private fun Content(editor: ComponentHideEditor) {
        val settings = editor.settings
        Scaffold(
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp))
                .background(MiuixTheme.colorScheme.background, RoundedCornerShape(20.dp))
                .testTag("component.page"),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(
                    Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = ::dismiss, modifier = Modifier.size(48.dp)) {
                        Icon(MiuixIcons.Basic.ArrowRight, contentDescription = "返回根目录",
                            modifier = Modifier.rotate(180f))
                    }
                    Text(
                        "组件隐藏", modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(48.dp))
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("component.list")) {
                    item(key = ComponentHideSettings.ENABLED) {
                        Toggle("隐藏总开关", ComponentHideSettings.ENABLED, settings.enabled, editor)
                    }
                    if (settings.enabled) {
                        item(key = ComponentHideSettings.UNIFORM_ENABLED) {
                            Toggle("统一透明度开关", ComponentHideSettings.UNIFORM_ENABLED,
                                settings.uniformEnabled, editor)
                        }
                        if (settings.uniformEnabled) {
                            item(key = ComponentHideSettings.UNIFORM_ALPHA) {
                                Alpha("统一透明度", null, ComponentHideSettings.UNIFORM_ALPHA,
                                    settings.uniformAlpha, editor)
                            }
                        }
                        item(key = ComponentHideSettings.HIDE_ON_SWITCH) {
                            Toggle("切换视频时,自动执行隐藏View", ComponentHideSettings.HIDE_ON_SWITCH,
                                settings.hideOnSwitch, editor)
                        }
                        item(key = ComponentHideSettings.SHOW_ON_PAUSE) {
                            Toggle("暂停播放时,显示View", ComponentHideSettings.SHOW_ON_PAUSE,
                                settings.showOnPause, editor)
                        }
                        if (settings.showOnPause) {
                            item(key = ComponentHideSettings.OPEN_COMMENTS_ON_SHOW) {
                                Toggle("暂停显示时打开评论", ComponentHideSettings.OPEN_COMMENTS_ON_SHOW,
                                    settings.openCommentsOnShow, editor)
                            }
                        }
                        if (!settings.uniformEnabled) {
                            items(Component.values().toList(), key = { it.key }) { component ->
                                Alpha(component.title, if (component == Component.CARD) "作者昵称头顶的" else null,
                                    component.key, settings.individualAlpha(component), editor)
                            }
                        }
                    }
                    item(key = "component.custom.header") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { SmallTitle("自定义视图") }
                            Text("${settings.customViews.size} 个",
                                modifier = Modifier.padding(end = 24.dp).testTag("component.custom.count"),
                                fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                    if (settings.customViews.isEmpty()) {
                        item(key = "component.custom.empty") {
                            BasicComponent(title = "暂无自定义视图")
                        }
                    }
                    items(settings.customViews, key = { "custom.${it.identityKey()}" }) { locator ->
                        BasicComponent(
                            title = locator.label,
                            summary = locator.scopeDescription() + " · " + locator.matchingDescription()
                                + "\n" + locator.viewClassName,
                            modifier = Modifier.testTag("component.custom.${locator.identityKey()}"),
                            onClick = {
                                dismissCustomDetails()
                                customDetails = ComponentHideSettingsPage.showCustomViewDetails(context, locator, true)
                            },
                            endActions = {
                                IconButton(
                                    onClick = { pendingDelete = locator },
                                    modifier = Modifier.size(48.dp)
                                        .testTag("component.custom.delete.${locator.identityKey()}"),
                                ) {
                                    Icon(MiuixIcons.Basic.Close, contentDescription = "删除自定义视图")
                                }
                            },
                        )
                        if (settings.enabled) {
                            Alpha("透明度", null, "component.custom.alpha.${locator.identityKey()}",
                                locator.alpha, editor, locator)
                        }
                    }
                    item(key = "component.bottom") { Spacer(Modifier.height(20.dp)) }
                }
            }
            OverlayDialog(
                show = pendingDelete != null,
                title = "删除自定义视图",
                summary = "删除后会恢复该视图。",
                onDismissRequest = { pendingDelete = null },
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton("取消", onClick = { pendingDelete = null }, modifier = Modifier.weight(1f)
                        .testTag("component.custom.confirm_cancel"))
                    TextButton("删除", onClick = {
                        pendingDelete?.let(editor::deleteCustomView)
                        pendingDelete = null
                    }, modifier = Modifier.weight(1f).testTag("component.custom.confirm_delete"))
                }
            }
            alphaInput?.let { input ->
                AlphaInputDialog(input)
            }
        }
    }

    @Composable
    private fun Toggle(title: String, key: String, checked: Boolean, editor: ComponentHideEditor) {
        SwitchPreference(
            checked = checked,
            onCheckedChange = { editor.setBoolean(key, it) },
            title = title,
            modifier = Modifier.background(MiuixTheme.colorScheme.background).testTag(key),
        )
    }

    @Composable
    private fun Alpha(
        title: String, subtitle: String?, key: String, initial: Int, editor: ComponentHideEditor,
        locator: CustomViewLocator? = null,
    ) {
        var alpha by remember(key, initial) { mutableIntStateOf(initial) }
        var touching by remember(key) { mutableStateOf(false) }
        var dragging by remember(key) { mutableStateOf(false) }
        var dirty by remember(key) { mutableStateOf(false) }
        val save by rememberUpdatedState<(Int) -> Unit> { value ->
            if (locator == null) editor.setAlpha(key, value) else editor.setCustomAlpha(locator, value)
        }
        val edit by rememberUpdatedState {
            alphaInput = AlphaInput(title, alpha, save)
        }
        val commit by rememberUpdatedState {
            if (dirty) {
                save(alpha)
                dirty = false
            }
        }
        Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.background)
            .padding(horizontal = 24.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(title, fontSize = 16.sp)
                    if (subtitle != null) {
                        Text(subtitle, fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
                Text("透明度 $alpha", modifier = Modifier.width(98.dp).heightIn(min = 48.dp)
                    .clickable(onClickLabel = "输入透明度", onClick = { edit() })
                    .padding(vertical = 14.dp).testTag("$key.value"), fontSize = 14.sp)
            }
            Slider(
                value = (alpha + 2).toFloat(),
                onValueChange = {
                    if (touching && !dragging) return@Slider
                    val changed = it.roundToInt().coerceIn(0, 257) - 2
                    if (alpha != changed) {
                        alpha = changed
                        dirty = true
                        if (!touching) commit()
                    }
                },
                valueRange = 0f..257f,
                steps = 256,
                onValueChangeFinished = { commit() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(key)
                    .semantics {
                        contentDescription = title
                        stateDescription = "透明度 $alpha"
                        onClick(label = "输入透明度") { edit(); true }
                    }
                    .pointerInput(key) {
                        // Miuix accessibility changes do not invoke onValueChangeFinished.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            touching = true
                            dragging = false
                            var tapped = false
                            try {
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val pointer = event.changes.firstOrNull { it.id == down.id }
                                    if (event.changes.size > 1 || pointer == null ||
                                        (pointer.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                        dragging = true
                                    }
                                    tapped = !dragging && pointer != null && !pointer.pressed &&
                                        pointer.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis
                                } while (event.changes.any { it.pressed })
                                // Keep the slider's release callback inside the same touch transaction.
                                awaitPointerEvent(PointerEventPass.Final)
                            } finally {
                                touching = false
                                commit()
                            }
                            if (tapped) edit()
                        }
                    },
            )
        }
    }

    private class AlphaInput(val title: String, val initial: Int, val save: (Int) -> Unit)

    @Composable
    private fun AlphaInputDialog(input: AlphaInput) {
        var value by remember(input) {
            mutableStateOf(TextFieldValue(input.initial.toString(), TextRange(0, input.initial.toString().length)))
        }
        var error by remember(input) { mutableStateOf(false) }
        val focus = remember(input) { FocusRequester() }
        OverlayDialog(show = true, title = input.title,
            summary = "输入 -2～255 的整数；-2 移除视图，-1 隐藏占位，0 全透明，255 不透明。",
            onDismissRequest = { alphaInput = null }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(value = value, onValueChange = { value = it; error = false },
                    singleLine = true, label = "透明度",
                    // The signed sentinel values need a keyboard with a minus key.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("component.alpha.input"))
                if (error) Text("请输入 -2～255 的整数", modifier = Modifier.testTag("component.alpha.error"),
                    color = hostThemeColor(0xFFB3261E.toInt()), fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton("取消", onClick = { alphaInput = null }, modifier = Modifier.weight(1f)
                        .testTag("component.alpha.cancel"))
                    TextButton("确定", onClick = {
                        val alpha = value.text.trim().toIntOrNull()
                        if (alpha == null || alpha !in -2..255) error = true
                        else { input.save(alpha); alphaInput = null }
                    }, modifier = Modifier.weight(1f).testTag("component.alpha.confirm"))
                }
            }
            LaunchedEffect(input) { focus.requestFocus() }
        }
    }

    private fun ownerActivity(source: Context): Activity? {
        var current = source
        while (current is ContextWrapper) {
            if (current is Activity) return current
            val next = current.baseContext
            if (next === current) return null
            current = next
        }
        return null
    }
}

/** Reload after writes so migration defaults and edits from the host remain authoritative. */
internal class ComponentHideEditor(private val store: SettingsStore) {
    var settings: ComponentHideSettings by mutableStateOf(ComponentHideSettings.readFrom(store))
        private set

    fun setBoolean(key: String, enabled: Boolean) {
        require(ComponentHideSettings.isBooleanKey(key))
        ComponentHideSettings.initialize(store)
        store.putBoolean(key, enabled)
        settings = ComponentHideSettings.readFrom(store)
    }

    fun setAlpha(key: String, alpha: Int) {
        require(ComponentHideSettings.isAlphaKey(key))
        require(alpha in -2..255)
        ComponentHideSettings.initialize(store)
        if (key != ComponentHideSettings.UNIFORM_ALPHA) {
            store.putBoolean(ComponentHideSettings.UNIFORM_ENABLED, false)
        }
        store.putString(key, alpha.toString())
        settings = ComponentHideSettings.readFrom(store)
    }

    fun deleteCustomView(locator: CustomViewLocator) {
        val latest = ComponentHideSettings.readFrom(store).customViews
        val updated = latest.filterNot { it.identityKey() == locator.identityKey() }
        store.putString(CustomViewLocator.SETTINGS_KEY, CustomViewLocator.serialize(updated))
        settings = ComponentHideSettings.readFrom(store)
    }

    fun setCustomAlpha(locator: CustomViewLocator, alpha: Int) {
        require(alpha in -2..255)
        val latest = ComponentHideSettings.readFrom(store).customViews
        val updated = latest.map { if (it.identityKey() == locator.identityKey()) it.withAlpha(alpha) else it }
        store.putString(CustomViewLocator.SETTINGS_KEY, CustomViewLocator.serialize(updated))
        settings = ComponentHideSettings.readFrom(store)
    }
}
