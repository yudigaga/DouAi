package com.douai.module.feature;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Outline;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
@LooperMode(LooperMode.Mode.PAUSED)
public final class LiquidGlassRenderingTest {
    @Test public void largePortraitAndLandscapeCaptureFit512WithoutUpscaling() {
        assertArrayEquals(new int[]{230, 512}, LiquidGlassBackdrop.captureSize(1080, 2400));
        assertArrayEquals(new int[]{512, 230}, LiquidGlassBackdrop.captureSize(2400, 1080));
        assertArrayEquals(new int[]{120, 80}, LiquidGlassBackdrop.captureSize(120, 80));
        assertArrayEquals(new int[]{1, 512}, LiquidGlassBackdrop.captureSize(1, Integer.MAX_VALUE));
    }

    @Test public void oneFlightPerWindowAnd15HzLimitBoundCaptureWork() {
        try (Fixture f = new Fixture(); LiquidGlassBackdrop backdrop = f.backdrop()) {
            backdrop.setSource(f.surface);
            backdrop.refresh(); backdrop.refresh();
            assertEquals(1, f.copy.requests.size());
            try (LiquidGlassBackdrop other = f.backdrop()) {
                other.setSource(f.surface); other.refresh();
                assertEquals(1, f.copy.requests.size());
            }
            f.copy.complete(0, PixelCopy.SUCCESS);
            assertNotNull(backdrop.frame());
            f.now = 66; backdrop.refresh();
            assertEquals(1, f.copy.requests.size());
            f.now = 67; backdrop.refresh();
            assertEquals(2, f.copy.requests.size());
            f.copy.complete(1, PixelCopy.SUCCESS);
        }
        assertBudgetAvailable();
    }

    @Test public void sourceChangeAndCloseDiscardLateCopiesWithoutRecyclingInFlight() {
        try (Fixture f = new Fixture(); LiquidGlassBackdrop backdrop = f.backdrop()) {
            backdrop.setSource(f.surface); backdrop.refresh();
            Bitmap pending = f.copy.requests.get(0).bitmap;
            backdrop.setSource(null);
            assertFalse("PixelCopy may still be writing", pending.isRecycled());
            f.copy.complete(0, PixelCopy.SUCCESS);
            assertTrue(pending.isRecycled()); assertNull(backdrop.frame());
            f.now = 100; backdrop.setSource(f.surface); backdrop.refresh();
            Bitmap closing = f.copy.requests.get(1).bitmap;
            backdrop.close();
            assertFalse(closing.isRecycled());
            f.copy.complete(1, PixelCopy.SUCCESS);
            assertTrue(closing.isRecycled());
            f.copy.complete(1, PixelCopy.SUCCESS);
        }
        assertBudgetAvailable();
    }

    @Test public void budgetExhaustionFallsBackBeforeAllocatingOrStartingPixelCopy() {
        try (Fixture f = new Fixture(); LiquidGlassBackdrop backdrop = f.backdrop();
             MediaMemoryBudget.Reservation all = MediaMemoryBudget.tryAcquire(MediaMemoryBudget.CAPACITY_BYTES)) {
            assertNotNull(all);
            backdrop.setSource(f.surface); backdrop.refresh();
            assertTrue(f.copy.requests.isEmpty()); assertNull(backdrop.frame());
        }
        assertBudgetAvailable();
    }

    @Test public void movedOrResizedSourcesCannotPublishStaleGeometry() {
        try (Fixture f = new Fixture(); LiquidGlassBackdrop backdrop = f.backdrop()) {
            backdrop.setSource(f.surface); backdrop.refresh();
            f.surface.setTranslationX(20);
            f.copy.complete(0, PixelCopy.SUCCESS);
            assertNull(backdrop.frame()); assertTrue(f.copy.requests.get(0).bitmap.isRecycled());
            f.now = 100; backdrop.refresh(); f.copy.complete(1, PixelCopy.SUCCESS);
            assertEquals(new Rect(60, 60, 300, 380), backdrop.sourceBounds());
            f.surface.layout(40, 60, 400, 400);
            assertNull(backdrop.frame());
        }
        assertBudgetAvailable();
    }

    @Test public void ordinaryForegroundViewIsNeverCapturedAsItsOwnBackdrop() {
        try (Fixture f = new Fixture(); LiquidGlassBackdrop backdrop = f.backdrop()) {
            backdrop.setSource(f.label); backdrop.refresh();
            assertTrue(f.copy.requests.isEmpty()); assertNull(backdrop.frame());
            assertTrue(backdrop.sourceBounds().isEmpty());
        }
    }

    @Test public void drawableChangesOnlyBackgroundAndClearsResourcesBeforeFrameRecycle() {
        try (Fixture f = new Fixture(); LiquidGlassBackdrop backdrop = f.backdrop();
             LiquidGlassDrawable glass = new LiquidGlassDrawable(f.label, true, backdrop)) {
            f.label.setBackground(glass);
            glass.setBounds(0, 0, 160, 48);
            glass.setLight(Float.NaN, Float.POSITIVE_INFINITY);
            f.label.setAlpha(.7f); f.label.setTextColor(Color.MAGENTA);
            backdrop.setSource(f.surface); backdrop.refresh(); f.copy.complete(0, PixelCopy.SUCCESS);
            Bitmap image = Bitmap.createBitmap(160, 48, Bitmap.Config.ARGB_8888);
            glass.draw(new Canvas(image));
            assertEquals("Foreground", f.label.getText().toString());
            assertEquals(Color.MAGENTA, f.label.getCurrentTextColor());
            assertEquals(.7f, f.label.getAlpha(), 0f);
            assertEquals(0, f.label.effectChanges);
            Bitmap sampled = backdrop.frame();
            boolean[] notified = {false};
            Runnable notification = () -> {
                assertFalse("Shader owners must be notified before recycle", sampled.isRecycled());
                notified[0] = true;
            };
            backdrop.addObserver(notification);
            backdrop.setSource(null);
            backdrop.removeObserver(notification);
            assertTrue(notified[0]);
            assertTrue(sampled.isRecycled());
            glass.draw(new Canvas(image));
            image.recycle();
        }
        assertBudgetAvailable();
    }

    @Test public void failureAndDetachReturnReservationsAndLeaveCompatibilityMaterial() {
        try (Fixture f = new Fixture(); LiquidGlassBackdrop backdrop = f.backdrop()) {
            backdrop.setSource(f.surface); backdrop.refresh();
            f.copy.complete(0, PixelCopy.ERROR_SOURCE_NO_DATA);
            assertNull(backdrop.frame()); assertBudgetAvailable();
            f.now = 200; backdrop.refresh(); f.copy.complete(1, PixelCopy.SUCCESS);
            Bitmap sampled = backdrop.frame(); assertNotNull(sampled);
            f.root.removeView(f.surface);
            assertNull(backdrop.frame()); assertTrue(sampled.isRecycled());
        }
        assertBudgetAvailable();
    }

    @Test public void textureReadsUseTheSameBoundedOwnedBufferAndRejectUnknownCropTransforms() {
        try (Fixture f = new Fixture(); LiquidGlassBackdrop backdrop = f.backdrop()) {
            VideoTexture texture = new VideoTexture(f.activity);
            f.root.addView(texture, new FrameLayout.LayoutParams(1080, 1920));
            texture.layout(0, 0, 1080, 1920);
            backdrop.setSource(texture); backdrop.refresh();
            assertNotNull(backdrop.frame());
            assertEquals(288, backdrop.frame().getWidth());
            assertEquals(512, backdrop.frame().getHeight());
            assertEquals(1, texture.reads);
            texture.cropped = true; f.now = 100; backdrop.refresh();
            assertNull(backdrop.frame()); assertEquals(1, texture.reads);
        }
        assertBudgetAvailable();
    }

    @Test @Config(sdk = 28) public void oldAndroidUsesCompatibilityWithoutCapturingUnusedFrames() {
        try (Fixture f = new Fixture(); LiquidGlassBackdrop backdrop = f.backdrop();
             LiquidGlassDrawable glass = new LiquidGlassDrawable(f.label, true, backdrop)) {
            backdrop.setSource(f.surface); backdrop.refresh();
            assertNull(backdrop.frame()); assertTrue(f.copy.requests.isEmpty());
            Bitmap image = Bitmap.createBitmap(160, 48, Bitmap.Config.ARGB_8888);
            glass.setBounds(0, 0, 160, 48); glass.draw(new Canvas(image));
            assertEquals(0, f.label.effectChanges); image.recycle();
        }
        assertBudgetAvailable();
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void navigationMaterialUsesItsWholeCapsuleRegardlessOfDescendantResourceIds() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout navigation = new FrameLayout(activity);
        activity.setContentView(navigation); navigation.layout(0, 0, 240, 64);
        try {
            for (int id : new int[]{0x7f0a30de, 0x7f0a3fb2}) {
                navigation.setId(id);
                try (LiquidGlassBackdrop backdrop = new LiquidGlassBackdrop(navigation, ignored -> {});
                     LiquidGlassDrawable glass = new LiquidGlassDrawable(navigation, true, backdrop)) {
                    Bitmap image = Bitmap.createBitmap(240, 64, Bitmap.Config.ARGB_8888);
                    try {
                        glass.setBounds(0, 0, 240, 64); glass.draw(new Canvas(image));
                        assertTrue("Navigation must not shrink into an old Feed icon or progress shape",
                                Color.alpha(image.getPixel(180, 24)) > 0);
                        assertEquals("The navigation capsule keeps its transparent outer corner",
                                0, Color.alpha(image.getPixel(0, 0)));
                    } finally { image.recycle(); }
                }
            }
        } finally { activity.finish(); }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void whiteTextInsideNavigationUsesATranslucentDarkMaterial() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout navigation = new FrameLayout(activity);
        TextView whiteText = new TextView(activity);
        whiteText.setText("推荐"); whiteText.setTextColor(Color.WHITE);
        navigation.addView(whiteText); activity.setContentView(navigation);
        try (LiquidGlassBackdrop backdrop = new LiquidGlassBackdrop(navigation, ignored -> {});
             LiquidGlassDrawable glass = new LiquidGlassDrawable(navigation, true, backdrop)) {
            Bitmap image = Bitmap.createBitmap(160, 48, Bitmap.Config.ARGB_8888);
            glass.setBounds(0, 0, 160, 48); glass.draw(new Canvas(image));
            int pixel = image.getPixel(80, 24);
            assertTrue("White text must receive a dark backing", Color.red(pixel) < 100);
            assertTrue("Navigation must retain more than half of the media", Color.alpha(pixel) <= 128);
            image.recycle();
        } finally { activity.finish(); }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers = "notnight-mdpi")
    public void deepDominantWhiteTextOutweighsASmallDarkBadgeInDayMode() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout navigation = new FrameLayout(activity);
        TextView badge = new TextView(activity);
        badge.setText("1"); badge.setTextColor(Color.BLACK);
        navigation.addView(badge, new FrameLayout.LayoutParams(12, 12));
        FrameLayout parent = navigation;
        for (int depth = 0; depth < 5; depth++) {
            FrameLayout child = new FrameLayout(activity);
            parent.addView(child, new FrameLayout.LayoutParams(160, 48)); parent = child;
        }
        TextView title = new TextView(activity);
        title.setText("推荐频道"); title.setTextColor(Color.WHITE);
        parent.addView(title, new FrameLayout.LayoutParams(144, 40));
        activity.setContentView(navigation);
        navigation.measure(View.MeasureSpec.makeMeasureSpec(160, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY));
        navigation.layout(0, 0, 160, 48);
        try (LiquidGlassBackdrop backdrop = new LiquidGlassBackdrop(navigation, ignored -> {});
             LiquidGlassDrawable glass = new LiquidGlassDrawable(navigation, true, backdrop)) {
            Bitmap image = Bitmap.createBitmap(160, 48, Bitmap.Config.ARGB_8888);
            image.eraseColor(Color.GRAY);
            glass.setBounds(0, 0, 160, 48); glass.draw(new Canvas(image));
            assertTrue("The main white title needs dark tint even beyond three levels",
                    Color.red(image.getPixel(80, 24)) < Color.red(Color.GRAY));
            assertEquals(Color.WHITE, title.getCurrentTextColor());
            assertEquals("推荐频道", title.getText().toString());
            image.recycle();
        } finally { activity.finish(); }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers = "notnight-mdpi")
    public void navigationCompatibilityMaterialPreservesTheUnderlyingCheckerContrast() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TextView label = new TextView(activity);
        label.setText("Foreground"); label.setTextColor(Color.WHITE);
        activity.setContentView(label);
        try (LiquidGlassBackdrop backdrop = new LiquidGlassBackdrop(label, ignored -> {});
             LiquidGlassDrawable glass = new LiquidGlassDrawable(label, true, backdrop)) {
            Bitmap image = checker(160, 64);
            glass.setBounds(0, 0, 160, 64); glass.draw(new Canvas(image));
            assertTrue("Navigation must transmit at least half of the video contrast",
                    Color.red(image.getPixel(56, 32)) - Color.red(image.getPixel(72, 32)) >= 128);
            image.eraseColor(Color.TRANSPARENT); glass.draw(new Canvas(image));
            assertTrue("Compatibility tint must keep the navigation translucent",
                    Color.alpha(image.getPixel(80, 32)) <= 128);
            assertEquals(Color.WHITE, label.getCurrentTextColor());
            assertEquals("Foreground", label.getText().toString());
            image.recycle();
        } finally { activity.finish(); }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers = "notnight-mdpi")
    public void nearlyTransparentAncestorCannotDominateTextMaterial() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout navigation = new FrameLayout(activity);
        TextView visible = new TextView(activity); visible.setText("设置"); visible.setTextColor(Color.BLACK);
        navigation.addView(visible, new FrameLayout.LayoutParams(80, 32));
        FrameLayout faded = new FrameLayout(activity); faded.setAlpha(.01f);
        TextView white = new TextView(activity); white.setText("本来不可见的白色标题"); white.setTextColor(Color.WHITE);
        faded.addView(white, new FrameLayout.LayoutParams(144, 40));
        navigation.addView(faded, new FrameLayout.LayoutParams(160, 48)); activity.setContentView(navigation);
        navigation.measure(View.MeasureSpec.makeMeasureSpec(160, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY));
        navigation.layout(0, 0, 160, 48);
        try (LiquidGlassBackdrop backdrop = new LiquidGlassBackdrop(navigation, ignored -> {});
             LiquidGlassDrawable glass = new LiquidGlassDrawable(navigation, true, backdrop)) {
            Bitmap image = Bitmap.createBitmap(160, 48, Bitmap.Config.ARGB_8888); image.eraseColor(Color.GRAY);
            glass.setBounds(0, 0, 160, 48); glass.draw(new Canvas(image));
            assertTrue("An almost hidden white title cannot outweigh the visible black text",
                    Color.red(image.getPixel(80, 24)) > Color.red(Color.GRAY));
            image.recycle();
        } finally { activity.finish(); }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers = "notnight-mdpi")
    public void navigationOutlineKeepsItsBoundsWithoutAddingOrInflatingHostShadows() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        View navigation = new View(activity); navigation.setClipToOutline(true);
        activity.setContentView(navigation); navigation.layout(0, 0, 240, 64);
        try {
            for (int color : new int[]{Color.TRANSPARENT, 0x20000000, Color.BLACK}) {
                ColorDrawable original = new ColorDrawable(color); original.setBounds(0, 0, 240, 64);
                navigation.setBackground(color == Color.TRANSPARENT ? null : original);
                try (LiquidGlassBackdrop backdrop = new LiquidGlassBackdrop(navigation, ignored -> {});
                     LiquidGlassDrawable glass = new LiquidGlassDrawable(navigation, true, backdrop)) {
                    glass.setBounds(0, 0, 240, 64); Outline outline = new Outline(); glass.getOutline(outline);
                    Rect bounds = new Rect(); assertTrue(outline.getRect(bounds));
                    assertEquals("Navigation content keeps the full clipping bounds", new Rect(0, 0, 240, 64), bounds);
                    assertEquals(32f, outline.getRadius(), 0f);
                    assertEquals("Glass cannot add or strengthen the original host shadow",
                            Math.min(Color.alpha(color) / 255f, .45f), outline.getAlpha(), .001f);
                }
            }
        } finally { activity.finish(); }
    }

    private static Bitmap checker(int width, int height) {
        Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            image.setPixel(x, y, ((x / 16 + y / 16) & 1) == 0 ? Color.BLACK : Color.WHITE);
        }
        return image;
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void nativeRuntimeShaderAcceptsTheBackgroundRefractionProgram() throws Exception {
        Bitmap source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        try {
            Class<?> api = Class.forName("com.douai.module.feature.LiquidGlassDrawable$Api33");
            java.lang.reflect.Method create = api.getDeclaredMethod("create", Bitmap.class);
            create.setAccessible(true);
            assertNotNull("The actual AGSL program and child bitmap shader must compile", create.invoke(null, source));
        } finally { source.recycle(); }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void newFramesKeepTheCompiledProgramAndDisconnectOldBitmapBeforeRecycling() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        VideoTexture texture = new VideoTexture(activity);
        TextView label = new TextView(activity);
        root.addView(texture, new FrameLayout.LayoutParams(240, 320));
        root.addView(label, new FrameLayout.LayoutParams(160, 48));
        activity.setContentView(root);
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 2400);
        texture.layout(40, 60, 280, 380); label.layout(70, 80, 230, 128);
        long[] now = {0};
        java.lang.reflect.Field programField = LiquidGlassDrawable.class.getDeclaredField("sampled");
        java.lang.reflect.Field bitmapField = LiquidGlassDrawable.class.getDeclaredField("sampledBitmap");
        programField.setAccessible(true); bitmapField.setAccessible(true);
        try (LiquidGlassBackdrop backdrop = new LiquidGlassBackdrop(root, ignored -> {},
                (s, b, l, h) -> fail("A texture never uses PixelCopy"), () -> now[0]);
             LiquidGlassDrawable glass = new LiquidGlassDrawable(label, true, backdrop)) {
            glass.setBounds(0, 0, 160, 48);
            backdrop.setSource(texture); backdrop.refresh();
            Bitmap old = backdrop.frame(); assertNotNull(old);
            ShaderCanvas canvas = new ShaderCanvas();
            glass.draw(canvas);
            Shader program = canvas.program; assertNotNull("The real shader path must run", program);
            assertTrue(shaderInput(program) instanceof BitmapShader);
            boolean[] detachedBeforeRecycle = {false};
            Runnable notification = () -> {
                try {
                    detachedBeforeRecycle[0] = programField.get(glass) == program && bitmapField.get(glass) == null
                            && !(shaderInput(program) instanceof BitmapShader) && !old.isRecycled();
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            };
            backdrop.addObserver(notification);
            now[0] = 100; backdrop.refresh();
            backdrop.removeObserver(notification);
            assertTrue("The same program must detach old bitmap input before recycling", detachedBeforeRecycle[0]);
            assertTrue(old.isRecycled());
            canvas.program = null; glass.draw(canvas);
            assertSame(program, canvas.program);
            assertSame(backdrop.frame(), bitmapField.get(glass));
            assertTrue(shaderInput(program) instanceof BitmapShader);
            backdrop.setSource(null);
            assertSame(program, programField.get(glass));
            assertFalse(shaderInput(program) instanceof BitmapShader);
            glass.close(); assertNull(programField.get(glass));
        } finally { activity.finish(); }
        assertBudgetAvailable();
    }

    private static Shader shaderInput(Shader program) throws ReflectiveOperationException {
        // This test is pinned to Android 15; inspect the actual child graph, not a mocked setter.
        java.lang.reflect.Field uniforms = RuntimeShader.class.getDeclaredField("mShaderUniforms");
        uniforms.setAccessible(true);
        return (Shader) ((java.util.Map<?, ?>) uniforms.get(program)).get("image");
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void perspectiveTiltKeepsTheActualBackgroundShaderAndFullWindowMapping() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        VideoTexture texture = new VideoTexture(activity);
        TextView label = new TextView(activity);
        root.addView(texture, new FrameLayout.LayoutParams(240, 320));
        root.addView(label, new FrameLayout.LayoutParams(160, 48));
        activity.setContentView(root);
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 2400);
        texture.layout(40, 60, 280, 380); label.layout(70, 80, 230, 128);
        label.setTranslationX(1.5f); label.setTranslationY(-1.25f);
        label.setRotationX(.35f); label.setRotationY(-.35f);
        try (LiquidGlassBackdrop backdrop = new LiquidGlassBackdrop(root, ignored -> {});
             LiquidGlassDrawable glass = new LiquidGlassDrawable(label, true, backdrop)) {
            backdrop.setSource(texture); backdrop.refresh(); assertNotNull(backdrop.frame());
            glass.setBounds(0, 0, 160, 48);
            ShaderCanvas canvas = new ShaderCanvas(); glass.draw(canvas);
            assertNotNull("Default 3D tilt must retain real refraction", canvas.program);
            java.lang.reflect.Field transform = LiquidGlassDrawable.class.getDeclaredField("anchorTransform");
            transform.setAccessible(true);
            Matrix actual = (Matrix) transform.get(glass);
            assertFalse("The regression must exercise camera perspective", actual.isAffine());
            Matrix expected = new Matrix(), rootGlobal = new Matrix();
            label.transformMatrixToGlobal(expected); root.transformMatrixToGlobal(rootGlobal);
            float[] origin = {0, 0}; rootGlobal.mapPoints(origin);
            expected.postTranslate(-origin[0], -origin[1]);
            float[] wanted = {0, 0, 160, 48, 80, 24}, mapped = wanted.clone();
            expected.mapPoints(wanted); actual.mapPoints(mapped);
            assertArrayEquals("Keep perspective and account for the window origin", wanted, mapped, 1.1f);
        } finally { activity.finish(); }
        assertBudgetAvailable();
    }

    private static final class ShaderCanvas extends Canvas {
        Shader program;
        @Override public boolean isHardwareAccelerated() { return true; }
        @Override public void drawRoundRect(RectF rect, float rx, float ry, Paint paint) {
            // Native compilation/input ownership stays real; only the unavailable GPU submission is omitted.
            if (paint.getShader() instanceof RuntimeShader) program = paint.getShader();
        }
    }

    private static void assertBudgetAvailable() {
        MediaMemoryBudget.Reservation all = MediaMemoryBudget.tryAcquire(MediaMemoryBudget.CAPACITY_BYTES);
        assertNotNull("All sampled buffers must release their reservations", all);
        all.close();
    }

    private static final class Copy implements LiquidGlassBackdrop.Copier {
        final List<Request> requests = new ArrayList<>();
        @Override public void request(SurfaceView source, Bitmap bitmap,
                                      PixelCopy.OnPixelCopyFinishedListener listener, Handler handler) {
            requests.add(new Request(bitmap, listener));
        }
        void complete(int index, int result) {
            Request request = requests.get(index);
            if (!request.bitmap.isRecycled()) request.bitmap.eraseColor(Color.BLUE);
            request.listener.onPixelCopyFinished(result);
        }
    }
    private static final class Request {
        final Bitmap bitmap;
        final PixelCopy.OnPixelCopyFinishedListener listener;
        Request(Bitmap bitmap, PixelCopy.OnPixelCopyFinishedListener listener) {
            this.bitmap = bitmap; this.listener = listener;
        }
    }
    private static final class ValidSurface extends Surface {
        ValidSurface() { super(new SurfaceTexture(0)); }
        @Override public boolean isValid() { return true; }
    }
    private static final class VideoSurface extends SurfaceView {
        final Surface validSurface = new ValidSurface();
        final SurfaceHolder holder = (SurfaceHolder) Proxy.newProxyInstance(SurfaceHolder.class.getClassLoader(),
                new Class<?>[]{SurfaceHolder.class}, (proxy, method, args) -> {
                    if ("getSurface".equals(method.getName())) return validSurface;
                    if ("getSurfaceFrame".equals(method.getName())) return new Rect(0, 0, getWidth(), getHeight());
                    if ("isCreating".equals(method.getName())) return false;
                    return null;
                });
        VideoSurface(Activity activity) { super(activity); }
        @Override public SurfaceHolder getHolder() { return holder; }
    }
    private static final class VideoTexture extends TextureView {
        int reads;
        boolean cropped;
        VideoTexture(Activity activity) { super(activity); }
        @Override public boolean isAvailable() { return true; }
        @Override public Bitmap getBitmap(Bitmap bitmap) { reads++; bitmap.eraseColor(Color.CYAN); return bitmap; }
        @Override public Matrix getTransform(Matrix matrix) {
            matrix.reset(); if (cropped) matrix.setScale(1.5f, 1.5f); return matrix;
        }
    }
    private static final class Fixture implements AutoCloseable {
        final Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        final FrameLayout root = new FrameLayout(activity);
        final VideoSurface surface = new VideoSurface(activity);
        final Foreground label = new Foreground(activity);
        final Copy copy = new Copy();
        long now;
        Fixture() {
            root.addView(surface, new FrameLayout.LayoutParams(240, 320));
            root.addView(label, new FrameLayout.LayoutParams(160, 48));
            label.setText("Foreground");
            activity.setContentView(root);
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1080, 2400);
            surface.layout(40, 60, 280, 380);
            label.layout(70, 80, 230, 128);
        }
        LiquidGlassBackdrop backdrop() { return new LiquidGlassBackdrop(root, ignored -> {}, copy, () -> now); }
        @Override public void close() {
            activity.finish(); surface.validSurface.release(); ShadowLooper.shadowMainLooper().idle();
        }
    }
    private static final class Foreground extends TextView {
        int effectChanges;
        Foreground(Activity activity) { super(activity); }
        @Override public void setRenderEffect(RenderEffect effect) {
            effectChanges++; super.setRenderEffect(effect);
        }
    }
}
