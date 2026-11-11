package com.douai.module.ui.miuix

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import com.douai.module.config.ComponentHideSettings
import com.douai.module.config.ComponentHideSettings.Component
import com.douai.module.config.CustomViewLocator
import com.douai.module.config.FeatureSettings
import com.douai.module.config.SettingsStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h720dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComponentHideDependencyMiuixTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val store = MemoryStore()
    private var page: MiuixComponentHidePage? = null
    private var pageWindow: Dialog? = null

    @After fun closePage() { compose.runOnUiThread { page?.dismiss() } }

    @Test fun masterUniformAndPauseOnlyShowTheirEffectiveControlsAndKeepPresets() {
        store.putBoolean(ComponentHideSettings.ENABLED, false)
        store.putBoolean(ComponentHideSettings.UNIFORM_ENABLED, true)
        store.putString(ComponentHideSettings.UNIFORM_ALPHA, "64")
        store.putString(Component.LIKE.key, "81")
        store.putBoolean(ComponentHideSettings.SHOW_ON_PAUSE, false)
        store.putBoolean(ComponentHideSettings.OPEN_COMMENTS_ON_SHOW, true)
        val before = store.values.toMap()
        showPage()
        compose.onNodeWithTag(ComponentHideSettings.ENABLED).assertIsDisplayed()
        compose.onNodeWithTag(ComponentHideSettings.UNIFORM_ENABLED).assertDoesNotExist()
        compose.onNodeWithTag(ComponentHideSettings.UNIFORM_ALPHA).assertDoesNotExist()
        compose.onNodeWithTag(Component.LIKE.key).assertDoesNotExist()
        compose.runOnIdle { assertEquals(before, store.values) }

        compose.onNodeWithTag(ComponentHideSettings.ENABLED).performClick()
        scrollTo(ComponentHideSettings.UNIFORM_ALPHA)
        compose.onNodeWithTag(ComponentHideSettings.UNIFORM_ALPHA).assertIsDisplayed()
        compose.onNodeWithTag(Component.LIKE.key).assertDoesNotExist()
        scrollTo(ComponentHideSettings.SHOW_ON_PAUSE)
        compose.onNodeWithTag(ComponentHideSettings.OPEN_COMMENTS_ON_SHOW).assertDoesNotExist()
        compose.onNodeWithTag(ComponentHideSettings.SHOW_ON_PAUSE).performClick()
        scrollTo(ComponentHideSettings.OPEN_COMMENTS_ON_SHOW)
        compose.onNodeWithText("暂停显示时打开评论").assertIsDisplayed()

        scrollTo(ComponentHideSettings.UNIFORM_ENABLED)
        compose.onNodeWithTag(ComponentHideSettings.UNIFORM_ENABLED).performClick()
        compose.onNodeWithTag(ComponentHideSettings.UNIFORM_ALPHA).assertDoesNotExist()
        scrollTo(Component.LIKE.key)
        compose.onNodeWithTag(Component.LIKE.key).assertIsDisplayed()
        scrollTo(ComponentHideSettings.ENABLED)
        compose.onNodeWithTag(ComponentHideSettings.ENABLED).performClick()
        compose.onNodeWithTag(Component.LIKE.key).assertDoesNotExist()
        compose.onNodeWithTag(ComponentHideSettings.SHOW_ON_PAUSE).assertDoesNotExist()
        compose.onNodeWithTag(ComponentHideSettings.ENABLED).performClick()
        scrollTo(Component.LIKE.key)
        compose.onNodeWithTag(Component.LIKE.key).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("81", store.getString(Component.LIKE.key, ""))
            assertEquals("64", store.getString(ComponentHideSettings.UNIFORM_ALPHA, ""))
            assertTrue(store.getBoolean(ComponentHideSettings.OPEN_COMMENTS_ON_SHOW, false))
        }
        scrollTo(ComponentHideSettings.ENABLED)
        savePreview("component-hide-individual")
    }

    @Test fun legacyEffectiveMasterShowsItsIndividualControlsWithoutInitializingStoredState() {
        store.putBoolean("hide_interface", true)
        val before = store.values.toMap()
        showPage()
        compose.onNodeWithTag(ComponentHideSettings.UNIFORM_ALPHA).assertDoesNotExist()
        scrollTo(Component.LIKE.key)
        compose.onNodeWithTag(Component.LIKE.key).assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(ComponentHideSettings.readFrom(store).enabled)
            assertEquals(-1, ComponentHideSettings.readFrom(store).individualAlpha(Component.LIKE))
            assertEquals(before, store.values)
        }
    }

    @Test fun customRulesCanBeViewedAndRemovedWhileExecutionIsOff() {
        store.putBoolean(ComponentHideSettings.ENABLED, false)
        store.putString(Component.LIKE.key, "81")
        lateinit var locator: CustomViewLocator
        compose.runOnUiThread {
            val host = FrameLayout(compose.activity)
            val target = TextView(compose.activity).apply { id = android.R.id.title }
            host.addView(target)
            locator = CustomViewLocator.capture(host, target)!!
            store.putString(CustomViewLocator.SETTINGS_KEY, CustomViewLocator.serialize(listOf(locator)))
        }
        showPage()
        compose.onNodeWithTag(ComponentHideSettings.UNIFORM_ENABLED).assertDoesNotExist()
        val tag = "component.custom.${locator.identityKey()}"
        scrollTo(tag)
        compose.onNodeWithTag("component.custom.alpha.${locator.identityKey()}").assertDoesNotExist()
        compose.onNodeWithTag(tag).performClick()
        compose.onNodeWithText("自定义视图详情").assertIsDisplayed()
        compose.runOnUiThread {
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        }
        savePreview("component-hide-disabled-rules")
        val delete = "component.custom.delete.${locator.identityKey()}"
        compose.onNodeWithTag(delete).performClick()
        compose.onNodeWithTag("component.custom.confirm_cancel").performClick()
        compose.runOnIdle { assertEquals(1, ComponentHideSettings.readFrom(store).customViews.size) }
        compose.onNodeWithTag(delete).performClick()
        compose.onNodeWithTag("component.custom.confirm_delete").performClick()
        compose.onNodeWithText("暂无自定义视图").assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(ComponentHideSettings.readFrom(store).enabled)
            assertEquals("81", store.getString(Component.LIKE.key, ""))
            assertTrue(ComponentHideSettings.readFrom(store).customViews.isEmpty())
        }
    }

    @Test fun tappingSliderEditsExactValueWithoutSavingUntilConfirmation() {
        store.putBoolean(ComponentHideSettings.ENABLED, true)
        store.putString(Component.LIKE.key, "81")
        showPage()
        scrollTo(Component.LIKE.key)
        compose.onNodeWithTag(Component.LIKE.key).performTouchInput { click() }
        compose.onNodeWithTag("component.alpha.input").assertIsDisplayed()
        compose.runOnIdle { assertEquals("81", store.getString(Component.LIKE.key, "")) }
        for (invalid in listOf("", "-3", "256", "1.5", "abc", "2147483648")) {
            compose.onNodeWithTag("component.alpha.input").performTextReplacement(invalid)
            compose.onNodeWithTag("component.alpha.confirm").performClick()
            compose.onNodeWithTag("component.alpha.error").assertIsDisplayed()
            compose.runOnIdle { assertEquals("81", store.getString(Component.LIKE.key, "")) }
        }
        compose.onNodeWithTag("component.alpha.input").performTextReplacement("-1")
        compose.onNodeWithTag("component.alpha.confirm").performClick()
        compose.onNodeWithTag("component.alpha.input").assertDoesNotExist()
        compose.runOnIdle { assertEquals("-1", store.getString(Component.LIKE.key, "")) }
        compose.onNodeWithTag("${Component.LIKE.key}.value").performClick()
        compose.onNodeWithTag("component.alpha.input").performTextReplacement("0")
        compose.onNodeWithTag("component.alpha.cancel").performClick()
        compose.runOnIdle { assertEquals("-1", store.getString(Component.LIKE.key, "")) }
        scrollTo(ComponentHideSettings.UNIFORM_ENABLED)
        compose.onNodeWithTag(ComponentHideSettings.UNIFORM_ENABLED).performClick()
        scrollTo(ComponentHideSettings.UNIFORM_ALPHA)
        compose.onNodeWithTag("${ComponentHideSettings.UNIFORM_ALPHA}.value").performClick()
        compose.onNodeWithTag("component.alpha.input").performTextReplacement("255")
        compose.onNodeWithTag("component.alpha.confirm").performClick()
        compose.runOnIdle {
            assertTrue(ComponentHideSettings.readFrom(store).uniformEnabled)
            assertEquals(255, ComponentHideSettings.readFrom(store).uniformAlpha)
        }
    }

    @Test fun customRulesHaveIndependentEditableSlidersAndKeepRuleDetails() {
        store.putBoolean(ComponentHideSettings.ENABLED, true)
        store.putBoolean(ComponentHideSettings.UNIFORM_ENABLED, true)
        lateinit var locator: CustomViewLocator
        compose.runOnUiThread {
            val host = FrameLayout(compose.activity)
            val target = TextView(compose.activity).apply { id = android.R.id.title }
            host.addView(target)
            locator = CustomViewLocator.capture(host, target)!!
            store.putString(CustomViewLocator.SETTINGS_KEY, CustomViewLocator.serialize(listOf(locator)))
        }
        showPage()
        val slider = "component.custom.alpha.${locator.identityKey()}"
        scrollTo(slider)
        compose.onNodeWithTag(slider).assertIsDisplayed()
        savePreview("component-hide-custom-alpha")
        compose.onNodeWithTag("$slider.value").performClick()
        compose.onNodeWithTag("component.alpha.input").performTextReplacement("128")
        savePreview("component-hide-alpha-input")
        compose.onNodeWithTag("component.alpha.confirm").performClick()
        compose.runOnIdle {
            val encoded = store.getString(CustomViewLocator.SETTINGS_KEY, "")!!
            val item = org.json.JSONObject(encoded).getJSONArray("items").getJSONObject(0)
            assertEquals(128, item.getInt("alpha"))
            assertTrue(ComponentHideSettings.readFrom(store).uniformEnabled)
        }
        compose.onNodeWithTag("component.custom.${locator.identityKey()}").performClick()
        compose.onNodeWithText("自定义视图详情").assertIsDisplayed()
    }

    @Test fun draggingSliderStillAdjustsWithoutOpeningNumberInput() {
        store.putBoolean(ComponentHideSettings.ENABLED, true)
        store.putString(Component.LIKE.key, "81")
        showPage()
        scrollTo(Component.LIKE.key)
        compose.onNodeWithTag(Component.LIKE.key).performTouchInput {
            swipe(Offset(width * 0.3f, height / 2f), Offset(width * 0.8f, height / 2f), 300)
        }
        compose.onNodeWithTag("component.alpha.input").assertDoesNotExist()
        compose.runOnIdle {
            val settings = ComponentHideSettings.readFrom(store)
            assertTrue(settings.individualAlpha(Component.LIKE) > 150)
        }
    }

    private fun showPage() {
        compose.runOnUiThread {
            page = MiuixComponentHidePage(compose.activity, store).also { it.show() }
            pageWindow = ShadowDialog.getLatestDialog()
        }
        compose.waitForIdle()
    }

    private fun savePreview(name: String) {
        compose.waitForIdle()
        val folder = System.getenv("DOUAI_CHAT_SCREENSHOTS") ?: return
        compose.runOnUiThread {
            val view = pageWindow!!.window!!.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val directory = File(folder).apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("component.list").performScrollToNode(hasTestTag(tag))
    }

    private class MemoryStore : SettingsStore {
        val values = mutableMapOf<String, Any>()
        private var features = FeatureSettings.defaults()
        override fun load(): FeatureSettings = features
        override fun save(settings: FeatureSettings) { features = settings }
        override fun getBoolean(key: String, fallback: Boolean): Boolean = values[key] as? Boolean ?: fallback
        override fun getString(key: String, fallback: String?): String? = values[key] as? String ?: fallback
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun putString(key: String, value: String) { values[key] = value }
    }
}
