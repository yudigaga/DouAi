package com.douai.module.feature;

import com.douai.module.scan.HostScanReport;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** 41-only position access. The host accepts percentages; everything else here uses milliseconds. */
final class PlaybackPositionAccess {
    static final class Snapshot {
        final String aid;
        final long millis, duration;
        final Object player;
        Snapshot(String aid,long millis,long duration,Object player) {
            this.aid=aid; this.millis=millis; this.duration=duration; this.player=player;
        }
    }
    final Method holder, player, eligible, position, duration, seek, media;
    PlaybackPositionAccess(HostScanReport report, ClassLoader loader) throws Exception {
        holder=HostTargets.method(report,"auto_slide_current_holder",loader);
        player=HostTargets.method(report,"adv_gesture_panel_player",loader);
        Method progress=HostTargets.method(report,"component_short_progress_legacy",loader);
        eligible=progress.getDeclaringClass().getDeclaredMethod("LIZIZ",progress.getParameterTypes()[0]);
        Class<?> info=Class.forName("com.ss.android.ugc.aweme.video.IPlayerInfoProvider",false,loader);
        Class<?> manager=player.getReturnType();
        position=info.getMethod("getCurrentPosition");
        duration=info.getMethod("getDuration");
        seek=manager.getMethod("seek",float.class);
        media=info.getMethod("LJIILLIIL");
        if(!info.isAssignableFrom(manager) || holder.getDeclaringClass()!=player.getDeclaringClass()
                || !Modifier.isStatic(eligible.getModifiers()) || eligible.getReturnType()!=boolean.class
                || position.getReturnType()!=long.class || duration.getReturnType()!=long.class
                || seek.getReturnType()!=void.class || !media.getReturnType().getName().equals(
                        "com.ss.android.ugc.playerkit.simapicommon.model.SimVideoUrlModel"))
            throw new NoSuchMethodException("41 playback position contract");
    }
    Snapshot snapshot(Object panel,Object selected,Object aweme,String aid) throws ReflectiveOperationException {
        if(panel==null || selected==null || !PlaybackBookmarkStore.id(aid) || !holder.getDeclaringClass().isInstance(panel)
                || holder.invoke(panel)!=selected || !Boolean.TRUE.equals(eligible.invoke(null,aweme))
                || !Integer.valueOf(0).equals(HostTargets.invoke(aweme,"getAwemeType"))
                || !Boolean.FALSE.equals(HostTargets.invoke(aweme,"isLive"))
                || !Boolean.FALSE.equals(HostTargets.invoke(aweme,"getAd"))) return null;
        Object playing=player.invoke(panel);
        if(playing==null) return null;
        Object model=media.invoke(playing);
        // The panel may already point to the new holder while its player still serves the previous item.
        Object mediaId=HostTargets.read(model,"aid");
        if(mediaId==null || "".equals(mediaId)) mediaId=HostTargets.read(model,"sourceId");
        if(!aid.equals(mediaId)) return null;
        long total=(long)duration.invoke(playing), at=(long)position.invoke(playing);
        if(total<=0 || at<0 || at>total) return null;
        return new Snapshot(aid,at,total,playing);
    }
    static float percent(long millis,long total) {
        if(total<=0) return Float.NaN;
        // Sending exactly 100 can finish the card and trigger auto-slide.
        long target=Math.max(0L, Math.min(total-1,millis));
        return Math.min(Math.nextDown(100f),(float)(target*100d/total));
    }
    static boolean seek(Snapshot expected,Snapshot current,long millis,Method seek) throws ReflectiveOperationException {
        if(expected==null || current==null || expected.player!=current.player || !expected.aid.equals(current.aid)) return false;
        float value=percent(millis,current.duration);
        if(!Float.isFinite(value)) return false;
        seek.invoke(current.player,value);
        return true;
    }
}
