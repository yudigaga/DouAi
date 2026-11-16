package com.douai.module.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.os.Build;
import android.util.Xml;
import com.caverock.androidsvg.SVG;
import com.douai.module.config.HomeUiCustomizationSettings;
import com.douai.module.feature.MediaMemoryBudget;
import org.xmlpull.v1.XmlPullParser;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts bounded static SVG to the existing content-validated WebP storage contract. */
public final class HomeIconImport {
    public static final int MAX_INPUT_BYTES=262144;
    private static final int MAX_NODES=512, MAX_DEPTH=24, MAX_PATH_DATA=32768, MAX_NUMBERS=8192;
    private static final Set<String> ELEMENTS=Set.of("svg","g","defs","path","rect","circle","ellipse",
            "line","polyline","polygon","linearGradient","radialGradient","stop","clipPath","symbol",
            "use","title","desc","style","text","tspan","textPath","switch");
    private static final Set<String> GEOMETRY=Set.of("x","y","x1","y1","x2","y2","cx","cy","r","rx","ry",
            "width","height","viewBox","d","points","transform","gradientTransform","stroke-width",
            "stroke-dasharray","stroke-dashoffset","stroke-miterlimit","font-size","offset");
    private static final Pattern NUMBER=Pattern.compile("[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?");
    private static final Pattern URL=Pattern.compile("url\\s*\\(([^)]*)\\)",Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTIVE_CSS=Pattern.compile("(?i)(?:animation|transition|behavior)\\s*:");
    private static final Pattern CSS_GEOMETRY=Pattern.compile("(?i)(?:width|height|stroke-width|stroke-dasharray|stroke-dashoffset|stroke-miterlimit|font-size)\\s*:\\s*([^;}]+)");

    private HomeIconImport() { }

    public static byte[] convert(byte[] input) throws IllegalArgumentException {
        if(input==null || input.length==0 || input.length>MAX_INPUT_BYTES)throw invalid("图标文件不能超过 256 KiB");
        if(webp(input)) {
            HomeUiCustomizationSettings.putIcon("{}","feed_comment",input);
            return input;
        }
        Bitmap bitmap=null;
        try {
            String xml=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(input)).toString();
            if(!xml.isEmpty() && xml.charAt(0)=='\ufeff')xml=xml.substring(1);
            inspect(xml);
            // A host-provided AndroidSVG must never have its process-global parser flags changed.
            if(SVG.class.getClassLoader()!=HomeIconImport.class.getClassLoader())throw invalid("当前页面未能隔离 SVG 解析库，请使用静态 WebP");
            SVG.setInternalEntitiesEnabled(false);
            SVG svg=SVG.getFromString(xml);
            RectF viewBox=svg.getDocumentViewBox();
            float width=svg.getDocumentWidth(),height=svg.getDocumentHeight();
            if(viewBox!=null) {
                if(!finite(viewBox.left) || !finite(viewBox.top) || !dimension(viewBox.width())
                        || !dimension(viewBox.height()))throw invalid("SVG viewBox 无效");
                // The declared viewport owns the canvas ratio; viewBox controls the drawing within it.
                if(!dimension(width) || !dimension(height)){width=viewBox.width();height=viewBox.height();}
            }
            if(!dimension(width) || !dimension(height))throw invalid("SVG 需要有效的 viewBox 或宽高尺寸");
            if(viewBox==null)svg.setDocumentViewBox(0,0,width,height);
            float scale=256f/Math.max(width,height);
            int outputWidth=Math.max(1,Math.min(256,Math.round(width*scale)));
            int outputHeight=Math.max(1,Math.min(256,Math.round(height*scale)));
            svg.setDocumentWidth(outputWidth);svg.setDocumentHeight(outputHeight);
            long bytes=4L*outputWidth*outputHeight+2L*HomeUiCustomizationSettings.MAX_ICON_BYTES+input.length+1024*1024;
            try(MediaMemoryBudget.Reservation reservation=MediaMemoryBudget.tryAcquire(bytes)) {
                if(reservation==null)throw invalid("图片内存繁忙，请稍后重试");
                try {
                    bitmap=Bitmap.createBitmap(outputWidth,outputHeight,Bitmap.Config.ARGB_8888);
                    bitmap.eraseColor(Color.TRANSPARENT);
                    svg.renderToCanvas(new Canvas(bitmap));
                    if(!visible(bitmap))throw invalid("SVG 没有可见图形，请检查填充和尺寸");
                    LimitedBytes encoded=new LimitedBytes();
                    Bitmap.CompressFormat format=Build.VERSION.SDK_INT>=30?Bitmap.CompressFormat.WEBP_LOSSLESS:Bitmap.CompressFormat.WEBP;
                    if(!bitmap.compress(format,100,encoded))throw invalid("无法生成 WebP 图标");
                    byte[] result=encoded.toByteArray();
                    HomeUiCustomizationSettings.putIcon("{}","feed_comment",result);
                    return result;
                } finally {
                    if(bitmap!=null){bitmap.recycle();bitmap=null;}
                }
            }
        } catch(IllegalArgumentException error) { throw error; }
        catch(OutOfMemoryError error) { throw invalid("图片内存不足，请简化 SVG"); }
        catch(Exception | LinkageError error) { throw invalid("SVG 无法解析或包含不支持的内容"); }
        finally { if(bitmap!=null)bitmap.recycle(); }
    }

    private static void inspect(String xml) throws Exception {
        String lower=xml.toLowerCase(Locale.ROOT);
        if(lower.contains("<!doctype") || lower.contains("<!entity"))throw invalid("SVG 不允许 DTD 或实体声明");
        XmlPullParser parser=Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);
        parser.setFeature("http://xmlpull.org/v1/doc/features.html#process-docdecl",false);
        parser.setInput(new StringReader(xml));
        List<Node> stack=new ArrayList<>();Map<String,Node> ids=new HashMap<>();
        Node root=null;int nodes=0,pathData=0,numbers=0,textLength=0,styleLength=0;
        for(int event=parser.nextToken();event!=XmlPullParser.END_DOCUMENT;event=parser.nextToken()) {
            if(event==XmlPullParser.DOCDECL || event==XmlPullParser.PROCESSING_INSTRUCTION)throw invalid("SVG 不允许外部声明");
            if(event==XmlPullParser.START_TAG) {
                String tag=parser.getName(),namespace=parser.getNamespace();
                if(!ELEMENTS.contains(tag) || namespace!=null && !namespace.isEmpty() && !"http://www.w3.org/2000/svg".equals(namespace)) {
                    throw invalid("SVG 包含不支持的元素："+tag);
                }
                if(++nodes>MAX_NODES || stack.size()>=MAX_DEPTH)throw invalid("SVG 结构过于复杂");
                Node node=new Node(tag);
                if(stack.isEmpty()) {
                    if(root!=null || !"svg".equals(tag))throw invalid("请选择 SVG 或静态 WebP 图标");
                    root=node;
                }else stack.get(stack.size()-1).children.add(node);
                stack.add(node);
                for(int i=0;i<parser.getAttributeCount();i++) {
                    String name=parser.getAttributeName(i),value=parser.getAttributeValue(i);
                    if(name.toLowerCase(Locale.ROOT).startsWith("on") || "base".equals(name) || "filter".equals(name)
                            || "mask".equals(name))throw invalid("SVG 不允许脚本、外部资源或不支持的滤镜");
                    if("id".equals(name)) {
                        if(value.isEmpty() || ids.put(value,node)!=null)throw invalid("SVG 元素 ID 重复或无效");
                    }
                    if("href".equals(name))node.references.add(fragment(value));
                    checkReferences(value,node);
                    if("style".equals(name)) {
                        styleLength+=value.length();if(styleLength>16384)throw invalid("SVG 样式过多");
                        checkStyle(value);
                    }
                    if(GEOMETRY.contains(name)) {
                        if(value.toLowerCase(Locale.ROOT).contains("nan") || value.toLowerCase(Locale.ROOT).contains("inf"))throw invalid("SVG 数值无效");
                        Matcher matcher=NUMBER.matcher(value);
                        while(matcher.find()) {
                            if(++numbers>MAX_NUMBERS || !finite(Double.parseDouble(matcher.group())))throw invalid("SVG 坐标过多或超出范围");
                        }
                    }
                    if("d".equals(name) || "points".equals(name)) {
                        pathData+=value.length();if(pathData>MAX_PATH_DATA)throw invalid("SVG 路径过于复杂");
                    }
                }
            } else if(event==XmlPullParser.END_TAG) {
                if(stack.isEmpty())throw invalid("SVG 结构无效");
                stack.remove(stack.size()-1);
            } else if(event==XmlPullParser.TEXT || event==XmlPullParser.CDSECT || event==XmlPullParser.ENTITY_REF) {
                String text=parser.getText();
                if(text!=null) {
                    textLength+=text.length();if(textLength>16384)throw invalid("SVG 文本或样式过多");
                    if(!stack.isEmpty() && "style".equals(stack.get(stack.size()-1).tag)) {
                        checkStyle(text);checkReferences(text,stack.get(stack.size()-1));
                    }
                }
            }
        }
        if(root==null || !stack.isEmpty())throw invalid("SVG 结构无效");
        expanded(root,ids,0);
    }

    private static void checkStyle(String style) {
        String lower=style.toLowerCase(Locale.ROOT);
        if(lower.contains("@") || lower.contains("\\") || lower.contains("expression(") || ACTIVE_CSS.matcher(style).find()) {
            throw invalid("SVG 不允许外部字体、样式导入或动态样式");
        }
        // These can cause unbounded extra rendering passes even on a small destination bitmap.
        if(lower.matches("(?s).*\\b(?:filter|mask)\\s*:.*"))throw invalid("SVG 暂不支持滤镜或蒙版");
        Matcher properties=CSS_GEOMETRY.matcher(style);
        while(properties.find()) {
            String value=properties.group(1).toLowerCase(Locale.ROOT);
            if(value.contains("nan") || value.contains("inf"))throw invalid("SVG 样式数值无效");
            Matcher numbers=NUMBER.matcher(value);int count=0;
            while(numbers.find())if(++count>MAX_NUMBERS || !finite(Double.parseDouble(numbers.group())))throw invalid("SVG 样式数值超出范围");
        }
    }

    private static void checkReferences(String value,Node node) {
        Matcher matcher=URL.matcher(value);
        while(matcher.find()) {
            String reference=matcher.group(1).trim();
            if(reference.length()>=2 && (reference.charAt(0)=='\'' || reference.charAt(0)=='"')) {
                char quote=reference.charAt(0);
                if(reference.charAt(reference.length()-1)!=quote)throw invalid("SVG 引用无效");
                reference=reference.substring(1,reference.length()-1).trim();
            }
            node.references.add(fragment(reference));
        }
    }

    private static String fragment(String value) {
        value=value.trim();
        if(!value.matches("#[A-Za-z_][A-Za-z0-9_.:-]{0,127}"))throw invalid("SVG 只允许内部图形引用，不允许外链");
        return value.substring(1);
    }

    private static int expanded(Node node,Map<String,Node> ids,int depth) {
        if(depth>MAX_DEPTH || node.visiting)throw invalid("SVG 引用循环或嵌套过深");
        if(node.cost!=0)return node.cost;
        node.visiting=true;int cost=1;
        for(Node child:node.children) {
            cost+=expanded(child,ids,depth+1);if(cost>4096)throw invalid("SVG 引用展开过大");
        }
        for(String reference:node.references) {
            Node target=ids.get(reference);
            if(target==null)throw invalid("SVG 引用了不存在的图形");
            cost+=expanded(target,ids,depth+1);if(cost>4096)throw invalid("SVG 引用展开过大");
        }
        node.visiting=false;node.cost=cost;return cost;
    }

    private static boolean visible(Bitmap bitmap) {
        int[] row=new int[bitmap.getWidth()];
        for(int y=0;y<bitmap.getHeight();y++) {
            bitmap.getPixels(row,0,row.length,0,y,row.length,1);
            for(int color:row)if(Color.alpha(color)!=0)return true;
        }
        return false;
    }
    private static boolean dimension(float value) { return value>=.000001f && finite(value); }
    private static boolean finite(double value) { return Double.isFinite(value) && Math.abs(value)<=1000000; }
    private static boolean webp(byte[] bytes) {
        return bytes.length>=12 && bytes[0]=='R' && bytes[1]=='I' && bytes[2]=='F' && bytes[3]=='F'
                && bytes[8]=='W' && bytes[9]=='E' && bytes[10]=='B' && bytes[11]=='P';
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
    private static final class Node {
        final String tag;final List<Node> children=new ArrayList<>();final List<String> references=new ArrayList<>();
        boolean visiting;int cost;
        Node(String tag) { this.tag=tag; }
    }
    private static final class LimitedBytes extends ByteArrayOutputStream {
        LimitedBytes() { super(HomeUiCustomizationSettings.MAX_ICON_BYTES); }
        @Override public synchronized void write(int value) {
            if(count>=HomeUiCustomizationSettings.MAX_ICON_BYTES)throw invalid("SVG 转换后超过 32 KiB，请简化图形");
            super.write(value);
        }
        @Override public synchronized void write(byte[] bytes,int offset,int length) {
            if(length>HomeUiCustomizationSettings.MAX_ICON_BYTES-count)throw invalid("SVG 转换后超过 32 KiB，请简化图形");
            super.write(bytes,offset,length);
        }
    }
}
