package com.douai.module.ui.miuix

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.database.DataSetObserver
import android.graphics.drawable.Drawable
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.SpinnerAdapter
import android.widget.AdapterView
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.douai.module.BuildConfig
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** Keeps Java form values and callbacks while replacing every visible widget with Miuix. */
object MiuixFormControls {
    @JvmStatic
    fun input(context: Context, label: String, initial: String, secret: Boolean): EditText =
        FormInput(context, label, initial, secret)

    @JvmStatic
    fun checkbox(context: Context, label: String, checked: Boolean): CheckBox =
        FormCheckbox(context, label, checked)

    @JvmStatic
    fun selectionCheckbox(context: Context, activate: Runnable): CheckBox = FormSelection(context, activate)

    @JvmStatic
    fun spinner(context: Context): Spinner = FormSpinner(context)

    @JvmStatic
    fun button(context: Context): android.widget.Button = FormButton(context)

    @JvmStatic
    fun viewFor(view: View): View = (view as? FormControl)?.miuixView ?: view
}

private interface FormControl {
    val miuixView: ComposeView
}

private open class ControlState(defaultTag: String) {
    var enabled by mutableStateOf(true)
    var tag by mutableStateOf(defaultTag)
    var view: ComposeView? = null
}

private class InputState(label: String, initial: String, val secret: Boolean) :
    ControlState("settings.input.$label") {
    var label by mutableStateOf(label)
    var value by mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    var inputType by mutableIntStateOf(InputType.TYPE_CLASS_TEXT)
    var imeOptions by mutableIntStateOf(EditorInfo.IME_ACTION_UNSPECIFIED)
    var maxLines by mutableIntStateOf(if (secret) 1 else 4)
    var singleLine by mutableStateOf(secret)
    var error by mutableStateOf<String?>(null)
}

private class FormInput(context: Context, label: String, initial: String, secret: Boolean) :
    EditText(context), FormControl {
    private var state: InputState? = null
    private var applyingComposeValue = false
    override val miuixView by lazy {
        formView(context, this, state!!) { Content() }
    }

    init {
        state = InputState(label, initial, secret)
        contentDescription = label
        setText(initial)
        inputType = InputType.TYPE_CLASS_TEXT or if (secret) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        maxLines = if (secret) 1 else 4
        hideProxy(this)
    }

    override fun setText(text: CharSequence?, type: TextView.BufferType?) {
        if (state != null && getText().toString() == text?.toString().orEmpty()) return
        super.setText(text, type)
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        val state = state ?: return
        if (applyingComposeValue) return
        val actual = text?.toString().orEmpty()
        state.value = TextFieldValue(actual, state.value.selection.clamped(actual.length))
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        val state = state ?: return
        if (!applyingComposeValue && selStart >= 0 && selEnd >= 0) {
            state.value = state.value.copy(selection = TextRange(selStart, selEnd).clamped(state.value.text.length))
        }
    }

    override fun setInputType(type: Int) {
        super.setInputType(type)
        state?.let {
            it.inputType = type
            it.singleLine = type and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT ||
                type and InputType.TYPE_TEXT_FLAG_MULTI_LINE == 0
            it.maxLines = maxLines.coerceAtLeast(1)
        }
    }

    override fun setSingleLine(singleLine: Boolean) {
        super.setSingleLine(singleLine)
        state?.singleLine = singleLine
    }

    override fun setImeOptions(imeOptions: Int) {
        super.setImeOptions(imeOptions)
        state?.imeOptions = imeOptions
    }

    override fun setMaxLines(maxLines: Int) {
        super.setMaxLines(maxLines)
        state?.maxLines = maxLines.coerceAtLeast(1)
    }

    override fun setError(error: CharSequence?, icon: Drawable?) {
        super.setError(error, icon)
        state?.error = error?.toString()
    }

    override fun setContentDescription(contentDescription: CharSequence?) {
        super.setContentDescription(contentDescription)
        state?.label = contentDescription?.toString().orEmpty()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        state?.enabled = enabled
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        state?.view?.visibility = visibility
    }

    override fun setTag(tag: Any?) {
        super.setTag(tag)
        if (tag != null) state?.tag = tag.toString()
    }

    private fun applyValue(next: TextFieldValue) {
        val state = state ?: return
        applyingComposeValue = true
        try {
            state.value = applyNativeTextEdit(this, state.value, next)
            state.error = error?.toString()
        } finally {
            applyingComposeValue = false
        }
    }

    @Composable
    private fun Content() {
        val state = state!!
        val secret = state.secret || state.inputType.isPassword()
        Column(Modifier.fillMaxWidth()) {
            TextField(
                value = state.value,
                onValueChange = ::applyValue,
                label = state.label,
                useLabelAsPlaceholder = true,
                enabled = state.enabled,
                singleLine = state.singleLine,
                maxLines = state.maxLines,
                keyboardOptions = KeyboardOptions(
                    keyboardType = state.inputType.keyboardType(secret),
                    imeAction = if (state.imeOptions and EditorInfo.IME_MASK_ACTION == EditorInfo.IME_ACTION_SEARCH)
                        ImeAction.Search else ImeAction.Default,
                ),
                keyboardActions = KeyboardActions(onSearch = {
                    this@FormInput.onEditorAction(EditorInfo.IME_ACTION_SEARCH)
                }),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().testTag(state.tag),
            )
            state.error?.let {
                Text(it, color = hostThemeColor(0xFFB3261E.toInt()), fontSize = 12.sp,
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp).testTag("${state.tag}.error"))
            }
        }
    }
}

private class SelectionState : ControlState("miuix.form.selection") {
    var checked by mutableStateOf(false)
}

/** The trailing checkbox delegates to the identity row's single selection action. */
@SuppressLint("ViewConstructor") // Created in code with a required row action; never inflated from XML.
private class FormSelection(context: Context, private val activate: Runnable) : CheckBox(context), FormControl {
    private var state: SelectionState? = null
    override val miuixView by lazy {
        formView(context, this, state!!) {
            val state = state!!
            Checkbox(
                state = if (state.checked) ToggleableState.On else ToggleableState.Off,
                onClick = { activate.run() },
                modifier = Modifier.size(44.dp).testTag(state.tag).semantics {
                    toggleableState = if (state.checked) ToggleableState.On else ToggleableState.Off
                    role = Role.Checkbox
                },
            )
        }
    }

    init {
        state = SelectionState()
        hideProxy(this)
    }

    override fun setChecked(checked: Boolean) {
        super.setChecked(checked)
        state?.checked = isChecked
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        state?.view?.visibility = visibility
    }

    override fun setTag(tag: Any?) {
        super.setTag(tag)
        if (tag != null) state?.tag = tag.toString()
    }
}

private class CheckboxState(label: String, checked: Boolean) : ControlState("miuix.form.switch.$label") {
    var label by mutableStateOf(label)
    var checked by mutableStateOf(checked)
}

private class FormCheckbox(context: Context, label: String, checked: Boolean) :
    CheckBox(context), FormControl {
    private var state: CheckboxState? = null
    override val miuixView by lazy {
        formView(context, this, state!!) {
            val state = state!!
            SwitchPreference(
                title = state.label,
                checked = state.checked,
                enabled = state.enabled,
                onCheckedChange = { isChecked = it },
                modifier = Modifier.fillMaxWidth().testTag(state.tag).semantics {
                    toggleableState = if (state.checked) ToggleableState.On else ToggleableState.Off
                    if (!state.enabled) disabled()
                },
            )
        }
    }

    init {
        state = CheckboxState(label, checked)
        text = label
        isChecked = checked
        hideProxy(this)
    }

    override fun setText(text: CharSequence?, type: TextView.BufferType?) {
        super.setText(text, type)
        state?.label = text?.toString().orEmpty()
    }

    override fun setChecked(checked: Boolean) {
        super.setChecked(checked)
        state?.checked = isChecked
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        state?.enabled = enabled
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        state?.view?.visibility = visibility
    }

    override fun setTag(tag: Any?) {
        super.setTag(tag)
        if (tag != null) state?.tag = tag.toString()
    }
}

private class SpinnerState : ControlState("miuix.form.spinner") {
    var labels by mutableStateOf(emptyList<String>())
    var selected by mutableIntStateOf(-1)
    var label by mutableStateOf("选择")
}

private class FormSpinner(context: Context) : Spinner(context), FormControl {
    private var state: SpinnerState? = null
    private var observedAdapter: SpinnerAdapter? = null
    private var chooser: AlertDialog? = null
    private var lastDeliveredPosition = Int.MIN_VALUE

    override fun setOnItemSelectedListener(listener: AdapterView.OnItemSelectedListener?) {
        lastDeliveredPosition = Int.MIN_VALUE
        super.setOnItemSelectedListener(if (listener == null) null else object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {
                lastDeliveredPosition = Int.MIN_VALUE
                listener.onNothingSelected(parent)
            }
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (lastDeliveredPosition == position) return
                lastDeliveredPosition = position
                listener.onItemSelected(parent, view, position, id)
            }
        })
    }
    private val observer = object : DataSetObserver() {
        override fun onChanged() { refreshOptions() }
        override fun onInvalidated() { refreshOptions() }
    }
    override val miuixView by lazy {
        formView(context, this, state!!) {
            val state = state!!
            BasicComponent(
                title = state.labels.getOrNull(state.selected).orEmpty().ifEmpty { state.label },
                enabled = state.enabled,
                modifier = Modifier.fillMaxWidth().testTag(state.tag),
                onClick = { performClick() },
                endActions = {
                    Icon(MiuixIcons.Basic.ArrowRight, contentDescription = null,
                        modifier = Modifier.rotate(90f))
                },
            )
        }
    }

    init {
        state = SpinnerState()
        hideProxy(this)
    }

    override fun setAdapter(adapter: SpinnerAdapter?) {
        super.setAdapter(adapter)
        val state = state ?: return
        observedAdapter?.unregisterDataSetObserver(observer)
        observedAdapter = adapter
        adapter?.registerDataSetObserver(observer)
        refreshOptions()
        state.selected = selectedItemPosition
    }

    private fun refreshOptions() {
        val state = state ?: return
        state.labels = adapter?.let { adapter ->
            (0 until adapter.count).map { adapter.getItem(it)?.toString().orEmpty() }
        }.orEmpty()
        state.selected = selectedItemPosition
    }

    override fun setSelection(position: Int) {
        super.setSelection(position)
        state?.selected = position
    }

    override fun setSelection(position: Int, animate: Boolean) {
        super.setSelection(position, animate)
        state?.selected = position
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        state?.selected = selectedItemPosition
    }

    override fun setContentDescription(contentDescription: CharSequence?) {
        super.setContentDescription(contentDescription)
        state?.label = contentDescription?.toString().orEmpty().ifEmpty { "选择" }
    }

    override fun setPrompt(prompt: CharSequence?) {
        super.setPrompt(prompt)
        if (!prompt.isNullOrEmpty()) state?.label = prompt.toString()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        state?.enabled = enabled
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        state?.view?.visibility = visibility
    }

    override fun setTag(tag: Any?) {
        super.setTag(tag)
        if (tag != null) state?.tag = tag.toString()
    }

    override fun performClick(): Boolean {
        val state = state ?: return false
        if (!isEnabled || state.labels.isEmpty() || context.formActivity() == null) return false
        chooser?.dismiss()
        chooser = MiuixDialogBuilder(context, true).setTitle(state.label)
            .setSingleChoiceItems(state.labels.toTypedArray(), state.selected) { dialog, index ->
                setSelection(index)
                dialog.dismiss()
            }.setNegativeButton("取消", null).create().also { it.show() }
        return true
    }

    override fun onDetachedFromWindow() {
        chooser?.dismiss()
        chooser = null
        super.onDetachedFromWindow()
    }
}

private class ButtonState : ControlState("miuix.form.button") {
    var text by mutableStateOf("")
}

private class FormButton(context: Context) : android.widget.Button(context), FormControl {
    private var state: ButtonState? = null
    override val miuixView by lazy {
        formView(context, this, state!!) {
            val state = state!!
            Button(
                onClick = { performClick() },
                enabled = state.enabled,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(state.tag),
            ) { Text(state.text) }
        }
    }

    init {
        state = ButtonState()
        hideProxy(this)
    }

    override fun setText(text: CharSequence?, type: TextView.BufferType?) {
        super.setText(text, type)
        state?.text = text?.toString().orEmpty()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        state?.enabled = enabled
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        state?.view?.visibility = visibility
    }

    override fun setTag(tag: Any?) {
        super.setTag(tag)
        if (tag != null) state?.tag = tag.toString()
    }

    override fun performClick(): Boolean = isEnabled && visibility == View.VISIBLE && super.performClick()
}

private fun formView(
    context: Context,
    proxy: View,
    state: ControlState,
    content: @Composable () -> Unit,
): ComposeView = ComposeView(context.formComposeContext()).apply {
    (this.context as? HostComposeContext)?.bindConfiguration(this)
    id = View.generateViewId()
    tag = "miuix.form.surface.$id"
    visibility = proxy.visibility
    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    state.view = this
    setContent {
        HostSettingsTheme {
            Box(Modifier.fillMaxWidth()) {
                // The native compatibility object must attach so Java post() and selection listeners work.
                AndroidView(factory = { proxy }, modifier = Modifier.size(0.dp).clipToBounds().clearAndSetSemantics {})
                content()
            }
        }
    }
}

private fun hideProxy(view: View) {
    view.alpha = 0f
    view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    view.isFocusable = false
    view.isFocusableInTouchMode = false
}

internal fun Context.formComposeContext(): Context {
    var wrapped: Context = this
    while (true) {
        if (wrapped is HostComposeContext) return wrapped
        if (wrapped !is ContextWrapper || wrapped.baseContext === wrapped) break
        wrapped = wrapped.baseContext
    }
    if (packageName == BuildConfig.APPLICATION_ID) return this
    val activity = formActivity() ?: error("Miuix form controls require an Activity context")
    return HostComposeContext.create(activity)
}

private fun Context.formActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val next = current.baseContext
        if (next === current) return null
        current = next
    }
    return current as? Activity
}

private fun TextRange.clamped(length: Int) = TextRange(start.coerceIn(0, length), end.coerceIn(0, length))

private fun Int.isPassword(): Boolean = when (this and InputType.TYPE_MASK_CLASS) {
    InputType.TYPE_CLASS_NUMBER -> this and InputType.TYPE_MASK_VARIATION == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    InputType.TYPE_CLASS_TEXT -> this and InputType.TYPE_MASK_VARIATION in setOf(
        InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
    )
    else -> false
}

private fun Int.keyboardType(secret: Boolean): KeyboardType = when (this and InputType.TYPE_MASK_CLASS) {
    InputType.TYPE_CLASS_NUMBER -> when {
        secret -> KeyboardType.NumberPassword
        this and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0 -> KeyboardType.Decimal
        else -> KeyboardType.Number
    }
    InputType.TYPE_CLASS_PHONE -> KeyboardType.Phone
    else -> when {
        secret -> KeyboardType.Password
        this and InputType.TYPE_MASK_VARIATION == InputType.TYPE_TEXT_VARIATION_URI -> KeyboardType.Uri
        this and InputType.TYPE_MASK_VARIATION == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> KeyboardType.Email
        else -> KeyboardType.Text
    }
}
