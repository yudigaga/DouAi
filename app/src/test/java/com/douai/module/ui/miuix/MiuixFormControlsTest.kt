package com.douai.module.ui.miuix

import android.app.AlertDialog
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-mdpi")
class MiuixFormControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After fun closeChoices() {
        compose.runOnUiThread { ShadowDialog.getLatestDialog()?.dismiss() }
    }

    @Test fun searchImeActionOnTheVisibleFieldReachesItsNativeEditorListenerOnce() {
        lateinit var input: EditText
        val actions = mutableListOf<Int>()
        compose.runOnUiThread {
            input = MiuixFormControls.input(compose.activity, "搜索用户", "", false)
            input.tag = "form.search"
            input.setSingleLine(true)
            input.imeOptions = EditorInfo.IME_ACTION_SEARCH
            input.setOnEditorActionListener { _, action, _ -> actions.add(action); true }
            mount(input)
        }
        compose.onNodeWithTag("form.search").assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Search))
            .performTextReplacement("Alice")
        compose.runOnIdle { assertTrue("Editing a search field must not execute its search", actions.isEmpty()) }
        compose.onNodeWithTag("form.search").performImeAction()
        compose.runOnIdle {
            assertEquals(listOf(EditorInfo.IME_ACTION_SEARCH), actions)
            assertEquals("Alice", input.text.toString())
        }
        compose.runOnUiThread { input.imeOptions = EditorInfo.IME_ACTION_UNSPECIFIED }
        compose.onNodeWithTag("form.search").assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Default))
    }

    @Test fun inputUsesNativeFiltersWatchersErrorsAndPreservesAnUnchangedSelection() {
        lateinit var input: EditText
        lateinit var parent: LinearLayout
        var changed = 0
        var posted = false
        compose.runOnUiThread {
            input = MiuixFormControls.input(compose.activity, "配置名称", "原始", false)
            input.tag = "form.name"
            input.filters = arrayOf(InputFilter.LengthFilter(6))
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed++ }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            parent = mount(input)
        }
        compose.onNodeWithTag("form.name").performTextReplacement("新的配置名称AB")
        compose.runOnIdle {
            assertEquals("新的配置名称", input.text.toString())
            assertTrue(changed > 0)
            assertSame(input, parent.findViewWithTag("form.name"))
            assertTrue(input.isAttachedToWindow)
            assertEquals(0, input.width)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, input.importantForAccessibility)
            input.post { posted = true }
            input.setText("extern")
            input.setSelection(2)
            input.setText("extern")
            input.error = "请输入有效配置"
        }
        compose.onNodeWithTag("form.name").assert(SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("extern")))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange,
                androidx.compose.ui.text.TextRange(2)))
        compose.onNodeWithText("请输入有效配置").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(2, input.selectionStart)
            assertTrue(posted)
        }
    }

    @Test fun secretInputHasPasswordSemanticsAndExternalEditableChangesRefreshTheField() {
        lateinit var input: EditText
        compose.runOnUiThread {
            input = MiuixFormControls.input(compose.activity, "API 密钥", "secret", true)
            input.tag = "form.key"
            mount(input)
        }
        compose.onNodeWithTag("form.key").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.runOnUiThread { input.text.replace(0, input.text.length, "updated") }
        compose.onNodeWithTag("form.key").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.InputText, androidx.compose.ui.text.AnnotatedString("updated")))
    }

    @Test fun visibleLongInputOnlyReplacesTheInsertedOrDeletedCharacter() {
        lateinit var input: EditText
        val initial = "甲".repeat(4096) + "尾"
        val caret = 2048
        val changes = mutableListOf<Triple<Int, Int, Int>>()
        compose.runOnUiThread {
            input = MiuixFormControls.input(compose.activity, "长输入", initial, false)
            input.tag = "form.long.input"
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    changes.add(Triple(start, before, count))
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            mount(input)
        }
        val field = compose.onNodeWithTag("form.long.input")
        field.performTextInputSelection(TextRange(caret))
        compose.runOnIdle { changes.clear() }
        field.performTextInput("中")
        compose.runOnIdle {
            assertEquals(initial.substring(0, caret) + "中" + initial.substring(caret), input.text.toString())
            assertEquals(listOf(Triple(caret, 0, 1)), changes)
            assertEquals(caret + 1, input.selectionStart)
        }
        field.performTextInputSelection(TextRange(caret, caret + 1))
        compose.runOnIdle { changes.clear() }
        field.performTextInput("")
        compose.runOnIdle {
            assertEquals(initial, input.text.toString())
            assertEquals(listOf(Triple(caret, 1, 0)), changes)
            assertEquals(caret, input.selectionStart)
        }
    }

    @Test fun visibleMiddleInsertionHonorsLengthFilterWithoutRemovingUntouchedSuffixOrMovingPastIt() {
        lateinit var input: EditText
        compose.runOnUiThread {
            input = MiuixFormControls.input(compose.activity, "长度限制", "前后", false)
            input.tag = "form.filtered.input"
            input.filters = arrayOf(InputFilter.LengthFilter(6))
            mount(input)
        }
        val field = compose.onNodeWithTag("form.filtered.input")
        field.performTextInputSelection(TextRange(1))
        field.performTextInput("abcdef")
        compose.runOnIdle {
            assertEquals("前abcd后", input.text.toString())
            assertEquals(5, input.selectionStart)
            assertEquals(5, input.selectionEnd)
        }
        field.performTextReplacement("甲乙丙丁戊己")
        field.performTextInputSelection(TextRange(2))
        field.performTextInput("X")
        compose.runOnIdle {
            assertEquals("甲乙丙丁戊己", input.text.toString())
            assertEquals(2, input.selectionStart)
            assertEquals(2, input.selectionEnd)
        }
    }

    @Test fun visibleSelectedEmojiReplacementKeepsTheSurrogatePairInOneNativeEdit() {
        lateinit var input: EditText
        val changes = mutableListOf<Triple<Int, Int, Int>>()
        compose.runOnUiThread {
            input = MiuixFormControls.input(compose.activity, "表情替换", "前😀后", false)
            input.tag = "form.emoji.input"
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    changes.add(Triple(start, before, count))
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            mount(input)
        }
        val field = compose.onNodeWithTag("form.emoji.input")
        field.performTextInputSelection(TextRange(1, 3))
        compose.runOnIdle { changes.clear() }
        field.performTextInput("😁")
        compose.runOnIdle {
            assertEquals("前😁后", input.text.toString())
            assertEquals(listOf(Triple(1, 2, 2)), changes)
            assertEquals(3, input.selectionStart)
        }
    }

    @Test fun textEditingBridgePreservesCompositionRangesAndMapsFilteredSelection() {
        lateinit var input: EditText
        compose.runOnUiThread {
            input = EditText(compose.activity)
            input.setText("前后")
            var value = TextFieldValue("前后", TextRange(1))
            value = applyNativeTextEdit(input, value, TextFieldValue("前ni后", TextRange(3), TextRange(1, 3)))
            assertEquals(TextRange(1, 3), value.composition)
            value = applyNativeTextEdit(input, value, TextFieldValue("前nin后", TextRange(4), TextRange(1, 4)))
            assertEquals("前nin后", input.text.toString())
            assertEquals(TextRange(1, 4), value.composition)
            value = applyNativeTextEdit(input, value, TextFieldValue("前你后", TextRange(2), TextRange(1, 2)))
            assertEquals(TextRange(1, 2), value.composition)
            value = applyNativeTextEdit(input, value, value.copy(composition = null))
            assertEquals(null, value.composition)
            value = applyNativeTextEdit(input, value, TextFieldValue("前你好后", TextRange(3)))
            assertEquals("前你好后", input.text.toString())
            assertEquals(3, input.selectionStart)

            input.setText("前后")
            input.filters = arrayOf(InputFilter.LengthFilter(6))
            value = applyNativeTextEdit(input, TextFieldValue("前后", TextRange(1)),
                TextFieldValue("前abcdef后", TextRange(7), TextRange(1, 7)))
            assertEquals("前abcd后", value.text)
            assertEquals(TextRange(5), value.selection)
            assertEquals(TextRange(1, 5), value.composition)
        }
    }

    @Test fun textEditingBridgeUsesReplacementBufferAndSelectionChosenByNativeWatcher() {
        compose.runOnUiThread {
            val input = EditText(compose.activity)
            input.setText("ab")
            input.setSelection(2)
            var formatting = false
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (!formatting && s != null && s.length > 2) {
                        formatting = true
                        try {
                            input.setText(s.take(2))
                            input.setSelection(2)
                        } finally { formatting = false }
                    }
                }
            })
            val value = applyNativeTextEdit(input, TextFieldValue("ab", TextRange(2)),
                TextFieldValue("abc", TextRange(3), TextRange(2, 3)))
            assertEquals("ab", input.text.toString())
            assertEquals("ab", value.text)
            assertEquals(TextRange(2), value.selection)
            assertEquals(2, input.selectionStart)
            assertEquals(null, value.composition)
        }
    }

    @Test fun textEditingBridgeHonorsWatcherChangesOutsideTheEditedRegion() {
        compose.runOnUiThread {
            val input = EditText(compose.activity)
            input.setText("ab")
            input.setSelection(1)
            var formatting = false
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (!formatting && s != null && s.toString() == "axb") {
                        formatting = true
                        try {
                            s.append('!')
                            input.setSelection(2)
                        } finally { formatting = false }
                    }
                }
            })
            val value = applyNativeTextEdit(input, TextFieldValue("ab", TextRange(1)),
                TextFieldValue("axb", TextRange(2), TextRange(1, 2)))
            assertEquals("axb!", input.text.toString())
            assertEquals("axb!", value.text)
            assertEquals(TextRange(2), value.selection)
            assertEquals(2, input.selectionStart)
            assertEquals(null, value.composition)
        }
    }

    @Test fun switchesAndButtonsShareJavaCallbacksAndDynamicEnabledState() {
        lateinit var check: CheckBox
        lateinit var button: Button
        var checked = false
        var clicked = 0
        compose.runOnUiThread {
            check = MiuixFormControls.checkbox(compose.activity, "限制输出", false)
            check.tag = "form.limit"
            check.setOnCheckedChangeListener { _, value -> checked = value }
            button = MiuixFormControls.button(compose.activity)
            button.tag = "form.fetch"
            button.text = "获取模型列表"
            button.setOnClickListener { clicked++ }
            mount(check, button)
        }
        compose.onNodeWithTag("form.limit").assertIsOff().performClick()
        compose.onNodeWithTag("form.fetch").performClick()
        compose.runOnIdle {
            assertTrue(checked)
            assertTrue(check.isChecked)
            assertEquals(1, clicked)
            button.text = "正在获取"
            button.isEnabled = false
            check.isChecked = false
        }
        compose.onNodeWithText("正在获取").assertIsDisplayed()
        compose.onNodeWithTag("form.fetch").assertIsNotEnabled()
        compose.onNodeWithTag("form.limit").assertIsOff()
        compose.runOnUiThread { check.isChecked = true }
        compose.onNodeWithTag("form.limit").assertIsOn()
    }

    @Test fun spinnerShowsMiuixChoicesAndRetainsNativeSelectionCallbacks() {
        lateinit var spinner: Spinner
        val selections = mutableListOf<Int>()
        compose.runOnUiThread {
            spinner = MiuixFormControls.spinner(compose.activity)
            spinner.tag = "form.provider"
            spinner.contentDescription = "供应商"
            spinner.adapter = ArrayAdapter(compose.activity, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("供应商甲", "供应商乙", "供应商丙"))
            spinner.setSelection(1)
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selections.add(position)
                }
            }
            mount(spinner)
        }
        compose.onNodeWithText("供应商乙").assertIsDisplayed()
        compose.runOnIdle { selections.clear() }
        compose.onNodeWithTag("form.provider").performClick()
        val chooser = ShadowDialog.getLatestDialog() as AlertDialog
        compose.onNodeWithText("供应商").assertIsDisplayed()
        compose.onNodeWithTag("miuix.dialog.choice.2").performClick()
        compose.onNodeWithText("供应商丙").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(2, spinner.selectedItemPosition)
            assertEquals("供应商丙", spinner.selectedItem)
            assertEquals(listOf(2), selections)
            assertTrue(!chooser.isShowing)
        }
        compose.runOnUiThread { spinner.setSelection(0) }
        compose.onNodeWithText("供应商甲").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(2, 0), selections) }
    }

    private fun mount(vararg controls: View): LinearLayout = LinearLayout(compose.activity).apply {
        orientation = LinearLayout.VERTICAL
        controls.forEach { addView(MiuixFormControls.viewFor(it),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
        compose.activity.setContentView(this)
    }
}
