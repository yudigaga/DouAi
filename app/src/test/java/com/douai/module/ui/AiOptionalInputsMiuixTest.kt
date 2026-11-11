package com.douai.module.ui

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.douai.module.config.AiModelConfig
import com.douai.module.config.AiModelSettings
import com.douai.module.config.FeatureSettings
import com.douai.module.config.SettingsStore
import com.douai.module.ui.miuix.MiuixFormControls
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h760dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AiOptionalInputsMiuixTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var editor: AiModelEditor? = null
    private val store = MemoryStore()

    @After fun closeEditor() {
        compose.runOnUiThread { editor?.close(); compose.activity.setContentView(View(compose.activity)) }
    }

    @Test fun optionalGroupsHideTheirActualMiuixInputsAndKeepDraftsUntilExplicitModelSave() {
        val original = AiModelConfig.fromJson(JSONObject().put("id", "optional-miuix").put("name", "可选参数配置")
            .put("apiEndpoint", "https://example.test/v1").put("modelName", "fixture-model")
            .put("temperature", 0.7).put("maxTokens", 512).put("topP", 0.9))
        AiModelSettings(listOf(original), original.id).save(store)
        val saved = store.getString(AiModelSettings.KEY, "")
        show(original)
        exerciseDrafts()
        compose.runOnIdle { assertEquals(saved, store.getString(AiModelSettings.KEY, "")) }
        compose.onNodeWithTag("miuix.dialog.button.-2").performClick()
        compose.runOnIdle {
            assertFalse(editor!!.dialog().isShowing)
            assertEquals(saved, store.getString(AiModelSettings.KEY, ""))
        }

        show(original)
        exerciseDrafts()
        savePreview("ai-optional-inputs-shown")
        compose.onNodeWithTag("miuix.dialog.button.-1").assertIsDisplayed().performClick()
        compose.runOnIdle {
            val result = AiModelSettings.read(store).configs.single()
            assertTrue(result.temperatureEnabled); assertTrue(result.maxTokensEnabled); assertTrue(result.topPEnabled)
            assertEquals(1.4, result.temperature, 0.0)
            assertEquals(1234, result.maxTokens)
            assertEquals(0.6, result.topP, 0.0)
            assertEquals(original.id, AiModelSettings.read(store).activeId)
        }
    }

    private fun exerciseDrafts() {
        for ((toggleLabel, title, draft) in listOf(Triple("设置温度", "温度", "1.4"),
            Triple("限制最大输出 Token", "最大输出 Token", "1234"), Triple("设置 Top P", "Top P", "0.6"))) {
            assertFieldHidden(title)
            toggle(toggleLabel)
            revealInput(title)
            compose.onNodeWithTag("settings.input.$title").assertIsDisplayed().performTextReplacement(draft)
            toggle(toggleLabel)
            assertFieldHidden(title)
            toggle(toggleLabel)
            revealInput(title)
            compose.onNodeWithTag("settings.input.$title").assertIsDisplayed().assertTextContains(draft)
        }
    }

    private fun show(config: AiModelConfig) {
        compose.runOnUiThread {
            editor = AiModelEditor(compose.activity, store, config, true, {})
            editor!!.show { dialog, cleanup ->
                dialog.setOwnerActivity(compose.activity)
                dialog.setOnDismissListener { cleanup.run() }
                dialog.show()
            }
        }
        compose.waitForIdle()
    }

    private fun toggle(label: String) {
        compose.runOnUiThread {
            val control = requireNotNull(findCheckbox(editor!!.dialog().window!!.decorView, label)) { label }
            reveal(MiuixFormControls.viewFor(control))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("miuix.form.switch.$label").assertIsDisplayed().performClick()
        compose.waitForIdle()
    }

    private fun revealInput(title: String) {
        compose.runOnUiThread {
            val input = requireNotNull(findInput(editor!!.dialog().window!!.decorView, title)) { title }
            reveal(MiuixFormControls.viewFor(input))
        }
        compose.waitForIdle()
    }

    private fun reveal(control: View) {
        val scroll = editor!!.dialog().window!!.decorView.findViewWithTag<ScrollView>("ai.model.scroll")
        val bounds = Rect(0, 0, control.width, control.height)
        scroll.offsetDescendantRectToMyCoords(control, bounds)
        scroll.scrollTo(0, (bounds.top - 8).coerceAtLeast(0))
    }

    private fun assertFieldHidden(title: String) {
        val tag = "settings.input.$title"
        if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag(tag).assertIsNotDisplayed()
        }
        compose.runOnIdle {
            val label = requireNotNull(findLabel(editor!!.dialog().window!!.decorView, title)) { title }
            assertFalse("$title heading must hide with its visible Miuix field", label.isShown)
        }
    }

    private fun findCheckbox(view: View, label: String): CheckBox? {
        if (view is CheckBox && view.text.toString() == label) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findCheckbox(view.getChildAt(index), label)?.let { return it }
        return null
    }

    private fun findInput(view: View, label: String): EditText? {
        if (view is EditText && view.contentDescription?.toString() == label) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findInput(view.getChildAt(index), label)?.let { return it }
        return null
    }

    private fun findLabel(view: View, label: String): TextView? {
        if (view is TextView && view !is EditText && view !is CheckBox && view.text.toString() == label) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findLabel(view.getChildAt(index), label)?.let { return it }
        return null
    }

    private fun savePreview(name: String) {
        val output = System.getenv("DOUAI_CHAT_SCREENSHOTS") ?: return
        compose.runOnUiThread {
            val view = editor!!.dialog().window!!.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val folder = File(output).apply { mkdirs() }
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private class MemoryStore : SettingsStore {
        val values = mutableMapOf<String, Any>()
        override fun load() = FeatureSettings.defaults()
        override fun save(settings: FeatureSettings) = Unit
        override fun getBoolean(key: String, fallback: Boolean) = values[key] as? Boolean ?: fallback
        override fun getString(key: String, fallback: String) = values[key] as? String ?: fallback
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun putString(key: String, value: String) { values[key] = value }
        override fun isInHost() = true
    }
}
