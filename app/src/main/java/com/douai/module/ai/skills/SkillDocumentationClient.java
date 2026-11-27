package com.douai.module.ai.skills;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Retrieves one explicitly reviewed, public OpenAPI JSON document without service credentials. */
public final class SkillDocumentationClient {
    public static final int MAX_DOCUMENT_BYTES = 384 * 1024;
    private static final long MAX_WAIT_NANOS = TimeUnit.SECONDS.toNanos(10);

    @FunctionalInterface
    interface Fetch {
        SkillHttpsTransport.Response read(String endpoint, long deadlineNanos, int maxBytes) throws IOException;
    }

    private SkillDocumentationClient() { }

    public static String normalize(String address) {
        try {
            if (address == null) throw invalidAddress();
            String reviewed = SkillHttpsTransport.validateEndpoint(address.trim());
            URI uri = URI.create(reviewed);
            String path = uri.getRawPath();
            if (!path.toLowerCase(Locale.ROOT).endsWith(".json")
                    || ("github.com".equalsIgnoreCase(uri.getHost()) && path.matches("/[^/]+/[^/]+/blob/.*"))) {
                throw invalidAddress();
            }
            SkillAuthoring.rejectEditorCredential(reviewed);
            SkillAuthoring.rejectEditorCredential(uri.getPath());
            return reviewed;
        } catch (SkillHttpsTransport.Failure | IllegalArgumentException invalid) {
            throw invalidAddress();
        }
    }

    public static String read(String endpoint, long deadlineNanos) throws IOException {
        return read(endpoint, deadlineNanos, SkillHttpsTransport::getDocumentation);
    }

    static String read(String endpoint, long deadlineNanos, Fetch fetch) throws IOException {
        String reviewed = normalize(endpoint);
        long now = System.nanoTime();
        long allowance = Math.min(deadlineNanos - now, MAX_WAIT_NANOS);
        if (allowance <= 0 || Thread.currentThread().isInterrupted()) {
            throw new IOException("读取接口文档已停止或超时");
        }
        SkillHttpsTransport.Response response;
        try { response = fetch.read(reviewed, now + allowance, MAX_DOCUMENT_BYTES); }
        catch (IOException unavailable) { throw new IOException("无法读取所选公开接口文档"); }
        byte[] body = response.body();
        if (response.status() != 200
                || !("application/json".equals(response.contentType()) || "text/plain".equals(response.contentType()))
                || body == null || body.length == 0 || body.length > MAX_DOCUMENT_BYTES) {
            throw new IOException("接口文档须直接返回大小合适的 JSON 文件");
        }
        try {
            String document = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString();
            String trimmed = document.trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) return document;
        } catch (CharacterCodingException malformed) {
            // Keep the untrusted response out of error messages.
        }
        throw new IOException("接口文档须为 UTF-8 编码的 JSON 对象");
    }

    private static IllegalArgumentException invalidAddress() {
        return new IllegalArgumentException("请输入公开 HTTPS JSON 文档的直链");
    }
}
