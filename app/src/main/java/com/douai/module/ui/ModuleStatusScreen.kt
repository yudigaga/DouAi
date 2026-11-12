package com.douai.module.ui

import android.content.ActivityNotFoundException
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.douai.module.BuildConfig
import com.douai.module.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** Used only in the module process; host dialogs retain their own UI and settings store. */
class ModuleStatusScreen(private val activity: ComponentActivity) {
    private var currentScan by mutableStateOf(ScanStatus.pending())
    private var frameworkConnected by mutableStateOf(false)
    private var frameworkMessage by mutableStateOf("")
    private var frameworkError by mutableStateOf<String?>(null)
    private var hiddenLauncher by mutableStateOf(LauncherVisibility.isHidden(activity))

    fun createView(): View = ComposeView(activity).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            DouAiTheme {
                SyncWindowTheme()
                StatusContent()
            }
        }
    }

    fun setScanStatus(status: ScanStatus) {
        currentScan = status
    }

    fun setFrameworkSyncStatus(connected: Boolean, message: String?, error: String?) {
        frameworkConnected = connected
        frameworkMessage = message.orEmpty()
        frameworkError = error
    }

    @Suppress("DEPRECATION")
    @Composable
    private fun SyncWindowTheme() {
        val dark = isSystemInDarkTheme()
        val surface = MiuixTheme.colorScheme.surface.toArgb()
        SideEffect {
            // This screen only belongs to the module activity; never theme a host window.
            val window = activity.window
            window.setBackgroundDrawable(ColorDrawable(surface))
            window.statusBarColor = surface
            window.navigationBarColor = surface
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    @Composable
    private fun StatusContent() {
        Scaffold(modifier = Modifier.fillMaxSize()) { insets ->
            Column(
                modifier = Modifier.fillMaxSize()
                    .padding(insets)
                    .consumeWindowInsets(insets)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.padding(horizontal = 28.dp)) {
                    Text(
                        text = stringResource(R.string.module_brand),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.module_version, BuildConfig.VERSION_NAME),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = stringResource(R.string.module_host_version),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 14.sp,
                    )
                }
                Spacer(Modifier.height(8.dp))
                SmallTitle(stringResource(R.string.module_status))
                SelectionContainer {
                    Column(Modifier.background(MiuixTheme.colorScheme.background)) {
                        BasicComponent(
                            title = stringResource(
                                if (frameworkConnected) R.string.module_framework_connected
                                else R.string.module_framework_disconnected,
                            ),
                            summary = listOfNotNull(
                                frameworkMessage.ifBlank {
                                    stringResource(R.string.module_framework_waiting)
                                },
                                frameworkError?.takeIf { it.isNotBlank() },
                            ).joinToString("\n"),
                        )
                        BasicComponent(
                            title = currentScan.title,
                            summary = currentScan.detail + if (currentScan.restartRequired) {
                                "\n" + stringResource(R.string.module_restart_required)
                            } else "",
                        )
                    }
                }
                SmallTitle(stringResource(R.string.module_settings_title))
                TextButton(
                    text = stringResource(R.string.module_open_host),
                    onClick = ::openHost,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp),
                )
                SwitchPreference(
                    title = "隐藏桌面图标",
                    checked = hiddenLauncher,
                    onCheckedChange = { hidden ->
                        try {
                            LauncherVisibility.setHidden(activity, hidden)
                            hiddenLauncher = LauncherVisibility.isHidden(activity)
                        } catch (_: RuntimeException) {
                            Toast.makeText(activity, "无法修改桌面图标状态", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                BasicComponent(title = "关于", summary = "DouAi ${BuildConfig.VERSION_NAME}",
                    onClick = { AboutDialog.create(activity, true).show() })
            }
        }
    }

    private fun openHost() {
        try {
            val intent = activity.packageManager.getLaunchIntentForPackage("com.ss.android.ugc.aweme")
            if (intent != null) {
                activity.startActivity(intent)
                return
            }
        } catch (_: ActivityNotFoundException) {
            // The host may have been removed between resolving and launching it.
        } catch (_: SecurityException) {
            // Restricted profiles may expose the package but disallow its launch.
        }
        Toast.makeText(activity, R.string.module_host_unavailable, Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun DouAiTheme(content: @Composable () -> Unit) {
    MiuixTheme(
        colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
