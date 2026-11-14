package com.douai.module.feature;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;

/** Only the host-provided transcript; the recognition status and timbre label aren't speech. */
final class CommentTranscript {
    private CommentTranscript() {}
    private static final LinkedHashMap<Class<?>,Field> AUDIO = new LinkedHashMap<>(), TEXT = new LinkedHashMap<>();

    static String read(Object comment) {
        if (comment == null) return null;
        try {
            Field field = mapped(comment.getClass(),false);
            Object audio = field == null ? null : field.get(comment);
            Field text = audio == null ? null : mapped(audio.getClass(),true);
            String value = text == null ? null : (String) text.get(audio);
            if (value != null) for (int i=0; i<value.length(); i++)
                if (!Character.isWhitespace(value.charAt(i)) && !Character.isSpaceChar(value.charAt(i))) return value;
        } catch (IllegalAccessException | RuntimeException | LinkageError ignored) {}
        return null;
    }
    private static synchronized Field mapped(Class<?> owner, boolean string) {
        LinkedHashMap<Class<?>,Field> cache=string?TEXT:AUDIO;
        if(cache.containsKey(owner))return cache.get(owner);
        Field result=null;
        for(Class<?> c=owner;c!=null;c=c.getSuperclass()){
            result=CommentToolsHook.fieldForJsonName(c,string?"asr_text":"comment_audio");
            if(result==null)continue;
            if(string && result.getType()!=String.class)result=null;
            else result.setAccessible(true);
            break;
        }
        if(cache.size()>=16)cache.clear();
        cache.put(owner,result);
        return result;
    }
}
