package com.douai.module.ui.miuix

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.function.Consumer
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** One persistent editor and list: new pages update rows without recreating input or the dialog. */
@OptIn(ExperimentalLayoutApi::class)
class MiuixCommentSearch(
    context: Context,
    private val changed: Consumer<String>,
    private val selected: Consumer<String>,
    private val loadMore: Runnable,
) : AutoCloseable {
    class Entry(val id: String, val title: String, val body: String)
    private var draft by mutableStateOf("")
    private var status by mutableStateOf("")
    private var entries by mutableStateOf<List<Entry>>(emptyList())
    private var canLoad by mutableStateOf(false)
    private var loading by mutableStateOf(false)
    private var caption by mutableStateOf("加载更多评论")
    private var transcripts by mutableStateOf(false)
    private var closed = false
    private var filters by mutableStateOf(false)
    private var media by mutableStateOf(0)
    private var onlyAuthor by mutableStateOf(false)
    private var onlyDigged by mutableStateOf(false)
    private var order by mutableStateOf(0)
    val view: View = ComposeView(context.formComposeContext()).apply {
        tag = "comment.search.root"
        id = View.generateViewId()
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        (this.context as? HostComposeContext)?.bindConfiguration(this)
        setContent {
            HostSettingsTheme {
                val keyboard = LocalSoftwareKeyboardController.current
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 460.dp).padding(horizontal = 16.dp)
                        .testTag("comment.search.root"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextField(
                        value = draft,
                        onValueChange = { value ->
                            if (!closed) {
                                val next = value.take(128)
                                if (draft != next) { draft = next; changed.accept(next) }
                            }
                        },
                        label = if (transcripts) "搜索评论或已有转写" else "搜索评论",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth().testTag("comment.search.query"),
                    )
                    Text(status, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.fillMaxWidth().testTag("comment.search.status"))
                    TextButton(text=if(filters) "收起筛选" else "筛选与排序",onClick={filters=!filters},
                        modifier=Modifier.testTag("comment.search.filters"))
                    if(filters) {
                        FlowRow(Modifier.weight(1f,fill=false).fillMaxWidth().heightIn(max=160.dp).verticalScroll(rememberScrollState())) {
                            listOf(1 to "图片",2 to "语音",4 to "视频").forEach { (bit,label)->
                                TextButton(text=label+if(media and bit!=0)" ✓" else "",onClick={media=media xor bit;changed.accept(draft)})
                            }
                            TextButton(text="作者发布"+if(onlyAuthor)" ✓" else "",onClick={onlyAuthor=!onlyAuthor;changed.accept(draft)})
                            TextButton(text="作者赞过"+if(onlyDigged)" ✓" else "",onClick={onlyDigged=!onlyDigged;changed.accept(draft)})
                            listOf("原顺序","点赞最多","最新","最早").forEachIndexed { index,label->
                                TextButton(text=label+if(order==index)" ✓" else "",onClick={order=index;changed.accept(draft)})
                            }
                        }
                        Text("媒体多选任一命中；其他条件同时满足。未知点赞/时间排末尾。",fontSize=12.sp)
                    }
                    LazyColumn(
                        Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(max = 300.dp)
                            .testTag("comment.search.results"),
                    ) {
                        if (entries.isEmpty()) item {
                            BasicComponent(
                                title = if (draft.isBlank()) "暂无可查找的评论" else "未找到匹配评论",
                                summary = "可继续加载评论后查找",
                                modifier = Modifier.testTag("comment.search.empty"),
                            )
                        } else items(entries, key = { it.id }) { row ->
                            BasicComponent(
                                title = row.title,
                                summary = row.body,
                                onClick = { if (!closed) selected.accept(row.id) },
                                modifier = Modifier.testTag("comment.search.row.${row.id}"),
                            )
                        }
                    }
                    TextButton(
                        text = caption,
                        enabled = canLoad && !loading && !closed,
                        onClick = { if (!closed && canLoad && !loading) loadMore.run() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("comment.search.more"),
                    )
                }
            }
        }
    }

    fun query(): String = draft
    fun mediaMask(): Int = media
    fun authorOnly(): Boolean = onlyAuthor
    fun diggedOnly(): Boolean = onlyDigged
    fun sortOrder(): Int = order
    fun setTranscriptSearch(value: Boolean) { transcripts = value }
    fun render(status: String, rows: List<Entry>, canLoadMore: Boolean, loading: Boolean, loadCaption: String) {
        if (closed) return
        this.status = status
        entries = rows.toList()
        canLoad = canLoadMore
        this.loading = loading
        caption = loadCaption
    }
    override fun close() {
        closed = true
        (view as ComposeView).disposeComposition()
    }
}
