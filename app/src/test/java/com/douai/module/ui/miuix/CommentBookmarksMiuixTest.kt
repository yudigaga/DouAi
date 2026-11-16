package com.douai.module.ui.miuix
import android.app.AlertDialog
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,35],qualifiers="w360dp-h640dp-night-mdpi")
class CommentBookmarksMiuixTest {
 @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
 private var dialog:AlertDialog?=null
 private var body:MiuixCommentBookmarks?=null
 @After fun close(){compose.runOnUiThread{dialog?.dismiss();body?.close()}}
 @Test fun contextSearchSelectsStableAidCidAndKeepsExcerptLocal(){
  var selected=""
  compose.runOnUiThread {
   body=MiuixCommentBookmarks(compose.activity,listOf(
    MiuixCommentBookmarks.Entry("21:31","甲","第一条","回复上下文：牛奶"),
    MiuixCommentBookmarks.Entry("22:32","乙","纯媒体评论","没有上下文")),{selected=it})
   dialog=MiuixDialogBuilder(compose.activity,true).setTitle("评论收藏").setView(body!!.view).show()
  }
  compose.onNodeWithTag("comment.bookmarks.query").performTextReplacement("牛奶")
  compose.onNodeWithTag("comment.bookmarks.row.22:32").assertDoesNotExist()
  compose.onNodeWithTag("comment.bookmarks.row.21:31").performClick()
  compose.runOnIdle{assertEquals("21:31",selected)}
  compose.onNodeWithTag("comment.bookmarks.query").performTextReplacement("没有结果")
  compose.onNodeWithTag("comment.bookmarks.empty").assertIsDisplayed()
 }
}
