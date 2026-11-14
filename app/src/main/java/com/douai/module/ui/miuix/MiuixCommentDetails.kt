package com.douai.module.ui.miuix

import android.content.Context
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The dialog owns the fixed footer and AndroidX owners; only its read-only body scrolls. */
object MiuixCommentDetails {
    @JvmStatic fun data(context: Context, summary: String, prettyJson: String): View =
        body(context, "comment_tools.data") {
            val blocks = remember(prettyJson) { textBlocks(prettyJson) }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).testTag("comment_tools.data.scroll"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            ) {
                item {
                    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)
                        .background(MiuixTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle("摘要")
                        SelectableText(summary, "comment_tools.data.summary")
                    }
                }
                item { SectionTitle("原始 JSON", Modifier.padding(start = 8.dp, bottom = 6.dp)) }
                items(blocks.size) { index ->
                    val shape = RoundedCornerShape(
                        topStart = if (index == 0) 16.dp else 0.dp, topEnd = if (index == 0) 16.dp else 0.dp,
                        bottomStart = if (index == blocks.lastIndex) 16.dp else 0.dp,
                        bottomEnd = if (index == blocks.lastIndex) 16.dp else 0.dp,
                    )
                    Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surface, shape)
                        .padding(horizontal = 16.dp)
                        .padding(top = if (index == 0) 14.dp else 0.dp, bottom = if (index == blocks.lastIndex) 14.dp else 0.dp)) {
                        SelectionContainer {
                            Text(blocks[index], modifier = Modifier.testTag("comment_tools.data.json.$index"),
                                color = MiuixTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp, lineHeight = 19.sp)
                        }
                    }
                }
            }
        }

    @JvmStatic fun transcript(context: Context, text: String): View = body(context, "comment_tools.transcript") {
        val blocks = remember(text) { textBlocks(text) }
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).testTag("comment_tools.transcript.scroll"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 6.dp)) {
            items(blocks.size) { index -> SelectableText(blocks[index], "comment_tools.transcript.text.$index") }
        }
    }

    private fun body(context: Context, key: String, content: @Composable () -> Unit): View =
        ComposeView(context.formComposeContext()).apply {
            (this.context as? HostComposeContext)?.bindConfiguration(this)
            id = View.generateViewId()
            tag = key
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { HostSettingsTheme { content() } }
        }

    @Composable private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
        Text(text, modifier = modifier, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontWeight = FontWeight.Medium, fontSize = 14.sp)
    }

    @Composable private fun SelectableText(text: String, key: String) {
        SelectionContainer {
            Text(text, modifier = Modifier.testTag(key), color = MiuixTheme.colorScheme.onSurface,
                fontSize = 15.sp, lineHeight = 22.sp)
        }
    }

    // A serialized video resource can contain a huge single line. Keep such data selectable
    // and scrollable without shaping the entire document every time the first block is drawn.
    internal fun textBlocks(text: String): List<String> {
        if (text.length <= 2048) return listOf(text)
        val out = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            var end = (start + 2048).coerceAtMost(text.length)
            if (end < text.length) {
                var nl = end - 1
                while (nl >= start && text[nl] != '\n') nl--
                if (nl >= start) end = nl + 1
                else if (Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
            }
            out.add(text.substring(start,end))
            start=end
        }
        return out
    }
}
