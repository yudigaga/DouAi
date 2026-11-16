package com.douai.module.ui.miuix
import android.app.AlertDialog
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],qualifiers="w420dp-h900dp-mdpi")
class PlaybackReadingMiuixTest {
 @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
 @Test fun loadingInitializesFullRangeAndOrdinaryRendersPreserveExplicitRange(){
  lateinit var body:MiuixPlaybackReading;lateinit var dialog:AlertDialog
  val actions=object:MiuixPlaybackReading.Actions {
   override fun chapters(){};override fun track(){};override fun export(){};override fun model(){}
   override fun ask(question:String,from:String,to:String){};override fun cancel(){};override fun row(index:Int){};override fun citation(index:Int){}
  }
  compose.runOnUiThread {
   body=MiuixPlaybackReading(compose.activity,actions)
   body.render("加载",emptyList(),false,true,"模型","","",emptyList(),1)
   dialog=MiuixDialogBuilder(compose.activity,true).setTitle("阅读").setView(body.view).show()
  }
  compose.waitForIdle()
  val rows=listOf(MiuixPlaybackReading.Row("1","甲"),MiuixPlaybackReading.Row("2","乙"))
  try {
   compose.runOnUiThread{body.render("已读取",rows,true,false,"模型","","",emptyList(),1)}
   compose.onAllNodes(hasSetTextAction())[2].assertTextContains("2")
   compose.onAllNodes(hasSetTextAction())[2].performTextReplacement("1")
   compose.runOnUiThread{body.render("取消",rows,true,false,"模型","","",emptyList(),1)}
   compose.onAllNodes(hasSetTextAction())[2].assertTextContains("1")
  }finally{compose.runOnUiThread{dialog.dismiss();body.close()}}
 }
}
