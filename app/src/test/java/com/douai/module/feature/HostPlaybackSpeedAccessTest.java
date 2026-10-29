package com.douai.module.feature;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

/** The command getter must not be confused with the host's numeric player metrics. */
public final class HostPlaybackSpeedAccessTest {
    public interface Command { }
    public static final class SpeedCommand implements Command { }
    public static final class EffectiveSpeedCommand implements Command { }
    public static final class OtherCommand {
        @Override public String toString() { return "PRIVATE_COMMAND_VALUE"; }
    }

    public static final class NativePlayer {
        Object value = 2.5f;
        Object received;
        int queryCalls;
        int frameRateCalls;

        public Object query(Command command) {
            received = command; queryCalls++; return value;
        }
        public Object objectQuery(Object command) {
            received = command; queryCalls++; return value;
        }
        public float LJIIJ() { frameRateCalls++; return 30f; }
        public float getFrameRate() { frameRateCalls++; return 30f; }
        public float typedSpeed(Command command) { return 2.5f; }
        public Number numberSpeed(Command command) { return 2.5f; }
        public Object twoArguments(Command command, int flags) { return value; }
    }

    public static final class LegacyPlayer {
        public float getSpeed() { return 1.25f; }
    }
    public static final class EffectivePlayer {
        final Command requestedCommand = new SpeedCommand();
        final Command effectiveCommand = new EffectiveSpeedCommand();
        Object effectiveValue = 1f;
        Object received;
        int requestedCalls;
        int effectiveCalls;

        public Object query(Command command) {
            received = command;
            if (command == requestedCommand) { requestedCalls++; return 2f; }
            if (command == effectiveCommand) { effectiveCalls++; return effectiveValue; }
            throw new IllegalArgumentException("Unexpected speed command");
        }
    }
    public static final class BoxedLegacyPlayer {
        public Float getSpeed() { return 1.25f; }
    }
    public static final class ObjectLegacyPlayer {
        public Object getSpeed() { return 1.25f; }
    }

    @Test public void typedSpeedCommandReadsPlaybackSpeedWhenThirtyFpsMetricAlsoExists() throws Exception {
        NativePlayer player = new NativePlayer();
        SpeedCommand command = new SpeedCommand();
        HostPlaybackSpeedAccess access = HostPlaybackSpeedAccess.of(
                NativePlayer.class.getMethod("query", Command.class), command);
        assertEquals(2.5f, access.read(player), 0f);
        assertSame(command, player.received);
        assertEquals(1, player.queryCalls);
        assertEquals("The unrelated 30.0 metric must never be used as the speed getter", 0, player.frameRateCalls);
    }

    @Test public void objectParameterAlsoReceivesTheExactResolvedCommand() throws Exception {
        NativePlayer player = new NativePlayer(); Object command = new SpeedCommand();
        HostPlaybackSpeedAccess access = HostPlaybackSpeedAccess.of(
                NativePlayer.class.getMethod("objectQuery", Object.class), command);
        assertEquals(2.5f, access.read(player), 0f);
        assertSame(command, player.received);
    }

    @Test public void onlyTheNamedZeroArgumentPrimitiveFloatLegacyGetterRemainsSupported() throws Exception {
        HostPlaybackSpeedAccess access = HostPlaybackSpeedAccess.of(LegacyPlayer.class.getMethod("getSpeed"), null);
        assertEquals(1.25f, access.read(new LegacyPlayer()), 0f);
        assertEquals(1.25f, access.readEffective(new LegacyPlayer()), 0f);
        assertThrows(IllegalArgumentException.class,
                () -> HostPlaybackSpeedAccess.of(BoxedLegacyPlayer.class.getMethod("getSpeed"), null));
        assertThrows(IllegalArgumentException.class,
                () -> HostPlaybackSpeedAccess.of(ObjectLegacyPlayer.class.getMethod("getSpeed"), null));
        assertThrows(IllegalArgumentException.class,
                () -> HostPlaybackSpeedAccess.of(LegacyPlayer.class.getMethod("getSpeed"), new SpeedCommand()));
    }

    @Test public void unrelatedNoArgumentNumericMetricsAreRejectedBeforeInvocation() throws Exception {
        NativePlayer player = new NativePlayer();
        for (String name : new String[]{"LJIIJ", "getFrameRate"}) {
            Method metric = NativePlayer.class.getMethod(name);
            assertThrows(IllegalArgumentException.class, () -> HostPlaybackSpeedAccess.of(metric, null));
        }
        assertEquals(0, player.frameRateCalls);
    }

    @Test public void commandQueriesRejectMissingAndIncompatibleCommandsWithoutPrintingThem() throws Exception {
        Method query = NativePlayer.class.getMethod("query", Command.class);
        assertThrows(IllegalArgumentException.class, () -> HostPlaybackSpeedAccess.of(query, null));
        Object wrongCommand = new OtherCommand();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> HostPlaybackSpeedAccess.of(query, wrongCommand));
        assertEquals("Playback speed getter relationship changed", failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test public void commandMethodsWithTheWrongReturnTypeOrArityCannotBecomeTheReader() throws Exception {
        SpeedCommand command = new SpeedCommand();
        for (String name : new String[]{"typedSpeed", "numberSpeed"}) {
            Method wrong = NativePlayer.class.getMethod(name, Command.class);
            assertThrows(IllegalArgumentException.class, () -> HostPlaybackSpeedAccess.of(wrong, command));
        }
        assertThrows(IllegalArgumentException.class, () -> HostPlaybackSpeedAccess.of(
                NativePlayer.class.getMethod("twoArguments", Command.class, int.class), command));
    }

    @Test public void nullAndNonnumericReadbacksHaveOneFixedFailureWithoutRawValues() throws Exception {
        NativePlayer player = new NativePlayer();
        HostPlaybackSpeedAccess access = HostPlaybackSpeedAccess.of(
                NativePlayer.class.getMethod("query", Command.class), new SpeedCommand());
        Object privateValue = new Object() {
            @Override public String toString() { return "PRIVATE_READBACK_VALUE"; }
        };
        for (Object value : new Object[]{null, "PRIVATE_READBACK_VALUE", privateValue, Boolean.TRUE}) {
            player.value = value;
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> access.read(player));
            assertEquals("Host did not return a valid playback speed", failure.getMessage());
            assertFalse(failure.getMessage().contains("PRIVATE_READBACK_VALUE"));
            assertNull(failure.getCause());
        }
    }

    @Test public void nonfiniteNonpositiveAndOverflowReadbacksNeverBecomeSpeedValues() throws Exception {
        NativePlayer player = new NativePlayer();
        HostPlaybackSpeedAccess access = HostPlaybackSpeedAccess.of(
                NativePlayer.class.getMethod("query", Command.class), new SpeedCommand());
        for (Number value : new Number[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                0f, -0f, -1f, Double.MAX_VALUE}) {
            player.value = value;
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> access.read(player));
            assertEquals("Host did not return a valid playback speed", failure.getMessage());
            assertNull(failure.getCause());
        }
        for (Number value : new Number[]{1, 2.5f, 3.25d}) {
            player.value = value;
            assertEquals(value.floatValue(), access.read(player), 0f);
        }
    }

    @Test public void effectiveReadbackQueriesASeparateCommandWhenRequestedSpeedIsOnlyCached() throws Exception {
        EffectivePlayer player = new EffectivePlayer();
        HostPlaybackSpeedAccess access = HostPlaybackSpeedAccess.of(
                EffectivePlayer.class.getMethod("query", Command.class),
                player.requestedCommand, player.effectiveCommand);
        assertEquals(2f, access.read(player), 0f);
        assertSame(player.requestedCommand, player.received);
        assertEquals(1f, access.readEffective(player), 0f);
        assertSame(player.effectiveCommand, player.received);
        assertEquals(1, player.requestedCalls);
        assertEquals(1, player.effectiveCalls);
    }

    @Test public void missingEffectiveCommandNeverFallsBackToTheRequestedSpeedCommand() throws Exception {
        EffectivePlayer player = new EffectivePlayer();
        HostPlaybackSpeedAccess access = HostPlaybackSpeedAccess.of(
                EffectivePlayer.class.getMethod("query", Command.class), player.requestedCommand);
        assertEquals(2f, access.read(player), 0f);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> access.readEffective(player));
        assertEquals("Host did not return a valid effective playback speed", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(1, player.requestedCalls);
        assertEquals(0, player.effectiveCalls);
    }

    @Test public void unavailableOrInvalidEffectiveSpeedNeverFallsBackToCachedRequestedSpeed() throws Exception {
        EffectivePlayer player = new EffectivePlayer();
        HostPlaybackSpeedAccess access = HostPlaybackSpeedAccess.of(
                EffectivePlayer.class.getMethod("query", Command.class),
                player.requestedCommand, player.effectiveCommand);
        Object privateValue = new Object() {
            @Override public String toString() { return "PRIVATE_EFFECTIVE_SPEED_VALUE"; }
        };
        Object[] values = {null, "PRIVATE_EFFECTIVE_SPEED_VALUE", privateValue, Boolean.TRUE,
                -1f, 0f, -0f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Double.MAX_VALUE};
        for (Object value : values) {
            player.effectiveValue = value;
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> access.readEffective(player));
            assertEquals("Host did not return a valid effective playback speed", failure.getMessage());
            assertNull(failure.getCause());
        }
        assertEquals(0, player.requestedCalls);
        assertEquals(values.length, player.effectiveCalls);
    }

    @Test public void effectiveCommandMustMatchTheGetterAndDifferFromTheRequestedCommand() throws Exception {
        Method query = EffectivePlayer.class.getMethod("query", Command.class);
        SpeedCommand command = new SpeedCommand();
        for (Object invalid : new Object[]{new OtherCommand(), command}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> HostPlaybackSpeedAccess.of(query, command, invalid));
            assertEquals("Playback speed getter relationship changed", failure.getMessage());
            assertNull(failure.getCause());
        }
        assertThrows(IllegalArgumentException.class, () -> HostPlaybackSpeedAccess.of(
                LegacyPlayer.class.getMethod("getSpeed"), null, new EffectiveSpeedCommand()));
    }
}
