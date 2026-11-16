package com.douai.module.feature;

import io.github.libxposed.api.XposedInterface;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.diagnostics.FeatureLogCatalog;

import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** An independently revocable registration, including partially installed hooks. */
final class BehaviorHookGroup implements AutoCloseable {
    private final String id;
    private final Consumer<String> log;
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private boolean closed;
    private String installationFailure = "";

    BehaviorHookGroup(String id, Consumer<String> log) {
        this.id = id;
        this.log = log;
    }

    synchronized void add(XposedInterface framework, Executable target,
                          XposedInterface.Hooker hooker) {
        add(framework, target, XposedInterface.PRIORITY_DEFAULT, hooker);
    }

    synchronized void add(XposedInterface framework, Executable target, int priority,
                          XposedInterface.Hooker hooker) {
        if (closed) throw new IllegalStateException("Closed feature: " + id);
        XposedInterface.HookHandle handle = framework.hook(target)
                .setPriority(priority)
                .setId("DouAi.Advanced." + id + "." + handles.size())
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(hooker);
        if (handle == null) throw new IllegalStateException("Empty hook handle: " + id);
        handles.add(handle);
        DouAiLog.event(FeatureLogCatalog.forHook("DouAi.Advanced." + id), "registration", "handle_installed", "handles=" + handles.size() + ";business_result=not_implied");
    }

    synchronized boolean isActive() { return !closed && !handles.isEmpty(); }
    synchronized String installationFailure() { return installationFailure; }

    void fail(Throwable failure) {
        String feature = FeatureLogCatalog.forHook("DouAi.Advanced." + id);
        DouAiLog.failure(feature, "installation_or_runtime_rollback", failure);
        DouAiLog.event(feature, "availability", "rolled_back", "hook_state=uninstalled;business_result=unknown");
        synchronized (this) {
            installationFailure = com.douai.module.diagnostics.HostAdaptationDiagnostics.installationFailure(failure);
        }
        close();
        try { log.accept("Advanced feature " + id + " rolled back: "
                + failure.getClass().getSimpleName() + ": " + failure.getMessage()); }
        catch (Throwable ignored) { }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (int i = handles.size() - 1; i >= 0; i--) {
            try { handles.get(i).unhook(); }
            catch (Throwable failure) {
                try { log.accept("Could not revoke " + id + ": " + failure.getMessage()); }
                catch (Throwable ignored) { }
            }
        }
        handles.clear();
        DouAiLog.event(FeatureLogCatalog.forHook("DouAi.Advanced." + id), "registration", "closed", "handles=0");
    }
}
