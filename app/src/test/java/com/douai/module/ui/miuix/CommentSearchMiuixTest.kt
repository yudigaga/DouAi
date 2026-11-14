package com.douai.module.ui.miuix

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.assert
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28,35], qualifiers = "w360dp-h720dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommentSearchMiuixTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var dialog: AlertDialog? = null
    private var surface: MiuixCommentSearch? = null
    @After fun close() { compose.runOnUiThread { dialog?.dismiss(); surface?.close() } }

    @Test fun pagingUpdatesTheSameMiuixSurfaceWithoutClearingItsQuery() {
        var requests = 0
        var selected = ""
        val queries = mutableListOf<String>()
        compose.runOnUiThread {
            surface = MiuixCommentSearch(compose.activity, { queries.add(it) }, { selected = it }, { requests++ })
            dialog = MiuixDialogBuilder(compose.activity, true).setTitle("查找评论")
                .setView(surface!!.view).setNegativeButton("关闭", null).show()
            surface!!.render("已查 20 条，命中 1 条", listOf(MiuixCommentSearch.Entry("21", "评论", "配方在这里")), true, false, "加载更多评论")
        }
        compose.onNodeWithTag("comment.search.query").performTextReplacement("配方")
        compose.onNodeWithTag("comment.search.query").performTextInputSelection(TextRange(1))
        compose.onNodeWithTag("comment.search.more").performClick()
        compose.runOnIdle { assertEquals(1, requests); assertEquals(listOf("配方"), queries) }
        val original = dialog
        compose.runOnUiThread {
            surface!!.render("已查 40 条，命中 2 条", listOf(
                MiuixCommentSearch.Entry("21", "评论", "配方在这里"),
                MiuixCommentSearch.Entry("22", "楼中楼回复", "这是第二个配方")), false, true, "正在加载…")
        }
        compose.onNodeWithTag("comment.search.query").assertIsDisplayed()
        compose.onNodeWithText("已查 40 条，命中 2 条").assertIsDisplayed()
        compose.onNodeWithTag("comment.search.more").assertIsNotEnabled()
        compose.onNodeWithTag("comment.search.query").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange,TextRange(1)))
        compose.onNodeWithTag("comment.search.query").performImeAction()
        compose.runOnIdle { assertSame(original, dialog); assertEquals("配方", surface!!.query()) }
        compose.onNodeWithTag("comment.search.row.22").performClick()
        compose.runOnIdle { assertEquals("22", selected) }
        save("comment-search")
    }

    @Test @Config(qualifiers = "w360dp-h720dp-night-mdpi")
    fun darkEmptyAndRetryStatesKeepTheSearchAndLoadControlVisible() {
        org.robolectric.RuntimeEnvironment.setFontScale(1.3f)
        compose.runOnUiThread {
            surface = MiuixCommentSearch(compose.activity, {}, {}, {})
            surface!!.setTranscriptSearch(true)
            dialog = MiuixDialogBuilder(compose.activity, true).setTitle("查找评论")
                .setView(surface!!.view).setNegativeButton("关闭", null).show()
            surface!!.render("已查 40 条，命中 0 条", emptyList(), true, false, "加载失败，重试")
        }
        compose.onNodeWithTag("comment.search.query").performTextReplacement("未命中")
        compose.onNodeWithTag("comment.search.query").assertIsDisplayed()
        compose.onNodeWithTag("comment.search.empty").assertIsDisplayed()
        compose.onNodeWithTag("comment.search.more").assertIsDisplayed()
        compose.onNodeWithTag("miuix.dialog.button.-2").assertIsDisplayed()
        save("comment-search-dark")
    }

    @Test @Config(qualifiers = "w360dp-h480dp-mdpi")
    fun longResultsInAShortWindowLeavePagingAndCloseReachable() {
        compose.runOnUiThread {
            surface = MiuixCommentSearch(compose.activity, {}, {}, {})
            dialog = MiuixDialogBuilder(compose.activity, true).setTitle("查找评论")
                .setView(surface!!.view).setNegativeButton("关闭", null).show()
            surface!!.render("已查 200 条，命中 200 条",
                (1..200).map { MiuixCommentSearch.Entry("$it", "评论", "较长的正文，包含查找的上下文。".repeat(3)) },
                true, false, "加载更多评论")
        }
        compose.onNodeWithTag("comment.search.query").assertIsDisplayed()
        compose.onNodeWithTag("comment.search.more").assertIsDisplayed()
        compose.onNodeWithTag("miuix.dialog.button.-2").assertIsDisplayed()
        save("comment-search-short")
    }

    private fun save(name: String) {
        // Match existing Miuix visual fixtures: software Canvas cannot replay API33 RuntimeShader.
        if (android.os.Build.VERSION.SDK_INT >= 33) return
        val directory = System.getenv("DOUAI_CHAT_SCREENSHOTS")?.let(::File) ?: return
        compose.waitForIdle()
        compose.runOnUiThread {
            assertTrue(directory.isDirectory || directory.mkdirs())
            val view = dialog!!.window!!.decorView
            assertTrue(view.width > 0 && view.height > 0)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
            bitmap.recycle()
        }
    }
}
