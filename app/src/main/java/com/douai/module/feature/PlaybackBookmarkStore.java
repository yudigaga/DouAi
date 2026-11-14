package com.douai.module.feature;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/** The main file stays compatible with the shipped DPB1 reader; optional display data is separate. */
final class PlaybackBookmarkStore {
    static final int MAX_ROWS=2000, MAX_NOTE=160, MAX_AUTHOR=80, MAX_DESCRIPTION=360, MAX_COVER_BYTES=12*1024;
    private static final int V1=0x44504231, DETAIL_VERSION=0x444d4431;
    final File directory;
    PlaybackBookmarkStore(File directory){this.directory=directory;}
    static final class Entry {
        final String id,aid,note,author,description; final long millis,created;
        Entry(String id,String aid,long millis,String note,long created,String author,String description){
            this.id=id; this.aid=aid; this.millis=millis; this.note=note; this.created=created;
            this.author=author;this.description=description;
        }
        String label(){return time(millis)+"  "+(note.isEmpty()?"作品 "+aid:note);}
        boolean matches(String query){return query.isEmpty()||aid.contains(query)||time(millis).contains(query)
                ||note.toLowerCase(Locale.ROOT).contains(query)||author.toLowerCase(Locale.ROOT).contains(query)
                ||description.toLowerCase(Locale.ROOT).contains(query);}
    }
    static String time(long millis){long seconds=Math.max(0,millis)/1000;
        return String.format(Locale.ROOT,"%02d:%02d",seconds/60,seconds%60);}
    static boolean id(String v){if(v==null||v.isEmpty()||v.length()>24||v.charAt(0)=='0')return false;
        for(int i=0;i<v.length();i++) if(v.charAt(i)<'0'||v.charAt(i)>'9')return false; return true;}
    private File file(String uid){if(!id(uid))throw new IllegalArgumentException("account"); return new File(directory,uid+".bookmarks");}
    static String clip(String value,int max){String s=value==null?"":value.trim();
        if(s.length()<=max)return s;return s.substring(0,Character.isHighSurrogate(s.charAt(max-1))?max-1:max);}
    private static String note(String value){return clip(value,MAX_NOTE);}
    private ArrayList<Entry> read(String uid)throws IOException{
        File file=file(uid); ArrayList<Entry> rows=new ArrayList<>(); if(!file.exists())return rows;
        if(file.length()>3_000_000) throw new IOException("oversize bookmark file");
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(file)))){
            int magic=in.readInt(),size=in.readInt();
            if(magic!=V1 || size<0 || size>MAX_ROWS)throw new IOException("invalid bookmark file");
            for(int i=0;i<size;i++){
                Entry e=new Entry(in.readUTF(),in.readUTF(),in.readLong(),in.readUTF(),in.readLong(),"","");
                if(e.id.length()!=36 || !id(e.aid) || e.millis<0 || e.note.length()>MAX_NOTE)throw new IOException("invalid bookmark");
                rows.add(e);
            }
            if(in.read()!=-1)throw new IOException("invalid bookmark tail");
        }
        return rows;
    }
    private interface Writer{void write(DataOutputStream out)throws IOException;}
    private void atomic(File target,Writer writer)throws IOException{
        if(!directory.isDirectory()&&!directory.mkdirs()&&!directory.isDirectory())throw new IOException("directory");
        File temp=File.createTempFile("bookmarks-",".tmp",directory);
        try {
            try(FileOutputStream stream=new FileOutputStream(temp);
                DataOutputStream out=new DataOutputStream(new BufferedOutputStream(stream))){
                writer.write(out);
                out.flush(); stream.getFD().sync();
            }
            Files.move(temp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp.toPath()); }
    }
    private void write(String uid,List<Entry> rows)throws IOException{
        atomic(file(uid),out->{out.writeInt(V1);out.writeInt(rows.size());
            for(Entry e:rows){out.writeUTF(e.id);out.writeUTF(e.aid);out.writeLong(e.millis);out.writeUTF(e.note);out.writeLong(e.created);}
        });
    }
    private File detailsFile(String uid){if(!id(uid))throw new IllegalArgumentException("account");return new File(directory,uid+".bookmark-details");}
    private Map<String,String[]> readDetails(String uid)throws IOException{
        File file=detailsFile(uid); Map<String,String[]> values=new HashMap<>();
        if(!file.exists())return values;
        if(file.length()>3_000_000)throw new IOException("oversize bookmark details");
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(file)))){
            int version=in.readInt(),size=in.readInt();
            if(version!=DETAIL_VERSION||size<0||size>MAX_ROWS)throw new IOException("invalid bookmark details");
            for(int i=0;i<size;i++){
                String aid=in.readUTF(),author=in.readUTF(),description=in.readUTF();
                if(!id(aid)||author.length()>MAX_AUTHOR||description.length()>MAX_DESCRIPTION)throw new IOException("invalid bookmark metadata");
                values.put(aid,new String[]{author,description});
            }
            if(in.read()!=-1)throw new IOException("invalid details tail");
        }
        return values;
    }
    private void writeDetails(String uid,Map<String,String[]> details)throws IOException{
        File file=detailsFile(uid);
        if(details.isEmpty()){Files.deleteIfExists(file.toPath());return;}
        atomic(file,out->{out.writeInt(DETAIL_VERSION);out.writeInt(details.size());
            for(Map.Entry<String,String[]> row:details.entrySet()){
                out.writeUTF(row.getKey());out.writeUTF(row.getValue()[0]);out.writeUTF(row.getValue()[1]);
            }
        });
    }
    List<Entry> list(String uid,String search)throws IOException{
        List<Entry> rows=read(uid);
        try{
            Map<String,String[]> details=readDetails(uid);
            for(int i=0;i<rows.size();i++){
                Entry e=rows.get(i);String[] d=details.get(e.aid);
                if(d!=null)rows.set(i,new Entry(e.id,e.aid,e.millis,e.note,e.created,d[0],d[1]));
            }
        }catch(IOException|SecurityException ignored){} // A damaged optional file cannot hide the user's time points.
        String q=note(search).toLowerCase(Locale.ROOT);
        if(!q.isEmpty()) rows.removeIf(e->!e.matches(q));
        return rows;
    }
    Entry add(String uid,String aid,long millis,String memo,long created)throws IOException{
        return add(uid,aid,millis,memo,created,"","");
    }
    Entry add(String uid,String aid,long millis,String memo,long created,String author,String description)throws IOException{
        if(!id(aid)||millis<0)throw new IllegalArgumentException("invalid bookmark");
        ArrayList<Entry> rows=read(uid); if(rows.size()>=MAX_ROWS)throw new IOException("已达到 "+MAX_ROWS+" 条书签上限");
        Entry e=new Entry(UUID.randomUUID().toString(),aid,millis,note(memo),created,clip(author,MAX_AUTHOR),clip(description,MAX_DESCRIPTION));
        rows.add(0,e); write(uid,rows);
        try{enrich(uid,rows,aid,e.author,e.description);}catch(IOException|SecurityException ignored){}
        return e;
    }
    boolean edit(String uid,String key,String memo)throws IOException{
        ArrayList<Entry> rows=read(uid); for(int i=0;i<rows.size();i++){Entry old=rows.get(i);
            if(old.id.equals(key)){rows.set(i,new Entry(old.id,old.aid,old.millis,note(memo),old.created,old.author,old.description));write(uid,rows);return true;}}
        return false;
    }
    boolean remove(String uid,String key)throws IOException{
        ArrayList<Entry> rows=read(uid);Entry removed=null;
        for(Entry e:rows)if(e.id.equals(key)){removed=e;break;}
        if(removed==null)return false;
        rows.remove(removed);write(uid,rows);
        if(!containsAid(rows,removed.aid)){
            try{Files.deleteIfExists(cover(uid,removed.aid).toPath());}catch(IOException|SecurityException ignored){}
            try{
                Map<String,String[]> details=readDetails(uid);
                if(details.remove(removed.aid)!=null)writeDetails(uid,details);
            }catch(IOException|SecurityException ignored){}
        }
        return true;
    }
    boolean enrich(String uid,String aid,String author,String description)throws IOException{
        return enrich(uid,read(uid),aid,author,description);
    }
    private boolean enrich(String uid,List<Entry> rows,String aid,String author,String description)throws IOException{
        if(!containsAid(rows,aid))return false;
        String name=clip(author,MAX_AUTHOR),desc=clip(description,MAX_DESCRIPTION);
        if(name.isEmpty()&&desc.isEmpty())return false;
        Map<String,String[]> details=readDetails(uid);String[] prior=details.get(aid);
        if(prior!=null){
            if(!prior[0].isEmpty())name=prior[0];if(!prior[1].isEmpty())desc=prior[1];
            if(prior[0].equals(name)&&prior[1].equals(desc))return false;
        }
        // A downgraded release can delete bookmarks without knowing about this sidecar.
        Set<String> live=new HashSet<>();for(Entry row:rows)live.add(row.aid);details.keySet().retainAll(live);
        details.put(aid,new String[]{name,desc});writeDetails(uid,details);return true;
    }
    File cover(String uid,String aid){
        if(!id(uid)||!id(aid))throw new IllegalArgumentException("invalid bookmark owner");
        return new File(new File(directory,"cover-"+uid),aid+".jpg");
    }
    private static boolean containsAid(List<Entry> rows,String aid){for(Entry row:rows)if(row.aid.equals(aid))return true;return false;}
    boolean needsCover(String uid,String aid)throws IOException{
        return !cover(uid,aid).isFile()&&containsAid(read(uid),aid);
    }
    // All store mutations, including this final commit after the separate network task, use the single store IO worker.
    boolean saveCoverIfReferenced(String uid,String aid,byte[] jpeg)throws IOException{
        if(jpeg==null||jpeg.length==0||jpeg.length>MAX_COVER_BYTES||!needsCover(uid,aid))return false;
        File target=cover(uid,aid), parent=target.getParentFile();
        Files.createDirectories(parent.toPath());File temp=File.createTempFile("thumb-",".part",parent);
        try{
            Files.write(temp.toPath(),jpeg);Files.move(temp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            return true;
        }finally{Files.deleteIfExists(temp.toPath());}
    }
}
