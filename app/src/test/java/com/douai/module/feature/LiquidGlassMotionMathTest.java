package com.douai.module.feature;

import org.junit.Test;
import static org.junit.Assert.*;

/** These cases exercise coordinates and scheduling without a sensor or Android runtime. */
public class LiquidGlassMotionMathTest {
    @Test public void gravityFollowsAllFourDisplayRotations() {
        float g = 9.80665f;
        assertVector(-.2f, .4f, LiquidGlassMotion.mapGravity(.2f * g, .4f * g, 0));
        assertVector(.4f, .2f, LiquidGlassMotion.mapGravity(.2f * g, .4f * g, 1));
        assertVector(.2f, -.4f, LiquidGlassMotion.mapGravity(.2f * g, .4f * g, 2));
        assertVector(-.4f, -.2f, LiquidGlassMotion.mapGravity(.2f * g, .4f * g, 3));
    }

    @Test public void diagonalTiltHasOneSharedDisplacementLimit() {
        LiquidGlassMotion.Vector tilt = LiquidGlassMotion.mapGravity(-9.80665f, 9.80665f, 0);
        assertEquals(1f, Math.hypot(tilt.x, tilt.y), .00001);
        assertEquals(.7071068f, tilt.x, .00001f);
        assertEquals(.7071068f, tilt.y, .00001f);
    }

    @Test public void invalidSensorValuesCannotReachViewTransforms() {
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MAX_VALUE}) {
            assertVector(0, 0, LiquidGlassMotion.mapGravity(invalid, 1, 0));
            assertVector(0, 0, LiquidGlassMotion.mapGravity(1, invalid, 0));
        }
    }

    @Test public void smoothingUsesElapsedTimeRatherThanCallbackCount() {
        LiquidGlassMotion.MotionState one = started(), many = started();
        one.accept(new LiquidGlassMotion.Vector(1, 0));
        many.accept(new LiquidGlassMotion.Vector(1, 0));
        one.step(500_000_000L);
        for (int i = 1; i <= 5; i++) many.step(i * 100_000_000L);
        assertEquals(one.x, many.x, .00001f);
        assertTrue(one.x > .8f && one.x < 1f);
    }

    @Test public void callbacksAreLimitedToThirtyFramesPerSecond() {
        LiquidGlassMotion.MotionState state = started();
        state.accept(new LiquidGlassMotion.Vector(1, 0));
        assertFalse(state.step(33_333_333L));
        assertEquals(0f, state.x, 0f);
        assertTrue(state.step(33_333_334L));
        float first = state.x;
        assertFalse(state.step(60_000_000L));
        assertEquals(first, state.x, 0f);
    }

    @Test public void interactionFreezesAndReleaseResumesSmoothly() {
        LiquidGlassMotion.MotionState state = started();
        state.accept(new LiquidGlassMotion.Vector(1, 0));
        state.step(100_000_000L);
        float frozen = state.x;
        state.setInteracting(true, 100_000_000L);
        state.accept(new LiquidGlassMotion.Vector(-1, 0));
        assertFalse(state.step(2_000_000_000L));
        assertEquals(frozen, state.x, 0f);
        assertFalse(state.needsFrame());
        state.setInteracting(false, 2_000_000_000L);
        state.step(2_034_000_000L);
        assertTrue(state.x < frozen && state.x > -.1f);
    }

    @Test public void settledInputStopsRequestingFramesAndResetReturnsToZero() {
        LiquidGlassMotion.MotionState state = started();
        state.accept(new LiquidGlassMotion.Vector(.5f, -.5f));
        state.step(5_000_000_000L);
        assertFalse(state.needsFrame());
        assertVector(.5f, -.5f, new LiquidGlassMotion.Vector(state.x, state.y));
        state.accept(new LiquidGlassMotion.Vector(.5001f, -.5001f));
        assertFalse(state.needsFrame());
        state.reset();
        assertEquals(0f, state.x, 0);
        assertEquals(0f, state.y, 0);
        assertFalse(state.needsFrame());
    }

    @Test public void accelerometerFallbackFiltersTimeAndRejectsMalformedSamples() {
        LiquidGlassMotion.GravityFilter filter = new LiquidGlassMotion.GravityFilter();
        assertTrue(filter.accept(0, 0, 9.80665f, 0, true));
        assertTrue(filter.accept(9.80665f, 0, 9.80665f, 100_000_000L, true));
        assertTrue(filter.x > 0 && filter.x < 3);
        float previous = filter.x;
        assertFalse(filter.accept(Float.NaN, 0, 9.80665f, 200_000_000L, true));
        assertEquals(previous, filter.x, 0);
        assertFalse(filter.accept(0, 0, 0, 300_000_000L, true));
    }

    @Test public void disablingDuringInteractionCannotFreezeTheNextActivation() {
        LiquidGlassMotion.MotionState state = started();
        state.setInteracting(true, 10);
        state.reset();
        state.accept(new LiquidGlassMotion.Vector(1, 0));
        state.step(0);
        assertTrue(state.needsFrame());
        assertTrue(state.step(100_000_000L));
    }

    @Test public void orientationReferenceCentersTheHoldingPoseAndReachesAllFourDirectionsAtTwentyDegrees() {
        LiquidGlassMotion.OrientationReference reference=reference();float g=9.80665f,s=.34202015f*g,c=.9396926f*g;
        assertVector(0,0,relative(reference,0,0,g,0));
        assertVector(1,0,relative(reference,-s,0,c,0));assertVector(-1,0,relative(reference,s,0,c,0));
        assertVector(0,1,relative(reference,0,s,c,0));assertVector(0,-1,relative(reference,0,-s,c,0));
    }

    @Test public void uprightReferenceUsesZToDistinguishForwardAndBackwardAtTheSameY() {
        LiquidGlassMotion.OrientationReference reference=reference();float g=9.80665f,s=.34202015f*g,c=.9396926f*g;
        assertVector(0,0,relative(reference,0,g,0,0));
        assertVector(0,-1,relative(reference,0,c,s,0));
        assertVector(0,1,relative(reference,0,c,-s,0));
    }

    @Test public void orientationReferenceFollowsScreenRotationAndRecentersOnlyWhenRequested() {
        float g=9.80665f,s=.34202015f*g,c=.9396926f*g;
        float[][] positiveX={{-s,0},{0,s},{s,0},{0,-s}};
        for(int rotation=0;rotation<4;rotation++) {
            LiquidGlassMotion.OrientationReference reference=reference();assertVector(0,0,relative(reference,0,0,g,rotation));
            assertVector(1,0,relative(reference,positiveX[rotation][0],positiveX[rotation][1],c,rotation));
            assertVector(0,0,relative(reference,positiveX[rotation][0],positiveX[rotation][1],c,(rotation+1)%4));
            resetReference(reference);assertVector(0,0,relative(reference,s,0,c,(rotation+1)%4));
        }
    }

    @Test public void orientationReferenceWrapsBackFacingPitchInsteadOfJumpingAcrossThePiBoundary() {
        LiquidGlassMotion.OrientationReference reference=reference();float g=9.80665f,s=.34202015f*g,c=.9396926f*g;
        assertVector(0,0,relative(reference,0,0,-g,0));
        assertVector(0,-1,relative(reference,0,s,-c,0));assertVector(0,1,relative(reference,0,-s,-c,0));
    }

    @Test public void invalidReferenceSamplesCannotCalibrateOrPoisonTheNextHoldingPose() {
        LiquidGlassMotion.OrientationReference reference=reference();float g=9.80665f;
        assertNull(relative(reference,Float.NaN,0,g,0));assertNull(relative(reference,0,0,0,0));
        assertVector(0,0,relative(reference,0,0,g,0));
        assertVector(1,0,relative(reference,-.34202015f*g,0,.9396926f*g,0));
    }

    private static LiquidGlassMotion.OrientationReference reference() {
        return new LiquidGlassMotion.OrientationReference();
    }
    private static LiquidGlassMotion.Vector relative(LiquidGlassMotion.OrientationReference reference,float x,float y,float z,int rotation) {
        return reference.accept(x,y,z,rotation);
    }
    private static void resetReference(LiquidGlassMotion.OrientationReference reference) {
        reference.reset();
    }

    private static LiquidGlassMotion.MotionState started() {
        LiquidGlassMotion.MotionState state = new LiquidGlassMotion.MotionState();
        state.step(0);
        return state;
    }

    private static void assertVector(float x, float y, LiquidGlassMotion.Vector actual) {
        assertEquals(x, actual.x, .00001f);
        assertEquals(y, actual.y, .00001f);
    }
}
