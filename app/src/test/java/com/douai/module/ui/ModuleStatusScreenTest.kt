package com.douai.module.ui

import android.graphics.Bitmap
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.WindowCompat
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModuleStatusScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var screen: ModuleStatusScreen

    @Before
    fun createScreen() {
        compose.runOnUiThread {
            screen = ModuleStatusScreen(compose.activity)
            compose.activity.setContentView(screen.createView())
        }
    }

    @Test
    fun standaloneScreenKeepsHostSettingsSeparateAndHandlesMissingHost() {
        compose.onNodeWithText("LSPosed 未连接").assertIsDisplayed()
        compose.onNodeWithText("组件隐藏").assertDoesNotExist()
        compose.onNodeWithText("推荐流筛选").assertDoesNotExist()
        compose.onNodeWithText("抖音内功能设置独立保存在抖音应用，本页只显示模块应用状态。")
            .assertDoesNotExist()
        compose.onNodeWithText("打开抖音").performScrollTo().performClick()
        assertEquals("未安装抖音或无法打开应用。", ShadowToast.getTextOfLatestToast())
        savePreview("module-light")
    }

    @Test
    fun liveDiagnosticsAndDisconnectReplacePreviousStatus() {
        compose.runOnUiThread {
            screen.setFrameworkSyncStatus(true, "服务已连接", null)
            screen.setScanStatus(ScanStatus("方法查找完成", "规则已匹配", true, true))
        }
        compose.onNodeWithText("LSPosed 已连接").assertIsDisplayed()
        compose.onNodeWithText("规则已匹配\n请完全退出并重新打开抖音后生效。")
            .assertIsDisplayed()
        compose.runOnUiThread {
            screen.setFrameworkSyncStatus(false, "连接已断开", "服务不可用")
            screen.setScanStatus(ScanStatus("等待抖音进程", "重新启动后读取诊断", false, false))
        }
        compose.onNodeWithText("LSPosed 已连接").assertDoesNotExist()
        compose.onNodeWithText("连接已断开\n服务不可用").assertIsDisplayed()
        compose.onNodeWithText("等待抖音进程").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w640dp-h320dp-land-night-mdpi")
    fun shortLandscapeDarkScreenCanScrollToAction() {
        compose.runOnUiThread {
            screen.setScanStatus(ScanStatus("方法查找完成", "已安装功能。\n".repeat(20), true, false))
        }
        compose.onNodeWithText("打开抖音").performScrollTo().assertIsDisplayed()
        assertWindowAndPageTheme(true)
        savePreview("module-dark-landscape")
    }

    @Test
    fun systemThemeChangesUpdatePageAndSystemBarsTogether() {
        assertWindowAndPageTheme(false)
        changeNightMode(Configuration.UI_MODE_NIGHT_YES)
        assertWindowAndPageTheme(true)
        compose.onNodeWithText("LSPosed 未连接").assertIsDisplayed()
        savePreview("module-dark")
        changeNightMode(Configuration.UI_MODE_NIGHT_NO)
        assertWindowAndPageTheme(false)
    }

    @Suppress("DEPRECATION")
    private fun changeNightMode(mode: Int) {
        compose.runOnUiThread {
            val resources = compose.activity.resources
            val changed = Configuration(resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
            }
            resources.updateConfiguration(changed, resources.displayMetrics)
            compose.activity.window.decorView.dispatchConfigurationChanged(changed)
        }
        compose.waitForIdle()
    }

    private fun assertWindowAndPageTheme(dark: Boolean) {
        val expected = (if (dark) darkColorScheme() else lightColorScheme()).surface.toArgb()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        // Scrolling may place a card's background over the page corner.
        val corner = android.graphics.Color.red(bitmap.getPixel(0, 0))
        assertTrue(if (dark) corner < 128 else corner > 180)
        compose.runOnUiThread {
            val window = compose.activity.window
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            assertEquals(!dark, controller.isAppearanceLightStatusBars)
            assertEquals(!dark, controller.isAppearanceLightNavigationBars)
            assertEquals(expected, (window.decorView.background as ColorDrawable).color)
        }
    }

    private fun savePreview(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
        val first = bitmap.getPixel(0, 0)
        assertTrue((0 until bitmap.height step 4).any { y ->
            (0 until bitmap.width step 4).any { x -> bitmap.getPixel(x, y) != first }
        })
        val folder = System.getenv("DOUAI_CHAT_SCREENSHOTS") ?: return
        val directory = File(folder).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
