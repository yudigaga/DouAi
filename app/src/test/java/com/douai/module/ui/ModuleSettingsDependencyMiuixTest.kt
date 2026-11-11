package com.douai.module.ui

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.douai.module.config.FeatureSettings
import com.douai.module.config.HostSettingsStore
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
class ModuleSettingsDependencyMiuixTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var store: HostSettingsStore
    private lateinit var screen: ModuleSettingsScreen

    @Before fun openSettings() {
        compose.runOnUiThread {
            store = HostSettingsStore(compose.activity)
            store.save(FeatureSettings.defaults())
            store.putBoolean("native_auto_comment", false)
            store.putBoolean("native_auto_reply", false)
            store.putBoolean("comment_append_suffix", false)
            store.putString("comment_suffix", "保留后缀")
            store.putBoolean("streak_enabled", false)
            store.putString("streak_content", "保留内容")
            store.putString("streak_content_api", "https://example.test/streak")
            screen = ModuleSettingsScreen(compose.activity, store)
            compose.activity.setContentView(screen.createMiuixView())
        }
    }

    @After fun closeSettings() {
        compose.runOnUiThread {
            if (::screen.isInitialized) { screen.dismissChildPages(); screen.close() }
            ShadowDialog.getShownDialogs().toList().filter { it.isShowing }.forEach { it.dismiss() }
            compose.activity.setContentView(View(compose.activity))
        }
    }

    @Test fun suffixDependsOnItsSwitchButSpeechDownloadsAndHistoryRemainIndependent() {
        category(7)
        assertHidden("settings.option.后缀内容")
        touch("settings.toggle.评论自动添加后缀")
        assertVisible("settings.option.后缀内容")
        touch("settings.toggle.评论自动添加后缀")
        assertHidden("settings.option.后缀内容")
        assertVisible("settings.option.文本转语音设置")
        compose.runOnIdle {
            assertEquals("保留后缀", store.getString("comment_suffix", ""))
            assertFalse(store.getBoolean("native_auto_comment", true))
        }
        closeChildren()
        category(3)
        touch("settings.option.下载任务")
        compose.runOnIdle { assertTrue(currentDialog()!!.isShowing); assertFalse(store.load().downloadEnabled) }
        closeChildren()
        category(5)
        touch("settings.option.发送与记录")
        touch("settings.option.回复历史记录")
        compose.onNodeWithTag("automation.history").assertIsDisplayed()
        compose.runOnIdle { assertFalse(store.getBoolean("native_auto_reply", true)) }
    }

    @Test fun streakApiUsesExplicitTextSaveAndHidesOnlyTheUnusedFixedSource() {
        category(8)
        assertHidden("settings.option.选择用户")
        assertHidden("settings.option.内容Api")
        assertVisible("settings.option.清空续火花记录")
        assertVisible("settings.option.重试结果未知的续火花")
        touch("settings.toggle.自动续火花")
        assertVisible("settings.option.选择用户")
        assertVisible("settings.option.内容Api")
        assertHidden("settings.option.续火花内容")
        touch("settings.option.内容Api")
        compose.onNodeWithTag("settings.input.内容Api").performTextReplacement("")
        dialogButton(AlertDialog.BUTTON_NEGATIVE, "取消")
        assertHidden("settings.option.续火花内容")
        compose.runOnIdle { assertEquals("https://example.test/streak", store.getString("streak_content_api", "")) }
        touch("settings.option.内容Api")
        compose.onNodeWithTag("settings.input.内容Api").performTextReplacement("")
        dialogButton(AlertDialog.BUTTON_POSITIVE, "保存")
        assertVisible("settings.option.续火花内容")
        touch("settings.toggle.自动续火花")
        assertHidden("settings.option.续火花内容")
        assertHidden("settings.option.内容Api")
        assertVisible("settings.option.清空续火花记录")
        compose.runOnIdle {
            assertEquals("保留内容", store.getString("streak_content", ""))
            assertEquals("", store.getString("streak_content_api", ""))
            assertEquals("", store.getString("streak_reset_token", ""))
        }
    }

    private fun category(index: Int) {
        compose.onNodeWithTag("settings.category.$index").performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun closeChildren() { compose.runOnUiThread { screen.dismissChildPages() }; compose.waitForIdle() }
    private fun currentDialog(): Dialog? = ShadowDialog.getShownDialogs().lastOrNull { it.isShowing }
    private fun root(): View = currentDialog()?.window?.decorView ?: compose.activity.window.decorView
    private fun assertHidden(tag: String) = compose.runOnIdle { assertFalse(tag, root().findViewWithTag<View>(tag)?.isShown ?: false) }
    private fun assertVisible(tag: String) = compose.runOnIdle { assertTrue(tag, root().findViewWithTag<View>(tag)?.isShown ?: false) }

    private fun dialogButton(which: Int, label: String) {
        compose.onNode(hasTestTag("miuix.dialog.button.$which") and hasText(label)).assertIsDisplayed().performClick()
        compose.waitForIdle()
    }

    private fun touch(tag: String) {
        compose.waitForIdle()
        compose.runOnUiThread {
            val control = requireNotNull(root().findViewWithTag<View>(tag)) { tag }
            var parent = control.parent
            while (parent is View) {
                if (parent is ScrollView) {
                    val bounds = Rect(0, 0, control.width, control.height)
                    parent.offsetDescendantRectToMyCoords(control, bounds)
                    parent.scrollTo(0, (bounds.top - 8).coerceAtLeast(0))
                }
                parent = parent.parent
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            val control = requireNotNull(root().findViewWithTag<View>(tag)) { tag }
            assertTrue(tag, control.isShown && control.getGlobalVisibleRect(Rect()))
            assertTrue(tag, control.width > 0 && control.height > 0)
            val now = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, control.width / 2f, control.height / 2f, 0)
            val up = MotionEvent.obtain(now, now + 32, MotionEvent.ACTION_UP, control.width / 2f, control.height / 2f, 0)
            try { control.dispatchTouchEvent(down); control.dispatchTouchEvent(up) }
            finally { down.recycle(); up.recycle() }
        }
        compose.waitForIdle()
    }
}
