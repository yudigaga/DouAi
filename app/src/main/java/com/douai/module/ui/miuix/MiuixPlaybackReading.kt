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
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Reading controls stay in the host Compose environment, including empty and failure states. */
class MiuixPlaybackReading(context: Context, private val actions: Actions): AutoCloseable {
    interface Actions {
        fun chapters()
        fun track()
        fun export()
        fun model()
        fun ask(question: String, from: String, to: String)
        fun cancel()
        fun row(index: Int)
        fun citation(index: Int)
    }
    class Row(val title: String, val text: String)
    private var rows by mutableStateOf<List<Row>>(emptyList())
    private var citations by mutableStateOf<List<Row>>(emptyList())
    private var status by mutableStateOf("")
    private var model by mutableStateOf("选择模型")
    private var answer by mutableStateOf("")
    private var coverage by mutableStateOf("")
    private var subtitle by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var revision by mutableStateOf(0)
    private var closed=false
    val view: View = ComposeView(context.formComposeContext()).apply {
        tag="playback.reading.root"; id=View.generateViewId()
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        (this.context as? HostComposeContext)?.bindConfiguration(this)
        setContent { HostSettingsTheme {
            var query by remember { mutableStateOf("") }
            var question by remember { mutableStateOf("") }
            var from by remember(revision,subtitle) { mutableStateOf("1") }
            var to by remember(revision,subtitle) { mutableStateOf(rows.size.toString()) }
            val filtered=rows.withIndex().filter { query.trim().isEmpty() || it.value.text.contains(query.trim(),true) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=560.dp).padding(horizontal=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                item {
                    Row(Modifier.fillMaxWidth()) {
                        TextButton("章节",onClick={actions.chapters()},modifier=Modifier.weight(1f).heightIn(min=48.dp))
                        TextButton("选择字幕",onClick={actions.track()},modifier=Modifier.weight(1f).heightIn(min=48.dp))
                    }
                    Text(status,fontSize=13.sp,color=MiuixTheme.colorScheme.onSurfaceVariantSummary,modifier=Modifier.testTag("reading.status"))
                    TextField(query,{query=it.take(128)},label="搜索原话（仅筛选显示）",singleLine=true,modifier=Modifier.fillMaxWidth())
                    Text("命中 ${filtered.size}，显示前200条；导出始终包含整条已读取字幕轨",fontSize=12.sp)
                    TextButton("导出字幕全文 TXT / Markdown",enabled=subtitle&&!busy,onClick={actions.export()},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp))
                    TextButton(model,onClick={actions.model()},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp))
                    Text("字幕仅在点击发送后交给所选模型；超出16000字符请明确缩小句号范围。",fontSize=13.sp)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextField(from,{from=it.take(5)},label="起始句号",singleLine=true,modifier=Modifier.weight(1f))
                        TextField(to,{to=it.take(5)},label="结束句号",singleLine=true,modifier=Modifier.weight(1f))
                    }
                    TextField(question,{question=it.take(2000)},label="用字幕提问",modifier=Modifier.fillMaxWidth())
                    TextButton("发送字幕提问",enabled=subtitle&&!busy&&question.isNotBlank(),onClick={actions.ask(question,from,to)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp))
                    if(busy)TextButton("取消当前读取 / 请求 / 导出",onClick={actions.cancel()},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp))
                    if(coverage.isNotBlank())Text(coverage,fontSize=13.sp,modifier=Modifier.testTag("reading.coverage"))
                    if(answer.isNotBlank())BasicComponent(title="AI回答",summary=answer,modifier=Modifier.testTag("reading.answer"))
                }
                items(citations.withIndex().toList(),key={"ref${it.index}"}) { ref ->
                    BasicComponent(title=ref.value.title,summary=ref.value.text,onClick={actions.citation(ref.index)},modifier=Modifier.testTag("reading.citation.${ref.index}"))
                }
                items(filtered.take(200),key={"row${it.index}"}) { entry ->
                    BasicComponent(title=entry.value.title,summary=entry.value.text,onClick={actions.row(entry.index)},modifier=Modifier.testTag("reading.row.${entry.index}"))
                }
                if(rows.isEmpty())item{BasicComponent(title="没有可读内容",summary="请选择当前作品提供的字幕轨；画面文字不在读取范围内")}
            }
        } }
    }
    fun render(status:String, rows:List<Row>, subtitle:Boolean, busy:Boolean, model:String, coverage:String, answer:String, citations:List<Row>, generation:Int) {
        if(closed)return
        this.status=status;this.rows=rows.toList();this.subtitle=subtitle;this.busy=busy;this.model=model
        this.coverage=coverage;this.answer=answer;this.citations=citations.toList();this.revision=generation
    }
    override fun close(){closed=true;(view as ComposeView).disposeComposition()}
}
