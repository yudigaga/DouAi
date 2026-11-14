package com.douai.module.feature;

import java.lang.ref.WeakReference;

/** A pause observed before the drawer opens, never a pause caused by opening it. Main thread only. */
final class SidebarPausedPlayback {
    private WeakReference<Object> owner=new WeakReference<>(null), holder=new WeakReference<>(null), player=new WeakReference<>(null);
    private String aid;
    private boolean active,seenDialog;
    private long deadline;
    void capture(Object owner,Object holder,Object player,String aid,boolean paused){
        clear();
        if(!paused||owner==null||holder==null||player==null||aid==null||aid.isEmpty())return;
        this.owner=new WeakReference<>(owner);this.holder=new WeakReference<>(holder);
        this.player=new WeakReference<>(player);this.aid=aid;
    }
    Object holder(){return holder.get();}
    Object player(){return player.get();}
    boolean matches(Object o,Object h,Object p,String id){
        return aid!=null && aid.equals(id) && o!=null && h!=null && p!=null
                && owner.get()==o && holder.get()==h && player.get()==p;
    }
    boolean enter(Object owner,Object holder,Object player,String aid,long now){
        if(!matches(owner,holder,player,aid)){clear();return false;}
        active=true;deadline=now+2000;return true;
    }
    boolean guards(Object o,Object h,Object p,String id){return active&&matches(o,h,p,id);}
    boolean active(){return active;}
    boolean keep(boolean settingsVisible,long now){
        if(!active)return false;
        if(settingsVisible){seenDialog=true;return true;}
        if(seenDialog){seenDialog=false;deadline=now+400;}
        if(now>=deadline){clear();return false;}
        return true;
    }
    void clear(){active=false;seenDialog=false;aid=null;owner.clear();holder.clear();player.clear();}
}
