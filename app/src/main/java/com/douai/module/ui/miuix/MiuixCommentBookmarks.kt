package com.douai.module.ui.miuix

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import java.util.function.Consumer
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField

class MiuixCommentBookmarks(context:Context, rows:List<Entry>, selected:Consumer<String>):AutoCloseable {
    class Entry(val id:String,val author:String,val body:String,val context:String)
    private var closed=false
    val view:View=ComposeView(context.formComposeContext()).apply {
        tag="comment.bookmarks.root";id=View.generateViewId()
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        (this.context as? HostComposeContext)?.bindConfiguration(this)
        setContent { HostSettingsTheme {
            var query by remember { mutableStateOf("") }
            val q=query.trim().lowercase(Locale.ROOT)
            val visible=remember(q,rows){rows.filter { q.isEmpty()||listOf(it.author,it.body,it.context).any { value->value.lowercase(Locale.ROOT).contains(q) } }}
            Column(Modifier.fillMaxWidth().heightIn(max=460.dp).padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                TextField(value=query,onValueChange={query=it.take(128)},label="搜索作者、正文或上下文",useLabelAsPlaceholder=true,
                    singleLine=true,modifier=Modifier.fillMaxWidth().testTag("comment.bookmarks.query"))
                Text("本机当前账号 · ${visible.size} / ${rows.size} 条 · 最多500条",fontSize=13.sp)
                LazyColumn(Modifier.fillMaxWidth().weight(1f,fill=false).heightIn(max=330.dp)) {
                    if(visible.isEmpty())item { Text(if(rows.isEmpty())"还没有评论收藏。长按评论，打开「评论工具」收藏所选评论。" else "没有找到匹配收藏",modifier=Modifier.padding(vertical=12.dp).testTag("comment.bookmarks.empty")) }
                    items(visible,key={it.id}){row->BasicComponent(title=row.author,summary=row.body,onClick={if(!closed)selected.accept(row.id)},modifier=Modifier.testTag("comment.bookmarks.row.${row.id}"))}
                }
            }
        } }
    }
    override fun close(){closed=true;(view as ComposeView).disposeComposition()}
}
