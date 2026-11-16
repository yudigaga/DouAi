package com.douai.module.ui

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.douai.module.config.HostSettingsStore
import com.douai.module.config.LiquidGlassSettings
import com.douai.module.config.HomeUiCustomizationSettings
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h760dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiquidGlassThemeSettingsMiuixTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var store: HostSettingsStore
    private lateinit var screen: ModuleSettingsScreen

    @Before fun openSettings() {
        compose.runOnUiThread {
            store = HostSettingsStore(compose.activity)
            store.putString(LiquidGlassSettings.STYLE, "original")
            store.putBoolean(LiquidGlassSettings.GRAVITY, true)
            store.putBoolean(HomeUiCustomizationSettings.HIDE_LABELS, false)
            screen = ModuleSettingsScreen(compose.activity, store)
            compose.activity.setContentView(screen.createMiuixView())
        }
        compose.onNodeWithTag("settings.category.1").performScrollTo().performClick()
        compose.waitForIdle()
    }

    @After fun closeSettings() {
        compose.runOnUiThread {
            screen.dismissChildPages()
            screen.close()
            ShadowDialog.getShownDialogs().toList().filter { it.isShowing }.forEach { it.dismiss() }
            compose.activity.setContentView(View(compose.activity))
        }
    }

    @Test fun glassChoiceEnablesGravityAndReturningToOriginalPreservesItsValue() {
        compose.onNodeWithTag("settings.toggle.重力感应").assertIsNotEnabled()
        choose(1)
        compose.onNodeWithTag("settings.toggle.重力感应").assertIsEnabled().performClick()
        compose.runOnIdle { assertFalse(store.getBoolean(LiquidGlassSettings.GRAVITY, true)) }
        choose(0)
        compose.onNodeWithTag("settings.toggle.重力感应").assertIsNotEnabled()
        choose(1)
        compose.onNodeWithTag("settings.toggle.重力感应").assertIsEnabled()
        compose.runOnIdle {
            assertEquals("liquid_glass", store.getString(LiquidGlassSettings.STYLE, ""))
            assertFalse(store.getBoolean(LiquidGlassSettings.GRAVITY, true))
        }
    }

    @Test fun hidingNavigationTextAndIconSettingsTrackTheTheme() {
        compose.onNodeWithTag("settings.toggle.隐藏导航文字").assertIsNotEnabled()
        compose.onNodeWithTag("settings.option.首页图标").assertIsNotEnabled()
        choose(1)
        compose.onNodeWithTag("settings.option.首页图标").assertIsEnabled()
        compose.onNodeWithTag("settings.toggle.隐藏导航文字").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(store.getBoolean(HomeUiCustomizationSettings.HIDE_LABELS, false)) }
        choose(0)
        compose.onNodeWithTag("settings.option.首页图标").assertIsNotEnabled()
        compose.onNodeWithTag("settings.toggle.隐藏导航文字").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(store.getBoolean(HomeUiCustomizationSettings.HIDE_LABELS, false)) }
    }

    private fun choose(index: Int) {
        compose.onNodeWithTag("settings.option.界面主题").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("miuix.dialog.choice.$index").performClick()
        compose.waitForIdle()
    }
}
