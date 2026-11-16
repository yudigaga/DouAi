package com.douai.module.feature;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/** Versioned account-local temporary rules; disk operations run on the owning serial worker. */
public final class TemporaryFeedRuleStore {
    public enum Kind { AUTHOR, MUSIC }
    public static final class Rule {
        public final Kind kind; public final String id, name; public final long createdAt,expiresAt;
        public Rule(Kind kind,String id,String name,long createdAt,long expiresAt){
            this.kind=kind;this.id=id;this.name=name;this.createdAt=createdAt;this.expiresAt=expiresAt;
        }
        public String key(){return kind.name()+":"+id;}
    }
    private static final int MAGIC=0x54524631,LIMIT=1000;
    private final File directory;
    public TemporaryFeedRuleStore(File directory) { this.directory=directory; }
    private File file(String account){
        if(!PlaybackBookmarkStore.id(account))throw new IllegalArgumentException("account");
        return new File(directory,account+".rules");
    }
    public List<Rule> list(String account,long now)throws IOException{
        File file=file(account);if(!file.exists())return List.of();
        if(file.length()>2_000_000)throw new IOException("oversize rules");
        LinkedHashMap<String,Rule> values=new LinkedHashMap<>(),remaining=new LinkedHashMap<>();boolean expired=false;
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(file)))){
            if(in.readInt()!=MAGIC)throw new IOException("unknown rules version");
            int count=in.readInt();if(count<0||count>LIMIT)throw new IOException("invalid rule count");
            for(int n=0;n<count;n++){
                int kind=in.readUnsignedByte();if(kind>=Kind.values().length)throw new IOException("invalid kind");
                Rule r=new Rule(Kind.values()[kind],in.readUTF(),in.readUTF(),in.readLong(),in.readLong());
                if(!valid(r))throw new IOException("invalid rule");
                if(now<r.expiresAt)remaining.put(r.key(),r);else expired=true;
                if(r.createdAt<=now&&now<r.expiresAt)values.put(r.key(),r);
            }
            if(in.read()!=-1)throw new IOException("invalid rules tail");
        }
        if(expired)write(account,remaining.values());
        return List.copyOf(values.values());
    }
    private static boolean valid(Rule r){return r!=null&&r.kind!=null&&PlaybackBookmarkStore.id(r.id)
            &&r.name!=null&&r.name.length()<=200&&r.createdAt>=0&&r.expiresAt>r.createdAt
            &&r.expiresAt-r.createdAt<=8*86_400_000L;}
    public void put(String account,Rule rule,long now)throws IOException {
        file(account);if(!valid(rule)||rule.createdAt>now||rule.expiresAt<=now)throw new IllegalArgumentException("rule");
        LinkedHashMap<String,Rule> next=new LinkedHashMap<>();
        for(Rule r:list(account,now))next.put(r.key(),r);
        next.put(rule.key(),rule);if(next.size()>LIMIT)throw new IOException("rule limit");
        write(account,next.values());
    }
    public void remove(String account,String key,long now)throws IOException {
        List<Rule> next=new ArrayList<>(list(account,now));next.removeIf(r->r.key().equals(key));write(account,next);
    }
    public void clear(String account)throws IOException {
        Files.deleteIfExists(file(account).toPath());
    }
    private void write(String account,Collection<Rule> rules)throws IOException {
        File target=file(account);
        if(!directory.isDirectory()&&!directory.mkdirs()&&!directory.isDirectory())throw new IOException("directory");
        File temp=File.createTempFile("rules-",".tmp",directory);
        try{
            try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(temp)))){
                out.writeInt(MAGIC);out.writeInt(rules.size());
                for(Rule r:rules){out.writeByte(r.kind.ordinal());out.writeUTF(r.id);out.writeUTF(r.name);out.writeLong(r.createdAt);out.writeLong(r.expiresAt);}
            }
            Files.move(temp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temp.toPath());}
    }
    public static boolean matches(List<Rule> rules,String author,String music,long now){
        for(Rule r:rules)if(r.createdAt<=now&&now<r.expiresAt
                &&r.id.equals(r.kind==Kind.AUTHOR?author:music))return true;
        return false;
    }
}
