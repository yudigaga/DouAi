package com.douai.module.feature;
import java.io.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
final class CommentBookmarkStore {
 static final int MAX_ROWS=500,MAX_ROW=16*1024,MAX_BYTES=8*1024*1024,V1=0x44434231;
 final File directory;
 private final java.util.function.BooleanSupplier current;
 CommentBookmarkStore(File directory,java.util.function.BooleanSupplier current){this.directory=directory;this.current=current;}
 CommentBookmarkStore(File directory){this(directory,()->true);}
 static final class Entry {
  final String aid,cid,authorUid,author,text,asr,rootCid,replyCid,replyUid,replyAuthor,replyText;
  final long savedAt;final boolean truncated;
  Entry(String aid,String cid,String authorUid,String author,String text,String asr,String rootCid,String replyCid,String replyUid,String replyAuthor,String replyText,long savedAt,boolean truncated){
   this.aid=aid;this.cid=cid;this.authorUid=authorUid;this.author=author;this.text=text;this.asr=asr;this.rootCid=rootCid;this.replyCid=replyCid;this.replyUid=replyUid;this.replyAuthor=replyAuthor;this.replyText=replyText;this.savedAt=savedAt;this.truncated=truncated;
  }
 }
 private File file(String account){if(!LoadedCommentIndex.id(account))throw new IllegalArgumentException("无法确认账号");return new File(directory,account+".localV1");}
 private static byte[] encode(Entry e)throws IOException{
  if(e==null||!LoadedCommentIndex.id(e.aid)||!LoadedCommentIndex.id(e.cid)||e.savedAt<0)throw new IOException("无效收藏");
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();
  try(DataOutputStream out=new DataOutputStream(bytes)){
   for(String value:new String[]{e.aid,e.cid,e.authorUid,e.author,e.text,e.asr,e.rootCid,e.replyCid,e.replyUid,e.replyAuthor,e.replyText}){
    byte[] utf=(value==null?"":value).getBytes(StandardCharsets.UTF_8);
    if(utf.length>MAX_ROW)throw new IOException("收藏内容过长");out.writeInt(utf.length);out.write(utf);
   }
   out.writeLong(e.savedAt);out.writeBoolean(e.truncated);
  }
  if(bytes.size()>MAX_ROW)throw new IOException("单条收藏超过16 KiB");return bytes.toByteArray();
 }
 private static Entry decode(byte[] bytes)throws IOException{
  try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes))){
   String[] f=new String[11];for(int i=0;i<f.length;i++){
    int n=in.readInt();if(n<0||n>in.available())throw new IOException("收藏字段损坏");byte[] utf=new byte[n];in.readFully(utf);
    f[i]=new String(utf,StandardCharsets.UTF_8);if(!Arrays.equals(utf,f[i].getBytes(StandardCharsets.UTF_8)))throw new IOException("UTF-8损坏");
   }
   Entry e=new Entry(f[0],f[1],f[2],f[3],f[4],f[5],f[6],f[7],f[8],f[9],f[10],in.readLong(),in.readBoolean());
   if(in.read()!=-1)throw new IOException("收藏尾部损坏");encode(e);return e;
  }
 }
 synchronized List<Entry> list(String account)throws IOException{
  File target=file(account);List<Entry> rows=new ArrayList<>();if(!target.exists())return rows;
  if(target.length()>MAX_BYTES)throw new IOException("收藏库超过8 MiB，保留原文件");
  try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(target)))){
   int magic=in.readInt(),count=in.readInt();if(magic!=V1||count<0||count>MAX_ROWS)throw new IOException("收藏库格式损坏，保留原文件");
   Set<String> keys=new HashSet<>();
   for(int i=0;i<count;i++){int n=in.readInt();if(n<0||n>MAX_ROW)throw new IOException("收藏行超额");byte[] bytes=new byte[n];in.readFully(bytes);Entry e=decode(bytes);
    if(!keys.add(e.aid+":"+e.cid))throw new IOException("重复收藏记录");rows.add(e);}
   if(in.read()!=-1)throw new IOException("收藏库尾部损坏");
  }return rows;
 }
 synchronized void add(String account,Entry entry)throws IOException{
  encode(entry);List<Entry> rows=list(account);rows.removeIf(e->e.aid.equals(entry.aid)&&e.cid.equals(entry.cid));
  if(rows.size()>=MAX_ROWS)throw new IOException("已达500条上限，原收藏保留");rows.add(0,entry);write(account,rows);
 }
 synchronized void remove(String account,String aid,String cid)throws IOException{
  List<Entry> rows=list(account);if(rows.removeIf(e->e.aid.equals(aid)&&e.cid.equals(cid)))write(account,rows);
 }
 private void write(String account,List<Entry> rows)throws IOException{
  File target=file(account);ByteArrayOutputStream bytes=new ByteArrayOutputStream();
  try(DataOutputStream out=new DataOutputStream(bytes)){
   out.writeInt(V1);out.writeInt(rows.size());for(Entry e:rows){byte[] row=encode(e);out.writeInt(row.length);out.write(row);}
  }
  if(bytes.size()>MAX_BYTES)throw new IOException("收藏库超过8 MiB，原收藏保留");
  Files.createDirectories(directory.toPath());File temp=File.createTempFile("comments-",".tmp",directory);
  try{try(FileOutputStream stream=new FileOutputStream(temp)){stream.write(bytes.toByteArray());stream.getFD().sync();}
   if(!current.getAsBoolean())throw new IOException("账号已更换，未写入收藏");
   Files.move(temp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
  }finally{Files.deleteIfExists(temp.toPath());}
 }
}
