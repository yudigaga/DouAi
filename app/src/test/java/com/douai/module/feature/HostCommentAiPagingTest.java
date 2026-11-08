package com.douai.module.feature;

import android.view.MotionEvent;
import org.junit.Test;
import static org.junit.Assert.*;

public final class HostCommentAiPagingTest {
    @Test public void upwardGestureCanRetryAtTheBottomWithoutAScrollCallback() {
        HostCommentAiPaging.SwipeGesture gesture = new HostCommentAiPaging.SwipeGesture();
        assertFalse(gesture.touch(MotionEvent.ACTION_DOWN, 140, 32));
        assertTrue(gesture.touch(MotionEvent.ACTION_UP, 100, 32));
        assertFalse(gesture.touch(MotionEvent.ACTION_UP, 60, 32));
        assertFalse(gesture.touch(MotionEvent.ACTION_DOWN, 140, 32));
        assertFalse(gesture.touch(MotionEvent.ACTION_UP, 115, 32));
        assertFalse(gesture.touch(MotionEvent.ACTION_DOWN, 100, 32));
        assertFalse(gesture.touch(MotionEvent.ACTION_UP, 150, 32));
    }

    @Test public void scrollRequestAndCancelledGestureCannotTriggerADuplicateOnRelease() {
        HostCommentAiPaging.SwipeGesture gesture = new HostCommentAiPaging.SwipeGesture();
        gesture.touch(MotionEvent.ACTION_DOWN, 200, 32);
        gesture.scrolled();
        assertFalse(gesture.touch(MotionEvent.ACTION_UP, 80, 32));
        gesture.touch(MotionEvent.ACTION_DOWN, 200, 32);
        gesture.touch(MotionEvent.ACTION_CANCEL, 100, 32);
        assertFalse(gesture.touch(MotionEvent.ACTION_UP, 80, 32));
        gesture.touch(MotionEvent.ACTION_DOWN, 200, 32);
        assertTrue(gesture.touch(MotionEvent.ACTION_UP, 168, 32));
    }

    @Test public void bottomThresholdUsesTheVisibleExtentAndRequiresValidScrollMetrics() {
        assertTrue(HostCommentAiPaging.nearBottom(5_000, 3_000, 1_000));
        assertTrue(HostCommentAiPaging.nearBottom(5_000, 4_000, 1_000));
        assertFalse(HostCommentAiPaging.nearBottom(5_000, 2_999, 1_000));
        assertFalse(HostCommentAiPaging.nearBottom(5_000, 4_000, 0));
        assertFalse(HostCommentAiPaging.nearBottom(-1, 0, 1_000));
        assertFalse(HostCommentAiPaging.nearBottom(5_000, -1, 1_000));
    }

    @Test public void extremeMetricsCannotOverflowIntoAPrematureBottom() {
        assertFalse(HostCommentAiPaging.nearBottom(Integer.MAX_VALUE, 1, 1_000));
        assertTrue(HostCommentAiPaging.nearBottom(Integer.MAX_VALUE, Integer.MAX_VALUE - 1_000, 1_000));
    }

    @Test public void unavailableHostCannotCreateABroadScrollInterceptor() {
        assertThrows(ClassNotFoundException.class, () -> HostCommentAiPaging.create(new ClassLoader(null) { }));
    }
}
