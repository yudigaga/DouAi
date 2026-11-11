package com.douai.module.ui

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import com.douai.module.feature.FollowListSnapshot
import com.douai.module.feature.NativeUserProfiles
import com.douai.module.feature.NativeUserSearch
import com.douai.module.feature.ProfileListEntry
import com.douai.module.ui.miuix.MiuixFormControls
import com.douai.module.ui.miuix.MiuixDialogBuilder
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.Executors
import java.util.function.Consumer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h760dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MemberListMiuixTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var members: MemberListView? = null
    private var searchUpdate: Consumer<NativeUserSearch.Update>? = null
    private var activeDialog: AlertDialog? = null

    @After fun closeList() {
        compose.runOnUiThread {
            activeDialog?.dismiss()
            members?.close()
            compose.activity.setContentView(View(compose.activity))
        }
    }

    @Test fun miuixUserRowsUseCompactCheckboxesAndKeepTheirUidSelectionWhenFiltered() {
        showList(true)
        compose.onNodeWithTag("settings.followLists.scope").assertIsDisplayed()
        compose.onNodeWithTag("settings.followLists.searchAll").assertIsDisplayed()
        compose.onNodeWithTag("member.selection.101").assertIsOn()
        compose.onNodeWithTag("member.selection.202").assertIsOff()
        compose.runOnIdle {
            val row = members!!.findViewWithTag<View>("101")
            assertNotNull(row)
            assertTrue((row as ViewGroup).getChildAt(0) is ImageView)
        }
        compose.onNodeWithTag("member.selection.101").performTouchInput { click() }
        compose.onNodeWithTag("member.selection.101").assertIsOff()
        compose.onNodeWithTag("settings.followLists.search").performTextReplacement("bob.dy")
        compose.runOnIdle {
            val list = members!!.findViewWithTag<ListView>("settings.followLists.members")
            assertEquals(1, list.adapter.count)
            assertEquals("202", (list.adapter.getItem(0) as ProfileListEntry).uid)
            members!!.findViewWithTag<View>("202").performClick()
            assertEquals(setOf("202"), members!!.selected())
        }
        compose.onNodeWithTag("member.selection.202").assertIsOn()
        compose.onNodeWithTag("settings.followLists.search").performTextReplacement("")
        compose.runOnIdle {
            val scope = members!!.findViewWithTag<Spinner>("settings.followLists.scope")
            assertEquals(4, scope.count)
            scope.setSelection(1)
        }
        compose.runOnIdle {
            val list = members!!.findViewWithTag<ListView>("settings.followLists.members")
            assertEquals(1, list.adapter.count)
            assertEquals("101", (list.adapter.getItem(0) as ProfileListEntry).uid)
            members!!.findViewWithTag<Spinner>("settings.followLists.scope").setSelection(2)
        }
        compose.onNodeWithTag("member.selection.202").assertIsOn()
        compose.runOnIdle {
            assertEquals(setOf("202"), members!!.selected())
            members!!.findViewWithTag<Spinner>("settings.followLists.scope").setSelection(0)
        }
        compose.onNodeWithTag("settings.followLists.search").performTextReplacement("bob.dy")
        savePreview("user-directory-miuix-light")
    }

    @Test
    @Config(sdk = [28], qualifiers = "w360dp-h760dp-night-mdpi")
    fun darkMiuixDirectoryUsesReadableIdentityTextAndCompactSelection() {
        showList(true)
        compose.onNodeWithTag("member.selection.101").assertIsOn()
        compose.runOnIdle {
            assertTrue(ModuleTheme.isDark(compose.activity))
            val row = members!!.findViewWithTag<View>("101")
            assertEquals(0xFFF2F2F2.toInt(), row.findViewWithTag<TextView>("member.name").currentTextColor)
            assertEquals(0xFFB0B6BB.toInt(), row.findViewWithTag<TextView>("member.uid").currentTextColor)
        }
        savePreview("user-directory-miuix-dark")
    }

    @Test fun readOnlyMiuixUserListKeepsIdentityRowsWithoutSelectionControls() {
        showList(false)
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).assertCountEquals(0)
        compose.runOnIdle {
            val row = members!!.findViewWithTag<View>("101")
            assertEquals("Alice", row.findViewWithTag<TextView>("member.name").text.toString())
            row.performClick()
            assertEquals(setOf("101"), members!!.selected())
            assertNull(members!!.findViewWithTag<View>("settings.followLists.scope"))
        }
    }

    @Test fun readOnlyAggregateDirectoryKeepsAllFourFiltersAndSearchWithoutCheckboxes() {
        showList(false, true)
        compose.onNodeWithTag("settings.followLists.scope").assertIsDisplayed()
        compose.onNodeWithTag("settings.followLists.searchAll").assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).assertCountEquals(0)
        compose.runOnIdle {
            val scope = members!!.findViewWithTag<Spinner>("settings.followLists.scope")
            assertEquals(listOf("全部", "未关注", "我关注", "关注我"), (0 until scope.count).map { scope.getItemAtPosition(it) })
            scope.setSelection(2)
        }
        compose.runOnIdle {
            val list = members!!.findViewWithTag<ListView>("settings.followLists.members")
            assertEquals(1, list.adapter.count)
            assertEquals("202", (list.adapter.getItem(0) as ProfileListEntry).uid)
            members!!.findViewWithTag<View>("202").performClick()
            assertEquals(setOf("101"), members!!.selected())
        }
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).assertCountEquals(0)
        savePreview("user-directory-miuix-readonly")
    }

    @Test fun constrainedDialogContentKeepsItsListAndMoreVisibleAndCanReachTheLastUser() {
        showList(true, true, 460)
        compose.onNodeWithTag("settings.followLists.search").performTextReplacement("person")
        compose.runOnIdle {
            members!!.findViewWithTag<View>("settings.followLists.searchAll").performClick()
            searchUpdate!!.accept(NativeUserSearch.Update(NativeUserSearch.Page((0 until 40).map {
                ProfileListEntry((1000 + it).toString(), "Person $it", "")
            }, 40, true, "native-session"), "", false))
        }
        compose.waitForIdle()
        savePreview("user-directory-miuix-constrained")
        compose.runOnIdle {
            val root = members!!
            val list = root.findViewWithTag<ListView>("settings.followLists.members")
            val more = MiuixFormControls.viewFor(root.findViewWithTag("settings.followLists.loadMore"))
            assertEquals(460, root.height)
            assertEquals(40, list.adapter.count)
            assertTrue("The user viewport must fit the actual dialog content height: ${list.bottom} <= ${root.height}",
                list.height > 0 && list.bottom <= root.height)
            assertTrue("The more action must remain inside the clipped dialog content: ${more.bottom} <= ${root.height}",
                more.height > 0 && more.bottom <= root.height)
            list.setSelection(39)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val list = members!!.findViewWithTag<ListView>("settings.followLists.members")
            val last = members!!.findViewWithTag<View>("1039")
            assertNotNull("The last user must remain reachable by scrolling the viewport", last)
            val viewport = Rect()
            val userBounds = Rect()
            assertTrue(list.getGlobalVisibleRect(viewport))
            assertTrue(last.getGlobalVisibleRect(userBounds))
            assertTrue("The last user must be fully visible within the viewport", viewport.contains(userBounds))
            assertEquals("The last user must not be vertically clipped", last.height, userBounds.height())
        }
        compose.onNodeWithTag("member.selection.1039").assertIsDisplayed()
        compose.onNodeWithTag("settings.followLists.loadMore").assertIsDisplayed()
    }

    @Test fun actualMiuixDialogKeepsOnlineUsersScrollableAndItsMoreActionVisible() {
        showList(true, true, actualDialog = true)
        compose.onNodeWithTag("settings.followLists.search").performTextReplacement("person")
        compose.runOnIdle {
            members!!.findViewWithTag<View>("settings.followLists.searchAll").performClick()
            searchUpdate!!.accept(NativeUserSearch.Update(NativeUserSearch.Page((0 until 40).map {
                ProfileListEntry((1000 + it).toString(), "Person $it", "")
            }, 40, true, "native-session"), "", false))
        }
        compose.waitForIdle()
        savePreview("user-directory-miuix-actual-dialog")
        compose.runOnIdle {
            val root = members!!
            val list = root.findViewWithTag<ListView>("settings.followLists.members")
            val more = MiuixFormControls.viewFor(root.findViewWithTag("settings.followLists.loadMore"))
            assertTrue(activeDialog!!.isShowing)
            assertEquals(40, list.adapter.count)
            assertTrue("The actual dialog must keep a nonempty user viewport inside its content", list.height > 0 && list.bottom <= root.height)
            assertTrue("The actual dialog must reserve height for its more action: height=${more.height}, bottom=${more.bottom}, content=${root.height}",
                more.height > 0 && more.bottom <= root.height)
            list.setSelection(39)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val list = members!!.findViewWithTag<ListView>("settings.followLists.members")
            val last = members!!.findViewWithTag<View>("1039")
            assertNotNull("The last online user must remain reachable in the actual dialog", last)
            val viewport = Rect()
            val userBounds = Rect()
            assertTrue(list.getGlobalVisibleRect(viewport))
            assertTrue(last.getGlobalVisibleRect(userBounds))
            assertTrue(viewport.contains(userBounds))
            assertEquals("The last actual-dialog user must not be clipped", last.height, userBounds.height())
        }
        compose.onNodeWithTag("settings.followLists.loadMore").assertIsDisplayed()
        compose.onNodeWithTag("member.selection.1039").assertIsDisplayed()
    }

    @Test fun actualMiuixGroupDialogKeepsItsExistingConversationListAndSelectionWithoutUserControls() {
        showList(true, false, actualDialog = true, conversations = true)
        compose.runOnIdle {
            val root = members!!
            val list = root.findViewWithTag<ListView>("settings.followLists.members")
            assertTrue(activeDialog!!.isShowing)
            assertNull(root.findViewWithTag<View>("settings.followLists.scope"))
            assertNull(root.findViewWithTag<View>("settings.followLists.searchAll"))
            assertNull(root.findViewWithTag<View>("settings.followLists.loadMore"))
            assertEquals(2, list.adapter.count)
            assertTrue((list.adapter.getItem(0) as ProfileListEntry).conversation)
            assertTrue("The existing group viewport must fit its actual dialog", list.height > 0 && list.bottom <= root.height)
        }
        compose.onNodeWithTag("member.selection.group-101").assertIsDisplayed().assertIsOn()
        compose.onNodeWithTag("member.selection.group-101").performTouchInput { click() }
        compose.onNodeWithTag("member.selection.group-101").assertIsOff()
        compose.runOnIdle { assertTrue(members!!.selected().isEmpty()) }
        savePreview("group-list-miuix-actual-dialog")
    }

    private fun showList(selectable: Boolean, directoryEnabled: Boolean = selectable, heightLimit: Int? = null,
                         actualDialog: Boolean = false, conversations: Boolean = false) {
        compose.runOnUiThread {
            val entries = if (conversations) listOf(
                ProfileListEntry.conversation("group-101", "Group One", ""),
                ProfileListEntry.conversation("group-202", "Group Two", "")) else listOf(
                ProfileListEntry("101", "Alice", "", "alice.dy"),
                ProfileListEntry("202", "Bob", "", "bob.dy"))
            members = MemberListView(compose.activity, true, entries,
                setOf(if (conversations) "group-101" else "101"), selectable, directoryEnabled,
                Executors.newSingleThreadExecutor(), { _, _ -> null },
                { _: List<ProfileListEntry>, _: Boolean, _: Consumer<NativeUserProfiles.Update> -> AutoCloseable {} },
                object : MemberListView.DirectoryBackend {
                    override fun search(query: String, cursor: Long, searchId: String, updated: Consumer<NativeUserSearch.Update>): AutoCloseable {
                        searchUpdate = updated
                        return AutoCloseable {}
                    }
                    override fun loadRelation(kind: FollowListSnapshot.Kind, loaded: Consumer<Boolean>): AutoCloseable {
                        loaded.accept(true)
                        return AutoCloseable {}
                    }
                    override fun members(kind: FollowListSnapshot.Kind) = if (kind == FollowListSnapshot.Kind.FOLLOWING)
                        listOf(ProfileListEntry("202", "Bob", "", "bob.dy"))
                        else listOf(ProfileListEntry("101", "Alice", "", "alice.dy"))
                })
            members!!.setBackgroundColor(0xFFFFFFFF.toInt())
            if (actualDialog) {
                activeDialog = MiuixDialogBuilder(compose.activity, true).setTitle(if (conversations) "选择群聊" else "用户列表")
                    .setMessage(if (conversations) null else "已聚合联系人、我关注和关注我的用户")
                    .setView(members).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
                activeDialog!!.show()
            } else if (heightLimit == null) compose.activity.setContentView(members)
            else {
                val frame = FrameLayout(compose.activity)
                frame.addView(members, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    (heightLimit * compose.activity.resources.displayMetrics.density).toInt()))
                compose.activity.setContentView(frame)
            }
        }
        compose.waitForIdle()
    }

    private fun savePreview(name: String) {
        compose.waitForIdle()
        val folder = System.getenv("DOUAI_CHAT_SCREENSHOTS") ?: return
        compose.runOnUiThread {
            val view = activeDialog?.window?.decorView ?: compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val directory = File(folder).apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
