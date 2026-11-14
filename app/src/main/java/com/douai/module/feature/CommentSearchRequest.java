package com.douai.module.feature;

/** One user request at a time; a panel becoming visible is not evidence its comments have loaded. */
final class CommentSearchRequest {
    static final long WAIT_MS = 8_000;
    enum Step { OPEN_COMMENTS, WAIT, SHOW, CANCEL, TIMEOUT, STALE }
    static final class Ticket {
        final String account, work;
        final Object screen;
        final long expires;
        Object panel;
        boolean opening;
        Ticket(String account,String work,Object screen,long expires) {
            this.account=account; this.work=work; this.screen=screen; this.expires=expires;
        }
    }
    private Ticket active;
    Ticket begin(String account,String work,Object screen,long now) {
        Ticket r=new Ticket(account,work,screen,now+WAIT_MS);
        if(active!=null && now<active.expires && account.equals(active.account)
                && work.equals(active.work) && screen==active.screen) {
            r.opening=active.opening; r.panel=active.panel;
        }
        return active=r;
    }
    void cancel(){active=null;}
    boolean pending(Ticket r){return r!=null && active==r;}
    void opened(Ticket r,boolean accepted){
        if(pending(r)){if(accepted)r.opening=true;else cancel();}
    }
    void bind(String account,String work,Object screen,Object panel) {
        if(active!=null && active.panel==null && screen==active.screen
                && active.account.equals(account) && active.work.equals(work))active.panel=panel;
    }
    boolean bound(Object panel){return panel!=null && active!=null && active.panel==panel;}
    Step step(Ticket r,String account,boolean sourceCurrent,long now,Object panel,boolean ready) {
        if(!pending(r))return Step.STALE;
        if(!sourceCurrent || !r.account.equals(account) || (r.panel!=null && r.panel!=panel)) {
            cancel();return Step.CANCEL;
        }
        if(now>=r.expires){cancel();return Step.TIMEOUT;}
        if(panel!=null){
            r.panel=panel;
            if(ready){cancel();return Step.SHOW;}
        }else if(!r.opening){return Step.OPEN_COMMENTS;}
        return Step.WAIT;
    }
}
