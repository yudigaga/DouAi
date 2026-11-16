package com.douai.module.feature;

import com.douai.module.config.PlaybackSpeedPresets;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Serialized by the caller; versioned, account-local rules with atomic replacement. */
public final class AuthorSpeedRuleStore {
 public static final class Rule {
  public final String id,name; public final float speed;
  public Rule(String id,String name,float speed){this.id=id;this.name=name;this.speed=speed;}
 }
 private final File directory;
 public AuthorSpeedRuleStore(File directory){this.directory=directory;}
 private static boolean id(String id){return id!=null&&id.matches("[0-9]{1,40}");}
 private File file(String account){if(!id(account))throw new IllegalArgumentException("account");return new File(directory,account+".speed");}
 private static boolean valid(Rule r){return r!=null&&id(r.id)&&r.name!=null&&r.name.length()<=200&&Float.isFinite(r.speed)&&PlaybackSpeedPresets.normalize(r.speed)==r.speed;}
 public synchronized List<Rule> list(String account)throws IOException{
  File f=file(account);if(!f.exists())return List.of();if(f.length()>1_000_000)throw new IOException("oversize rules");
  List<Rule> rows=new ArrayList<>();Set<String> ids=new HashSet<>();
  try(var in=new DataInputStream(new FileInputStream(f))){
   if(in.readInt()!=0x41535031)throw new IOException("unknown version");int n=in.readInt();if(n<0||n>500)throw new IOException("rule count");
   for(int i=0;i<n;i++){Rule r=new Rule(in.readUTF(),in.readUTF(),in.readFloat());if(!valid(r)||!ids.add(r.id))throw new IOException("invalid rule");rows.add(r);}
   if(in.read()!=-1)throw new IOException("trailing data");
  }return List.copyOf(rows);
 }
 public synchronized void put(String account,Rule rule)throws IOException{
  if(!valid(rule))throw new IllegalArgumentException("rule");List<Rule> rows=new ArrayList<>(list(account));rows.removeIf(r->r.id.equals(rule.id));rows.add(rule);
  if(rows.size()>500)throw new IOException("rule limit 500");write(account,rows);
 }
 public synchronized void remove(String account,String id)throws IOException{List<Rule> rows=new ArrayList<>(list(account));rows.removeIf(r->r.id.equals(id));write(account,rows);}
 public synchronized void clear(String account)throws IOException{write(account,List.of());}
 private void write(String account,List<Rule> rows)throws IOException{
  File target=file(account);if(!directory.isDirectory()&&!directory.mkdirs()&&!directory.isDirectory())throw new IOException("directory");
  File tmp=File.createTempFile("author-",".tmp",directory);
  try{try(var stream=new FileOutputStream(tmp);var out=new DataOutputStream(stream)){out.writeInt(0x41535031);out.writeInt(rows.size());for(Rule r:rows){out.writeUTF(r.id);out.writeUTF(r.name);out.writeFloat(r.speed);}out.flush();stream.getFD().sync();}
   Files.move(tmp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
  }finally{Files.deleteIfExists(tmp.toPath());}
 }
 public static Float choose(Float manual,Float author,Float global,boolean moduleOnly){return manual!=null?manual:author!=null?author:global!=null?global:moduleOnly?Float.valueOf(1f):null;}
}
