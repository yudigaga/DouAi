package com.douai.module.ui.miuix

import android.widget.FrameLayout
import android.widget.TextView
import com.douai.module.config.ComponentHideSettings
import com.douai.module.config.ComponentHideSettings.Component
import com.douai.module.config.CustomViewLocator
import com.douai.module.config.FeatureSettings
import com.douai.module.config.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ComponentHideEditorTest {
    @Test
    fun firstIndividualEditPreservesLegacyValuesAndMasterState() {
        val store = MemoryStore()
        store.putBoolean("hide_interface", true)
        val editor = ComponentHideEditor(store)

        editor.setAlpha(Component.PROGRESS.key, -2)

        assertTrue(editor.settings.configured)
        assertTrue(editor.settings.enabled)
        assertEquals(-2, editor.settings.individualAlpha(Component.PROGRESS))
        assertEquals(-1, editor.settings.individualAlpha(Component.COMMENT))
        assertEquals(255, editor.settings.individualAlpha(Component.TOP_NAV))

        val disabled = ComponentHideEditor(MemoryStore())
        disabled.setBoolean(ComponentHideSettings.SHOW_ON_PAUSE, false)
        assertFalse(disabled.settings.enabled)
    }

    @Test
    fun individualEditDisablesUniformButKeepsOtherAlphaValues() {
        val store = MemoryStore()
        store.putBoolean(ComponentHideSettings.ENABLED, true)
        store.putBoolean(ComponentHideSettings.UNIFORM_ENABLED, true)
        store.putString(ComponentHideSettings.UNIFORM_ALPHA, "-2")
        store.putString(Component.COMMENT.key, "-1")
        val editor = ComponentHideEditor(store)

        editor.setAlpha(Component.PROGRESS.key, 255)

        assertFalse(editor.settings.uniformEnabled)
        assertEquals(-1, editor.settings.alpha(Component.COMMENT))
        assertEquals(255, editor.settings.alpha(Component.PROGRESS))
        assertEquals(-2, editor.settings.uniformAlpha)
        editor.setBoolean(ComponentHideSettings.UNIFORM_ENABLED, true)
        editor.setAlpha(ComponentHideSettings.UNIFORM_ALPHA, 0)
        assertTrue(editor.settings.uniformEnabled)
        assertEquals(0, editor.settings.alpha(Component.COMMENT))
    }

    @Test
    fun deletingAfterAnotherViewIsAddedPreservesTheLatestStore() {
        val context = RuntimeEnvironment.getApplication()
        val root = FrameLayout(context)
        val first = TextView(context)
        val second = TextView(context)
        root.addView(first)
        root.addView(second)
        val removed = CustomViewLocator.capture(root, first)
        val retained = CustomViewLocator.capture(root, second)
        val store = MemoryStore()
        store.putString(CustomViewLocator.SETTINGS_KEY, CustomViewLocator.serialize(listOf(removed)))
        val editor = ComponentHideEditor(store)
        store.putString(CustomViewLocator.SETTINGS_KEY,
            CustomViewLocator.serialize(listOf(removed, retained)))

        editor.deleteCustomView(removed)

        assertEquals(listOf(retained.identityKey()), editor.settings.customViews.map { it.identityKey() })
        assertEquals(listOf(retained.identityKey()),
            ComponentHideSettings.readFrom(store).customViews.map { it.identityKey() })
        editor.deleteCustomView(removed)
        assertEquals(1, editor.settings.customViews.size)
    }

    @Test
    fun customAlphaEditsPreserveNewRulesUniformModeAndDeletedRules() {
        val context = RuntimeEnvironment.getApplication()
        val root = FrameLayout(context)
        val first = TextView(context).apply { id = android.R.id.title }
        val second = TextView(context).apply { id = android.R.id.summary }
        root.addView(first)
        root.addView(second)
        val edited = CustomViewLocator.capture(root, first)!!
        val retained = CustomViewLocator.capture(root, second)!!
        val store = MemoryStore()
        store.putBoolean(ComponentHideSettings.ENABLED, true)
        store.putBoolean(ComponentHideSettings.UNIFORM_ENABLED, true)
        store.putString(CustomViewLocator.SETTINGS_KEY, CustomViewLocator.serialize(listOf(edited)))
        val editor = ComponentHideEditor(store)
        store.putString(CustomViewLocator.SETTINGS_KEY, CustomViewLocator.serialize(listOf(edited, retained)))

        editor.setCustomAlpha(edited, 128)

        assertEquals(listOf(128, -2), editor.settings.customViews.map { it.alpha })
        assertEquals(listOf(edited.identityKey(), retained.identityKey()),
            editor.settings.customViews.map { it.identityKey() })
        assertTrue(editor.settings.uniformEnabled)
        editor.deleteCustomView(edited)
        editor.setCustomAlpha(edited, 0)
        assertEquals(listOf(retained.identityKey()), editor.settings.customViews.map { it.identityKey() })
        val saved = store.getString(CustomViewLocator.SETTINGS_KEY, "")
        for (value in listOf(-3, 256, Int.MAX_VALUE)) {
            try { editor.setCustomAlpha(retained, value); org.junit.Assert.fail("invalid alpha accepted") }
            catch (_: IllegalArgumentException) { }
            assertEquals(saved, store.getString(CustomViewLocator.SETTINGS_KEY, ""))
        }
    }

    private class MemoryStore : SettingsStore {
        private val values = mutableMapOf<String, Any>()
        private var features = FeatureSettings.defaults()
        override fun load(): FeatureSettings = features
        override fun save(settings: FeatureSettings) { features = settings }
        override fun getBoolean(key: String, fallback: Boolean): Boolean =
            values[key] as? Boolean ?: fallback
        override fun getString(key: String, fallback: String?): String? =
            values[key] as? String ?: fallback
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun putString(key: String, value: String) { values[key] = value }
    }
}
