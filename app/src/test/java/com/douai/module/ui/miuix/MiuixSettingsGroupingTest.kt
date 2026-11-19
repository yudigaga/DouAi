package com.douai.module.ui.miuix

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import com.douai.module.config.HostSettingsStore
import com.douai.module.ui.ModuleSettingsScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h720dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MiuixSettingsGroupingTest {
    companion object {
        private val GROUP_LABELS = listOf(
            "界面与内容", "基础弹窗、主题与手势、推荐筛选、组件显示",
            "播放与自动化", "播放规则、互动自动化、自动回复/评论、续火花",
            "评论与消息", "手动评论工具、消息展示与语音",
            "下载与工具", "下载、AI模型、文本转语音、分析与备份",
            "本地与帮助", "本地资料/版本显示、帮助、日志与项目信息",
        )
        private val FLAT_HOME_ORDER = listOf(
            "settings.category.0", "settings.category.1", "settings.category.2", "settings.category.9",
            "settings.category.4", "settings.category.5", "settings.category.6", "settings.category.8",
            "settings.category.7", "settings.category.10", "settings.category.3", "settings.category.12",
            "settings.category.11", "settings.category.13",
        )
    }

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var store: HostSettingsStore
    private lateinit var screen: ModuleSettingsScreen
    private lateinit var home: View

    @Before
    fun openSettings() {
        compose.runOnUiThread {
            store = HostSettingsStore(compose.activity)
            screen = ModuleSettingsScreen(compose.activity, store)
            home = screen.createMiuixView()
            compose.activity.setContentView(home)
        }
    }

    @After
    fun closeSettings() {
        compose.runOnUiThread { screen.close() }
    }

    @Test
    fun miuixFlatHomeKeepsOriginalCategoryOrderAndSearchRoutes() {
        lateinit var saved: Map<String, *>
        compose.runOnIdle { saved = store.snapshot() }

        compose.onNodeWithTag("settings.group.view-content", useUnmergedTree = true)
            .assertDoesNotExist()
        assertFlatHome()
        assertHomeUnchanged("", saved)

        replaceQuery("相关")
        assertFlatCategories(listOf(1, 3, 7))
        assertHomeUnchanged("相关", saved)

        replaceQuery("本地")
        assertFlatCategories(listOf(11))
        assertHomeUnchanged("本地", saved)

        replaceQuery("")
        assertFlatHome()
        assertHomeUnchanged("", saved)
        replaceQuery("   ")
        assertFlatHome()

        val fourthCategory = tagsInLayoutOrder("settings.category.")[3]
        assertEquals("settings.category.9", fourthCategory)
        compose.onNodeWithTag(fourthCategory).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("component.page").assertIsDisplayed()
        compose.onNodeWithTag("miuix.category.page").assertDoesNotExist()
        compose.runOnIdle { assertEquals(saved, store.snapshot()) }
        compose.onNodeWithContentDescription("返回根目录").assertIsDisplayed().performClick()
        compose.onNodeWithTag("component.page").assertDoesNotExist()
        assertHomeUnchanged("   ", saved)
        assertFlatHome()

        replaceQuery("API Key")
        assertNoGroupHeadings()
        compose.onNodeWithText("扩展功能 > AI 模型设置").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings.search.result.ai")
            .performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertNotNull(ShadowDialog.getLatestDialog().window!!.decorView
                .findViewWithTag<View>("settings.option.添加模型配置"))
            assertEquals(saved, store.snapshot())
        }
        compose.runOnUiThread { screen.dismissChildPages() }
        assertHomeUnchanged("API Key", saved)
        replaceQuery("")
        compose.onNodeWithTag("settings.search.result.ai").assertDoesNotExist()
        assertFlatHome()
        assertHomeUnchanged("", saved)
    }

    private fun assertFlatHome() {
        compose.onAllNodes(taggedWithPrefix("settings.group."), useUnmergedTree = true)
            .assertCountEquals(0)
        GROUP_LABELS.forEach { label ->
            compose.onNodeWithText(label, useUnmergedTree = true).assertDoesNotExist()
        }
        for (index in 0..13) {
            compose.onAllNodesWithTag("settings.category.$index", useUnmergedTree = true)
                .assertCountEquals(1)
        }
        assertEquals(
            FLAT_HOME_ORDER,
            tagsInLayoutOrder("settings.category."),
        )
    }

    private fun assertNoGroupHeadings() {
        compose.onAllNodes(taggedWithPrefix("settings.group."), useUnmergedTree = true)
            .assertCountEquals(0)
        GROUP_LABELS.forEach { label ->
            compose.onNodeWithText(label, useUnmergedTree = true).assertDoesNotExist()
        }
    }

    private fun assertFlatCategories(originalIndices: List<Int>) {
        assertNoGroupHeadings()
        assertEquals(
            originalIndices.map { "settings.category.$it" },
            tagsInLayoutOrder("settings.category."),
        )
    }

    private fun replaceQuery(query: String) {
        compose.onNodeWithTag("settings.search").performScrollTo().performTextReplacement(query)
    }

    private fun assertHomeUnchanged(query: String, saved: Map<String, *>) {
        compose.onNodeWithTag("settings.search").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(query)),
        )
        compose.runOnIdle {
            assertSame(home, compose.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
            assertEquals("douai.miuix.home", home.tag)
            assertEquals(saved, store.snapshot())
        }
    }

    private fun taggedWithPrefix(vararg prefixes: String) = SemanticsMatcher(
        "test tag starts with ${prefixes.joinToString()}",
    ) { node ->
        val tag = node.config.getOrNull(SemanticsProperties.TestTag)
        tag != null && prefixes.any { tag.startsWith(it) }
    }

    private fun tagsInLayoutOrder(vararg prefixes: String): List<String> =
        compose.onAllNodes(taggedWithPrefix(*prefixes), useUnmergedTree = true)
            .fetchSemanticsNodes()
            // Unclipped layout positions retain offscreen rows; tag values never determine order.
            .sortedBy { it.positionInRoot.y }
            .map { it.config[SemanticsProperties.TestTag] }
}
