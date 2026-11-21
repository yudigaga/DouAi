package com.douai.module.feature;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class FeedFirstFrameTraceTest {
    @Test public void onlyMatchingDispatcherAndWorkCanCompleteCurrentSelection() {
        List<String> events=new ArrayList<>();
        FeedFirstFrameTrace trace=new FeedFirstFrameTrace((stage,aid,delta)->events.add(stage+":"+aid+":"+delta));
        Object panel=new Object(),other=new Object();
        trace.selected(panel,"work",100);
        trace.drawOpportunity(116);
        trace.drawOpportunity(132);
        trace.rendered(other,"work",136);
        trace.rendered(panel,"stale",140);
        trace.rendered(panel,"work",150);
        trace.rendered(panel,"work",160);
        assertEquals(List.of("selected:work:0","draw_opportunity:work:16","renderer_callback:work:50"),events);
    }

    @Test public void anEarlyCallbackIsIdentifiedAsPreSelectionAndOnlyUsedOnce() {
        List<String> events=new ArrayList<>();
        FeedFirstFrameTrace trace=new FeedFirstFrameTrace((stage,aid,delta)->events.add(stage+":"+aid+":"+delta));
        Object panel=new Object(),other=new Object();
        trace.rendered(panel,"next",90);
        trace.selected(other,"next",96);
        trace.selected(panel,"next",100);
        trace.selected(panel,"next",110);
        assertEquals(List.of("selected:next:0","selected:next:0","renderer_before_selection:next:-10","selected:next:0"),events);
    }

    @Test public void staleOrClearedPendingRendererDoesNotBecomeTheNextFirstFrame() {
        List<String> events=new ArrayList<>();
        FeedFirstFrameTrace trace=new FeedFirstFrameTrace((stage,aid,delta)->events.add(stage+":"+aid+":"+delta));
        Object panel=new Object();
        trace.rendered(panel,"old",1);
        trace.selected(panel,"old",3000);
        trace.rendered(panel,"old",9000);
        trace.rendered(panel,"next",9050);
        trace.clear();
        trace.drawOpportunity(9060);
        trace.selected(panel,"next",9070);
        assertEquals(List.of("selected:old:0","selected:next:0"),events);
    }
}
