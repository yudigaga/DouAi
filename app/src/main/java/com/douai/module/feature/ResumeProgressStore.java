package com.douai.module.feature;
import java.io.*;
import java.nio.file.*;
import java.util.*;
/** Independent account V1 file, used only on the playback serial IO executor. */
final class ResumeProgressStore {
 static final long RETENTION=30L*86400000;
 static final class Entry {final String aid;final long positionMs,durationMs,updatedAt;Entry(String a,long p,long d,long t){aid=a;positionMs=p;durationMs=d;updatedAt=t;}}
 private final File directory;private final Map<String,Long> epochs=new java.util.concurrent.ConcurrentHashMap<>();
 ResumeProgressStore(File f){directory=f;}
 long epoch(String a){return epochs.getOrDefault(a,0L);}
 private File file(String a){if(!PlaybackBookmarkStore.id(a))throw new IllegalArgumentException("account");return new File(directory,a+".resume");}
 private List<Entry> read(String a,long now)throws IOException {
  File f=file(a);List<Entry> rows=new ArrayList<>();if(!f.exists())return rows;
  if(f.length()>512000)throw new IOException("resume size");
  try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(f)))){
   if(in.readInt()!=0x44525031)throw new IOException("resume version");int n=in.readInt();if(n<0||n>2000)throw new IOException("resume count");
   for(int i=0;i<n;i++){Entry e=new Entry(in.readUTF(),in.readLong(),in.readLong(),in.readLong());
    if(!PlaybackBookmarkStore.id(e.aid)||e.positionMs<0||e.durationMs<=0||e.positionMs>e.durationMs)throw new IOException("resume entry");
    if(now-e.updatedAt<RETENTION&&e.updatedAt<=now)rows.add(e);
   }if(in.read()!=-1)throw new IOException("resume tail");
  }return rows;
 }
 synchronized Entry get(String a,String id,long now)throws IOException{for(Entry e:read(a,now))if(e.aid.equals(id))return e;return null;}
 synchronized void save(String a,Entry e,long epoch,long now)throws IOException {
  if(epoch!=epoch(a))return;if(!PlaybackBookmarkStore.id(e.aid)||e.positionMs<0||e.durationMs<=0||e.positionMs>e.durationMs)return;
  List<Entry> rows=read(a,now);rows.removeIf(x->x.aid.equals(e.aid));rows.add(e);rows.sort(Comparator.comparingLong(x->-x.updatedAt));
  if(rows.size()>2000)rows=new ArrayList<>(rows.subList(0,2000));write(a,rows);
 }
 synchronized void clear(String a)throws IOException{epochs.put(a,epoch(a)+1);write(a,Collections.emptyList());}
 private void write(String a,List<Entry> rows)throws IOException {
  File target=file(a);if(!directory.isDirectory()&&!directory.mkdirs()&&!directory.isDirectory())throw new IOException("directory");
  File tmp=File.createTempFile("resume-",".tmp",directory);
  try{try(FileOutputStream f=new FileOutputStream(tmp);DataOutputStream out=new DataOutputStream(new BufferedOutputStream(f))){
   out.writeInt(0x44525031);out.writeInt(rows.size());for(Entry e:rows){out.writeUTF(e.aid);out.writeLong(e.positionMs);out.writeLong(e.durationMs);out.writeLong(e.updatedAt);}out.flush();f.getFD().sync();}
   Files.move(tmp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
  }finally{Files.deleteIfExists(tmp.toPath());}
 }
}
