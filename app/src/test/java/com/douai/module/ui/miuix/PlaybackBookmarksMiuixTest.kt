package com.douai.module.ui.miuix

import android.app.AlertDialog
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.test.onAllNodesWithTag

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],qualifiers="w360dp-h720dp-mdpi")
class PlaybackBookmarksMiuixTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun distinguishAndFindSameWorkMarksByAuthorDescriptionAndMemo(){
        lateinit var dialog:AlertDialog
        lateinit var surface:MiuixPlaybackBookmarks
        var clicked=""
        compose.runOnUiThread {
            val a=compose.activity; val absent=File(a.noBackupFilesDir,"no-cover")
            val rows=listOf(
                MiuixPlaybackBookmarks.Row("a","123","00:11","青色","林野","秋天调色",absent),
                MiuixPlaybackBookmarks.Row("b","123","00:24","曲线","林野","秋天调色",absent),
                MiuixPlaybackBookmarks.Row("c","234","01:33","旧备注","","",absent))
            surface=MiuixPlaybackBookmarks(a,rows){clicked=it}
            dialog=MiuixDialogBuilder(a,true).setTitle("我的时间点书签").setView(surface.view).create()
            dialog.show()
        }
        try {
            for(q in listOf("林野","调色")){
                compose.onNodeWithTag("bookmark.query").performTextReplacement(q)
                compose.onNodeWithText("仅保存在本机当前账号 · 2 / 3 条").assertIsDisplayed()
            }
            compose.onNodeWithTag("bookmark.query").performTextReplacement("曲线")
            compose.onNodeWithTag("bookmark.row.b").performClick()
            compose.runOnIdle {assertEquals("b",clicked)}
            compose.onNodeWithTag("bookmark.query").performTextReplacement("旧备注")
            compose.onNodeWithText("作品 234").assertIsDisplayed()
        }finally{compose.runOnUiThread {dialog.dismiss();surface.close()}}
    }

    @Test @Config(sdk=[28]) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun storedCoverAuthorAndDescriptionRenderTogetherWithoutNetworking(){
        lateinit var dialog:AlertDialog
        lateinit var surface:MiuixPlaybackBookmarks
        compose.runOnUiThread {
            val a=compose.activity;val file=File(a.noBackupFilesDir,"bookmark-cover-fixture.jpg")
            val bitmap=Bitmap.createBitmap(96,128,Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0xff557d7b.toInt())
            Canvas(bitmap).drawRect(0f,74f,96f,128f,Paint().apply {color=0xffb7a276.toInt()})
            file.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,75,it))};bitmap.recycle()
            val bounds=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
            android.graphics.BitmapFactory.decodeFile(file.path,bounds)
            assertEquals(96,bounds.outWidth);assertEquals(128,bounds.outHeight);assertTrue(file.length()<=12288)
            surface=MiuixPlaybackBookmarks(a,listOf(
                MiuixPlaybackBookmarks.Row("a","123","00:11","调色参数","林野","秋天的山路与调色过程",file),
                MiuixPlaybackBookmarks.Row("b","123","00:24","曲线细节","林野","秋天的山路与调色过程",file),
                MiuixPlaybackBookmarks.Row("c","234","01:33","旧书签","","",File(a.noBackupFilesDir,"none")))){}
            dialog=MiuixDialogBuilder(a,true).setTitle("我的时间点书签").setView(surface.view).setNegativeButton("关闭",null).show()
        }
        try {
            compose.waitUntil(5000){compose.onAllNodesWithTag("bookmark.cover.a",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("bookmark.cover.a",useUnmergedTree=true).assertIsDisplayed()
            compose.onNodeWithText("00:11  调色参数").assertIsDisplayed()
            System.getenv("DOUAI_CHAT_SCREENSHOTS")?.let { path ->
                compose.runOnUiThread {
                    val dir=File(path);assertTrue(dir.isDirectory || dir.mkdirs())
                    val view=dialog.window!!.decorView
                    val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(image))
                    File(dir,"bookmark-details.png").outputStream().use {assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it))};image.recycle()
                }
            }
        } finally {compose.runOnUiThread {dialog.dismiss();surface.close()}}
    }
}
