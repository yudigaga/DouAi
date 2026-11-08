package com.douai.module.scan;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads the installed base APK identity before any Context or PackageManager hooks are needed. */
public final class HostApkProfile {
    private static final int MAX_MANIFEST_BYTES = 16 * 1024 * 1024;
    private static final String ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android";

    private HostApkProfile() { }

    /** Unknown valid host identities return null; malformed or unreadable Manifests fail explicitly. */
    public static HostAdaptation.Profile read(File apk) throws IOException {
        Identity identity = readIdentity(apk);
        HostAdaptation.Profile profile = HostAdaptation.forVersion(
                identity.packageName, identity.versionName, identity.versionCode);
        if (profile != null && profile.updateVersionCode >= 0
                && identity.updateVersionCode != profile.updateVersionCode) return null;
        return profile;
    }

    public static Identity readIdentity(File apk) throws IOException {
        if (apk == null) throw new IOException("Missing base APK path");
        try (ZipFile archive = new ZipFile(apk)) {
            ZipEntry manifest = archive.getEntry("AndroidManifest.xml");
            if (manifest == null || manifest.isDirectory()) throw new IOException("Missing base APK Manifest");
            if (manifest.getSize() > MAX_MANIFEST_BYTES) throw new IOException("Base APK Manifest is too large");
            try (InputStream input = archive.getInputStream(manifest)) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count > MAX_MANIFEST_BYTES - output.size()) throw new IOException("Base APK Manifest is too large");
                    output.write(buffer, 0, count);
                }
                return parseManifest(output.toByteArray());
            }
        }
    }

    static Identity parseManifest(byte[] xml) throws IOException {
        if (xml == null || xml.length < 8 || xml.length > MAX_MANIFEST_BYTES)
            throw new IOException("Invalid binary APK Manifest length");
        ByteBuffer data = ByteBuffer.wrap(xml).order(ByteOrder.LITTLE_ENDIAN);
        if (u16(data, 0) != 0x0003) throw new IOException("Expected binary APK Manifest");
        int limit = data.getInt(4), rootHeader = u16(data, 2);
        if (rootHeader < 8 || limit < rootHeader || limit > xml.length)
            throw new IOException("Invalid Manifest size");
        String[] strings = null;
        Identity identity = null;
        long updateVersionCode = -1;
        int depth = 0, applicationDepth = -1;
        for (int offset = rootHeader; offset + 8 <= limit; ) {
            int type = u16(data, offset), header = u16(data, offset + 2), size = data.getInt(offset + 4);
            if (header < 8 || size < header || size > limit - offset)
                throw new IOException("Invalid Manifest chunk");
            if (type == 0x0001) strings = strings(data, offset, size);
            if (type == 0x0102) {
                if (header < 16 || size < 36 || strings == null) throw new IOException("Invalid Manifest element");
                String tag = string(strings, data.getInt(offset + 20));
                if ("manifest".equals(tag) && depth == 0) identity = identity(data, strings, offset, size);
                if ("application".equals(tag) && depth == 1) applicationDepth = depth;
                if ("meta-data".equals(tag) && applicationDepth >= 0 && depth == applicationDepth + 1) {
                    Long value = updateVersion(data, strings, offset, size);
                    if (value != null) {
                        if (updateVersionCode >= 0) throw new IOException("Duplicate APK update version code");
                        updateVersionCode = value;
                    }
                }
                depth++;
            } else if (type == 0x0103) {
                depth--;
                if (depth == applicationDepth) applicationDepth = -1;
            }
            offset += size;
        }
        if (identity == null) throw new IOException("Missing Manifest root element");
        return new Identity(identity.packageName, identity.versionName, identity.versionCode, updateVersionCode);
    }

    private static Long updateVersion(ByteBuffer data, String[] strings, int offset, int size) throws IOException {
        String name = null;
        Long value = null;
        int start = offset + 16 + u16(data, offset + 24);
        int stride = u16(data, offset + 26), count = u16(data, offset + 28);
        if (start < offset + 36 || stride < 20 || (long) start + (long) count * stride > offset + size)
            throw new IOException("Invalid APK metadata attributes");
        for (int i = 0; i < count; i++) {
            int at = start + i * stride;
            int namespace = data.getInt(at);
            if (namespace < 0 || !ANDROID_NAMESPACE.equals(string(strings, namespace))) continue;
            String attribute = string(strings, data.getInt(at + 4));
            int kind = data.get(at + 15) & 255, compiled = data.getInt(at + 16);
            if ("name".equals(attribute) && kind == 3) name = string(strings, compiled);
            if ("value".equals(attribute)) {
                if (kind == 0x10 || kind == 0x11) value = Integer.toUnsignedLong(compiled);
                else if (kind == 3) {
                    try { value = Long.decode(string(strings, compiled)); }
                    catch (NumberFormatException ignored) { }
                }
            }
        }
        if (!"UPDATE_VERSION_CODE".equals(name)) return null;
        if (value == null || value < 0 || value > 0xffffffffL) throw new IOException("Invalid APK update version code");
        return value;
    }

    private static Identity identity(ByteBuffer data, String[] strings, int offset, int size) throws IOException {
        String packageName = null, versionName = null;
        long versionCode = -1, versionCodeMajor = 0;
        int attributeStart = u16(data, offset + 24), start = offset + 16 + attributeStart;
        int attributeSize = u16(data, offset + 26), count = u16(data, offset + 28);
        if (attributeStart < 20 || attributeSize < 20
                || (long) start + (long) count * attributeSize > offset + size)
            throw new IOException("Invalid Manifest attributes");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < count; i++) {
            int at = start + i * attributeSize, namespaceIndex = data.getInt(at);
            String namespace = namespaceIndex == -1 ? null : string(strings, namespaceIndex);
            String name = string(strings, data.getInt(at + 4));
            boolean packageAttribute = namespace == null && "package".equals(name);
            boolean versionAttribute = ANDROID_NAMESPACE.equals(namespace)
                    && ("versionName".equals(name) || "versionCode".equals(name) || "versionCodeMajor".equals(name));
            if (!packageAttribute && !versionAttribute) continue;
            if (!seen.add(name)) throw new IOException("Duplicate Manifest identity attribute: " + name);
            if (u16(data, at + 12) != 8) throw new IOException("Invalid Manifest identity value");
            int valueType = data.get(at + 15) & 255, value = data.getInt(at + 16);
            // Android consumes the compiled typed value; raw source text is not authoritative.
            String text = valueType == 3 ? string(strings, value) : null;
            if (packageAttribute) packageName = text;
            if ("versionName".equals(name)) versionName = text;
            if ("versionCode".equals(name)) versionCode = integer(valueType, value, text);
            if ("versionCodeMajor".equals(name)) versionCodeMajor = integer(valueType, value, text);
        }
        if (packageName == null || packageName.isEmpty() || versionName == null || versionName.isEmpty() || versionCode < 0)
            throw new IOException("Missing original APK version attributes");
        return new Identity(packageName, versionName, (versionCodeMajor << 32) | versionCode);
    }

    private static long integer(int type, int value, String text) throws IOException {
        if (type == 0x10 || type == 0x11) return Integer.toUnsignedLong(value);
        if (text != null) {
            try {
                long number = Long.decode(text);
                if (number >= 0 && number <= 0xffffffffL) return number;
            } catch (NumberFormatException ignored) { }
        }
        throw new IOException("Invalid Manifest version code");
    }

    private static String[] strings(ByteBuffer data, int offset, int size) throws IOException {
        int header = u16(data, offset + 2);
        if (header < 28 || size < 28) throw new IOException("Invalid Manifest string pool header");
        int count = data.getInt(offset + 8), flags = data.getInt(offset + 16), start = data.getInt(offset + 20);
        if (count < 0 || (long) header + (long) count * 4 > size || start < header || start >= size)
            throw new IOException("Invalid Manifest string pool");
        String[] result = new String[count];
        int end = offset + size;
        for (int i = 0; i < count; i++) {
            int relative = data.getInt(offset + header + i * 4);
            long absolute = (long) offset + start + relative;
            if (relative < 0 || absolute < offset || absolute >= end) throw new IOException("Invalid Manifest string offset");
            int at = (int) absolute;
            if ((flags & 0x100) != 0) {
                // UTF-8 pools store a UTF-16 character count followed by the UTF-8 byte count.
                int first = data.get(at++) & 255;
                if ((first & 128) != 0) {
                    if (at >= end) throw new IOException("Truncated Manifest UTF-8 length");
                    at++;
                }
                if (at >= end) throw new IOException("Truncated Manifest UTF-8 length");
                int length = data.get(at++) & 255;
                if ((length & 128) != 0) {
                    if (at >= end) throw new IOException("Truncated Manifest UTF-8 length");
                    length = ((length & 127) << 8) | (data.get(at++) & 255);
                }
                if (length >= end - at || data.get(at + length) != 0)
                    throw new IOException("Invalid Manifest UTF-8 string");
                result[i] = new String(data.array(), at, length, StandardCharsets.UTF_8);
            } else {
                if (at > end - 2) throw new IOException("Truncated Manifest UTF-16 length");
                int length = u16(data, at); at += 2;
                if ((length & 0x8000) != 0) {
                    if (at > end - 2) throw new IOException("Truncated Manifest UTF-16 length");
                    length = ((length & 0x7fff) << 16) | u16(data, at); at += 2;
                }
                long bytes = (long) length * 2;
                if (length < 0 || bytes > end - at - 2 || u16(data, at + (int) bytes) != 0)
                    throw new IOException("Invalid Manifest UTF-16 string");
                result[i] = new String(data.array(), at, (int) bytes, StandardCharsets.UTF_16LE);
            }
        }
        return result;
    }

    private static String string(String[] strings, int index) throws IOException {
        if (index < 0 || index >= strings.length) throw new IOException("Invalid Manifest string index");
        return strings[index];
    }

    private static int u16(ByteBuffer data, int offset) { return data.getShort(offset) & 0xffff; }

    public static final class Identity {
        public final String packageName;
        public final String versionName;
        public final long versionCode;
        public final long updateVersionCode;
        private Identity(String packageName, String versionName, long versionCode) {
            this(packageName, versionName, versionCode, -1);
        }
        private Identity(String packageName, String versionName, long versionCode, long updateVersionCode) {
            this.packageName = packageName;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.updateVersionCode = updateVersionCode;
        }
    }
}
