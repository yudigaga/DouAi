package com.douai.module.feature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Ephemeral snapshot of the current panel. Only previously displayed replies survive a fold. */
final class LoadedCommentIndex {
    static final int LIMIT=500, TEXT_LIMIT=1024, SCAN_LIMIT=1200;
    private static final Pattern ID=Pattern.compile("[1-9][0-9]{0,79}");
    static boolean id(String value) { return value!=null && ID.matcher(value).matches(); }
    static final class Row {
        final String id,root,text,asr;
        final int position;
        Row(String id,String root,String text,String asr,int position) {
            this.id=id; this.root=root; this.text=clip(text); this.asr=clip(asr); this.position=position;
        }
        private static String clip(String value) { return value==null ? "" : value.substring(0,Math.min(value.length(),TEXT_LIMIT)); }
        String match(String query) {
            String q=query.toLowerCase(Locale.ROOT);
            for(String s:new String[]{text,asr}) {
                int at=s.toLowerCase(Locale.ROOT).indexOf(q);
                if(at>=0 && !s.isEmpty()) {
                    int from=Math.max(0,Math.min(s.length(),at)-24), end=Math.min(s.length(),from+110);
                    return (from>0?"…":"")+s.substring(from,end)+(end<s.length()?"…":"");
                }
            }
            return "";
        }
    }
    private final LinkedHashMap<String,Row> rows=new LinkedHashMap<>();
    private long generation;
    private boolean active,limited;
    long open(String account,String work) { close(); active=id(account)&&id(work); return generation; }
    void close() { generation++;active=false;rows.clear();limited=false; }
    void refresh(long token) { if(active && token==generation) {rows.clear();limited=false;} }
    long token() {return generation;}
    void update(long token,List<Row> visible,boolean incomplete) {
        if(!active || generation!=token)return;
        LinkedHashMap<String,Row> next=new LinkedHashMap<>();
        limited=incomplete;
        for(Row row:visible) {
            if(!id(row.id))continue;
            if(next.containsKey(row.id))continue;
            if(next.size()>=LIMIT){limited=true;break;}
            next.put(row.id,row);
        }
        for(Row old:rows.values()) {
            if(old.root.isEmpty() || !next.containsKey(old.root) || next.containsKey(old.id))continue;
            if(next.size()>=LIMIT){limited=true;break;}
            next.put(old.id,new Row(old.id,old.root,old.text,old.asr,-1));
        }
        rows.clear(); rows.putAll(next);
    }
    List<Row> find(String query) {
        List<Row> result=new ArrayList<>();
        for(Row row:rows.values()) if(query.isEmpty() || !row.match(query).isEmpty())result.add(row);
        return result;
    }
    int size(){return rows.size();}
    boolean partial(){return limited;}
}
