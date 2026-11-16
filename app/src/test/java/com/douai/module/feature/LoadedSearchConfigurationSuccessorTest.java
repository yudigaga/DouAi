package com.douai.module.feature;

import android.app.AlertDialog;
import android.content.Context;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class LoadedSearchConfigurationSuccessorTest {
    @Test public void newerActionMustFinishTheDialogDismissalEvenAfterDisabledOptionsWereAccepted() throws Exception {
        Method factory = ConfigurationReconciliationActionTest.class.getDeclaredMethod("searchHook");
        factory.setAccessible(true);
        try (LoadedCommentSearchHook hook = (LoadedCommentSearchHook) factory.invoke(null)) {
            hook.setOptions(true, false);
            FailingDialog dialog = new FailingDialog(RuntimeEnvironment.getApplication());
            Field field = LoadedCommentSearchHook.class.getDeclaredField("dialog");
            field.setAccessible(true); field.set(hook, dialog);
            CompletableFuture<Void> first = hook.prepareOptions(false, false).apply(() -> true).toCompletableFuture();
            ShadowLooper.idleMainLooper();
            assertTrue(first.isCompletedExceptionally());
            assertSame(dialog, field.get(hook));
            // The coordinator replaces an action on any related COMMENT_TOOLS change, even if these flags match.
            CompletableFuture<Void> latest = hook.prepareOptions(false, false).apply(() -> true).toCompletableFuture();
            ShadowLooper.idleMainLooper();
            assertTrue(latest.isDone());
            assertFalse(latest.isCompletedExceptionally());
            assertEquals(2, dialog.calls);
            assertNull(field.get(hook));
        }
    }

    private static final class FailingDialog extends AlertDialog {
        int calls;
        FailingDialog(Context context) { super(context); }
        @Override public void dismiss() {
            if (++calls == 1) throw new IllegalStateException("local dismiss failed before completion");
            super.dismiss();
        }
    }
}
