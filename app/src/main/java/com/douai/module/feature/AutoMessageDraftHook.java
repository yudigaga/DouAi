package com.douai.module.feature;

import android.view.View;
import android.view.ViewParent;
import android.widget.EditText;

import com.douai.module.config.ExtendedSettings;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.config.FeatureSettings;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostAdaptation;
import com.douai.module.scan.HostResourceProfile;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Prefills an empty native single-chat text editor and leaves sending to the user. */
public final class AutoMessageDraftHook implements AutoCloseable {
    private final Class<?> drawerClass;
    private final Class<?> singleInputDrawerClass;
    private final Class<?> sessionClass;
    private final Class<?> logicClass;
    private final Class<?> plainTextModeClass;
    private final Field sessionField;
    private final Method getConversationId;
    private final Method logicProvider;
    private final Method curInputType;
    private final Method getEditTextView;
    private final Method setText;
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Map<EditText, PrefillRecord> prefillsByEditor =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Consumer<String> log;
    private volatile FeatureSettings featureSettings;
    private volatile ExtendedSettings extendedSettings;
    private volatile boolean closed;

    private AutoMessageDraftHook(Class<?> drawerClass, Class<?> singleInputDrawerClass,
                                 Class<?> sessionClass,
                                 Class<?> logicClass, Class<?> plainTextModeClass,
                                 Field sessionField, Method getConversationId,
                                 Method logicProvider, Method curInputType,
                                 Method getEditTextView, Method setText,
                                 FeatureSettings featureSettings,
                                 ExtendedSettings extendedSettings, Consumer<String> log) {
        this.drawerClass = drawerClass;
        this.singleInputDrawerClass = singleInputDrawerClass;
        this.sessionClass = sessionClass;
        this.logicClass = logicClass;
        this.plainTextModeClass = plainTextModeClass;
        this.sessionField = sessionField;
        this.getConversationId = getConversationId;
        this.logicProvider = logicProvider;
        this.curInputType = curInputType;
        this.getEditTextView = getEditTextView;
        this.setText = setText;
        this.featureSettings = featureSettings;
        this.extendedSettings = extendedSettings;
        this.log = log;
    }

    public static AutoMessageDraftHook install(XposedInterface framework, HostScanReport report,
                                               ClassLoader loader, FeatureSettings featureSettings,
                                               ExtendedSettings extendedSettings,
                                               Consumer<String> log) throws Exception {
        Method bind = resolveMethod(report, "auto_message_editor_bind", loader);
        HostCandidate sessionAnchor = uniqueCandidate(report, "auto_message_editor_fields");
        Method editorGetter = resolveMethod(report, "auto_message_editor_getter", loader);
        Method textSetter = resolveMethod(report, "auto_message_text_api", loader);
        Method conversationGetter = resolveMethod(report, "auto_message_session_id", loader);
        HostCandidate logicAnchor = uniqueCandidate(report, "auto_message_input_logic");
        HostCandidate textModeAnchor = uniqueCandidate(report, "auto_message_plain_text_mode");
        HostCandidate singleDrawerAnchor = uniqueCandidate(report, "auto_message_single_chat_module");

        Class<?> drawer = bind.getDeclaringClass();
        Class<?> sessionOwner = classForCandidate(sessionAnchor, loader);
        Class<?> logic = classForCandidate(logicAnchor, loader);
        Class<?> plainTextMode = classForCandidate(textModeAnchor, loader);
        Class<?> singleDrawer = classForCandidate(singleDrawerAnchor, loader);
        if (sessionOwner != drawer || !descriptor(drawer).equals(sessionAnchor.getClassDescriptor())) {
            throw new IllegalStateException("Direct-message session rule does not identify the drawer UI");
        }
        if (!drawer.isAssignableFrom(singleDrawer)) {
            throw new IllegalStateException("Single-chat drawer is not based on the host input drawer");
        }
        if (!editorGetter.getDeclaringClass().isAssignableFrom(drawer)
                || editorGetter.getParameterCount() != 0
                || !EditText.class.isAssignableFrom(editorGetter.getReturnType())
                || Modifier.isStatic(editorGetter.getModifiers())) {
            throw new IllegalStateException("Direct-message editor getter contract changed");
        }
        if (!textSetter.getDeclaringClass().isAssignableFrom(drawer)
                || Modifier.isStatic(textSetter.getModifiers())
                || textSetter.getReturnType() != void.class
                || !hasParameters(textSetter, CharSequence.class, String.class, String.class,
                List.class, Map.class)) {
            throw new IllegalStateException("Direct-message native text setter contract changed");
        }
        if (conversationGetter.getParameterCount() != 0
                || conversationGetter.getReturnType() != String.class
                || Modifier.isStatic(conversationGetter.getModifiers())) {
            throw new IllegalStateException("Session conversation ID getter contract changed");
        }

        Field session = drawer.getDeclaredField("e");
        if (session.getType() != conversationGetter.getDeclaringClass()) {
            throw new IllegalStateException("Direct-message session field relation changed");
        }
        Class<?> inputTypeBaseClass = plainTextMode.getSuperclass();
        if (inputTypeBaseClass == null) {
            throw new IllegalStateException("Plain-text mode is not a subtype of the active input type");
        }
        Method inputTypeGetter = logic.getDeclaredMethod("curInputType");
        if (inputTypeGetter.getParameterCount() != 0
                || inputTypeGetter.getReturnType() != inputTypeBaseClass
                || Modifier.isStatic(inputTypeGetter.getModifiers())) {
            throw new IllegalStateException("Direct-message input type getter contract changed");
        }
        Method provider = findMethodInHierarchy(drawer, HostResourceProfile.ripsLogicProvider(
                HostAdaptation.currentProfile()));
        if (provider == null || Modifier.isStatic(provider.getModifiers())
                || !"com.ss.android.ugc.aweme.rips.BasicRipsLogic".equals(
                provider.getReturnType().getName())) {
            throw new IllegalStateException("Direct-message logic provider contract changed");
        }
        if (bind.getDeclaringClass() != drawer || bind.getParameterCount() != 1
                || bind.getParameterTypes()[0] != View.class || bind.getReturnType() != void.class
                || Modifier.isStatic(bind.getModifiers())) {
            throw new IllegalStateException("Direct-message drawer binding contract changed");
        }

        session.setAccessible(true);
        conversationGetter.setAccessible(true);
        provider.setAccessible(true);
        inputTypeGetter.setAccessible(true);
        editorGetter.setAccessible(true);
        textSetter.setAccessible(true);

        AutoMessageDraftHook hook = new AutoMessageDraftHook(drawer, singleDrawer,
                session.getType(), logic,
                plainTextMode, session, conversationGetter, provider, inputTypeGetter,
                editorGetter, textSetter, featureSettings, extendedSettings,
                log == null ? ignored -> { } : log);
        try {
            XposedInterface.HookHandle handle = framework.hook(bind)
                    .setId("DouAi.Automation.MessageDraftPrefill")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(hook::afterDrawerBound);
            if (handle == null) throw new IllegalStateException("Framework returned an empty hook handle");
            hook.handles.add(handle);
            return hook;
        } catch (Throwable failure) {
            hook.unhookAll(failure);
            if (failure instanceof Exception) throw (Exception) failure;
            throw failure;
        }
    }

    public void setSettings(FeatureSettings featureSettings, ExtendedSettings extendedSettings) {
        if (closed) return;
        this.featureSettings = featureSettings;
        this.extendedSettings = extendedSettings;
    }

    private Object afterDrawerBound(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        if (closed) return result;
        Object drawer = chain.getThisObject();
        Object rootArgument = chain.getArg(0);
        if (!drawerClass.isInstance(drawer) || !singleInputDrawerClass.isInstance(drawer)
                || !(rootArgument instanceof View)) return result;
        try {
            View root = (View) rootArgument;
            Object session = sessionField.get(drawer);
            String conversationId = conversationId(session);
            Object editorObject = getEditTextView.invoke(drawer);
            if (conversationId != null && editorObject instanceof EditText) {
                root.post(() -> prefillIfStillActive(drawer, root, (EditText) editorObject,
                        conversationId));
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            safeLog("Could not inspect the active direct-message composer: " + failure.getMessage());
        }
        return result;
    }

    private void prefillIfStillActive(Object drawer, View root, EditText editor,
                                      String boundConversationId) {
        if (closed || !root.isAttachedToWindow() || !root.isShown()
                || !editor.isAttachedToWindow() || !editor.isShown()
                || !isDescendant(editor, root)) { DouAiLog.event("auto_message", "eligibility", "composer_not_active", "closed=" + closed); return; }
        FeatureSettings features = featureSettings;
        ExtendedSettings extended = extendedSettings;
        if (features == null || !features.automationEnabled || !features.autoMessageEnabled
                || extended == null || extended.autoMessageText.isEmpty()) { DouAiLog.event("auto_message", "eligibility", "disabled_or_text_empty", "features_present=" + (features != null) + ";text_configured=" + (extended != null && !extended.autoMessageText.isEmpty())); return; }
        try {
            if (!boundConversationId.equals(currentConversationId(drawer))
                    || !isPlainTextMode(drawer)) return;
            String expectedText;
            PrefillRecord previous;
            PrefillRecord pending = new PrefillRecord(boundConversationId, extended.autoMessageText);
            synchronized (prefillsByEditor) {
                if (closed || !boundConversationId.equals(currentConversationId(drawer))
                        || !isPlainTextMode(drawer)) return;
                CharSequence currentText = editor.getText();
                expectedText = currentText == null ? "" : currentText.toString();
                previous = prefillsByEditor.get(editor);
                if (previous != null && previous.conversationId.equals(boundConversationId)) { DouAiLog.event("auto_message", "eligibility", "already_prefilled", "conversation_ref=" + DouAiLog.reference(boundConversationId)); return; }
                boolean stillOwnsPreviousText = previous != null
                        && expectedText.equals(previous.insertedText);
                if (!expectedText.isEmpty() && !stillOwnsPreviousText) { DouAiLog.event("auto_message", "eligibility", "user_draft_present", "chars=" + expectedText.length()); return; }
                prefillsByEditor.put(editor, pending);
            }

            DouAiLog.Operation trace = DouAiLog.begin("auto_message", "composer_bound");
            trace.stage("draft_apply", "conversation_ref=" + DouAiLog.reference(boundConversationId) + ";manual_send=true");
            boolean applied = false;
            try {
                FeatureSettings latestFeatures = featureSettings;
                ExtendedSettings latestExtended = extendedSettings;
                if (closed || latestFeatures == null || !latestFeatures.automationEnabled
                        || !latestFeatures.autoMessageEnabled || latestExtended == null
                        || latestExtended.autoMessageText.isEmpty()
                        || !boundConversationId.equals(currentConversationId(drawer))
                        || !isPlainTextMode(drawer)
                        || !expectedText.equals(editor.getText() == null
                        ? "" : editor.getText().toString())) return;
                String draftText = latestExtended.autoMessageText;
                PrefillRecord updated = new PrefillRecord(boundConversationId, draftText);
                synchronized (prefillsByEditor) {
                    if (closed || prefillsByEditor.get(editor) != pending) return;
                    prefillsByEditor.put(editor, updated);
                }
                pending = updated;
                setText.invoke(drawer, draftText, null, draftText,
                        Collections.emptyList(), Collections.emptyMap());
                applied = true;
                boolean observed = draftText.contentEquals(editor.getText() == null ? "" : editor.getText());
                trace.finish(observed ? DouAiLog.Outcome.SUCCESS : DouAiLog.Outcome.UNCONFIRMED, observed ? "draft_text_observed" : "draft_setter_returned", "chars=" + draftText.length() + ";editor_matches=" + observed + ";message_sent=false");
                safeLog("Updated the native direct-message draft; sending remains manual");
            } finally {
                if (!applied) { trace.finish(DouAiLog.Outcome.CANCELLED, "draft_apply_guard_changed_or_failed", "restored_prefill_record=true;message_sent=false"); restorePrefillRecord(editor, pending, previous); }
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            DouAiLog.failure("auto_message", "apply_draft", failure);
            safeLog("Could not prefill the active direct-message editor: " + failure.getMessage());
        }
    }

    private boolean isPlainTextMode(Object drawer) throws ReflectiveOperationException {
        Object logic = logicProvider.invoke(drawer);
        if (!logicClass.isInstance(logic)) return false;
        Object inputType = curInputType.invoke(logic);
        return inputType != null && inputType.getClass() == plainTextModeClass;
    }

    private String currentConversationId(Object drawer) throws ReflectiveOperationException {
        return conversationId(sessionField.get(drawer));
    }

    private String conversationId(Object session) throws ReflectiveOperationException {
        if (session == null || !sessionClass.isInstance(session)) return null;
        Object value = getConversationId.invoke(session);
        if (!(value instanceof String)) return null;
        String conversationId = ((String) value).trim();
        return conversationId.isEmpty() ? null : conversationId;
    }

    private void restorePrefillRecord(EditText editor, PrefillRecord pending,
                                      PrefillRecord previous) {
        synchronized (prefillsByEditor) {
            if (prefillsByEditor.get(editor) != pending) return;
            if (previous == null) prefillsByEditor.remove(editor);
            else prefillsByEditor.put(editor, previous);
        }
    }

    private static boolean isDescendant(View child, View ancestor) {
        View current = child;
        while (current != null) {
            if (current == ancestor) return true;
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private static boolean hasParameters(Method method, Class<?>... parameterTypes) {
        if (method.getParameterCount() != parameterTypes.length) return false;
        for (int index = 0; index < parameterTypes.length; index++) {
            if (method.getParameterTypes()[index] != parameterTypes[index]) return false;
        }
        return true;
    }

    private static Method resolveMethod(HostScanReport report, String ruleId,
                                        ClassLoader loader) throws Exception {
        HostCandidate candidate = uniqueCandidate(report, ruleId);
        Class<?> owner = classForCandidate(candidate, loader);
        for (Method method : owner.getDeclaredMethods()) {
            if (method.getName().equals(candidate.getMethodName())
                    && methodDescriptor(method).equals(candidate.getMethodDescriptor())) return method;
        }
        throw new NoSuchMethodException("Scanned method is unavailable: " + ruleId);
    }

    private static HostCandidate uniqueCandidate(HostScanReport report, String ruleId) {
        RuleScanResult result = report == null ? null : report.getResults().get(ruleId);
        if (result == null || result.getStatus() != RuleScanResult.Status.UNIQUE) {
            throw new IllegalStateException("Host rule is not unique: " + ruleId);
        }
        return result.getCandidates().get(0);
    }

    private static Class<?> classForCandidate(HostCandidate candidate, ClassLoader loader)
            throws ClassNotFoundException {
        return Class.forName(descriptorClassName(candidate.getClassDescriptor()), false, loader);
    }

    private static String descriptorClassName(String descriptor) {
        if (descriptor == null || descriptor.length() < 3 || descriptor.charAt(0) != 'L'
                || descriptor.charAt(descriptor.length() - 1) != ';') {
            throw new IllegalArgumentException("Invalid class descriptor: " + descriptor);
        }
        return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
    }

    private static Method findMethodInHierarchy(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                // Continue through the host UI base classes.
            }
        }
        return null;
    }

    private static String descriptor(Class<?> type) {
        return "L" + type.getName().replace('.', '/') + ";";
    }

    private static String methodDescriptor(Method method) {
        StringBuilder descriptor = new StringBuilder("(");
        for (Class<?> parameter : method.getParameterTypes()) descriptor.append(typeDescriptor(parameter));
        return descriptor.append(')').append(typeDescriptor(method.getReturnType())).toString();
    }

    private static String typeDescriptor(Class<?> type) {
        if (type.isArray()) return type.getName().replace('.', '/');
        if (!type.isPrimitive()) return "L" + type.getName().replace('.', '/') + ";";
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        throw new IllegalArgumentException("Unsupported primitive type: " + type);
    }

    private static final class PrefillRecord {
        final String conversationId;
        final String insertedText;

        PrefillRecord(String conversationId, String insertedText) {
            this.conversationId = conversationId;
            this.insertedText = insertedText;
        }
    }

    @Override
    public void close() {
        closed = true;
        unhookAll(null);
        prefillsByEditor.clear();
    }

    private void unhookAll(Throwable originalFailure) {
        Throwable failure = originalFailure;
        for (int index = handles.size() - 1; index >= 0; index--) {
            try {
                handles.get(index).unhook();
            } catch (Throwable unhookFailure) {
                if (failure == null) failure = unhookFailure;
                else failure.addSuppressed(unhookFailure);
            }
        }
        handles.clear();
        if (originalFailure == null && failure != null) {
            safeLog("Direct-message draft unhook failed: " + failure.getMessage());
        }
    }

    private void safeLog(String message) {
        try {
            log.accept(message);
        } catch (Throwable ignored) {
            // Diagnostics must not interrupt the native messaging flow.
        }
    }
}
