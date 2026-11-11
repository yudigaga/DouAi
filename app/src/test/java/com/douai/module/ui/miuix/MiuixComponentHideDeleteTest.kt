package com.douai.module.ui.miuix

import android.app.Activity
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.douai.module.config.ComponentHideSettings
import com.douai.module.config.CustomViewLocator
import com.douai.module.config.HostSettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-mdpi")
class MiuixComponentHideDeleteTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var controller: ActivityController<Activity>? = null
    private var page: MiuixComponentHidePage? = null
    private lateinit var store: HostSettingsStore
    private lateinit var deleteTag: String

    @After fun cleanUp() {
        compose.runOnUiThread {
            page?.dismiss()
            controller?.pause()?.stop()?.destroy()
        }
    }

    @Test fun plainHostCanCancelAndConfirmDeletingACustomView() {
        compose.runOnUiThread {
            controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
            val activity = controller!!.get()
            assertFalse(activity is androidx.navigationevent.NavigationEventDispatcherOwner)
            store = HostSettingsStore(activity)
            val root = FrameLayout(activity)
            val target = TextView(activity).apply { id = android.R.id.title }
            root.addView(target)
            val locator = CustomViewLocator.capture(root, target)!!
            store.putString(CustomViewLocator.SETTINGS_KEY, CustomViewLocator.serialize(listOf(locator)))
            deleteTag = "component.custom.delete.${locator.identityKey()}"
            page = MiuixComponentHidePage(activity, store).also { it.show() }
        }
        compose.onNodeWithTag("component.list").performScrollToNode(hasTestTag(deleteTag))
        compose.onNodeWithTag(deleteTag).performClick()
        compose.onNodeWithText("删除自定义视图").assertIsDisplayed()
        compose.runOnUiThread {
            val window = ShadowDialog.getLatestDialog()
            window.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            window.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        compose.onNodeWithText("删除自定义视图").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, ComponentHideSettings.readFrom(store).customViews.size)
            assertTrue(page!!.isShowing())
        }
        compose.onNodeWithTag(deleteTag).performClick()
        compose.onNodeWithText("删除自定义视图").assertIsDisplayed()
        compose.onNodeWithTag("component.custom.confirm_cancel").performClick()
        compose.runOnIdle { assertEquals(1, ComponentHideSettings.readFrom(store).customViews.size) }
        compose.onNodeWithTag(deleteTag).performClick()
        compose.onNodeWithTag("component.custom.confirm_delete").performClick()
        compose.runOnIdle {
            assertTrue(ComponentHideSettings.readFrom(store).customViews.isEmpty())
            assertTrue(page!!.isShowing())
        }
        compose.onNodeWithText("暂无自定义视图").assertIsDisplayed()
    }

    @Test fun plainHostBackDismissesNumberInputBeforeComponentPage() {
        compose.runOnUiThread {
            controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
            store = HostSettingsStore(controller!!.get())
            store.putBoolean(ComponentHideSettings.ENABLED, true)
            store.putString(ComponentHideSettings.Component.LIKE.key, "81")
            page = MiuixComponentHidePage(controller!!.get(), store).also { it.show() }
        }
        val key = ComponentHideSettings.Component.LIKE.key
        compose.onNodeWithTag("component.list").performScrollToNode(hasTestTag(key))
        compose.onNodeWithTag("$key.value").performClick()
        compose.onNodeWithTag("component.alpha.input").performTextReplacement("0")
        compose.runOnUiThread {
            val window = ShadowDialog.getLatestDialog()
            window.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            window.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        compose.onNodeWithTag("component.alpha.input").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("81", store.getString(key, ""))
            assertTrue(page!!.isShowing())
        }
    }
}
