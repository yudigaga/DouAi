package com.douai.module.ui.miuix

import android.content.Context
import android.view.View
import androidx.compose.foundation.clickable
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
import com.douai.module.feature.TemporaryFeedRuleStore
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.function.Consumer
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MiuixTemporaryFeedRules(context:Context, private val rows:List<TemporaryFeedRuleStore.Rule>, private val select:Consumer<TemporaryFeedRuleStore.Rule>){
    val view:View=ComposeView(context.formComposeContext()).apply{
        id=View.generateViewId();tag="temporary.rules.root"
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        (this.context as? HostComposeContext)?.bindConfiguration(this)
        setContent{HostSettingsTheme{
            var query by remember{mutableStateOf("")}
            val q=query.trim().lowercase(Locale.ROOT)
            val filtered=remember(q,rows){rows.filter{q.isEmpty()||q in (it.name+" "+it.id).lowercase(Locale.ROOT)}}
            Column(Modifier.fillMaxWidth().heightIn(max=480.dp).padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                TextField(value=query,onValueChange={query=it.take(128)},label="搜索作者、配乐或ID",useLabelAsPlaceholder=true,singleLine=true)
                Text("仅过滤本机后续主推荐 · ${filtered.size} / ${rows.size} 条",fontSize=13.sp,color=MiuixTheme.colorScheme.onSurfaceVariantSummary)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max=350.dp)){
                    if(filtered.isEmpty())item{Text("没有生效中的临时规则",Modifier.padding(vertical=16.dp))}
                    items(filtered,key={it.key()}){rule->Column(Modifier.fillMaxWidth().clickable{select.accept(rule)}.padding(vertical=12.dp)){
                        Text((if(rule.kind==TemporaryFeedRuleStore.Kind.AUTHOR)"作者 · " else "配乐 · ")+rule.name)
                        Text("至 "+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(rule.expiresAt)),fontSize=13.sp,color=MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }}
                }
            }
        }}
    }
    fun close(){(view as ComposeView).disposeComposition()}
}
