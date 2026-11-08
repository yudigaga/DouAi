package com.douai.module.ui.miuix

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.douai.module.ui.ScanStatus
import com.douai.module.ui.ModuleTheme
import java.util.function.Consumer
import java.util.function.IntConsumer
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.basic.Close
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.theme.darkColorScheme

/** Java-facing view adapters. Configuration and option behavior stay in ModuleSettingsScreen. */
object MiuixSettingsViews {
    @JvmStatic
    fun categoryPage(
        environment: HostComposeEnvironment,
        title: String,
        content: View,
        close: Runnable,
    ): View {
        ModuleTheme.bind(content)
        val view = ComposeView(environment.context())
        environment.attach(view)
        view.setContent {
            HostSettingsTheme {
                Column(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp))
                        .background(MiuixTheme.colorScheme.background, RoundedCornerShape(20.dp))
                        .testTag("miuix.category.page"),
                ) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = close::run,
                            modifier = Modifier.size(48.dp).testTag("miuix.category.back"),
                        ) {
                            Icon(
                                MiuixIcons.Basic.ArrowRight,
                                contentDescription = "返回设置分类",
                                modifier = Modifier.rotate(180f),
                            )
                        }
                        Text(
                            title,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.size(48.dp))
                    }
                    AndroidView(
                        factory = { content },
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp).clipToBounds(),
                    )
                }
            }
        }
        return view
    }

    class Home(
        activity: Activity,
        private val titles: Array<String>,
        private val counts: IntArray,
        private val onCategory: IntConsumer,
        private val onClose: Runnable,
    ) : AutoCloseable {
        private val environment = HostComposeEnvironment(activity, onClose)
        private var currentScan by mutableStateOf(ScanStatus.pending())
        private var categorySearchTerms by mutableStateOf(titles.copyOf())
        private var featureSearch by mutableStateOf<List<FeatureSearch>>(emptyList())
        private var onFeature: IntConsumer? = null
        val view = ComposeView(environment.context())

        init {
            view.tag = "douai.miuix.home"
            environment.attach(view)
            view.setContent { HostSettingsTheme { Content() } }
        }

        fun setScanStatus(status: ScanStatus) { currentScan = status }
        fun setSearchTerms(terms: Array<String>) {
            require(terms.size == titles.size)
            categorySearchTerms = terms.copyOf()
        }
        fun setFeatureSearch(ids: Array<String>, labels: Array<String>, paths: Array<String>,
                             terms: Array<String>, action: IntConsumer) {
            require(ids.size == labels.size && ids.size == paths.size && ids.size == terms.size)
            onFeature = action
            featureSearch = ids.indices.map { FeatureSearch(ids[it], labels[it], paths[it], terms[it]) }
        }
        fun bindBackNavigation(dialog: Dialog) = environment.bindBackNavigation(dialog)
        override fun close() = environment.close()

        @Composable
        private fun Content() {
            var query by androidx.compose.runtime.remember { mutableStateOf("") }
            Column(
                Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp))
                    .background(MiuixTheme.colorScheme.background, RoundedCornerShape(20.dp))
                    .verticalScroll(rememberScrollState()).padding(vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("DouAi", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text("抖音 40.6.0 / 40.8.0 / 41.0.0", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    IconButton(onClick = { onClose.run() }) {
                        Icon(MiuixIcons.Basic.Close, contentDescription = "关闭设置")
                    }
                }
                BasicComponent(
                    title = currentScan.title,
                    summary = currentScan.detail + if (currentScan.restartRequired) "\n请完全退出并重新打开抖音后生效。" else "",
                    startAction = {
                        Box(Modifier.padding(end = 12.dp).size(8.dp).background(
                            if (currentScan.ready) Color(0xFF009688) else Color(0xFFE79728), CircleShape,
                        ))
                    },
                )
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "搜索功能",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .testTag("settings.search"),
                )
                val matching = titles.indices.filter { categorySearchTerms[it].contains(query.trim(), ignoreCase = true) }
                val features = if (query.isBlank()) emptyList() else featureSearch.indices.filter {
                    featureSearch[it].terms.contains(query.trim(), ignoreCase = true)
                }
                if (matching.isEmpty() && features.isEmpty()) BasicComponent(title = "没有匹配的设置分类")
                matching.forEach { index ->
                    BasicComponent(
                        title = titles[index],
                        modifier = Modifier.testTag("settings.category.$index"),
                        endActions = {
                            if (counts[index] >= 0) {
                                Text(if (counts[index] == 0) "未适配" else "${counts[index]}项",
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                            Icon(MiuixIcons.Basic.ArrowRight, contentDescription = null)
                        },
                        onClick = { onCategory.accept(index) },
                    )
                }
                features.forEach { index ->
                    val entry = featureSearch[index]
                    BasicComponent(
                        title = entry.title,
                        summary = entry.path,
                        modifier = Modifier.testTag("settings.search.result.${entry.id}"),
                        onClick = { onFeature?.accept(index) },
                    )
                }
            }
        }

        private data class FeatureSearch(val id: String, val title: String, val path: String, val terms: String)
    }

    class Toggle(
        context: Context,
        private val title: String,
        summary: String?,
        initialChecked: Boolean,
        initialEnabled: Boolean,
        private val onChange: Consumer<Boolean>,
    ) {
        private var checkedState by mutableStateOf(initialChecked)
        private var summaryState by mutableStateOf(summary)
        private var enabledState by mutableStateOf(initialEnabled)
        private var longClick by mutableStateOf<View.OnLongClickListener?>(null)
        val view = rowView(context, "settings.toggle.$title")

        init {
            view.setContent {
                HostSettingsTheme {
                    SwitchPreference(
                        title = title,
                        summary = summaryState?.takeIf { it.isNotEmpty() },
                        checked = checkedState,
                        enabled = enabledState,
                        onCheckedChange = { setChecked(it) },
                        modifier = Modifier.testTag("settings.toggle.$title")
                            .semantics {
                                if (!enabledState) disabled()
                                if (enabledState && longClick != null) {
                                    onLongClick("选择长按操作") { longClick?.onLongClick(view) ?: false }
                                }
                            }
                            .pointerInput(enabledState, longClick) {
                            if (enabledState && longClick != null) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val press = awaitLongPressOrCancellation(down.id)
                                    if (press != null) {
                                        press.consume()
                                        longClick?.onLongClick(view)
                                        do {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            event.changes.forEach { it.consume() }
                                        } while (event.changes.any { it.pressed })
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }

        fun setChecked(value: Boolean) {
            if (checkedState == value) return
            checkedState = value
            onChange.accept(value)
        }
        fun setEnabled(value: Boolean) { enabledState = value; view.isEnabled = value }
        fun setSummary(value: String?) { summaryState = value }
        fun setOnLongClickListener(listener: View.OnLongClickListener) { longClick = listener }
    }

    class Option(
        context: Context,
        private val title: String,
        private val summary: String?,
        initialValue: String?,
        initialEnabled: Boolean,
        private val onClick: Runnable?,
    ) {
        private var currentValue by mutableStateOf(initialValue)
        private var enabledState by mutableStateOf(initialEnabled)
        private var swatchColor by mutableStateOf<Int?>(null)
        val view = rowView(context, "settings.option.$title")

        init {
            view.setContent {
                HostSettingsTheme {
                    BasicComponent(
                        title = title,
                        summary = summary?.takeIf { it.isNotEmpty() },
                        enabled = enabledState,
                        modifier = Modifier.testTag("settings.option.$title")
                            .semantics { if (!enabledState) disabled() },
                        onClick = onClick?.let { action -> { if (enabledState) action.run() } },
                        bottomAction = {
                            currentValue?.let { current ->
                                Text(
                                    current.ifEmpty { "未设置" },
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    fontSize = 13.sp,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        },
                        endActions = {
                            swatchColor?.let { Box(Modifier.padding(end = 8.dp).size(24.dp).background(Color(it), CircleShape)) }
                            if (onClick != null) Icon(MiuixIcons.Basic.ArrowRight, contentDescription = null,
                                modifier = Modifier.testTag("settings.option.arrow.$title"))
                        },
                    )
                }
            }
        }

        fun setValue(text: String) { currentValue = text }
        fun setEnabled(value: Boolean) { enabledState = value; view.isEnabled = value }
        fun setColor(value: Int) { swatchColor = value }
    }

    private fun rowView(context: Context, tag: String) = ComposeView(context).apply {
        this.tag = tag
        id = View.generateViewId()
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }
}

@Composable
internal fun HostSettingsTheme(content: @Composable () -> Unit) {
    // Read configuration so existing module compositions also update on system night changes.
    LocalConfiguration.current
    val colors = if (ModuleTheme.isDark(LocalContext.current)) darkColorScheme() else lightColorScheme()
    MiuixTheme(colors = colors) {
        // Miuix Text/Icon inherit this separately from the color scheme. Supply it for headers
        // and editor content rendered outside Miuix Surface/Scaffold containers.
        CompositionLocalProvider(LocalContentColor provides colors.onBackground, content = content)
    }
}

@Composable
internal fun hostThemeColor(lightColor: Int): Color {
    LocalConfiguration.current
    return Color(ModuleTheme.color(LocalContext.current, lightColor))
}
