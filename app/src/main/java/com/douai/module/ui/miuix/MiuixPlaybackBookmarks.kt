package com.douai.module.ui.miuix

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.Locale
import java.util.function.Consumer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Local-only reader. Only visible rows decode their own small JPEG on an IO thread. */
class MiuixPlaybackBookmarks(context: Context, private val rows: List<Row>, private val select: Consumer<String>) {
    class Row(val id: String, val aid: String, val time: String, val note: String, val author: String,
              val description: String, val thumbnail: File) {
        internal val search = listOf(aid,time,note,author,description).joinToString("\n").lowercase(Locale.ROOT)
    }
    private val updated = mutableStateMapOf<String,Int>()
    fun thumbnailReady(aid:String){updated[aid]=(updated[aid]?:0)+1}
    val view:View = ComposeView(context.formComposeContext()).apply {
        tag="bookmark.list.root";id=View.generateViewId()
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        (this.context as? HostComposeContext)?.bindConfiguration(this)
        setContent {
            HostSettingsTheme {
                var query by remember { mutableStateOf("") }
                val needle=query.trim().lowercase(Locale.ROOT)
                val visible=remember(needle,rows){ if(needle.isEmpty())rows else rows.filter { needle in it.search } }
                val keyboard=LocalSoftwareKeyboardController.current
                Column(Modifier.fillMaxWidth().heightIn(max=480.dp).padding(horizontal=16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)){
                    TextField(value=query,onValueChange={query=it.take(128)},label="搜索作者、文案或备注",
                        useLabelAsPlaceholder=true,singleLine=true,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),
                        keyboardActions=KeyboardActions(onSearch={keyboard?.hide()}),modifier=Modifier.fillMaxWidth().testTag("bookmark.query"))
                    Text("仅保存在本机当前账号 · ${visible.size} / ${rows.size} 条",fontSize=13.sp,
                        color=MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    LazyColumn(Modifier.fillMaxWidth().weight(1f,fill=false).heightIn(max=340.dp)) {
                        if(visible.isEmpty())item {
                            Text(if(rows.isEmpty())"还没有书签。在视频上选择「记下这一刻」即可保存。" else "没有找到匹配的书签",
                                modifier=Modifier.padding(vertical=16.dp))
                        }
                        items(visible,key={it.id}){ item->
                            val revision=updated[item.aid]?:0
                            val bitmap by produceState<Bitmap?>(null,item.thumbnail,revision){
                                value=withContext(Dispatchers.IO){
                                    val file=item.thumbnail
                                    try {
                                        if(file.length() !in 1L..12288L)return@withContext null
                                        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                                        BitmapFactory.decodeFile(file.path,bounds)
                                        if(bounds.outWidth !in 1..128 || bounds.outHeight !in 1..128)null else BitmapFactory.decodeFile(file.path)
                                    } catch(_:RuntimeException){null}
                                }
                            }
                            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().clickable { select.accept(item.id) }
                                .padding(vertical=8.dp).testTag("bookmark.row."+item.id),verticalAlignment=Alignment.CenterVertically){
                                Box(Modifier.size(54.dp,64.dp).clip(RoundedCornerShape(8.dp))
                                    .background(MiuixTheme.colorScheme.secondaryContainer),contentAlignment=Alignment.Center){
                                    bitmap?.let {Image(it.asImageBitmap(),null,Modifier.fillMaxSize().testTag("bookmark.cover."+item.id),contentScale=ContentScale.Crop)}
                                        ?: Text("无封面",fontSize=11.sp,color=MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){
                                    Text(item.time+(if(item.note.isBlank())"" else "  "+item.note),fontWeight=FontWeight.SemiBold,
                                        maxLines=2,overflow=TextOverflow.Ellipsis)
                                    if(item.author.isNotBlank())Text("@"+item.author,fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                                    Text(item.description.ifBlank { "作品 "+item.aid },fontSize=13.sp,maxLines=2,
                                        overflow=TextOverflow.Ellipsis,color=MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    fun close(){(view as ComposeView).disposeComposition()}
}
