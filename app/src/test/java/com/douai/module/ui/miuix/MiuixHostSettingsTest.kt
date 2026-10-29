package com.douai.module.ui.miuix

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import com.douai.module.config.HostSettingsStore
import com.douai.module.config.AiModelConfig
import com.douai.module.config.AiModelSettings
import com.douai.module.config.ThirdPartyDownloadSettings
import com.douai.module.config.LocalOptions
import com.douai.module.analytics.LocalAnalytics
import com.douai.module.analytics.LocalPortraitContext
import org.json.JSONObject
import com.douai.module.ui.ModuleSettingsScreen
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MiuixHostSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var store: HostSettingsStore
    private lateinit var screen: ModuleSettingsScreen

    @Before
    fun openSettings() {
        compose.runOnUiThread {
            store = HostSettingsStore(compose.activity)
            screen = ModuleSettingsScreen(compose.activity, store)
            compose.activity.setContentView(screen.createMiuixView())
        }
    }

    @After
    fun closeSettings() {
        compose.runOnUiThread { screen.close() }
    }

    @Test
    @Config(sdk = [28])
    fun playbackUsesSevenMiuixChoicesAndSelectsTheNearestLegacyPresetWithoutAnEditor() {
        compose.runOnUiThread { store.putString("playback_speed", "2.75") }
        compose.onNodeWithTag("settings.category.4").performScrollTo().performClick()
        compose.onNodeWithTag("settings.toggle.仅模块控制倍速").assertIsEnabled()
        compose.runOnIdle { assertFalse(store.getBoolean(LocalOptions.MODULE_ONLY_SPEED, false)) }
        compose.onNodeWithText("3x").assertIsDisplayed()
        compose.onNodeWithTag("settings.option.倍速数值").performClick()
        compose.runOnIdle {
            val choices = ShadowDialog.getLatestDialog() as android.app.AlertDialog
            assertEquals(7, choices.listView.count)
            assertEquals(5, choices.listView.checkedItemPosition)
            assertEquals("4x", choices.listView.getItemAtPosition(6).toString())
            assertNull(choices.window!!.decorView.findViewWithTag<View>("settings.input.倍速数值"))
            assertEquals("2.75", store.getString("playback_speed", ""))
        }
        compose.onNodeWithTag("miuix.dialog.choices").performScrollToIndex(6)
        compose.onNodeWithTag("miuix.dialog.choice.6").performClick()
        compose.runOnIdle { assertEquals("4", store.getString("playback_speed", "")) }
        compose.onNodeWithText("4x").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [28])
    fun moduleOnlySpeedDisablesDependentMiuixControlsAndRestoresTheirRawChoices() {
        compose.runOnUiThread {
            store.putBoolean("auto_speed", true)
            store.putBoolean("block_long_press_speed", false)
            store.putBoolean(LocalOptions.INTERCEPT_NATIVE_LONG_PRESS, true)
        }
        compose.onNodeWithTag("settings.category.4").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(store.getBoolean(LocalOptions.MODULE_ONLY_SPEED, false)) }
        compose.onNodeWithTag("settings.toggle.仅模块控制倍速").performClick()
        compose.onNodeWithTag("settings.toggle.自动倍速播放").assertIsNotEnabled()
        compose.onNodeWithText("仅模块模式下手动切换优先，已保留原设置。").assertExists()
        compose.runOnIdle {
            assertTrue(store.getBoolean(LocalOptions.MODULE_ONLY_SPEED, false))
            assertTrue(store.getBoolean("auto_speed", false))
            assertFalse(store.getBoolean("block_long_press_speed", true))
            assertTrue(store.getBoolean(LocalOptions.INTERCEPT_NATIVE_LONG_PRESS, false))
        }
        compose.runOnUiThread { screen.dismissChildPages() }
        compose.onNodeWithTag("settings.category.1").performScrollTo().performClick()
        compose.onNodeWithTag("settings.toggle.拦截长按倍数启用场景").assertIsNotEnabled()
        compose.onNodeWithTag("settings.toggle.拦截系统长按手势").assertIsNotEnabled()
        compose.runOnUiThread { screen.dismissChildPages() }

        compose.onNodeWithTag("settings.category.4").performScrollTo().performClick()
        compose.onNodeWithTag("settings.toggle.仅模块控制倍速").performClick()
        compose.onNodeWithTag("settings.toggle.自动倍速播放").assertIsEnabled()
        compose.runOnUiThread { screen.dismissChildPages() }
        compose.onNodeWithTag("settings.category.1").performScrollTo().performClick()
        compose.onNodeWithTag("settings.toggle.拦截长按倍数启用场景").assertIsEnabled()
        compose.onNodeWithTag("settings.toggle.拦截系统长按手势").assertIsEnabled()
        compose.runOnIdle {
            assertFalse(store.getBoolean(LocalOptions.MODULE_ONLY_SPEED, true))
            assertTrue(store.getBoolean("auto_speed", false))
            assertFalse(store.getBoolean("block_long_press_speed", true))
            assertTrue(store.getBoolean(LocalOptions.INTERCEPT_NATIVE_LONG_PRESS, false))
        }
    }

    @Test
    @Config(sdk = [28])
    fun commentReplyReceptionUsesTheParentModeAndContentWithoutOpeningSeparateSettings() {
        compose.onNodeWithTag("settings.category.5").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("fixed", store.getString("reply_mode", "fixed"))
            assertFalse(store.getBoolean("native_auto_reply", false))
            assertFalse(store.getBoolean("native_comment_reply", false))
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            findNativeScroll(ShadowDialog.getLatestDialog().window!!.decorView)!!.fullScroll(View.FOCUS_DOWN)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("settings.toggle.接收评论回复").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertTrue(store.getBoolean("native_comment_reply", false))
            assertFalse(store.getBoolean("native_auto_reply", false))
            assertFalse(store.load().automationEnabled)
        }
        compose.onNodeWithText("已暂停：自动回复未开启").assertExists()
        compose.onNodeWithTag("settings.option.评论回复设置").assertDoesNotExist()
        compose.onNodeWithTag("settings.option.每条评论最多回复数").assertDoesNotExist()
        compose.runOnUiThread {
            findNativeScroll(ShadowDialog.getLatestDialog().window!!.decorView)!!.fullScroll(View.FOCUS_UP)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("settings.toggle.开启自动回复").performClick()
        compose.runOnIdle {
            assertTrue(store.getBoolean("native_auto_reply", false))
            assertTrue(store.getBoolean("native_comment_reply", false))
        }
        compose.onNodeWithText("已暂停：自动回复未开启").assertDoesNotExist()
        compose.onNodeWithTag("settings.option.回复模式").performClick()
        compose.onNodeWithTag("miuix.dialog.choice.1").performClick()
        compose.runOnIdle { assertEquals("ai", store.getString("reply_mode", "")) }
        compose.onNodeWithTag("settings.option.回复内容").performClick()
        compose.onNodeWithTag("settings.option.模型配置").assertIsDisplayed()
        compose.onNodeWithTag("settings.option.二级提示词").assertIsDisplayed()
        saveDialogPreview("auto-reply-shared-settings")
    }

    @Test
    @Config(sdk = [28])
    fun replyRecognitionUsesTheAiGateAndRetainsIndependentImageAndVideoChoices() {
        compose.runOnUiThread {
            store.putBoolean("comment_recognize_images", false)
            store.putBoolean("comment_recognize_video", true)
        }
        compose.onNodeWithTag("settings.category.5").performScrollTo().performClick()
        compose.onNodeWithTag("settings.option.素材识别").assertIsNotEnabled()
        compose.onNodeWithTag("settings.option.回复模式").performClick()
        compose.onNodeWithTag("miuix.dialog.choice.1").performClick()
        compose.onNodeWithTag("settings.option.素材识别").assertIsEnabled().performClick()
        compose.onNodeWithTag("settings.toggle.识别视频").performClick()
        compose.onNodeWithTag("settings.toggle.识别图片").performClick()
        compose.runOnIdle {
            assertFalse(store.getBoolean("reply_recognize_images", true))
            assertTrue(store.getBoolean("reply_recognize_video", false))
            assertFalse(store.getBoolean("comment_recognize_images", true))
            assertTrue(store.getBoolean("comment_recognize_video", false))
        }
        compose.runOnUiThread { ShadowDialog.getLatestDialog().dismiss() }
        compose.onNodeWithTag("settings.option.回复模式").performClick()
        compose.onNodeWithTag("miuix.dialog.choice.0").performClick()
        compose.onNodeWithTag("settings.option.素材识别").assertIsNotEnabled()
        compose.onNodeWithTag("settings.option.回复模式").performClick()
        compose.onNodeWithTag("miuix.dialog.choice.1").performClick()
        compose.onNodeWithTag("settings.option.素材识别").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertFalse(store.getBoolean("reply_recognize_images", true))
            assertTrue(store.getBoolean("reply_recognize_video", false))
        }
        saveDialogPreview("auto-reply-recognition")
    }

    @Test
    @Config(sdk = [28])
    fun searchFindsCategoriesAndDownloadSwitchesKeepTheirDependency() {
        for (query in listOf("本地数据分析", "配置备份", "数据备份")) {
            compose.onNodeWithTag("settings.search").performTextReplacement(query)
            compose.onNodeWithText("基本功能").assertDoesNotExist()
            compose.onNodeWithText("扩展功能").assertIsDisplayed()
        }
        compose.onNodeWithTag("settings.search").performTextReplacement("下载")
        compose.onNodeWithText("基本功能").assertDoesNotExist()
        compose.onNodeWithText("下载相关").assertIsDisplayed().performClick()
        val categoryDialog = ShadowDialog.getLatestDialog()
        assertTrue(categoryDialog.window!!.attributes.width > 0)
        assertTrue(categoryDialog.window!!.attributes.height > 0)
        assertTrue(categoryDialog.window!!.attributes.width !=
            android.view.WindowManager.LayoutParams.MATCH_PARENT)
        assertTrue(categoryDialog.window!!.attributes.height !=
            android.view.WindowManager.LayoutParams.MATCH_PARENT)
        compose.onNodeWithTag("miuix.category.page").assertIsDisplayed()
        compose.onNodeWithTag("miuix.category.back").assertIsDisplayed()
        compose.onNodeWithTag("settings.toggle.自定义文件名").performClick()
        compose.runOnIdle {
            assertTrue(store.getBoolean("download_custom_name", false))
            assertTrue(store.load().downloadEnabled)
        }
        saveDialogPreview("host-download")
        compose.runOnUiThread { ShadowDialog.getLatestDialog().dismiss() }
        compose.onNodeWithText("下载相关").assertIsDisplayed()
        compose.onNodeWithTag("settings.search").performTextReplacement("不存在的分类")
        compose.onNodeWithText("没有匹配的设置分类").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [28])
    fun featureSearchUsesTheSameMajorFeaturePagesWithoutChangingSettings() {
        lateinit var saved: Map<String, *>
        compose.runOnIdle { saved = store.snapshot() }
        val cases = listOf(
            listOf("API Key", "ai", "扩展功能 > AI 模型设置", "settings.option.添加模型配置"),
            listOf("minimax", "speech", "扩展功能 > 文本转语音设置", "settings.option.语音来源"),
            listOf("保存目录", "download-directory", "下载相关 > 保存位置", "settings.option.保存位置"),
            listOf("去水印", "download-watermark", "下载相关 > 无水印下载图集或者视频", "settings.toggle.无水印下载图集或者视频"),
            listOf("自动评论", "auto-comment", "自动评论 > 评论内容与作品范围", "settings.toggle.开启自动评论"),
            listOf("私信自动回复", "auto-reply", "自动回复 > 私聊与群聊", "settings.toggle.开启自动回复"),
            listOf("评论回复", "comment-reply", "自动回复 > 接收评论回复", "settings.toggle.接收评论回复"),
            listOf("导入配置", "backup", "扩展功能 > 配置备份", "config.backup.import"),
            listOf("PDF", "analytics", "扩展功能 > 本地数据分析", "analytics.root"),
        )
        cases.forEach { (query, id, path, option) ->
            compose.onNodeWithTag("settings.search").performTextReplacement(query)
            compose.onNodeWithText(path).assertIsDisplayed()
            compose.onNodeWithTag("settings.search.result.$id").performScrollTo().performClick()
            compose.runOnIdle {
                assertNotNull(ShadowDialog.getLatestDialog().window!!.decorView.findViewWithTag<View>(option))
                assertEquals(saved, store.snapshot())
            }
            compose.runOnUiThread { screen.dismissChildPages() }
        }
        compose.onNodeWithTag("settings.search").performTextReplacement("")
        compose.onNodeWithTag("settings.search.result.ai").assertDoesNotExist()
        compose.runOnIdle { assertEquals(saved, store.snapshot()) }
    }

    @Test
    @Config(sdk = [28])
    fun downloadSearchFocusesTheComposeRowAndDismissalClearsItsHighlight() {
        compose.onNodeWithTag("settings.search").performTextReplacement("去水印")
        compose.onNodeWithTag("settings.search.result.download-watermark").performScrollTo().performClick()
        compose.runOnIdle {
            val dialog = ShadowDialog.getLatestDialog()
            val decor = dialog.window!!.decorView
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val row = decor.findViewWithTag<View>("settings.toggle.无水印下载图集或者视频")
            assertEquals("settings.target.download-watermark", row.getTag(com.douai.module.R.id.settings_search_target))
            assertTrue(row.isActivated)
            assertNotNull(row.foreground)
            assertTrue(findNativeScroll(decor)!!.scrollY > 0)
            screen.dismissChildPages()
            assertFalse(row.isActivated)
            assertNull(row.foreground)
            assertFalse(dialog.isShowing)
        }
    }

    @Test
    @Config(sdk = [28], qualifiers = "w320dp-h640dp-notnight-mdpi")
    fun localAnalyticsUsesMiuixControlsAndReturnsToExtensionsInLightMode() {
        verifyLocalAnalyticsMiuixPage("light")
    }

    @Test
    @Config(sdk = [28], qualifiers = "w320dp-h640dp-night-mdpi")
    fun localAnalyticsUsesMiuixControlsAndReturnsToExtensionsInDarkMode() {
        verifyLocalAnalyticsMiuixPage("dark")
    }

    private fun verifyLocalAnalyticsMiuixPage(theme: String) {
        val hostActivity = compose.activity
        val analytics = LocalAnalytics.get(hostActivity)
        runAnalyticsIo {
            analytics.setEnabled(false)
            analytics.clear()
            LocalPortraitContext.clear(hostActivity)
        }
        try {
            compose.onNodeWithTag("settings.search").performTextReplacement("本地数据分析")
            compose.onNodeWithText("扩展功能").assertIsDisplayed().performClick()
            val extensions = ShadowDialog.getLatestDialog()
            compose.onNodeWithTag("settings.option.本地数据分析").assertIsDisplayed().performClick()
            val report = ShadowDialog.getLatestDialog()
            waitForAnalyticsLoad(report, "记录已关闭")
            compose.runOnIdle {
                val decor = report.window!!.decorView
                assertTrue(report.isShowing)
                assertTrue("The extension page stays open beneath its report", extensions.isShowing)
                assertNotNull(decor.findViewWithTag<View>("miuix.dialog.root"))
                assertNull("The report must use the shared dialog header", decor.findViewWithTag<View>("analytics.back"))
                assertNotNull(decor.findViewWithTag<View>("analytics.empty"))
            }
            compose.onAllNodesWithTag("miuix.dialog.title").onLast().assertTextEquals("本地数据分析")
            compose.onNodeWithTag("analytics.recording").assertIsDisplayed().assertIsEnabled().assertIsOff()
            saveDialogPreview("host-local-analytics-$theme")
            compose.onNodeWithTag("analytics.recording").performClick()
            waitForAnalyticsLoad(report, "正在本地记录")
            compose.onNodeWithTag("analytics.recording").assertIsOn()
            assertTrue("The visible Miuix switch updates the local recorder", analytics.isEnabled)

            val populated = runAnalyticsIo {
                val types = listOf("short_video", "image", "live_photo", "long_video")
                for (index in 0 until 12) {
                    val workId = "miuix-ui-synthetic-work-$index"
                    analytics.recordView(workId, types[index % types.size], "synthetic-author-${index % 3}",
                        "测试作者 ${index % 3 + 1}", listOf("测试话题：摄影", "测试话题：${if (index % 2 == 0) "旅行" else "做饭"}"),
                        false, if (index % 4 == 3) 360_000L else 20_000L)
                    if (index % 3 == 0) analytics.recordAction(analytics.generation(), workId,
                        "copy_text", "测试复制文案", "manual", "success")
                }
                analytics.snapshot(7)
            }
            assertEquals(12L, populated.totalViews)
            assertEquals(4L, populated.totalFeatureUses)
            scrollAnalyticsViewIntoView(report, "analytics.refresh")
            compose.onNodeWithTag("analytics.refresh").assertIsDisplayed().performClick()
            waitForAnalyticsLoad(report, "正在本地记录")
            compose.runOnIdle {
                assertNull("Refreshing the real page must replace its empty state", report.window!!.decorView
                    .findViewWithTag<View>("analytics.empty"))
                assertNotNull(report.window!!.decorView.findViewWithTag<View>("analytics.portrait.types.chart"))
                for (section in listOf("types", "topics", "settings")) {
                    assertEquals("Long report details initially stay collapsed", View.GONE,
                        report.window!!.decorView.findViewWithTag<View>("analytics.section.body.$section").visibility)
                }
            }
            expandAnalyticsSection(report, "types")
            scrollAnalyticsViewIntoView(report, "analytics.portrait.types.selection")
            saveDialogPreview("host-local-analytics-content-$theme")
            expandAnalyticsSection(report, "topics")
            scrollAnalyticsViewIntoView(report, "analytics.portrait.topics.selection")
            saveDialogPreview("host-local-analytics-topics-$theme")
            expandAnalyticsSection(report, "settings")
            scrollAnalyticsViewIntoView(report, "analytics.purpose")
            compose.onNodeWithTag("analytics.purpose").assertIsDisplayed().performClick()
            compose.onNodeWithTag("analytics.purpose.input").assertIsDisplayed()
                .performTextReplacement("测试数据：摄影、旅行与做饭灵感")
            saveDialogPreview("host-local-analytics-purpose-$theme")
            compose.onAllNodesWithTag("miuix.dialog.button.${Dialog.BUTTON_POSITIVE}").onLast().performClick()
            waitForAnalyticsLoad(report, "正在本地记录")
            assertEquals("测试数据：摄影、旅行与做饭灵感", LocalPortraitContext.current(hostActivity).purpose)

            scrollAnalyticsViewIntoView(report, "analytics.range.selector")
            compose.onNodeWithTag("analytics.range.selector").assertIsDisplayed().performClick()
            compose.onNodeWithTag("miuix.dialog.choice.2").assertIsDisplayed().performClick()
            waitForAnalyticsLoad(report, "最近 30 天")
            compose.runOnIdle {
                assertTrue(report.window!!.decorView.findViewWithTag<android.widget.TextView>("analytics.status")
                    .text.toString().contains("最近 30 天"))
            }

            compose.onAllNodesWithTag("miuix.dialog.button.${Dialog.BUTTON_NEGATIVE}").onLast().performClick()
            compose.runOnIdle {
                assertFalse(report.isShowing)
                assertTrue(extensions.isShowing)
            }
            compose.onNodeWithTag("settings.option.本地数据分析").assertIsDisplayed().performClick()
            val reopened = ShadowDialog.getLatestDialog()
            waitForAnalyticsLoad(reopened, "正在本地记录")
            compose.onNodeWithTag("analytics.recording").assertIsOn()
            compose.runOnUiThread {
                screen.close()
                assertFalse("Closing settings also closes the active analytics page", reopened.isShowing)
                assertFalse(extensions.isShowing)
            }
        } finally {
            runAnalyticsIo {
                analytics.setEnabled(false)
                analytics.clear()
                LocalPortraitContext.clear(hostActivity)
            }
        }
    }

    private fun waitForAnalyticsLoad(dialog: Dialog, expectedStatus: String) {
        try {
            compose.waitUntil(10_000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                dialog.window!!.decorView.findViewWithTag<android.widget.TextView>("analytics.status")
                    ?.text?.toString()?.contains(expectedStatus) == true
            }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            val decor = dialog.window!!.decorView
            val status = decor.findViewWithTag<android.widget.TextView>("analytics.status")?.text
            throw AssertionError("Expected analytics status '$expectedStatus'; actual='$status'; " +
                "showing=${dialog.isShowing}; sharedRoot=${decor.findViewWithTag<View>("miuix.dialog.root") != null}; " +
                "analyticsRoot=${decor.findViewWithTag<View>("analytics.root") != null}; " +
                "latestIsReport=${ShadowDialog.getLatestDialog() === dialog}", failure)
        }
        compose.waitForIdle()
    }

    private fun <T> runAnalyticsIo(action: () -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        return try {
            executor.submit<T> { action() }.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun expandAnalyticsSection(dialog: Dialog, key: String) {
        val tag = "analytics.section.toggle.$key"
        scrollAnalyticsViewIntoView(dialog, tag)
        compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(View.VISIBLE, dialog.window!!.decorView
                .findViewWithTag<View>("analytics.section.body.$key").visibility)
        }
    }

    private fun scrollAnalyticsViewIntoView(dialog: Dialog, tag: String) {
        compose.runOnUiThread {
            val decor = dialog.window!!.decorView
            val scroll = decor.findViewWithTag<android.widget.ScrollView>("analytics.scroll")
            var control = decor.findViewWithTag<View>(tag)
            while (control.width == 0 || control.height == 0) control = control.parent as View
            val bounds = android.graphics.Rect(0, 0, control.width, control.height)
            scroll.offsetDescendantRectToMyCoords(control, bounds)
            scroll.scrollTo(0, (bounds.top - 32 * compose.activity.resources.displayMetrics.density).toInt().coerceAtLeast(0))
        }
        compose.waitForIdle()
    }

    @Test
    @Config(sdk = [28])
    fun downloadMethodSwitchesMiuixRowsAndThirdPartyInputsPersist() {
        compose.onNodeWithTag("settings.search").performTextReplacement("下载")
        compose.onNodeWithText("下载相关").performClick()
        compose.onNodeWithTag("settings.option.下载方式").performClick()
        compose.onNodeWithTag("miuix.dialog.choice.1").performClick()
        compose.onNodeWithTag("settings.option.文件名模板").assertDoesNotExist()
        compose.onNodeWithTag("settings.option.GET 请求").assertIsDisplayed()
        compose.onNodeWithTag("settings.option.保存位置").assertIsDisplayed()
        compose.onNodeWithTag("settings.option.请求参数").assertExists()
        compose.onNodeWithTag("settings.option.签名方式").assertExists()
        compose.onNodeWithTag("settings.option.签名密钥").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(ThirdPartyDownloadSettings.THIRD_PARTY,
                store.getString(ThirdPartyDownloadSettings.METHOD, ""))
        }
        saveDialogPreview("host-third-party-download")
        compose.onNodeWithTag("settings.option.GET 请求").performClick()
        compose.onNodeWithTag("settings.input.GET 请求")
            .performTextReplacement("https://example.com/parse?url={url}")
        compose.onNodeWithTag("miuix.dialog.button.-1").performClick()
        compose.runOnIdle {
            assertEquals("https://example.com/parse?url={url}",
                store.getString(ThirdPartyDownloadSettings.GET_TEMPLATE, ""))
        }
        compose.onNodeWithTag("settings.option.请求参数").performClick()
        compose.onNodeWithTag("third_party.parameter.name.0").performTextReplacement("link")
        compose.onNodeWithContentDescription("添加参数").performClick()
        compose.onNodeWithTag("third_party.parameter.name.1").performTextReplacement("ID")
        compose.onNodeWithTag("third_party.parameter.value.1").performTextReplacement("123")
        saveDialogPreview("host-third-party-parameters")
        compose.onNodeWithTag("miuix.dialog.button.-1").performClick()
        compose.runOnIdle {
            assertEquals("https://example.com/parse?link={url}&ID=123",
                store.getString(ThirdPartyDownloadSettings.GET_TEMPLATE, ""))
        }
        compose.onNodeWithTag("settings.option.签名方式").performClick()
        compose.onNodeWithTag("miuix.dialog.choice.1").performClick()
        compose.onNodeWithTag("settings.option.签名密钥").assertExists()
        compose.onNodeWithTag("settings.option.签名请求头").assertExists()
        compose.onNodeWithTag("settings.option.下载方式").performClick()
        compose.onNodeWithTag("miuix.dialog.choice.0").performClick()
        compose.onNodeWithTag("settings.option.GET 请求").assertDoesNotExist()
        compose.onNodeWithTag("settings.option.保存位置").assertExists()
        compose.onNodeWithTag("settings.option.文件名模板").assertExists()
    }

    @Test
    @Config(sdk = [28])
    fun filterMasterUpdatesChildEnabledStateAndPersistsChanges() {
        compose.onNodeWithText("视频筛选").performScrollTo().performClick()
        compose.onNodeWithTag("settings.toggle.去广告开关").assertIsNotEnabled()
        compose.onNodeWithTag("settings.toggle.过滤总开关").performClick()
        compose.onNodeWithTag("settings.toggle.去广告开关").assertIsEnabled().performClick()
        compose.onNodeWithTag("settings.toggle.去商品/购物卡片开关").assertIsEnabled().performClick()
        compose.onNodeWithTag("settings.toggle.去热点榜单卡开关").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertTrue(store.load().feedFilterEnabled)
            assertTrue(store.load().hideAds)
            assertTrue(store.getBoolean("hide_product_cards", false))
            assertTrue(store.getBoolean("hide_hotspot_rank_cards", false))
        }
        compose.onNodeWithTag("settings.toggle.过滤总开关").performClick()
        compose.onNodeWithTag("settings.toggle.去广告开关").assertIsNotEnabled()
        saveDialogPreview("host-filter")
    }

    @Test
    @Config(sdk = [28])
    fun editorSaveUpdatesMiuixPreviewAndComponentWindowClosesWithParent() {
        compose.onNodeWithTag("settings.search").performTextReplacement("下载")
        compose.onNodeWithText("下载相关").performClick()
        compose.onNodeWithTag("settings.option.文件名模板").performClick()
        compose.waitForIdle()
        compose.runOnUiThread {
            val editor = ShadowDialog.getLatestDialog() as android.app.AlertDialog
            assertNotNull(editor.window!!.decorView.findViewWithTag<View>("miuix.dialog.root"))
            editor.findViewById<View>(android.R.id.content)
                .findViewWithTag<EditText>("settings.input.文件名模板").setText("#aid#.#ext#")
            editor.getButton(Dialog.BUTTON_POSITIVE).performClick()
        }
        compose.onNodeWithText("#aid#.#ext#").assertIsDisplayed()
        assertEquals("#aid#.#ext#", store.getString("download_name_template", ""))
        compose.runOnUiThread { ShadowDialog.getLatestDialog().dismiss() }
        compose.onNodeWithTag("settings.search").performTextReplacement("组件")
        compose.onNodeWithTag("settings.category.9").performClick()
        compose.onNodeWithText("隐藏总开关").assertIsDisplayed().performClick()
        assertTrue(store.getBoolean("component_hide_enabled", false))
        saveDialogPreview("host-components")
        compose.runOnUiThread {
            val child = ShadowDialog.getLatestDialog()
            screen.close()
            assertFalse(child.isShowing)
        }
    }

    @Test
    @Config(sdk = [28, 35])
    fun composeEditorWindowAllowsImeAndVisibleInputTakesFocus() {
        compose.onNodeWithTag("settings.search").performTextReplacement("下载")
        compose.onNodeWithText("下载相关").performClick()
        compose.onNodeWithTag("settings.option.文件名模板").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            val flags = ShadowDialog.getLatestDialog().window!!.attributes.flags
            assertEquals("An editor dialog must remain focusable", 0,
                flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            assertEquals("Compose editors must be allowed to connect to the IME", 0,
                flags and android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
        compose.onNodeWithTag("settings.input.文件名模板").performClick()
        compose.onNodeWithTag("settings.input.文件名模板").assertIsFocused()
    }

    @Test
    @Config(sdk = [28], qualifiers = "w320dp-h640dp-mdpi")
    fun organizedAutomationPagesRenderAndCommentInputCanTakeFocus() {
        compose.onNodeWithTag("settings.category.5").performClick()
        compose.onNodeWithTag("settings.option.回复内容").assertIsDisplayed()
        compose.onNodeWithTag("settings.option.回复预设内容").assertDoesNotExist()
        saveDialogPreview("reply-main")
        compose.onNodeWithTag("settings.option.私聊范围").performClick()
        val privateSettings = ShadowDialog.getLatestDialog()
        compose.onNodeWithTag("settings.option.消息筛选").performClick()
        compose.onNodeWithTag("miuix.dialog.choice.1").performClick()
        saveDialogPreview("reply-private")
        compose.runOnUiThread { privateSettings.dismiss(); screen.dismissChildPages() }
        compose.onNodeWithTag("settings.category.6").performClick()
        compose.onNodeWithTag("settings.option.评论内容").assertIsDisplayed()
        compose.onNodeWithTag("settings.option.素材识别").assertIsEnabled()
        saveDialogPreview("comment-main")
        compose.onNodeWithTag("settings.option.作品范围").performClick()
        compose.onNodeWithTag("settings.option.作品筛选").performClick()
        compose.onNodeWithTag("miuix.dialog.choice.1").performClick()
        saveDialogPreview("comment-scope")
        compose.onNodeWithTag("settings.option.筛选关键词").performClick()
        compose.onNodeWithTag("settings.input.筛选关键词").performClick().assertIsFocused()
        compose.runOnIdle {
            assertEquals(0, ShadowDialog.getLatestDialog().window!!.attributes.flags and
                android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
        compose.onNodeWithTag("settings.input.筛选关键词").performTextReplacement("茶,咖啡")
        saveDialogPreview("comment-keyword-editor")
    }

    @Test
    @Config(sdk = [28], qualifiers = "w640dp-h320dp-land-mdpi")
    fun organizedCommentPageFitsShortLandscapeWindow() {
        compose.onNodeWithTag("settings.category.6").performScrollTo().performClick()
        compose.waitForIdle()
        compose.runOnUiThread {
            val scroll = findNativeScroll(ShadowDialog.getLatestDialog().window!!.decorView)
            assertNotNull("The category must expose its native scroll container after composition", scroll)
            scroll!!.fullScroll(View.FOCUS_DOWN)
        }
        compose.onNodeWithTag("settings.option.发送与记录").assertIsDisplayed()
        saveDialogPreview("comment-landscape")
    }

    @Test
    fun nestedReplyModeAndTargetIdPagesUseMiuixChoiceAndActionShells() {
        compose.onNodeWithTag("settings.category.5").performClick()
        compose.onNodeWithTag("settings.option.回复模式").performClick()
        val choices = ShadowDialog.getLatestDialog() as android.app.AlertDialog
        compose.runOnIdle {
            assertNotNull(choices.window!!.decorView.findViewWithTag<View>("miuix.dialog.root"))
            choices.listView.performItemClick(null, 1, choices.listView.adapter.getItemId(1))
        }
        compose.runOnIdle { assertEquals("ai", store.getString("reply_mode", "")) }

        compose.onNodeWithTag("settings.option.私聊范围").performClick()
        val privateSettings = ShadowDialog.getLatestDialog()
        compose.onNodeWithTag("settings.option.指定用户").performClick()
        val targets = ShadowDialog.getLatestDialog() as android.app.AlertDialog
        assertNotNull(targets.getButton(Dialog.BUTTON_POSITIVE))
        targets.getButton(Dialog.BUTTON_POSITIVE).performClick()
        compose.runOnIdle { assertEquals("", store.getString("reply_targets", "")) }
        compose.runOnUiThread { privateSettings.dismiss() }
        compose.onNodeWithTag("settings.option.回复模式").performClick()
        compose.onNodeWithTag("miuix.dialog.choice.3").performClick()
        compose.onNodeWithTag("settings.option.回复内容").performClick()
        val pool = ShadowDialog.getLatestDialog() as android.app.AlertDialog
        compose.waitForIdle()
        assertNotNull(pool.window!!.decorView.findViewWithTag<View>("miuix.dialog.root"))
        pool.getButton(Dialog.BUTTON_POSITIVE).performClick()
        saveDialogPreview("host-miuix-deep-reply")
    }

    @Test
    fun scopeRelationAndKeywordFieldPickersPersistMiuixMultiSelections() {
        compose.onNodeWithTag("settings.category.5").performClick()
        compose.onNodeWithTag("settings.option.私聊范围").performClick()
        compose.onNodeWithTag("settings.option.关注关系").performClick()
        compose.onNodeWithTag("miuix.dialog.choice.0").performClick()
        compose.onNodeWithTag("miuix.dialog.choice.2").performClick()
        compose.runOnUiThread {
            (ShadowDialog.getLatestDialog() as android.app.AlertDialog)
                .getButton(Dialog.BUTTON_POSITIVE).performClick()
        }
        compose.runOnIdle { assertEquals("following,strangers", store.getString("reply_scope_relations", "")) }

        compose.onNodeWithTag("settings.option.消息筛选").performClick()
        compose.onNodeWithTag("miuix.dialog.choice.1").performClick()
        compose.onNodeWithTag("settings.option.匹配对象").performClick()
        compose.onNodeWithTag("miuix.dialog.choice.0").performClick()
        compose.onNodeWithTag("miuix.dialog.choice.2").assertDoesNotExist()
        compose.runOnUiThread {
            (ShadowDialog.getLatestDialog() as android.app.AlertDialog)
                .getButton(Dialog.BUTTON_POSITIVE).performClick()
        }
        compose.runOnIdle { assertEquals("author,caption", store.getString("reply_filter_fields", "")) }
    }

    @Test
    @Config(sdk = [28])
    fun modelEditorUsesMiuixControlsAndKeepsSavedCustomEndpointAndPromptDraft() {
        val config = AiModelConfig.fromJson(JSONObject().put("id", "ui-profile")
            .put("name", "自定义代理").put("provider", "openai")
            .put("apiEndpoint", "https://proxy.example.test/v1").put("modelName", "test-model")
            .put("systemPrompt", "原来的记忆"))
        compose.runOnUiThread { AiModelSettings(listOf(config), config.id).save(store) }
        compose.onNodeWithTag("settings.search").performTextReplacement("扩展")
        compose.onNodeWithText("扩展功能").assertIsDisplayed().performClick()
        compose.onNodeWithTag("settings.option.AI 模型设置").performClick()
        compose.onNodeWithTag("settings.option.自定义代理").performClick()
        compose.onNodeWithTag("settings.input.API 基础地址").assertExists()
        saveDialogPreview("host-model-editor")
        compose.runOnUiThread {
            val editor = ShadowDialog.getLatestDialog()
            val endpoint = findNativeInput(editor.window!!.decorView, "API 基础地址")
            assertEquals("https://proxy.example.test/v1", endpoint.text.toString())
            val row = editor.window!!.decorView.findViewWithTag<View>("ai.prompt.primary")
            var parent = row.parent
            while (parent !is android.widget.ScrollView) parent = parent.parent
            parent.scrollTo(0, row.top)
        }
        compose.onNodeWithTag("settings.option.一级提示词").performClick()
        compose.onNodeWithTag("settings.input.一级提示词").performTextReplacement("草稿记忆\n")
        compose.onNodeWithTag("prompt.save").performClick()
        compose.runOnIdle { assertEquals("原来的记忆", AiModelSettings.read(store).selected().systemPrompt) }
        compose.onNodeWithTag("miuix.dialog.button.${Dialog.BUTTON_POSITIVE}").performClick()
        compose.runOnIdle {
            assertEquals("草稿记忆\n", AiModelSettings.read(store).selected().systemPrompt)
            assertEquals("https://proxy.example.test/v1", AiModelSettings.read(store).selected().apiEndpoint)
        }
    }

    @Test
    @Config(sdk = [28], qualifiers = "w296dp-h560dp-mdpi")
    fun aboutAndSearchableHelpRemainReadableOnNarrowScreens() {
        verifyAboutHelp()
    }

    @Test
    @Config(sdk = [28], qualifiers = "w640dp-h360dp-land-mdpi")
    fun aboutAndSearchableHelpRemainReadableInLandscape() {
        verifyAboutHelp()
    }

    private fun verifyAboutHelp() {
        val layout = if (compose.activity.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE) "landscape" else "narrow"
        compose.onNodeWithTag("settings.search").performTextReplacement("关于")
        compose.onNode(hasText("关于") and !hasTestTag("settings.search"))
            .assertIsDisplayed().performClick()
        compose.onNodeWithTag("settings.option.帮助").assertIsDisplayed()
        saveDialogPreview("about-$layout")
        compose.runOnUiThread {
            val page = ShadowDialog.getLatestDialog().window!!.decorView
                .findViewWithTag<android.widget.ScrollView>("about.page")
            val details = page.findViewWithTag<android.widget.TextView>("about.details")
            assertTrue(details.text.toString().contains(com.douai.module.ui.AboutDialog.DISCLAIMER_TEXT))
            page.fullScroll(View.FOCUS_DOWN)
        }
        compose.waitForIdle()
        saveDialogPreview("about-disclaimer-$layout")
        compose.runOnUiThread {
            val page = ShadowDialog.getLatestDialog().window!!.decorView
                .findViewWithTag<android.widget.ScrollView>("about.page")
            assertTrue("About disclaimer must be reachable by scrolling", page.scrollY > 0)
            page.fullScroll(View.FOCUS_UP)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("settings.option.帮助").performClick()
        compose.onNodeWithTag("help.search").performTextReplacement("{voice}")
        val section = com.douai.module.ui.HelpContent.search("{voice}").first()
        compose.onNodeWithTag("settings.option.${section.title}").assertIsDisplayed()
        saveDialogPreview("help-search-$layout")
        compose.onNodeWithTag("settings.option.${section.title}").performClick()
        compose.runOnIdle {
            val body = ShadowDialog.getLatestDialog().window!!.decorView
                .findViewWithTag<android.widget.TextView>("help.body")
            for (part in section.parts) {
                assertTrue(body.text.toString().contains(part.title))
                assertTrue(body.text.toString().contains(part.text))
            }
            assertTrue(body.isTextSelectable)
        }
        saveDialogPreview("help-topic-$layout")
        compose.onAllNodesWithTag("miuix.dialog.button.${Dialog.BUTTON_NEGATIVE}").onLast().performClick()
        compose.onNodeWithTag("help.search").assertExists()
        compose.onAllNodesWithTag("miuix.dialog.button.${Dialog.BUTTON_NEGATIVE}").onLast().performClick()
        compose.onNodeWithTag("settings.option.帮助").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [28])
    fun gestureThirdLevelSelectionUpdatesItsMiuixGridCell() {
        compose.onNodeWithTag("settings.category.1").performClick()
        compose.onNodeWithTag("settings.option.长按事件").performClick()
        compose.onNodeWithTag("settings.gesture.long_press_top_left").performClick()
        compose.onNodeWithTag("miuix.dialog.choice.2").performClick()
        compose.onNodeWithText("左上\n复制文案").assertIsDisplayed()
        assertEquals("copy_text", store.getString("long_press_top_left", ""))
        saveDialogPreview("host-gesture")
    }

    private fun findNativeInput(root: View, label: String): EditText {
        if (root is EditText && root.contentDescription == label) return root
        if (root is android.view.ViewGroup) {
            for (index in 0 until root.childCount) {
                try { return findNativeInput(root.getChildAt(index), label) } catch (_: NoSuchElementException) { }
            }
        }
        throw NoSuchElementException(label)
    }

    @Test
    fun systemBarSecondaryPageUsesRealMiuixSwitches() {
        compose.onNodeWithTag("settings.category.1").performClick()
        compose.onNodeWithTag("settings.option.沉浸式全屏").performClick()
        compose.onNodeWithTag("settings.toggle.隐藏状态栏").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(store.getBoolean("remove_black_edges", false)) }
    }

    @Test
    fun commentLongPressOptionPreservesPartialSelectionAndAllowsIndividualDeselection() {
        compose.onNodeWithTag("settings.search").performTextReplacement("评论相关")
        compose.onNodeWithTag("settings.category.7").performClick()
        compose.onNodeWithTag("settings.toggle.长按操作").assertDoesNotExist()
        compose.waitForIdle()
        compose.runOnUiThread {
            val decor = ShadowDialog.getLatestDialog().window!!.decorView
            val row = decor.findViewWithTag<View>("settings.option.长按操作")
            assertNotNull(row)
            findNativeScroll(decor)!!.scrollTo(0, row.top)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("settings.option.长按操作").assertIsDisplayed().performClick()
        compose.runOnUiThread {
            val options = ShadowDialog.getLatestDialog() as android.app.AlertDialog
            assertFalse(store.getBoolean("copy_comment_text", false))
            val list = options.listView
            list.performItemClick(list.getChildAt(0), 0, 0)
            options.getButton(Dialog.BUTTON_POSITIVE).performClick()
        }
        compose.runOnIdle {
            assertTrue(store.getBoolean("copy_comment_text", false))
            assertFalse(store.getBoolean("comment_media_tools", false))
            assertFalse(store.getBoolean("copy_source_data", false))
        }
        compose.onNodeWithTag("settings.option.长按操作").performClick()
        compose.runOnUiThread {
            val options = ShadowDialog.getLatestDialog() as android.app.AlertDialog
            assertTrue(options.listView.isItemChecked(0))
            assertFalse(options.listView.isItemChecked(1))
            assertFalse(options.listView.isItemChecked(2))
        }
        compose.onNodeWithTag("miuix.dialog.choice.0").performClick()
        compose.onNodeWithTag("miuix.dialog.button.${Dialog.BUTTON_POSITIVE}").performClick()
        compose.runOnIdle {
            assertFalse(store.getBoolean("copy_comment_text", true))
            assertFalse(store.getBoolean("comment_media_tools", false))
            assertFalse(store.getBoolean("copy_source_data", false))
        }
    }

    @Test
    @Config(sdk = [28])
    fun enabledCommentVoiceReplacementKeepsItsPathEditorReachable() {
        compose.runOnUiThread {
            store.putBoolean("comment_voice_replace", true)
            store.putString("comment_voice_path", "/storage/emulated/0/Music/original.mp3")
        }
        compose.onNodeWithTag("settings.search").performTextReplacement("评论相关")
        compose.onNodeWithTag("settings.category.7").performClick()
        compose.runOnIdle { assertTrue(store.getBoolean("comment_voice_replace", false)) }
        compose.onNodeWithTag("settings.toggle.替换语音文件").assertIsDisplayed()
        compose.onNodeWithTag("settings.option.评论语音文件").assertIsEnabled().performClick()
        compose.onNodeWithTag("settings.input.文件路径").assertIsDisplayed()
            .performTextReplacement("/storage/emulated/0/Music/replacement.mp3")
        saveDialogPreview("menu-comment-voice-path")
        compose.onNodeWithTag("miuix.dialog.button.${Dialog.BUTTON_POSITIVE}").performClick()
        compose.runOnIdle {
            assertEquals("/storage/emulated/0/Music/replacement.mp3",
                store.getString("comment_voice_path", ""))
            assertTrue(store.getBoolean("comment_voice_replace", false))
        }
        compose.onNodeWithTag("settings.toggle.替换语音文件").assertIsDisplayed()
        compose.onNodeWithTag("settings.option.评论语音文件").performClick()
        compose.onNodeWithTag("settings.input.文件路径").assertIsDisplayed()
            .performTextReplacement("/storage/emulated/0/Music/second.mp3")
        compose.onNodeWithTag("miuix.dialog.button.${Dialog.BUTTON_POSITIVE}").performClick()
        compose.runOnIdle {
            assertEquals("/storage/emulated/0/Music/second.mp3", store.getString("comment_voice_path", ""))
            assertTrue(store.getBoolean("comment_voice_replace", false))
        }
    }

    private fun saveDialogPreview(name: String) {
        compose.waitForIdle()
        if (android.os.Build.VERSION.SDK_INT >= 33) return
        val folder = System.getenv("DOUAI_CHAT_SCREENSHOTS") ?: return
        compose.runOnUiThread {
            val window = ShadowDialog.getShownDialogs().last { it.isShowing }.window!!
            val view = window.decorView
            val bounds = window.attributes
            val width = bounds.width.takeIf { it > 0 } ?: view.width
            val height = bounds.height.takeIf { it > 0 } ?: view.height
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("Miuix preview must contain rendered controls", pixels.toSet().size > 8)
            val directory = File(folder).apply { mkdirs() }
            File(directory, "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
    }

    private fun findNativeScroll(root: View): android.widget.ScrollView? {
        if (root is android.widget.ScrollView) return root
        if (root is android.view.ViewGroup) {
            for (index in 0 until root.childCount) findNativeScroll(root.getChildAt(index))?.let { return it }
        }
        return null
    }
}
