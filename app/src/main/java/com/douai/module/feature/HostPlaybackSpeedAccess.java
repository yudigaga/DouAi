package com.douai.module.feature;

import com.douai.module.scan.HostScanReport;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Reads the host's typed playback-speed command, never an arbitrary numeric player metric. */
final class HostPlaybackSpeedAccess {
    private final Method getter;
    private final Object command;
    private final Object effectiveCommand;

    private HostPlaybackSpeedAccess(Method getter, Object command, Object effectiveCommand) {
        this.getter = getter;
        this.command = command;
        this.effectiveCommand = effectiveCommand;
    }

    static HostPlaybackSpeedAccess resolve(Method getter, HostScanReport report, ClassLoader loader) throws Exception {
        Object command = null;
        Object effectiveCommand = null;
        if (getter.getParameterCount() == 1) {
            command = resolveCommand(getter, report, loader, "adv_player_speed_command");
            effectiveCommand = resolveCommand(getter, report, loader, "adv_player_real_speed_command");
        }
        return of(getter, command, effectiveCommand);
    }

    private static Object resolveCommand(Method getter, HostScanReport report, ClassLoader loader,
                                         String ruleId) throws Exception {
        Class<?> type = HostTargets.type(report, ruleId, loader);
        Field singleton = HostTargets.field(type, "f");
        if (!Modifier.isStatic(singleton.getModifiers()) || !Modifier.isFinal(singleton.getModifiers())
                || singleton.getType() != type || !getter.getParameterTypes()[0].isAssignableFrom(type)) {
            throw new IllegalStateException("Playback speed command relationship changed");
        }
        return singleton.get(null);
    }

    static HostPlaybackSpeedAccess of(Method getter, Object command) {
        return of(getter, command, null);
    }

    static HostPlaybackSpeedAccess of(Method getter, Object command, Object effectiveCommand) {
        boolean direct = getter.getParameterCount() == 0 && getter.getReturnType() == float.class
                && "getSpeed".equals(getter.getName()) && command == null && effectiveCommand == null;
        boolean query = getter.getParameterCount() == 1 && getter.getReturnType() == Object.class
                && command != null && getter.getParameterTypes()[0].isInstance(command)
                && (effectiveCommand == null || (effectiveCommand != command
                    && getter.getParameterTypes()[0].isInstance(effectiveCommand)));
        if (!direct && !query) throw new IllegalArgumentException("Playback speed getter relationship changed");
        return new HostPlaybackSpeedAccess(getter, command, effectiveCommand);
    }

    float read(Object player) throws ReflectiveOperationException {
        Object value = command == null ? getter.invoke(player) : getter.invoke(player, command);
        if (!(value instanceof Number)) throw invalidReadback();
        float speed = ((Number) value).floatValue();
        if (!Float.isFinite(speed) || speed <= 0f) throw invalidReadback();
        return speed;
    }

    /** Reads the engine's effective speed; a cached requested speed is never a substitute. */
    float readEffective(Object player) throws ReflectiveOperationException {
        if (command == null) return read(player);
        if (effectiveCommand == null) throw invalidEffectiveReadback();
        Object value = getter.invoke(player, effectiveCommand);
        if (!(value instanceof Number)) throw invalidEffectiveReadback();
        float speed = ((Number) value).floatValue();
        if (!Float.isFinite(speed) || speed <= 0f) throw invalidEffectiveReadback();
        return speed;
    }

    private static IllegalStateException invalidReadback() {
        return new IllegalStateException("Host did not return a valid playback speed");
    }

    private static IllegalStateException invalidEffectiveReadback() {
        return new IllegalStateException("Host did not return a valid effective playback speed");
    }
}
