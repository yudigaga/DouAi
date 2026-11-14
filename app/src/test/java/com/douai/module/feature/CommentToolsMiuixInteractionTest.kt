package com.douai.module.feature

import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.douai.module.ui.miuix.MiuixCommentDetails
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h720dp-port-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommentToolsMiuixInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var hook: CommentToolsHook? = null
    private fun dialog() = ShadowDialog.getLatestDialog() as AlertDialog
    private val clipboard get() = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun open() = compose.runOnUiThread {
        hook = CommentToolsHookTest.hook(compose.activity, ArrayList())
        hook!!.setReadingOptions(true, false)
        assertTrue(CommentToolsHookTest.menu(hook, compose.activity))
    }
    @After fun close() { compose.runOnUiThread {
        ShadowDialog.getLatestDialog()?.dismiss()
        hook?.close()
    } }

    @Test fun actionsAndDataUseRenderedMiuixComponentsAndCopyTheFullSource() {
        open()
        compose.onNodeWithText("查看评论数据").assertIsDisplayed()
        render("comment-tools", false)
        compose.onNodeWithText("查看评论数据").performClick()
        compose.onNodeWithTag("comment_tools.data.summary").assertIsDisplayed().assertTextContains("音频：有",substring = true)
        compose.onNodeWithText("原始 JSON").assertIsDisplayed()
        compose.onNodeWithTag("comment_tools.data.json.0").assertTextContains("private-token",substring = true)
        compose.runOnIdle { assertFalse(clipboard.hasPrimaryClip()) }
        render("comment-data", false)
        compose.onNodeWithTag("miuix.dialog.button.${Dialog.BUTTON_POSITIVE}").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("private-token", org.json.JSONObject(clipboard.primaryClip!!.getItemAt(0).text.toString()).getString("commentToken"))
            assertTrue(dialog().isShowing)
        }
        compose.onNodeWithTag("miuix.dialog.button.${Dialog.BUTTON_NEGATIVE}").performClick()
    }

    @Test @Config(qualifiers = "w320dp-h600dp-port-night-mdpi")
    fun transcriptShowsOriginalTextAndDarkPaletteAndCopiesOnlyOnRequest() {
        open()
        compose.onNodeWithText("查看语音转写").performClick()
        compose.onNodeWithTag("comment_tools.transcript.text.0").assertIsDisplayed().assertTextContains(" 转写原文 ")
        compose.runOnIdle { assertFalse(clipboard.hasPrimaryClip()) }
        render("comment-transcript-dark",true)
        compose.onNodeWithText("复制转写").assertIsDisplayed().performClick()
        compose.runOnIdle {assertEquals(" 转写原文 ",clipboard.primaryClip!!.getItemAt(0).text.toString())}
    }

    @Test fun longRawLineAndNewlinesRetainAllCharactersAndSurrogatePairs() {
        val source = "a".repeat(2047)+"😀\n"+"乙".repeat(9000)+"\n}\n"
        val blocks = MiuixCommentDetails.textBlocks(source)
        assertTrue(blocks.size > 1)
        assertTrue(blocks.all { it.length <= 2048 && !Character.isHighSurrogate(it.last()) })
        assertEquals(source,blocks.joinToString(""))
    }

    private fun render(name:String, dark:Boolean) {
        compose.waitForIdle()
        compose.runOnUiThread {
            val decor=dialog().window!!.decorView
            val bitmap=Bitmap.createBitmap(decor.width,decor.height,Bitmap.Config.ARGB_8888)
            try {
                decor.draw(Canvas(bitmap))
                val pixels=IntArray(bitmap.width*bitmap.height)
                bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                assertTrue(pixels.toSet().size>8)
                val surface=pixels.asSequence().filter { Color.alpha(it)>200 }.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
                assertEquals(dark,Color.red(surface)+Color.green(surface)+Color.blue(surface)<450)
                val dest=System.getenv("DOUAI_CHAT_SCREENSHOTS") ?: return@runOnUiThread
                val dir=File(dest);require(dir.isAbsolute && (dir.isDirectory || dir.mkdirs()))
                File(dir,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            } finally { bitmap.recycle() }
        }
    }
}
