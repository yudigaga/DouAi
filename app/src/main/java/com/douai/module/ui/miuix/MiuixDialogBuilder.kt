package com.douai.module.ui.miuix

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.database.Cursor
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button as AndroidButton
import android.widget.CompoundButton
import android.widget.ListAdapter
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.EditText
import android.text.InputType
import android.view.Gravity
import com.douai.module.ui.HostDialogBounds
import com.douai.module.ui.ModuleTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.douai.module.ui.miuix.HostSettingsTheme
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * AlertDialog.Builder-compatible entry point for the Miuix-backed settings dialogs.
 *
 * Native form containers host Miuix controls. getButton/getListView expose compatibility proxies
 * for the existing Java validation and selection callbacks.
 */
class MiuixDialogBuilder(context: Context, private val enabled: Boolean) :
    AlertDialog.Builder(context, ModuleTheme.alertTheme(context)) {
    private val sourceContext = context
    private val spec = DialogSpec(context)

    companion object {
        /** Replace a searchable picker's visible rows without replacing its input or dialog. */
        @JvmStatic fun updateSingleChoiceItems(dialog: AlertDialog, labels: Array<out CharSequence>,
                                              checkedItem: Int, listener: DialogInterface.OnClickListener) {
            if (dialog is MiuixAlertDialog) {
                dialog.updateSingleChoiceItems(labels, checkedItem, listener)
                return
            }
            val list = requireNotNull(dialog.listView) { "单选列表尚未创建" }
            require(list.choiceMode == ListView.CHOICE_MODE_SINGLE) { "当前弹窗不是单选列表" }
            list.adapter = ArrayAdapter(dialog.context, android.R.layout.simple_list_item_single_choice,
                labels.map { it.toString() })
            list.clearChoices()
            if (checkedItem in labels.indices) list.setItemChecked(checkedItem, true)
            list.setOnItemClickListener { _, _, position, _ -> listener.onClick(dialog, position) }
        }

        @JvmStatic fun prompt(context: Context, enabled: Boolean, title: String, initial: String,
                              save: java.util.function.Consumer<String>): AlertDialog {
            val input = EditText(ModuleTheme.themedContext(context)).apply {
                tag = "settings.input.$title"
                contentDescription = title
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                    android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION
                gravity = Gravity.TOP or Gravity.START
                textSize = 16f
                minHeight = (360 * resources.displayMetrics.density).toInt()
                isVerticalScrollBarEnabled = true
                setText(initial)
            }
            return MiuixDialogBuilder(context, enabled).setTitle(title).setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存") { _, _ -> save.accept(input.text.toString()) }
                .create()
        }
    }

    override fun create(): AlertDialog {
        val activity = sourceContext.findActivity()
        if (!enabled || activity == null || activity.isFinishing || activity.isDestroyed) {
            return super.create().also { dialog ->
                dialog.window!!.setBackgroundDrawable(GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = 20f * sourceContext.resources.displayMetrics.density
                })
                ModuleTheme.bind(dialog.window!!.decorView)
            }
        }
        return MiuixAlertDialog(activity, spec.copyForDialog())
    }

    override fun show(): AlertDialog = create().also(AlertDialog::show)

    override fun setTitle(titleId: Int): AlertDialog.Builder {
        super.setTitle(titleId)
        spec.title = sourceContext.getText(titleId).toString()
        spec.customTitle = null
        return this
    }

    override fun setTitle(title: CharSequence?): AlertDialog.Builder {
        super.setTitle(title)
        spec.title = title?.toString()
        spec.customTitle = null
        return this
    }

    override fun setCustomTitle(customTitleView: View?): AlertDialog.Builder {
        super.setCustomTitle(customTitleView)
        customTitleView?.let(ModuleTheme::bind)
        spec.customTitle = customTitleView
        return this
    }

    override fun setMessage(messageId: Int): AlertDialog.Builder {
        super.setMessage(messageId)
        spec.message = sourceContext.getText(messageId).toString()
        return this
    }

    override fun setMessage(message: CharSequence?): AlertDialog.Builder {
        super.setMessage(message)
        spec.message = message?.toString()
        return this
    }

    override fun setView(view: View?): AlertDialog.Builder {
        super.setView(view)
        spec.customView = view
        if (enabled && view != null) styleForm(view)
        return this
    }

    override fun setView(layoutResId: Int): AlertDialog.Builder {
        super.setView(layoutResId)
        spec.customView = android.view.LayoutInflater.from(sourceContext).inflate(layoutResId, null)
        if (enabled) spec.customView?.let(::styleForm)
        return this
    }

    private fun styleForm(root: View) {
        val density = sourceContext.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun surface(fill: Int, stroke: Int) = GradientDrawable().apply {
            setColor(fill)
            setCornerRadius(dp(12).toFloat())
            setStroke(dp(1), stroke)
        }
        fun visit(view: View) {
            when (view) {
                is EditText -> {
                    view.setTextColor(Color.rgb(36, 42, 48))
                    view.setHintTextColor(Color.rgb(119, 128, 135))
                    view.background = surface(Color.rgb(248, 249, 250), Color.rgb(220, 225, 229))
                    view.setPadding(dp(12), dp(10), dp(12), dp(10))
                }
                is Spinner -> {
                    view.background = surface(Color.rgb(248, 249, 250), Color.rgb(220, 225, 229))
                    view.setPadding(dp(12), view.paddingTop, dp(10), view.paddingBottom)
                }
                is CompoundButton -> {
                    view.setTextColor(Color.rgb(36, 42, 48))
                    if (view is android.widget.Switch) {
                        val colors = ColorStateList(
                            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked)),
                            intArrayOf(Color.rgb(0, 133, 121), Color.rgb(138, 149, 155)),
                        )
                        view.thumbTintList = colors
                        view.trackTintList = ColorStateList(
                            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked)),
                            intArrayOf(Color.rgb(172, 219, 211), Color.rgb(215, 220, 222)),
                        )
                    } else {
                        view.buttonTintList = ColorStateList.valueOf(Color.rgb(0, 133, 121))
                    }
                }
                is AndroidButton -> {
                    view.isAllCaps = false
                    view.setTextColor(Color.rgb(0, 119, 108))
                    view.background = surface(Color.rgb(231, 245, 242), Color.rgb(201, 229, 223))
                    view.minHeight = dp(44)
                }
                is TextView -> view.setTextColor(Color.rgb(36, 42, 48))
            }
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)
        ModuleTheme.bind(root)
    }

    override fun setPositiveButton(textId: Int, listener: DialogInterface.OnClickListener?): AlertDialog.Builder =
        setPositiveButton(sourceContext.getText(textId), listener)

    override fun setPositiveButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): AlertDialog.Builder {
        super.setPositiveButton(text, listener)
        spec.setButton(DialogInterface.BUTTON_POSITIVE, text, listener)
        return this
    }

    override fun setNegativeButton(textId: Int, listener: DialogInterface.OnClickListener?): AlertDialog.Builder =
        setNegativeButton(sourceContext.getText(textId), listener)

    override fun setNegativeButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): AlertDialog.Builder {
        super.setNegativeButton(text, listener)
        spec.setButton(DialogInterface.BUTTON_NEGATIVE, text, listener)
        return this
    }

    override fun setNeutralButton(textId: Int, listener: DialogInterface.OnClickListener?): AlertDialog.Builder =
        setNeutralButton(sourceContext.getText(textId), listener)

    override fun setNeutralButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): AlertDialog.Builder {
        super.setNeutralButton(text, listener)
        spec.setButton(DialogInterface.BUTTON_NEUTRAL, text, listener)
        return this
    }

    override fun setItems(itemsId: Int, listener: DialogInterface.OnClickListener?): AlertDialog.Builder =
        setItems(sourceContext.resources.getTextArray(itemsId), listener)

    override fun setItems(
        items: Array<out CharSequence>?,
        listener: DialogInterface.OnClickListener?,
    ): AlertDialog.Builder {
        super.setItems(items, listener)
        spec.choice = ChoiceSpec(items.orEmpty().map { it.toString() }, ChoiceMode.ITEMS,
            clickListener = listener)
        return this
    }

    override fun setSingleChoiceItems(
        itemsId: Int,
        checkedItem: Int,
        listener: DialogInterface.OnClickListener?,
    ): AlertDialog.Builder = setSingleChoiceItems(sourceContext.resources.getTextArray(itemsId), checkedItem, listener)

    override fun setSingleChoiceItems(
        items: Array<out CharSequence>?,
        checkedItem: Int,
        listener: DialogInterface.OnClickListener?,
    ): AlertDialog.Builder {
        super.setSingleChoiceItems(items, checkedItem, listener)
        spec.choice = ChoiceSpec(items.orEmpty().map { it.toString() }, ChoiceMode.SINGLE,
            selectedIndex = checkedItem, clickListener = listener)
        return this
    }

    override fun setSingleChoiceItems(
        adapter: ListAdapter?,
        checkedItem: Int,
        listener: DialogInterface.OnClickListener?,
    ): AlertDialog.Builder {
        super.setSingleChoiceItems(adapter, checkedItem, listener)
        spec.choice = ChoiceSpec(adapter.labels(), ChoiceMode.SINGLE,
            selectedIndex = checkedItem, clickListener = listener)
        return this
    }

    override fun setSingleChoiceItems(
        cursor: Cursor?,
        checkedItem: Int,
        labelColumn: String?,
        listener: DialogInterface.OnClickListener?,
    ): AlertDialog.Builder {
        super.setSingleChoiceItems(cursor, checkedItem, labelColumn, listener)
        spec.choice = ChoiceSpec(cursor.labels(labelColumn), ChoiceMode.SINGLE,
            selectedIndex = checkedItem, clickListener = listener)
        return this
    }

    override fun setMultiChoiceItems(
        itemsId: Int,
        checkedItems: BooleanArray?,
        listener: DialogInterface.OnMultiChoiceClickListener?,
    ): AlertDialog.Builder = setMultiChoiceItems(sourceContext.resources.getTextArray(itemsId), checkedItems, listener)

    override fun setMultiChoiceItems(
        items: Array<out CharSequence>?,
        checkedItems: BooleanArray?,
        listener: DialogInterface.OnMultiChoiceClickListener?,
    ): AlertDialog.Builder {
        super.setMultiChoiceItems(items, checkedItems, listener)
        spec.choice = ChoiceSpec(items.orEmpty().map { it.toString() }, ChoiceMode.MULTI,
            checkedItems = checkedItems?.toList().orEmpty(), multiChoiceListener = listener)
        return this
    }

    override fun setMultiChoiceItems(
        cursor: Cursor?,
        isCheckedColumn: String?,
        labelColumn: String?,
        listener: DialogInterface.OnMultiChoiceClickListener?,
    ): AlertDialog.Builder {
        super.setMultiChoiceItems(cursor, isCheckedColumn, labelColumn, listener)
        val labels = cursor.labels(labelColumn)
        val checked = cursor.booleanColumn(isCheckedColumn, labels.size)
        spec.choice = ChoiceSpec(labels, ChoiceMode.MULTI, checkedItems = checked,
            multiChoiceListener = listener)
        return this
    }

    override fun setAdapter(adapter: ListAdapter?, listener: DialogInterface.OnClickListener?): AlertDialog.Builder {
        super.setAdapter(adapter, listener)
        spec.choice = ChoiceSpec(adapter.labels(), ChoiceMode.ITEMS, clickListener = listener)
        return this
    }

    override fun setCursor(
        cursor: Cursor?,
        listener: DialogInterface.OnClickListener?,
        labelColumn: String?,
    ): AlertDialog.Builder {
        super.setCursor(cursor, listener, labelColumn)
        spec.choice = ChoiceSpec(cursor.labels(labelColumn), ChoiceMode.ITEMS, clickListener = listener)
        return this
    }

    override fun setCancelable(cancelable: Boolean): AlertDialog.Builder {
        super.setCancelable(cancelable)
        spec.cancelable = cancelable
        return this
    }

    override fun setOnCancelListener(listener: DialogInterface.OnCancelListener?): AlertDialog.Builder {
        super.setOnCancelListener(listener)
        spec.cancelListener = listener
        return this
    }

    override fun setOnDismissListener(listener: DialogInterface.OnDismissListener?): AlertDialog.Builder {
        super.setOnDismissListener(listener)
        spec.dismissListener = listener
        return this
    }

    override fun setOnKeyListener(listener: DialogInterface.OnKeyListener?): AlertDialog.Builder {
        super.setOnKeyListener(listener)
        spec.keyListener = listener
        return this
    }

    override fun setOnItemSelectedListener(listener: android.widget.AdapterView.OnItemSelectedListener?): AlertDialog.Builder {
        super.setOnItemSelectedListener(listener)
        spec.itemSelectedListener = listener
        return this
    }
}

private class DialogSpec(private val context: Context) {
    var title: String? = null
    var customTitle: View? = null
    var message: String? = null
    var customView: View? = null
    var choice: ChoiceSpec? = null
    var cancelable = true
    var cancelListener: DialogInterface.OnCancelListener? = null
    var dismissListener: DialogInterface.OnDismissListener? = null
    var keyListener: DialogInterface.OnKeyListener? = null
    var itemSelectedListener: android.widget.AdapterView.OnItemSelectedListener? = null
    val buttons = linkedMapOf<Int, ButtonSpec>()

    fun setButton(which: Int, text: CharSequence?, listener: DialogInterface.OnClickListener?) {
        buttons[which] = ButtonSpec(text?.toString().orEmpty(), listener)
    }

    fun copyForDialog() = DialogSnapshot(
        title, customTitle, message, customView, choice?.copyForDialog(), cancelable,
        cancelListener, dismissListener, keyListener, itemSelectedListener,
        buttons.mapValues { (_, button) -> button.copy() }, context,
    )
}

private data class DialogSnapshot(
    val title: String?,
    val customTitle: View?,
    val message: String?,
    val customView: View?,
    val choice: ChoiceSpec?,
    val cancelable: Boolean,
    val cancelListener: DialogInterface.OnCancelListener?,
    val dismissListener: DialogInterface.OnDismissListener?,
    val keyListener: DialogInterface.OnKeyListener?,
    val itemSelectedListener: android.widget.AdapterView.OnItemSelectedListener?,
    val buttons: Map<Int, ButtonSpec>,
    val sourceContext: Context,
)

private data class ButtonSpec(
    var text: String,
    val listener: DialogInterface.OnClickListener?,
)

private enum class ChoiceMode { ITEMS, SINGLE, MULTI }

private data class ChoiceSpec(
    val labels: List<String>,
    val mode: ChoiceMode,
    val selectedIndex: Int = -1,
    val checkedItems: List<Boolean> = emptyList(),
    val clickListener: DialogInterface.OnClickListener? = null,
    val multiChoiceListener: DialogInterface.OnMultiChoiceClickListener? = null,
) {
    fun copyForDialog() = copy(labels = labels.toList(), checkedItems = checkedItems.toList())
}

private class MiuixAlertDialog(
    private val activity: Activity,
    private val spec: DialogSnapshot,
) : AlertDialog(activity, ModuleTheme.alertTheme(activity)) {
    private var maxContentHeight by mutableStateOf(run {
        val metrics = activity.resources.displayMetrics
        val available = activity.window.decorView.height.takeIf { it > 0 } ?: metrics.heightPixels
        minOf(available / metrics.density * 0.84f, available / metrics.density - 48f)
            .coerceAtLeast(1f).dp
    })
    private var titleState by mutableStateOf(spec.title)
    private var messageState by mutableStateOf(spec.message)
    private var customTitleState by mutableStateOf(spec.customTitle)
    private var customViewState by mutableStateOf(spec.customView)
    private var choiceState by mutableStateOf(spec.choice)
    private var selectedIndex by mutableIntStateOf(spec.choice?.selectedIndex ?: -1)
    private val checkedItems = mutableStateListOf<Boolean>().apply {
        val choice = spec.choice
        if (choice?.mode == ChoiceMode.MULTI) {
            repeat(choice.labels.size) { index -> add(choice.checkedItems.getOrElse(index) { false }) }
        }
    }
    private val buttonStates = spec.buttons.mapValues { (which, button) ->
        ActionButtonState(button.text, button.listener, initiallyVisible = true)
    }
    private val buttons = buttonStates.mapValues { (which, state) ->
        CompatButton(activity, state) { invokeButton(which, state) }
    }
    private val choiceList = CompatChoiceListView(activity)
    private var composeEnvironment: HostComposeEnvironment? = null
    private var composeView: ComposeView? = null

    init {
        setCancelable(spec.cancelable)
        setCanceledOnTouchOutside(spec.cancelable)
        setOnCancelListener(spec.cancelListener)
        setOnDismissListener(spec.dismissListener)
        setOwnerActivity(activity)
        spec.choice?.let { choice ->
            choiceList.choiceMode = when (choice.mode) {
                ChoiceMode.SINGLE -> ListView.CHOICE_MODE_SINGLE
                ChoiceMode.MULTI -> ListView.CHOICE_MODE_MULTIPLE
                ChoiceMode.ITEMS -> ListView.CHOICE_MODE_NONE
            }
            choiceList.adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, choice.labels)
            choiceList.setOnItemSelectedListener(spec.itemSelectedListener)
            choiceList.onCompatItemClick = ::activateChoice
            if (choice.mode == ChoiceMode.SINGLE && choice.selectedIndex in choice.labels.indices) {
                choiceList.setItemChecked(choice.selectedIndex, true)
            }
            if (choice.mode == ChoiceMode.MULTI) {
                checkedItems.forEachIndexed { index, checked -> choiceList.setItemChecked(index, checked) }
            }
        }
    }

    override fun getButton(whichButton: Int): AndroidButton? = buttons[whichButton]

    override fun getListView(): ListView? = if (choiceState == null) null else choiceList

    fun updateSingleChoiceItems(labels: Array<out CharSequence>, checkedItem: Int,
                                listener: DialogInterface.OnClickListener) {
        require(choiceState?.mode == ChoiceMode.SINGLE) { "当前弹窗不是单选列表" }
        val next = ChoiceSpec(labels.map { it.toString() }, ChoiceMode.SINGLE,
            selectedIndex = if (checkedItem in labels.indices) checkedItem else -1, clickListener = listener)
        choiceState = next
        selectedIndex = next.selectedIndex
        checkedItems.clear()
        choiceList.adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, next.labels)
        choiceList.clearChoices()
        if (selectedIndex >= 0) choiceList.setItemChecked(selectedIndex, true)
    }

    override fun setTitle(title: CharSequence?) {
        super.setTitle(title)
        titleState = title?.toString()
        customTitleState = null
    }

    override fun setTitle(titleId: Int) {
        setTitle(activity.getText(titleId))
    }

    override fun setMessage(message: CharSequence?) {
        super.setMessage(message)
        messageState = message?.toString()
    }

    override fun setView(view: View?) {
        super.setView(view)
        view?.let(ModuleTheme::bind)
        customViewState = view
    }

    override fun setView(view: View?, viewSpacingLeft: Int, viewSpacingTop: Int, viewSpacingRight: Int, viewSpacingBottom: Int) {
        super.setView(view, viewSpacingLeft, viewSpacingTop, viewSpacingRight, viewSpacingBottom)
        view?.let(ModuleTheme::bind)
        customViewState = view
    }

    override fun setCustomTitle(customTitleView: View?) {
        super.setCustomTitle(customTitleView)
        customTitleView?.let(ModuleTheme::bind)
        customTitleState = customTitleView
        titleState = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureComposeContent()
    }

    override fun onStart() {
        super.onStart()
        ensureComposeContent()
        composeEnvironment?.bindBackNavigation(this)
        setOnKeyListener { dialog, keyCode, event ->
            if (spec.keyListener?.onKey(dialog, keyCode, event) == true) return@setOnKeyListener true
            if (keyCode != KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                composeEnvironment?.onBackPressedDispatcher?.onBackPressed() ?: dismiss()
            }
            true
        }
        configureWindow()
        ModuleTheme.bind(window!!.decorView)
    }

    override fun onStop() {
        val environment = composeEnvironment
        composeEnvironment = null
        composeView = null
        environment?.close()
        super.onStop()
    }

    private fun ensureComposeContent() {
        if (composeEnvironment != null) return
        val environment = HostComposeEnvironment(activity, Runnable(::dismiss))
        val view = ComposeView(environment.context()).apply {
            tag = "miuix.dialog.root"
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                HostSettingsTheme {
                    DialogContent()
                }
            }
        }
        environment.attach(view)
        composeEnvironment = environment
        composeView = view
        setContentView(view)
    }

    private fun configureWindow() {
        val window = window ?: return
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply { dimAmount = 0.42f }
        HostDialogBounds.install(activity, this, true) { pixels ->
            maxContentHeight = (pixels / activity.resources.displayMetrics.density).dp
        }
    }

    private fun invokeButton(which: Int, state: ActionButtonState) {
        if (!isShowing || state.visibility != View.VISIBLE || !state.enabled) return
        val override = state.overrideListener
        if (override != null) {
            override.onClick(buttons.getValue(which))
            return
        }
        state.builderListener?.onClick(this, which)
        dismiss()
    }

    private fun activateChoice(index: Int) {
        val choice = choiceState ?: return
        if (index !in choice.labels.indices) return
        when (choice.mode) {
            ChoiceMode.ITEMS -> {
                choice.clickListener?.onClick(this, index)
                dismiss()
            }
            ChoiceMode.SINGLE -> {
                selectedIndex = index
                choiceList.setItemChecked(index, true)
                choice.clickListener?.onClick(this, index)
            }
            ChoiceMode.MULTI -> {
                val next = !checkedItems[index]
                checkedItems[index] = next
                choiceList.setItemChecked(index, next)
                choice.multiChoiceListener?.onClick(this, index, next)
            }
        }
    }

    @Composable
    private fun DialogContent() {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = maxContentHeight)
                .background(MiuixTheme.colorScheme.background, RoundedCornerShape(20.dp)),
        ) {
            val customHeader = customTitleState
            if (customHeader != null) {
                AndroidView(
                    factory = { customHeader },
                    modifier = Modifier.fillMaxWidth().testTag("miuix.dialog.customTitle"),
                )
            } else {
                titleState?.takeIf { it.isNotBlank() }?.let { title ->
                    Text(
                        title,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 12.dp)
                            .testTag("miuix.dialog.title"),
                    )
                }
            }
            messageState?.takeIf { it.isNotBlank() }?.let { message ->
                Text(
                    message,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
                        .testTag("miuix.dialog.message"),
                )
            }
            val choice = choiceState
            if (choice != null) {
                LazyColumn(
                    Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(max = 452.dp)
                        .testTag("miuix.dialog.choices"),
                ) {
                    itemsIndexed(choice.labels) { index, label ->
                        BasicComponent(
                            title = label,
                            modifier = Modifier.fillMaxWidth().testTag("miuix.dialog.choice.$index"),
                            onClick = { if (choice === choiceState) activateChoice(index) },
                            endActions = {
                                when (choice.mode) {
                                    ChoiceMode.ITEMS -> Unit
                                    ChoiceMode.SINGLE -> RadioButton(
                                        selected = selectedIndex == index,
                                        onClick = null,
                                        modifier = Modifier.padding(end = 18.dp),
                                    )
                                    ChoiceMode.MULTI -> Checkbox(
                                        state = if (checkedItems.getOrElse(index) { false }) ToggleableState.On else ToggleableState.Off,
                                        onClick = null,
                                        modifier = Modifier.padding(end = 18.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            } else {
                customViewState?.let { form ->
                    AndroidView(
                        factory = { form },
                        modifier = Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(max = 460.dp)
                            .clipToBounds()
                            .testTag("miuix.dialog.customView"),
                    )
                }
            }
            DialogActions()
        }
    }

    @Composable
    private fun DialogActions() {
        val visible = listOf(DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL,
            DialogInterface.BUTTON_POSITIVE).mapNotNull { which ->
            val state = buttonStates[which]?.takeIf { it.visibility == View.VISIBLE } ?: return@mapNotNull null
            which to state
        }
        if (visible.isEmpty()) return
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = androidx.compose.ui.Alignment.End),
        ) {
            visible.forEach { (which, state) ->
                val click = { invokeButton(which, state) }
                if (which == DialogInterface.BUTTON_POSITIVE) {
                    Button(
                        onClick = click,
                        enabled = state.enabled,
                        modifier = Modifier.weight(1f).testTag("miuix.dialog.button.$which"),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) { Text(state.text) }
                } else {
                    TextButton(
                        text = state.text,
                        onClick = click,
                        enabled = state.enabled,
                        modifier = Modifier.weight(1f).testTag("miuix.dialog.button.$which"),
                    )
                }
            }
        }
    }
}

private class ActionButtonState(
    initialText: String,
    val builderListener: DialogInterface.OnClickListener?,
    initiallyVisible: Boolean,
) {
    var text by mutableStateOf(initialText)
    var enabled by mutableStateOf(true)
    var visibility by mutableIntStateOf(if (initiallyVisible) View.VISIBLE else View.GONE)
    var overrideListener: View.OnClickListener? by mutableStateOf(null)
}

private class CompatButton(
    context: Context,
    state: ActionButtonState,
    activate: () -> Unit,
) : AndroidButton(context) {
    private var stateRef: ActionButtonState? = null
    private var activateAction: (() -> Unit)? = null

    init {
        stateRef = state
        activateAction = activate
        super.setText(state.text)
        super.setEnabled(state.enabled)
        super.setVisibility(state.visibility)
    }

    override fun setText(text: CharSequence?, type: TextView.BufferType?) {
        super.setText(text, type)
        stateRef?.text = text?.toString().orEmpty()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        stateRef?.enabled = enabled
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        stateRef?.visibility = visibility
    }

    override fun setOnClickListener(listener: View.OnClickListener?) {
        super.setOnClickListener(listener)
        stateRef?.overrideListener = listener
    }

    override fun performClick(): Boolean {
        if (!isEnabled || visibility != View.VISIBLE) return false
        activateAction?.invoke() ?: return false
        return true
    }
}

private class CompatChoiceListView(context: Context) : ListView(context) {
    var onCompatItemClick: ((Int) -> Unit)? = null

    override fun performItemClick(view: View?, position: Int, id: Long): Boolean {
        val click = onCompatItemClick ?: return super.performItemClick(view, position, id)
        click(position)
        return true
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val next = current.baseContext
        if (next === current) return null
        current = next
    }
    return current as? Activity
}

private fun ListAdapter?.labels(): List<String> = this?.let { adapter ->
    (0 until adapter.count).map { index -> adapter.getItem(index)?.toString().orEmpty() }
}.orEmpty()

private fun Cursor?.labels(columnName: String?): List<String> {
    val cursor = this ?: return emptyList()
    if (columnName == null) return emptyList()
    val column = cursor.getColumnIndex(columnName)
    if (column < 0) return emptyList()
    val originalPosition = cursor.position
    return try {
        buildList(cursor.count) {
            for (position in 0 until cursor.count) {
                if (cursor.moveToPosition(position)) add(cursor.getString(column).orEmpty())
            }
        }
    } finally {
        cursor.moveToPosition(originalPosition)
    }
}

private fun Cursor?.booleanColumn(columnName: String?, expectedSize: Int): List<Boolean> {
    val cursor = this ?: return emptyList()
    if (columnName == null) return emptyList()
    val column = cursor.getColumnIndex(columnName)
    if (column < 0) return emptyList()
    val originalPosition = cursor.position
    return try {
        buildList(expectedSize) {
            for (position in 0 until expectedSize) {
                add(cursor.moveToPosition(position) && cursor.getInt(column) != 0)
            }
        }
    } finally {
        cursor.moveToPosition(originalPosition)
    }
}
