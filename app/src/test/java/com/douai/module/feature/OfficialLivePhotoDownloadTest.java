package com.douai.module.feature;

import com.douai.module.config.LocalOptions;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.io.BufferedInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class OfficialLivePhotoDownloadTest {
    @Test public void copiesActualEncodedContainerToChosenTreeAndRemovesOnlyOwnCache() throws Exception {
        DownloadDocumentTreeTest.RecordingDocuments documents = DownloadDocumentTreeTest.register();
        AtomicReference<File> owned = new AtomicReference<>();
        File external = File.createTempFile("host-live-", ".jpg");
        Files.writeString(external.toPath(), "encoded-live-photo");
        try (MockWebServer server = source()) {
            OfficialLivePhotoDownload.Encoder encoder = encoder((image, video) -> {
                assertEquals("source-image", Files.readString(image.toPath()));
                assertEquals("source-video", Files.readString(video.toPath()));
                owned.set(image.getParentFile());
                return new OfficialLivePhotoDownload.Encoded(0, false,
                        Collections.singletonList(external.getAbsolutePath()));
            }, true);
            DownloadTransfer transfer = new DownloadTransfer(); transfer.start();
            OfficialLivePhotoDownload.save(RuntimeEnvironment.getApplication(), plan(server),
                    DownloadDocumentTreeTest.TREE, encoder, transfer);
            assertEquals(1, documents.files.size());
            String id = documents.files.keySet().iterator().next();
            assertEquals("custom_01.jpg", documents.names.get(id));
            assertEquals("encoded-live-photo", Files.readString(documents.files.get(id).toPath()));
            assertFalse(owned.get().exists());
            assertTrue("host-owned encoding output must survive cleanup", external.exists());
            assertEquals(3, transfer.completedFiles);
        } finally { external.delete(); }
    }

    @Test public void refusesUnencodedPairAndFailedOrAlreadyPublishedResults() throws Exception {
        File image = File.createTempFile("plain-image-", ".jpg");
        File video = File.createTempFile("plain-video-", ".mp4");
        Files.writeString(image.toPath(), "ordinary image");
        try {
            assertRefused(new OfficialLivePhotoDownload.Encoded(0, false,
                    Arrays.asList(image.getAbsolutePath(), video.getAbsolutePath())), true);
            assertRefused(new OfficialLivePhotoDownload.Encoded(7, false,
                    Collections.singletonList(image.getAbsolutePath())), true);
            assertRefused(new OfficialLivePhotoDownload.Encoded(0, true,
                    Collections.singletonList(image.getAbsolutePath())), true);
            assertRefused(new OfficialLivePhotoDownload.Encoded(0, false,
                    Collections.singletonList(image.getAbsolutePath())), false);
        } finally { image.delete(); video.delete(); }
    }

    @Test public void acceptsEncoderThatRewritesInputImageOnlyAfterLivePhotoValidation() throws Exception {
        DownloadDocumentTreeTest.RecordingDocuments documents = DownloadDocumentTreeTest.register();
        AtomicReference<File> input = new AtomicReference<>();
        try (MockWebServer server = source()) {
            OfficialLivePhotoDownload.Encoder encoder = encoder((image, video) -> {
                input.set(image);
                Files.writeString(image.toPath(), "encoded-in-place");
                return new OfficialLivePhotoDownload.Encoded(0, false,
                        Collections.singletonList(image.getAbsolutePath()));
            }, true);
            DownloadTransfer transfer = new DownloadTransfer(); transfer.start();
            OfficialLivePhotoDownload.save(RuntimeEnvironment.getApplication(), plan(server),
                    DownloadDocumentTreeTest.TREE, encoder, transfer);
            File saved = documents.files.values().iterator().next();
            assertEquals("encoded-in-place", Files.readString(saved.toPath()));
            assertFalse(input.get().exists());
        }
    }

    @Test public void cancelledEncodingCannotPublishOrDowngradeAndCleansCache() throws Exception {
        DownloadDocumentTreeTest.RecordingDocuments documents = DownloadDocumentTreeTest.register();
        AtomicReference<File> owned = new AtomicReference<>();
        DownloadTransfer transfer = new DownloadTransfer(); transfer.start();
        try (MockWebServer server = source()) {
            OfficialLivePhotoDownload.Encoder encoder = encoder((image, video) -> {
                owned.set(image.getParentFile());
                transfer.cancel();
                return new OfficialLivePhotoDownload.Encoded(0, false,
                        Collections.singletonList(image.getAbsolutePath()));
            }, true);
            try {
                OfficialLivePhotoDownload.save(RuntimeEnvironment.getApplication(), plan(server),
                        DownloadDocumentTreeTest.TREE, encoder, transfer);
                fail("cancelled encoding must not publish");
            } catch (CancellationException expected) { }
            assertTrue(documents.names.isEmpty());
            assertFalse(owned.get().exists());
        }
    }

    @Test public void encodingFailureLeavesDestinationEmptyAndCleansDownloads() throws Exception {
        DownloadDocumentTreeTest.RecordingDocuments documents = DownloadDocumentTreeTest.register();
        AtomicReference<File> owned = new AtomicReference<>();
        try (MockWebServer server = source()) {
            OfficialLivePhotoDownload.Encoder encoder = encoder((image, video) -> {
                owned.set(image.getParentFile());
                throw new IOException("encoding failed");
            }, true);
            DownloadTransfer transfer = new DownloadTransfer(); transfer.start();
            try {
                OfficialLivePhotoDownload.save(RuntimeEnvironment.getApplication(), plan(server),
                        DownloadDocumentTreeTest.TREE, encoder, transfer);
                fail("encoding failure must remain a failure");
            } catch (IOException expected) { }
            assertTrue(documents.names.isEmpty());
            assertFalse(owned.get().exists());
        }
    }

    @Test public void planRequiresSelectedLiveItemAndPreservesConfiguredNaming() {
        Aweme aweme = new Aweme();
        java.util.Map<String, Object> settings = new java.util.HashMap<>();
        settings.put("download_custom_name", true);
        settings.put("download_name_template", "#desc#_#type#_#index#");
        OfficialLivePhotoDownload.Plan selected = OfficialLivePhotoDownload.plan(aweme, 1, new LocalOptions(settings));
        assertEquals("https://media.test/image.jpg", selected.imageUrl);
        assertEquals("https://media.test/live.mp4", selected.videoUrl);
        assertEquals("caption_livephoto_2.jpg", selected.name);
        try {
            OfficialLivePhotoDownload.plan(aweme, 0, new LocalOptions(settings));
            fail("ordinary photo is not a LivePhoto");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void cleanLivePlanUsesCleanPhotoAndRejectsOnlyWatermarkedFallbacks() {
        Aweme aweme = new Aweme();
        Image live = aweme.images.get(1);
        live.watermark_free_download_url_list = Collections.singletonList("https://media.test/clean.jpg");
        LocalOptions clean = new LocalOptions(Collections.singletonMap("remove_watermark", true));
        assertEquals("https://media.test/clean.jpg", OfficialLivePhotoDownload.plan(aweme, 1, clean).imageUrl);
        live.watermark_free_download_url_list = Collections.emptyList();
        live.url_list = Collections.emptyList();
        try {
            OfficialLivePhotoDownload.plan(aweme, 1, clean);
            fail("clean mode must not use watermarked download images");
        } catch (IllegalArgumentException expected) { }
        try {
            OfficialLivePhotoDownload.plan(aweme, 1, new LocalOptions(Collections.emptyMap()));
            fail("default clean mode must agree with the choice dialog");
        } catch (IllegalArgumentException expected) { }
        assertEquals("https://media.test/download.jpg", OfficialLivePhotoDownload.plan(aweme, 1,
                new LocalOptions(Collections.singletonMap("remove_watermark", false))).imageUrl);
        live.url_list = Collections.singletonList("https://media.test/image.jpg");
        live.video.play_addr = null;
        try {
            OfficialLivePhotoDownload.plan(aweme, 1, clean);
            fail("clean mode must not use a watermarked video download fallback");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void supportsUnannotatedCamelCaseClipTypeFixture() {
        assertNotNull(OfficialLivePhotoDownload.plan(new CamelAweme(), 0, new LocalOptions(Collections.emptyMap())));
    }

    @Test public void reflectionContractMatchesLocalDouyin4060Smali() throws Exception {
        Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        if (!Files.isDirectory(root.resolve("douyin-40.6.0")) && root.getParent() != null) root = root.getParent();
        root = root.resolve("douyin-40.6.0/apktool-no-res");
        Assume.assumeTrue("local host APK evidence is optional on CI", Files.isDirectory(root));
        String service = Files.readString(root.resolve("smali_classes9/com/ss/android/ugc/aweme/services/IExternalService.smali"));
        assertTrue(service.contains(".field public static final Companion:Lcom/ss/android/ugc/aweme/services/IExternalService$Companion;"));
        assertTrue(service.contains(".method public abstract albumService()LX/0PoP;"));
        String companion = Files.readString(root.resolve("smali_classes43/com/ss/android/ugc/aweme/services/IExternalService$Companion.smali"));
        assertTrue(companion.contains(".method public final getOrDefault()Lcom/ss/android/ugc/aweme/services/IExternalService;"));
        String album = Files.readString(root.resolve("smali_classes9/X/0PoP.smali"));
        assertTrue(album.contains(".method public abstract Xi(Ljava/lang/String;Ljava/lang/String;J)LX/15De;"));
        assertTrue(album.contains(".method public abstract isLivePhoto(Ljava/lang/String;)Z"));
        String result = Files.readString(root.resolve("smali_classes33/X/15De.1.smali"));
        assertTrue(result.contains(".field public final LIZ:Ljava/util/List;"));
        assertTrue(result.contains(".field public final LIZIZ:Z"));
        assertTrue(result.contains(".field public final LIZJ:I"));
        String rom = Files.readString(root.resolve("smali_classes53/X/0BDp.1.smali"));
        assertTrue(rom.contains(".method public static final LIZLLL()Z"));
    }

    @Test public void reflectionContractExistsInOriginalHostApkDex() throws Exception {
        String path = System.getenv("DOUAI_TEST_APK");
        Assume.assumeTrue("DOUAI_TEST_APK is not configured", path != null);
        File apk = new File(path);
        assertTrue("DOUAI_TEST_APK must point to the original host APK", apk.isFile());
        boolean legacy = com.douai.module.scan.HostApkTestProfile.is406(apk);
        boolean latest = com.douai.module.scan.HostApkTestProfile.is410(apk);
        String albumType = legacy ? "LX/0PoP;" : latest ? "LX/0UlC;" : "LX/0S4A;";
        String resultType = legacy ? "LX/15De;" : latest ? "LX/1DS6;" : "LX/1WiA;";
        String romType = legacy ? "LX/0BDp;" : latest ? "LX/0qS2;" : "LX/0eiB;";
        Map<String, ClassDef> types = new HashMap<>();
        try (ZipFile archive = new ZipFile(apk)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements() && types.size() < 5) {
                String dexName = entries.nextElement().getName();
                if (!dexName.matches("classes[0-9]*\\.dex")) continue;
                try (BufferedInputStream input = new BufferedInputStream(archive.getInputStream(archive.getEntry(dexName)))) {
                    DexBackedDexFile dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input);
                    for (ClassDef type : dex.getClasses()) {
                        if (Arrays.asList("Lcom/ss/android/ugc/aweme/services/IExternalService;",
                                "Lcom/ss/android/ugc/aweme/services/IExternalService$Companion;",
                                albumType, resultType, romType).contains(type.getType())) {
                            types.put(type.getType(), com.android.tools.smali.dexlib2.immutable.ImmutableClassDef.of(type));
                        }
                    }
                }
            }
        }
        assertEquals(5, types.size());
        assertMethod(types.get("Lcom/ss/android/ugc/aweme/services/IExternalService;"), "albumService()" + albumType, false);
        assertField(types.get("Lcom/ss/android/ugc/aweme/services/IExternalService;"),
                "Companion", "Lcom/ss/android/ugc/aweme/services/IExternalService$Companion;", true);
        assertMethod(types.get("Lcom/ss/android/ugc/aweme/services/IExternalService$Companion;"),
                "getOrDefault()Lcom/ss/android/ugc/aweme/services/IExternalService;", false);
        assertMethod(types.get(albumType), (legacy ? "Xi" : latest ? "gj" : "hj") + "(Ljava/lang/String;Ljava/lang/String;J)" + resultType, false);
        assertMethod(types.get(albumType), "isLivePhoto(Ljava/lang/String;)Z", false);
        assertMethod(types.get(romType), "LIZLLL()Z", true);
        assertField(types.get(resultType), "LIZ", "Ljava/util/List;", false);
        assertField(types.get(resultType), "LIZIZ", "Z", false);
        assertField(types.get(resultType), "LIZJ", "I", false);
    }

    private static void assertMethod(ClassDef type, String signature, boolean isStatic) {
        assertNotNull(type);
        for (Method method : type.getMethods()) {
            StringBuilder actual = new StringBuilder(method.getName()).append('(');
            for (CharSequence parameter : method.getParameterTypes()) actual.append(parameter);
            actual.append(')').append(method.getReturnType());
            if (signature.contentEquals(actual)) {
                assertTrue(AccessFlags.PUBLIC.isSet(method.getAccessFlags()));
                assertEquals(isStatic, AccessFlags.STATIC.isSet(method.getAccessFlags()));
                return;
            }
        }
        fail(type.getType() + " is missing " + signature);
    }

    private static void assertField(ClassDef type, String name, String descriptor, boolean isStatic) {
        assertNotNull(type);
        for (com.android.tools.smali.dexlib2.iface.Field field : type.getFields()) {
            if (name.equals(field.getName()) && descriptor.equals(field.getType())) {
                assertTrue(AccessFlags.PUBLIC.isSet(field.getAccessFlags()));
                assertEquals(isStatic, AccessFlags.STATIC.isSet(field.getAccessFlags()));
                return;
            }
        }
        fail(type.getType() + " is missing field " + name + ':' + descriptor);
    }

    private interface Encode { OfficialLivePhotoDownload.Encoded run(File image, File video) throws Exception; }
    private static OfficialLivePhotoDownload.Encoder encoder(Encode action, boolean live) {
        return new OfficialLivePhotoDownload.Encoder() {
            @Override public OfficialLivePhotoDownload.Encoded encode(ClassLoader loader, File image, File video) throws Exception {
                return action.run(image, video);
            }
            @Override public boolean isLivePhoto(ClassLoader loader, File file) { return live; }
        };
    }
    private static void assertRefused(OfficialLivePhotoDownload.Encoded result, boolean live) throws Exception {
        try {
            OfficialLivePhotoDownload.validatedOutput(result, null, encoder((image, video) -> result, live));
            fail("only an encoded and validated independent LivePhoto is accepted");
        } catch (IOException | UnsupportedOperationException expected) { }
    }
    private static MockWebServer source() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setBody("source-image"));
        server.enqueue(new MockResponse().setBody("source-video"));
        server.start();
        return server;
    }
    private static OfficialLivePhotoDownload.Plan plan(MockWebServer server) {
        return new OfficialLivePhotoDownload.Plan(server.url("/image.jpg").toString(),
                server.url("/video.mp4").toString(), "custom_01.jpg", getLoader());
    }
    private static ClassLoader getLoader() { return OfficialLivePhotoDownloadTest.class.getClassLoader(); }
    static final class Aweme {
        String desc = "caption", aweme_id = "123";
        java.util.List<Image> images = Arrays.asList(new Image(0), new Image(5));
    }
    static final class Image {
        int clip_type;
        java.util.List<String> url_list = Collections.singletonList("https://media.test/image.jpg");
        java.util.List<String> watermark_free_download_url_list = Collections.emptyList();
        java.util.List<String> download_url_list = Collections.singletonList("https://media.test/download.jpg");
        Video video = new Video();
        Image(int type) { clip_type = type; }
    }
    static final class CamelAweme { java.util.List<CamelImage> images = Collections.singletonList(new CamelImage()); }
    static final class CamelImage {
        int clipType = 5;
        java.util.List<String> url_list = Collections.singletonList("https://media.test/image.jpg");
        Video video = new Video();
    }
    static final class Video { Url play_addr = new Url(), download_addr = new Url(); }
    static final class Url { java.util.List<String> url_list = Collections.singletonList("https://media.test/live.mp4"); }
}
