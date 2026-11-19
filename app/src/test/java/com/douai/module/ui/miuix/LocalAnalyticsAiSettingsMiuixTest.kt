package com.douai.module.ui.miuix

import android.app.Dialog
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.douai.module.analytics.LocalAnalytics
import com.douai.module.config.AiModelConfig
import com.douai.module.config.AiModelSettings
import com.douai.module.config.AnalyticsAiSettings
import com.douai.module.config.HostSettingsStore
import com.douai.module.ui.ModuleSettingsScreen
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h720dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalAnalyticsAiSettingsMiuixTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var store: HostSettingsStore
    private lateinit var screen: ModuleSettingsScreen
    private lateinit var profiles: String

    @Before fun openHostSettings() {
        lateinit var analyticsContext: android.content.Context
        compose.runOnUiThread { analyticsContext = compose.activity.applicationContext }
        val io = Executors.newSingleThreadExecutor()
        try {
            io.submit {
                LocalAnalytics.get(analyticsContext).setEnabled(false)
                LocalAnalytics.get(analyticsContext).clear()
            }.get(10, TimeUnit.SECONDS)
        } finally { io.shutdownNow() }
        compose.runOnUiThread {
            store = HostSettingsStore(compose.activity)
            AiModelSettings(listOf(profile("first", false), profile("second", true), profile("global", false)),
                "global").save(store)
            profiles = store.getString(AiModelSettings.KEY, "")
            store.putString(AnalyticsAiSettings.MODEL_ID, "")
            store.putString(AnalyticsAiSettings.SECONDARY_PROMPT, "")
            store.putString(AiModelSettings.REPLY_MODEL_ID, "first")
            store.putString(AiModelSettings.COMMENT_MODEL_ID, "second")
            screen = ModuleSettingsScreen(compose.activity, store)
            compose.activity.setContentView(screen.createMiuixView())
        }
    }

    @After fun closeSettings() {
        if (this::screen.isInitialized) compose.runOnUiThread { screen.close() }
    }

    @Test fun hostReportSelectsItsOwnSupplierAndEditsItsOwnSecondaryPrompt() {
        compose.runOnUiThread { screen.openSidebarEntry("analytics") }
        val report = ShadowDialog.getLatestDialog()
        compose.waitUntil(10_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            report.window!!.decorView.findViewWithTag<TextView>("analytics.status")
                ?.text?.toString()?.contains("记录已关闭") == true
        }
        compose.waitForIdle()
        scrollTo(report, "analytics.analysis.model")
        compose.onNodeWithTag("analytics.analysis.model").assertIsDisplayed().performClick()
        compose.onNodeWithText("跟随全局模型").assertIsDisplayed()
        compose.onNodeWithText("second供应商 · second-model · 流式").assertIsDisplayed()
        compose.onNodeWithTag("miuix.dialog.choice.2").performClick()
        compose.runOnIdle {
            assertEquals("second", store.getString(AnalyticsAiSettings.MODEL_ID, ""))
            assertEquals("global", AiModelSettings.read(store).activeId)
            assertEquals(profiles, store.getString(AiModelSettings.KEY, ""))
        }
        scrollTo(report, "analytics.analysis.prompt")
        compose.onNodeWithTag("analytics.analysis.prompt").assertIsDisplayed().performClick()
        compose.onNodeWithTag("settings.input.本地数据分析二级提示词")
            .performTextReplacement("重点比较本周与前期的浏览时段变化。")
        compose.onNodeWithTag("prompt.save").performClick()
        compose.runOnIdle {
            assertEquals("重点比较本周与前期的浏览时段变化。", store.getString(AnalyticsAiSettings.SECONDARY_PROMPT, ""))
            assertEquals("first", store.getString(AiModelSettings.REPLY_MODEL_ID, ""))
            assertEquals("second", store.getString(AiModelSettings.COMMENT_MODEL_ID, ""))
            assertEquals(profiles, store.getString(AiModelSettings.KEY, ""))
        }
        scrollTo(report, "analytics.analysis.prompt.reset")
        compose.onNodeWithTag("analytics.analysis.prompt.reset").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("", store.getString(AnalyticsAiSettings.SECONDARY_PROMPT, "missing")) }
    }

    private fun scrollTo(dialog: Dialog, tag: String) {
        compose.runOnUiThread {
            val decor = dialog.window!!.decorView
            val scroll = decor.findViewWithTag<ScrollView>("analytics.scroll")
            var control = decor.findViewWithTag<View>(tag)
            while (control.width == 0 || control.height == 0) control = control.parent as View
            val bounds = Rect(0, 0, control.width, control.height)
            scroll.offsetDescendantRectToMyCoords(control, bounds)
            scroll.scrollTo(0, (bounds.top - 32 * compose.activity.resources.displayMetrics.density).toInt().coerceAtLeast(0))
        }
        compose.waitForIdle()
    }

    private fun profile(id: String, streaming: Boolean): AiModelConfig =
        AiModelConfig.fromJson(JSONObject().put("id", id).put("name", id + "供应商")
            .put("apiEndpoint", "https://$id.example.test/v1").put("modelName", "$id-model")
            .put("responseMode", if (streaming) AiModelConfig.RESPONSE_STREAM else AiModelConfig.RESPONSE_SYNC))
}
