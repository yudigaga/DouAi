package com.douai.module.ui.miuix

import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.douai.module.config.CustomViewLocator
import com.douai.module.ui.ComponentHideSettingsPage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-mdpi")
class MiuixDialogInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var dialog: AlertDialog? = null

    @After fun closeDialog() { compose.runOnUiThread { dialog?.dismiss() } }

    @Test fun messageAndDynamicActionStateRenderInMiuixAndValidationCanKeepTheDialogOpen() {
        var saved = 0
        compose.runOnUiThread {
            dialog = MiuixDialogBuilder(compose.activity, true).setTitle("模型配置")
                .setMessage("完整消息正文").setNegativeButton("取消", null)
                .setNeutralButton("删除", null).setPositiveButton("保存并启用", null).create()
            dialog!!.show()
            dialog!!.getButton(Dialog.BUTTON_NEUTRAL).visibility = View.GONE
            dialog!!.getButton(Dialog.BUTTON_POSITIVE).isEnabled = false
            dialog!!.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener { saved++ }
        }
        compose.onNodeWithText("完整消息正文").assertIsDisplayed()
        compose.onNodeWithTag("miuix.dialog.button.${Dialog.BUTTON_NEUTRAL}").assertDoesNotExist()
        compose.onNodeWithTag("miuix.dialog.button.${Dialog.BUTTON_POSITIVE}").assertIsNotEnabled()
        compose.runOnUiThread { dialog!!.getButton(Dialog.BUTTON_POSITIVE).isEnabled = true }
        compose.onNodeWithTag("miuix.dialog.button.${Dialog.BUTTON_POSITIVE}").performClick()
        compose.runOnIdle {
            assertEquals(1, saved)
            assertTrue(dialog!!.isShowing)
        }
        compose.onNodeWithTag("miuix.dialog.button.${Dialog.BUTTON_NEGATIVE}").performClick()
        compose.runOnIdle { assertFalse(dialog!!.isShowing) }
    }

    @Test fun singleChoiceKeepsTheDialogOpenAndUsesTheOriginalCallback() {
        var selected = -1
        compose.runOnUiThread {
            dialog = MiuixDialogBuilder(compose.activity, true).setTitle("回复模式")
                .setSingleChoiceItems(arrayOf("固定", "AI"), 0) { _, index -> selected = index }
                .setPositiveButton("保存", null).create()
            dialog!!.show()
        }
        compose.onNodeWithTag("miuix.dialog.choice.1").performClick()
        compose.runOnIdle {
            assertEquals(1, selected)
            assertEquals(1, dialog!!.listView.checkedItemPosition)
            assertTrue(dialog!!.isShowing)
        }
    }

    @Test fun searchableChoicesUpdateRenderedRowsAndCallbackWithoutReplacingTheHeader() {
        var selected = ""
        lateinit var header: android.widget.EditText
        compose.runOnUiThread {
            header = android.widget.EditText(compose.activity).apply { setText("温柔") }
            dialog = MiuixDialogBuilder(compose.activity, true).setCustomTitle(header)
                .setSingleChoiceItems(arrayOf("old-a", "old-b"), 0) { _, _ -> selected = "old" }
                .setNegativeButton("关闭", null).create()
            dialog!!.show()
            MiuixDialogBuilder.updateSingleChoiceItems(dialog!!, arrayOf("温柔（Soft Voice）"), -1) { _, index ->
                selected = "filtered-$index"
            }
        }
        compose.onNodeWithText("温柔（Soft Voice）").assertIsDisplayed()
        compose.onNodeWithTag("miuix.dialog.choice.1").assertDoesNotExist()
        compose.onNodeWithTag("miuix.dialog.choice.0").performClick()
        compose.runOnIdle {
            assertEquals("filtered-0", selected)
            assertEquals("温柔", header.text.toString())
            assertTrue(header.isShown)
            assertEquals(1, dialog!!.listView.count)
            assertEquals(0, dialog!!.listView.checkedItemPosition)
            assertTrue(dialog!!.isShowing)
        }
    }

    @Test fun searchableNativeChoicesHandleEmptyResultsThenRestoreTheCorrectCallback() {
        var selected = ""
        compose.runOnUiThread {
            dialog = MiuixDialogBuilder(compose.activity, false).setTitle("音色")
                .setSingleChoiceItems(arrayOf("A", "B"), 1) { _, _ -> selected = "old" }.create()
            dialog!!.show()
            MiuixDialogBuilder.updateSingleChoiceItems(dialog!!, emptyArray(), -1) { _, _ -> selected = "empty" }
            assertEquals(0, dialog!!.listView.count)
            assertEquals(-1, dialog!!.listView.checkedItemPosition)
            MiuixDialogBuilder.updateSingleChoiceItems(dialog!!, arrayOf("B"), 0) { _, index -> selected = "B-$index" }
            dialog!!.listView.performItemClick(null, 0, 0)
            assertEquals("B-0", selected)
            assertEquals(0, dialog!!.listView.checkedItemPosition)
        }
    }

    @Test fun customViewDetailsCopyMetadataThroughTheMiuixAction() {
        var information = ""
        compose.runOnUiThread {
            val host = FrameLayout(compose.activity)
            val target = TextView(compose.activity).apply {
                id = android.R.id.title
                text = "private host text"
                contentDescription = "private host description"
            }
            host.addView(target)
            val locator = CustomViewLocator.capture(host, target)!!
            dialog = ComponentHideSettingsPage.showCustomViewDetails(compose.activity, locator, true)
        }
        compose.runOnIdle {
            val content = dialog!!.window!!.decorView.findViewWithTag<TextView>("component.custom.details")
            assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, dialog!!.window!!.attributes.height)
            information = content.text.toString()
            assertTrue(information.contains("当前窗口"))
            assertTrue(information.contains("唯一资源 ID"))
            assertTrue(information.contains("android:id/title"))
            assertTrue(information.contains(TextView::class.java.name))
            assertFalse(information.contains("private host text"))
            assertFalse(information.contains("private host description"))
        }
        compose.onNodeWithTag("miuix.dialog.button.${Dialog.BUTTON_POSITIVE}").performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(information, clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertFalse(dialog!!.isShowing)
        }
    }
}
