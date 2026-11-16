package com.douai.module.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import com.douai.module.config.HomeUiCustomizationSettings;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk={28,35})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HomeIconImportTest {
    @Test public void explicitViewportKeepsCanvasRatioAndItsPreserveAspectRatioSetting() {
        for(boolean stretch:new boolean[]{false,true}) {
            Bitmap bitmap=decode(convert("<svg xmlns='http://www.w3.org/2000/svg' width='100' height='100' viewBox='0 0 200 100'"
                    +(stretch?" preserveAspectRatio='none'":"")
                    +"><circle cx='100' cy='50' r='30' fill='#ff0000'/></svg>"));
            try {
                assertEquals(256,bitmap.getWidth());assertEquals(256,bitmap.getHeight());
                assertEquals(0,Color.alpha(bitmap.getPixel(0,0)));
                assertTrue(Color.alpha(bitmap.getPixel(128,128))>200);
                if(stretch)assertTrue(Color.alpha(bitmap.getPixel(128,70))>200);
                else assertEquals(0,Color.alpha(bitmap.getPixel(128,70)));
            } finally {bitmap.recycle();}
        }
    }
    @Test public void existingStaticWebpRetainsItsExactBytesAndObjectIdentity() {
        Bitmap bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.RED);
        ByteArrayOutputStream encoded=new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.WEBP,100,encoded));bitmap.recycle();
        byte[] input=encoded.toByteArray();assertSame(input,HomeIconImport.convert(input));
        assertArrayEquals(input,HomeIconImport.convert(input));
    }
    @Test public void rendersCirclesAndPathsOnATransparentCanvas() {
        byte[] output=convert("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'>"
                +"<circle cx='16' cy='16' r='10' fill='#ff0000'/><path d='M12 12h8v8h-8z' fill='#0000ff'/></svg>");
        Bitmap bitmap=decode(output);
        try {
            assertEquals(256,bitmap.getWidth());assertEquals(256,bitmap.getHeight());
            assertEquals(0,Color.alpha(bitmap.getPixel(0,0)));
            assertTrue(Color.red(bitmap.getPixel(128,65))>200);
            assertTrue(Color.blue(bitmap.getPixel(128,128))>200);
            assertTrue(output.length<=HomeUiCustomizationSettings.MAX_ICON_BYTES);
            HomeUiCustomizationSettings.putIcon("{}","feed_comment",output);
        } finally {bitmap.recycle();}
    }
    @Test public void viewBoxWithoutIntrinsicSizePreservesItsAspectRatioAndOffset() {
        Bitmap bitmap=decode(convert("<svg viewBox='10 20 100 50'><rect x='20' y='30' width='80' height='30' fill='red'/></svg>"));
        try {
            assertEquals(256,bitmap.getWidth());assertEquals(128,bitmap.getHeight());
            assertEquals(0,Color.alpha(bitmap.getPixel(0,0)));
            assertTrue(Color.red(bitmap.getPixel(128,64))>200);
        } finally {bitmap.recycle();}
    }
    @Test public void intrinsicPixelDimensionsWorkAndTinyRatiosStayAtLeastOnePixel() {
        Bitmap bitmap=decode(convert("<svg width='24px' height='48px'><path d='M0 0h24v48H0z' fill='#00ff00'/></svg>"));
        try {assertEquals(128,bitmap.getWidth());assertEquals(256,bitmap.getHeight());assertTrue(Color.green(bitmap.getPixel(64,128))>200);}
        finally {bitmap.recycle();}
        bitmap=decode(convert("<svg viewBox='0 0 1 1000'><rect width='1' height='1000' fill='red'/></svg>"));
        try {assertEquals(1,bitmap.getWidth());assertEquals(256,bitmap.getHeight());}finally{bitmap.recycle();}
    }
    @Test public void acceptsCommonStaticGradientsClippingAndInternalUse() {
        Bitmap bitmap=decode(convert("<svg viewBox='0 0 24 24'><defs><linearGradient id='g'><stop stop-color='red'/>"
                +"<stop offset='1' stop-color='blue'/></linearGradient><path id='p' d='M2 2h20v20H2z'/></defs>"
                +"<use href='#p' fill='url(#g)'/></svg>"));
        try {assertTrue(Color.alpha(bitmap.getPixel(128,128))>200);assertEquals(0,Color.alpha(bitmap.getPixel(0,0)));}
        finally {bitmap.recycle();}
    }
    @Test public void svgSourceCanExceedWebpStorageLimitWhileEncodedResultRemainsBounded() {
        String svg="<svg viewBox='0 0 100 50'><!--"+"x".repeat(40*1024)+"--><rect x='10' y='10' width='80' height='30' fill='red'/></svg>";
        byte[] input=svg.getBytes(StandardCharsets.UTF_8),output=HomeIconImport.convert(input);
        assertTrue(input.length>32768);assertTrue(output.length<=32768);assertNotSame(input,output);
        Bitmap bitmap=decode(output);try{assertEquals(256,bitmap.getWidth());assertEquals(128,bitmap.getHeight());}finally{bitmap.recycle();}
    }
    @Test public void rejectsExternalResourcesActiveContentAndEntityDeclarationsBeforeRendering() {
        for(String body:new String[]{"<image href='https://example.invalid/a.png'/>","<use href='file:///private/a.svg#p'/>",
                "<style>@import url(https://example.invalid/a.css);</style>","<style>@font-face{src:url(https://example.invalid/f.woff)}</style>",
                "<path fill='url(https://example.invalid/p.svg#g)' d='M0 0h24v24z'/>","<script>alert(1)</script>",
                "<animate attributeName='x' values='0;1'/>","<rect width='24' height='24' onclick='x()'/>",
                "<foreignObject><div>hidden unsupported content</div></foreignObject>","<pattern id='p' width='.00000001' height='.00000001'/>"}) {
            assertThrows(body,IllegalArgumentException.class,()->convert("<svg viewBox='0 0 24 24'>"+body+"</svg>"));
        }
        assertThrows(IllegalArgumentException.class,()->convert("<!DOCTYPE svg [<!ENTITY p SYSTEM 'https://example.invalid/a'>]><svg viewBox='0 0 24 24'>&p;</svg>"));
    }
    @Test public void rejectsMalformedMissingUnboundedAndTransparentGeometry() {
        for(String svg:new String[]{"not svg","<svg viewBox='0 0 24 24'><path></svg>","<svg><circle r='5'/></svg>",
                "<svg viewBox='0 0 0 24'/>","<svg width='Infinity' height='24'/>","<svg width='1e30' height='24'/>",
                "<svg viewBox='0 0 24 24'><rect width='24' height='24' fill='none'/></svg>"}) {
            assertThrows(svg,IllegalArgumentException.class,()->convert(svg));
        }
        assertThrows(IllegalArgumentException.class,()->HomeIconImport.convert(new byte[HomeIconImport.MAX_INPUT_BYTES+1]));
        assertThrows(IllegalArgumentException.class,()->HomeIconImport.convert(null));
        assertThrows(IllegalArgumentException.class,()->HomeIconImport.convert(new byte[]{(byte)0x89,'P','N','G'}));
    }
    @Test public void rejectsReferenceCyclesExcessiveExpansionAndStructuralBudgets() {
        assertThrows(IllegalArgumentException.class,()->convert("<svg viewBox='0 0 24 24'><defs><g id='a'><use href='#a'/></g></defs><use href='#a'/></svg>"));
        StringBuilder definitions=new StringBuilder("<svg viewBox='0 0 24 24'><defs><path id='p0' d='M0 0h1v1z'/>");
        for(int i=1;i<20;i++)definitions.append("<g id='p").append(i).append("'><use href='#p").append(i-1).append("'/><use href='#p").append(i-1).append("'/></g>");
        definitions.append("</defs><use href='#p19'/></svg>");
        assertThrows(IllegalArgumentException.class,()->convert(definitions.toString()));
        String deep="<svg viewBox='0 0 24 24'>"+"<g>".repeat(40)+"<rect width='24' height='24'/>"+"</g>".repeat(40)+"</svg>";
        assertThrows(IllegalArgumentException.class,()->convert(deep));
        String many="<svg viewBox='0 0 24 24'>"+"<rect width='1' height='1'/>".repeat(600)+"</svg>";
        assertThrows(IllegalArgumentException.class,()->convert(many));
        String paths="<svg viewBox='0 0 24 24'><path d='M0 0"+"L1 1 ".repeat(8000)+"'/></svg>";
        assertThrows(IllegalArgumentException.class,()->convert(paths));
    }
    private static byte[] convert(String source){return HomeIconImport.convert(source.getBytes(StandardCharsets.UTF_8));}
    private static Bitmap decode(byte[] bytes){Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);assertNotNull(bitmap);return bitmap;}
}
