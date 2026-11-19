package com.douai.module.ui

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.view.View
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.douai.module.config.HomeUiCustomizationSettings
import com.douai.module.config.HomeUiCustomizationSettings.IconState
import com.douai.module.config.HostSettingsStore
import com.douai.module.config.LiquidGlassSettings
import com.douai.module.feature.HomeUiCustomization
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.Future

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h760dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeIconSettingsDialogMiuixTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var screen: ModuleSettingsScreen
    private lateinit var store: HostSettingsStore
    private lateinit var metadataRegistry: Any
    private var metadataBefore: Map<String, Any?>? = null

    @Before fun openSettings() {
        metadataRegistry = ReflectionHelpers.getStaticField(HomeUiCustomization::class.java, "NAVIGATION_METADATA")
        synchronized(metadataRegistry) {
            val observed = ReflectionHelpers.getField<MutableMap<String, Any?>>(metadataRegistry, "observed")
            metadataBefore = LinkedHashMap(observed)
            observed.clear()
        }
        compose.runOnUiThread {
            store = HostSettingsStore(compose.activity)
            store.putString(LiquidGlassSettings.STYLE, "liquid_glass")
            store.putString(HomeUiCustomizationSettings.ICONS, "{}")
            store.putString(HomeUiCustomizationSettings.HIDDEN_NAVIGATION, "[]")
            screen = ModuleSettingsScreen(compose.activity, store)
            compose.activity.setContentView(screen.createMiuixView())
        }
        compose.onNodeWithTag("settings.category.1").performScrollTo().performClick()
        compose.waitForIdle()
    }

    @After fun closeSettings() {
        try {
            compose.runOnUiThread {
                if (::screen.isInitialized) {
                    screen.dismissChildPages()
                    screen.close()
                }
                ShadowDialog.getShownDialogs().toList().filter { it.isShowing }.forEach { it.dismiss() }
                compose.activity.setContentView(View(compose.activity))
            }
        } finally {
            metadataBefore?.let { previous ->
                synchronized(metadataRegistry) {
                    val observed = ReflectionHelpers.getField<MutableMap<String, Any?>>(metadataRegistry, "observed")
                    observed.clear()
                    observed.putAll(previous)
                }
            }
        }
    }

    @Test fun miuixIconOptionOpensTheLogicalTargetAndItsTwoStateEditor() {
        compose.onNodeWithTag("settings.option.首页图标").assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("settings.option.点赞").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            val editor = ShadowDialog.getLatestDialog() as AlertDialog
            val root = editor.window!!.decorView
            for (state in arrayOf(IconState.DAY_NORMAL, IconState.DAY_SELECTED)) {
                assertNotNull(root.findViewWithTag<View>("home.icons.state.${state.key}"))
                assertNotNull(root.findViewWithTag<ImageView>("home.icons.preview.${state.key}"))
            }
            assertNull(root.findViewWithTag<View>("home.icons.state.night_normal"))
            assertNull(root.findViewWithTag<View>("home.icons.state.night_selected"))
            assertNotNull(root.findViewWithTag<View>("home.icons.builtin.all"))
        }
    }

    @Test fun miuixDefaultBuiltInSaveAppliesTwoPreviewsWithoutAnySelectionDialog() {
        compose.onNodeWithTag("settings.option.首页图标").assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("settings.option.下导航 · 首页").performClick()
        compose.waitForIdle()
        lateinit var editor: AlertDialog
        compose.runOnIdle {
            editor = ShadowDialog.getLatestDialog() as AlertDialog
            val action = editor.window!!.decorView.findViewWithTag<android.widget.Button>("home.icons.builtin.all")
            assertEquals("使用内置图标并保存", action.text.toString())
            assertTrue(action.isEnabled)
            assertTrue(action.performClick())
        }
        awaitSavedIcons(editor)
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(editor.isShowing)
            for (dialog in ShadowDialog.getShownDialogs().filter { it.isShowing }) {
                val button = (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)
                assertTrue(button == null || button.text.toString() != "确认保存")
                assertNull((dialog as? AlertDialog)?.listView)
            }
            val snapshot = store.getString(HomeUiCustomizationSettings.ICONS, "{}")
            for (state in arrayOf(IconState.DAY_NORMAL, IconState.DAY_SELECTED)) {
                val bytes = HomeUiCustomizationSettings.readIcon(snapshot, "nav_bottom:homepage_home", state)
                assertNotNull(bytes)
                val saved = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                try {
                    val image = editor.window!!.decorView.findViewWithTag<ImageView>("home.icons.preview.${state.key}")
                    val preview = (image.drawable as android.graphics.drawable.BitmapDrawable).bitmap
                    assertTrue(saved.sameAs(preview))
                } finally { saved.recycle() }
            }
            assertNull(HomeUiCustomizationSettings.readIcon(snapshot, "nav_bottom:homepage_home", IconState.NIGHT_NORMAL))
            assertNull(HomeUiCustomizationSettings.readIcon(snapshot, "nav_bottom:homepage_home", IconState.NIGHT_SELECTED))
        }
    }

    @Test fun miuixNavigationCanHideAndShowWithoutChangingItsArtwork() {
        compose.onNodeWithTag("settings.option.首页图标").assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("settings.option.下导航 · 首页").performClick()
        compose.waitForIdle()
        lateinit var editor: AlertDialog
        compose.runOnIdle {
            editor = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(editor.window!!.decorView.findViewWithTag<View>("home.icons.visibility").performClick())
        }
        awaitNavigationHidden(editor, true)
        compose.runOnIdle {
            assertEquals("{}", store.getString(HomeUiCustomizationSettings.ICONS, "{}"))
            val status = editor.window!!.decorView.findViewWithTag<android.widget.TextView>("home.icons.visibility.status")
            assertTrue(status.text.toString().contains("已隐藏"))
            val action = editor.window!!.decorView.findViewWithTag<android.widget.Button>("home.icons.visibility")
            assertEquals("显示此导航项", action.text.toString())
            assertTrue(action.performClick())
        }
        awaitNavigationHidden(editor, false)
        compose.runOnIdle { assertEquals("{}", store.getString(HomeUiCustomizationSettings.ICONS, "{}")) }
    }

    private fun awaitSavedIcons(editor: AlertDialog) = awaitSetting(editor, "Built-in icon save") {
        store.getString(HomeUiCustomizationSettings.ICONS, "{}") != "{}"
    }

    private fun awaitNavigationHidden(editor: AlertDialog, hidden: Boolean) = awaitSetting(editor, "Navigation visibility save") {
        HomeUiCustomizationSettings.readHiddenNavigation(store.getString(HomeUiCustomizationSettings.HIDDEN_NAVIGATION, "[]"))
            .contains("nav_bottom:homepage_home") == hidden
    }

    private fun awaitSetting(editor: AlertDialog, description: String, condition: () -> Boolean) {
        var progress = "not sampled"
        try {
            compose.waitUntil(timeoutMillis = 10_000) {
                // Compose clock advancement does not drain the worker's Android Handler publication.
                ShadowLooper.idleMainLooper()
                var saved = false
                compose.runOnUiThread {
                    saved = condition()
                    val page = ReflectionHelpers.getField<HomeIconSettingsDialog?>(screen, "homeIconSettingsPage")
                    val pending = page?.let { ReflectionHelpers.getField<Any?>(it, "operation") }
                    val future = pending?.let { ReflectionHelpers.getField<Future<*>?>(it, "future") }
                    val status = page?.dialog()?.window?.decorView
                        ?.findViewWithTag<android.widget.TextView>("home.icons.status")?.text?.toString()
                    progress = "pagePresent=${page != null}, editorShowing=${editor.isShowing}, " +
                        "closed=${page?.let { ReflectionHelpers.getField<Boolean>(it, "closed") }}, " +
                        "generation=${page?.let { ReflectionHelpers.getField<Long>(it, "generation") }}, " +
                        "pending=${pending != null}, futureDone=${future?.isDone}, " +
                        "futureCancelled=${future?.isCancelled}, status=$status"
                }
                saved
            }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("$description did not complete: $progress", failure)
        }
    }
}
