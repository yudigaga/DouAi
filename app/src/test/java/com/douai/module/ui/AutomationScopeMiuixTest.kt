package com.douai.module.ui

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView
import android.widget.Spinner
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import com.douai.module.config.HostSettingsStore
import com.douai.module.feature.NativeFollowLists
import com.douai.module.feature.NativeMessagingHooks
import com.douai.module.feature.NativeUserProfiles
import com.douai.module.feature.NativeUserSearch
import com.douai.module.feature.ProfileListEntry
import com.douai.module.ui.miuix.MiuixFormControls
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
import java.io.File
import java.lang.reflect.Field

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h760dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutomationScopeMiuixTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var store: HostSettingsStore
    private lateinit var screen: ModuleSettingsScreen
    private val savedStatics = linkedMapOf<Field, Any?>()

    @Before fun openSettings() {
        compose.runOnUiThread {
            replaceStatic(NativeMessagingHooks::class.java, "active", null)
            replaceStatic(NativeMessagingHooks::class.java, "contacts", linkedMapOf("101" to "Alice", "202" to "Bob"))
            replaceStatic(NativeMessagingHooks::class.java, "contactProfiles", linkedMapOf(
                "101" to ProfileListEntry("101", "Alice", "", "alice.dy", "同事"),
                "202" to ProfileListEntry("202", "Bob", "", "bob.dy")))
            replaceStatic(NativeMessagingHooks::class.java, "groupMembers", emptyMap<String, Map<String, ProfileListEntry>>())
            replaceStatic(NativeFollowLists::class.java, "active", null)
            replaceStatic(NativeUserProfiles::class.java, "active", null)
            replaceStatic(NativeUserSearch::class.java, "active", null)
            store = HostSettingsStore(compose.activity)
            for (prefix in listOf("reply", "comment")) {
                store.putString("${prefix}_scope_mode", "all")
                store.putString("${prefix}_scope_relations", "")
                store.putString("${prefix}_filter_mode", "off")
            }
            store.putString("reply_targets", "")
            store.putString("comment_scope_users", "")
            store.putBoolean("reply_private_chats", true)
            store.putBoolean("reply_group_chats", true)
            store.putString("reply_group_scope_mode", "all")
            store.putString("reply_group_mode", "all")
            store.putString("reply_group_targets", "group-keep")
            store.putString("reply_group_members", "202")
            screen = ModuleSettingsScreen(compose.activity, store)
            compose.activity.setContentView(screen.createMiuixView())
        }
    }

    @After fun closeSettings() {
        compose.runOnUiThread {
            if (::screen.isInitialized) { screen.dismissChildPages(); screen.close() }
            ShadowDialog.getShownDialogs().toList().filter { it.isShowing }.forEach { it.dismiss() }
            compose.activity.setContentView(View(compose.activity))
            savedStatics.entries.toList().asReversed().forEach { (field, value) -> field.set(null, value) }
        }
    }

    @Test fun privateWhitelistUsesRealCheckboxesAndOnlySaveCommitsTheDraftAndUpdatesItsParent() {
        category(5)
        option("私聊范围")
        assertAbsent("settings.option.关注与粉丝名单")
        assertHidden("settings.option.选择用户")
        choose("用户范围", 1)
        compose.runOnIdle { assertEquals("whitelist", store.getString("reply_scope_mode", "")) }
        option("选择用户")
        assertCompactRightFilterAboveSearch()
        compose.onNodeWithTag("settings.followLists.searchAll").assertDoesNotExist()
        compose.onNodeWithTag("settings.followLists.refreshProfiles").assertDoesNotExist()
        compose.runOnIdle {
            val alice = requireNotNull(currentRoot().findViewWithTag<View>("101"))
            assertEquals("Alice（同事）", alice.findViewWithTag<android.widget.TextView>("member.name").text.toString())
        }
        compose.onNodeWithTag("member.selection.101").assertIsOff().performTouchInput { click() }
        compose.onNodeWithTag("member.selection.101").assertIsOn()
        compose.runOnIdle { assertEquals("", store.getString("reply_targets", "")) }
        button(AlertDialog.BUTTON_NEGATIVE, "取消")
        compose.runOnIdle { assertEquals("", store.getString("reply_targets", "")) }

        option("选择用户")
        compose.onNodeWithTag("member.selection.101").assertIsOff().performTouchInput { click() }
        compose.onNodeWithTag("settings.followLists.search").performTextReplacement("Alice")
        compose.onAllNodesWithText("刷新").assertCountEquals(1)
        compose.onNodeWithText("填写 UID").assertDoesNotExist()
        compose.onNode(hasTestTag("miuix.dialog.button.-3") and hasText("刷新")).assertIsDisplayed().performClick()
        compose.onNodeWithTag("settings.followLists.search").assertIsDisplayed()
        compose.onNodeWithTag("member.selection.101").assertIsOn()
        compose.runOnIdle {
            assertEquals("Alice", currentRoot().findViewWithTag<android.widget.EditText>("settings.followLists.search").text.toString())
            assertEquals("", store.getString("reply_targets", ""))
        }
        savePreview("scope-private-checkbox")
        button(AlertDialog.BUTTON_POSITIVE, "保存")
        compose.runOnIdle { assertEquals(setOf("101"), csv(store.getString("reply_targets", ""))) }
        assertOptionPeople("选择用户", 1)
        button(AlertDialog.BUTTON_NEGATIVE, "返回")
        assertOptionPeople("私聊范围", 1)
        savePreview("scope-private-saved")
    }

    @Test fun authorBlacklistKeepsItsSelectionAndHiddenContentFilterValuesAcrossModeChanges() {
        compose.runOnUiThread {
            store.putString("comment_scope_mode", "blacklist")
            store.putString("comment_scope_users", "202")
            store.putString("comment_filter_keywords", "保留关键词")
            store.putString("comment_filter_fields", "author,title")
        }
        category(6)
        option("作品范围")
        assertAbsent("settings.option.关注与粉丝名单")
        assertTrue(optionText("作者范围").contains("排除名单内"))
        assertHidden("settings.option.筛选关键词")
        assertHidden("settings.option.匹配对象")
        choose("作品筛选", 1)
        assertVisible("settings.option.筛选关键词")
        assertVisible("settings.option.匹配对象")
        choose("作品筛选", 0)
        assertHidden("settings.option.筛选关键词")
        assertHidden("settings.option.匹配对象")
        compose.runOnIdle {
            assertEquals("保留关键词", store.getString("comment_filter_keywords", ""))
            assertEquals("author,title", store.getString("comment_filter_fields", ""))
        }
        option("排除作者")
        compose.onNodeWithTag("member.selection.202").assertIsOn()
        compose.onNodeWithTag("member.selection.101").assertIsOff().performTouchInput { click() }
        button(AlertDialog.BUTTON_POSITIVE, "保存")
        compose.runOnIdle { assertEquals(setOf("101", "202"), csv(store.getString("comment_scope_users", ""))) }
        assertOptionPeople("排除作者", 2)
        savePreview("scope-author-blacklist")
        choose("作者范围", 0)
        assertHidden("settings.option.选择作者")
        assertHidden("settings.option.排除作者")
        compose.runOnIdle { assertEquals(setOf("101", "202"), csv(store.getString("comment_scope_users", ""))) }
    }

    @Test fun receiveSwitchesHideTheirRangesAndGroupMemberEditingStaysIndependentOfPrivateTargets() {
        compose.runOnUiThread {
            store.putBoolean("reply_private_chats", false)
            store.putBoolean("reply_group_chats", false)
            store.putString("reply_targets", "202")
        }
        category(5)
        assertHidden("settings.option.私聊范围")
        assertHidden("settings.option.群聊范围")
        touchCurrent("settings.toggle.接收私聊")
        assertVisible("settings.option.私聊范围")
        touchCurrent("settings.toggle.接收群聊")
        option("群聊范围")
        choose("群聊范围", 2)
        assertVisible("settings.option.排除群聊")
        choose("触发条件", 1)
        option("选择成员")
        compose.onNodeWithTag("member.selection.101").assertIsOff().performTouchInput { click() }
        compose.onNodeWithTag("member.selection.202").assertIsOn()
        button(AlertDialog.BUTTON_POSITIVE, "保存")
        compose.runOnIdle {
            assertEquals(setOf("101", "202"), csv(store.getString("reply_group_members", "")))
            assertEquals("202", store.getString("reply_targets", ""))
            assertEquals("group-keep", store.getString("reply_group_targets", ""))
        }
        assertOptionPeople("选择成员", 2)
        choose("触发条件", 0)
        assertHidden("settings.option.选择成员")
        button(AlertDialog.BUTTON_NEGATIVE, "返回")
        touchCurrent("settings.toggle.接收私聊")
        touchCurrent("settings.toggle.接收群聊")
        assertHidden("settings.option.私聊范围")
        assertHidden("settings.option.群聊范围")
        compose.runOnIdle {
            assertEquals("202", store.getString("reply_targets", ""))
            assertEquals(setOf("101", "202"), csv(store.getString("reply_group_members", "")))
        }
    }

    private fun category(index: Int) {
        compose.onNodeWithTag("settings.category.$index").performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun option(title: String) = touchCurrent("settings.option.$title")

    private fun choose(title: String, index: Int) {
        option(title)
        if (title == "用户范围" || title == "作者范围" || title == "群聊范围") {
            compose.onNode(hasTestTag("miuix.dialog.choice.0") and hasText("全部")).assertIsDisplayed()
            compose.onNode(hasTestTag("miuix.dialog.choice.1") and hasText("仅名单内")).assertIsDisplayed()
            compose.onNode(hasTestTag("miuix.dialog.choice.2") and hasText("排除名单内")).assertIsDisplayed()
        }
        compose.onNodeWithTag("miuix.dialog.choice.$index").performClick()
        compose.waitForIdle()
    }

    private fun button(which: Int, label: String) {
        compose.onNode(hasTestTag("miuix.dialog.button.$which") and hasText(label)).assertIsDisplayed().performClick()
        compose.waitForIdle()
    }

    // Scope labels may exist in the background category too; touch the current window's real ComposeView.
    private fun touchCurrent(tag: String) {
        compose.waitForIdle()
        compose.runOnUiThread {
            val control = requireNotNull(currentRoot().findViewWithTag<View>(tag)) { tag }
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
            val control = requireNotNull(currentRoot().findViewWithTag<View>(tag)) { tag }
            val bounds = Rect()
            assertTrue("$tag must be visible", control.isShown && control.getGlobalVisibleRect(bounds))
            assertTrue(tag, control.width > 0 && control.height > 0)
            val now = SystemClock.uptimeMillis()
            val x = control.width / 2f
            val y = control.height / 2f
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(now, now + 32, MotionEvent.ACTION_UP, x, y, 0)
            try { control.dispatchTouchEvent(down); control.dispatchTouchEvent(up) }
            finally { down.recycle(); up.recycle() }
        }
        compose.waitForIdle()
    }

    private fun currentDialog(): Dialog? = ShadowDialog.getShownDialogs().lastOrNull { it.isShowing }
    private fun currentRoot(): View = currentDialog()?.window?.decorView ?: compose.activity.window.decorView
    private fun assertCompactRightFilterAboveSearch() = compose.runOnIdle {
        val root = currentRoot()
        val filter = MiuixFormControls.viewFor(requireNotNull(root.findViewWithTag<Spinner>("settings.followLists.scope")))
        val search = MiuixFormControls.viewFor(requireNotNull(root.findViewWithTag<android.widget.EditText>("settings.followLists.search")))
        val filterBounds = Rect(); val searchBounds = Rect()
        assertTrue(filter.getGlobalVisibleRect(filterBounds)); assertTrue(search.getGlobalVisibleRect(searchBounds))
        assertTrue("The filter belongs to a compact control on the right, rather than another full-width row",
            filterBounds.width() < root.width * 0.6f)
        assertTrue("The right-aligned filter must stay above the search input", filterBounds.bottom <= searchBounds.top)
        assertTrue("The filter must align with the right side of the picker",
            filterBounds.right >= searchBounds.right - (24 * root.resources.displayMetrics.density).toInt())
    }
    private fun assertAbsent(tag: String) = compose.runOnIdle { assertNull(tag, currentRoot().findViewWithTag<View>(tag)) }
    private fun assertHidden(tag: String) = compose.runOnIdle {
        assertFalse(tag, currentRoot().findViewWithTag<View>(tag)?.isShown ?: false)
    }
    private fun assertVisible(tag: String) = compose.runOnIdle {
        assertTrue(tag, currentRoot().findViewWithTag<View>(tag)?.isShown ?: false)
    }

    private fun optionText(title: String): String = allText(compose.onNodeWithTag("settings.option.$title").fetchSemanticsNode())
    private fun allText(node: SemanticsNode): String =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text } +
            " " + node.children.joinToString(" ") { allText(it) }

    private fun assertOptionPeople(title: String, number: Int) {
        val text = optionText(title)
        assertTrue("$title must render the saved user count: $text", Regex("(?<!\\d)$number\\s*人").containsMatchIn(text))
        assertFalse("$title must not expose a raw UID CSV", text.contains("101"))
    }

    private fun csv(value: String) = value.split(',').filter { it.isNotBlank() }.toSet()
    private fun replaceStatic(owner: Class<*>, name: String, value: Any?) {
        val field = owner.getDeclaredField(name).apply { isAccessible = true }
        savedStatics[field] = field.get(null)
        field.set(null, value)
    }

    private fun savePreview(name: String) {
        val output = System.getenv("DOUAI_CHAT_SCREENSHOTS") ?: return
        compose.runOnUiThread {
            val view = currentRoot()
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val directory = File(output).apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
