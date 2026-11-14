package com.douai.module.feature;

/** Main-thread, intentionally not persisted. Superseded and cancelled requests cannot revive. */
final class BookmarkJump {
    static final long TTL=12000L;
    private long serial,active,expires,millis;
    private String account,aid;
    long start(String account,String aid,long millis,long now){
        this.account=account;this.aid=aid;this.millis=millis;expires=now+TTL;return active=++serial;
    }
    boolean pending(long token,String uid,long now){
        if(active==0||active!=token)return false;
        if(!account.equals(uid)||now>=expires){cancel(token);return false;}return true;
    }
    long consume(long token,String uid,String visibleAid,long now){
        if(!pending(token,uid,now)||!aid.equals(visibleAid))return -1;
        cancel(token);return millis;
    }
    void cancel(long token){if(active==token){active=0;account=null;aid=null;}}
}
