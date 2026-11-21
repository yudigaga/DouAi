package com.douai.module.ai.skills;

import android.content.Context;

import com.douai.module.ai.AiCredentialVault;
import com.douai.module.ai.AiExtensionHttp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** User-registered one-call Skill APIs with endpoint-bound approval and credentials. */
public final class SkillServices {
    public static final String MOCK_ORDERS = "mock-orders";
    public static final int MAX_COST_UNITS = 1_000_000;
    public record Service(String id, String label, String endpoint, int costUnits) {}

    public enum Code {
        CONFIRMATION_REQUIRED, INVALID_INPUT, STORAGE, CREDENTIAL_REQUIRED,
        UNAVAILABLE, REJECTED, UNKNOWN_RESULT, CANCELLED
    }

    public static final class Failure extends IOException {
        private final Code code;

        private Failure(Code code, String message) {
            super(message);
            this.code = code;
        }

        public Code code() { return code; }
    }

    interface Transport {
        Response post(String endpoint, String token, String request, long deadlineNanos)
                throws AiExtensionHttp.Failure;
    }

    record Response(int status, String mediaType, String body) {}
    private record Registration(Service service, String ciphertext, int maximumCost) {}
    private interface Edit { void apply(LinkedHashMap<String, Registration> registrations) throws Failure; }

    private static final Service MOCK = new Service(MOCK_ORDERS, "模拟订单", "", 0);
    private static final int VERSION = 1;
    private static final int MAX_SERVICES = 30;
    private static final int MAX_ARGUMENT = 4_000;
    private static final int MAX_WIRE_BYTES = 16 * 1024;
    private static final int MAX_FILE_BYTES = 256 * 1024;
    private static final int DISARMED = -1;
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,47}");
    private static final AiCredentialVault.Scope CREDENTIAL_SCOPE = AiCredentialVault.Scope.SKILL_SERVICE;
    // All instances targeting the same host app file share a lock. Never hold it during I/O to a service.
    private static final Object REGISTRY_LOCK = new Object();

    private final Path file;
    private final AiCredentialVault credentials;
    private final Transport transport;

    private SkillServices(Context app) {
        this(new File(app.getNoBackupFilesDir(), "douai-ai"), AiCredentialVault.forDevice(), SkillServices::post);
    }

    SkillServices(File directory, AiCredentialVault credentials, Transport transport) {
        this.file = directory.toPath().resolve("skill-services.json");
        this.credentials = credentials;
        this.transport = transport;
    }

    public static SkillServices forContext(Context context) {
        Context application = context.getApplicationContext();
        return new SkillServices(application == null ? context : application);
    }

    public List<Service> list() throws Failure {
        synchronized (REGISTRY_LOCK) {
            List<Service> result = new ArrayList<>();
            result.add(MOCK);
            for (Registration registration : read().values()) result.add(registration.service());
            return Collections.unmodifiableList(result);
        }
    }

    public Service find(String id) throws Failure {
        if (MOCK_ORDERS.equals(id)) return MOCK;
        synchronized (REGISTRY_LOCK) {
            Registration found = read().get(id);
            if (found == null) throw problem(Code.INVALID_INPUT, "服务不存在");
            return found.service();
        }
    }

    /** An empty credential field keeps the existing key only for the exact same HTTPS endpoint. */
    public void register(Service service, String bearerToken) throws Failure {
        validate(service);
        String token = bearerToken == null ? "" : bearerToken;
        if (!token.isEmpty() && !AiCredentialVault.validBearer(token)) {
            throw problem(Code.INVALID_INPUT, "Bearer 密钥应为 8–2048 位有效字符");
        }
        edit(registrations -> {
            Registration previous = registrations.get(service.id());
            if (previous == null && registrations.size() == MAX_SERVICES) {
                throw problem(Code.INVALID_INPUT, "最多登记 30 个外部服务");
            }
            boolean sameEndpoint = previous != null && previous.service().endpoint().equals(service.endpoint());
            String ciphertext = sameEndpoint ? previous.ciphertext() : "";
            if (!token.isEmpty()) {
                verifyNoMetadataSecret(service, token);
                ciphertext = encrypt(service, token);
            } else if (sameEndpoint && !ciphertext.isEmpty()
                    && !previous.service().label().equals(service.label())) {
                verifyNoMetadataSecret(service, decrypt(previous));
            }
            int approval = previous != null && sameBinding(service, previous.service()) && token.isEmpty()
                    ? previous.maximumCost() : DISARMED;
            registrations.put(service.id(), new Registration(service, ciphertext, approval));
        });
    }

    public boolean isAuthorized(Service expected) throws Failure {
        if (MOCK.equals(expected)) return true;
        synchronized (REGISTRY_LOCK) {
            Registration current = expected == null ? null : read().get(expected.id());
            return current != null && sameBinding(expected, current.service())
                    && current.maximumCost() >= current.service().costUnits();
        }
    }

    public void authorize(Service expected, int maximumCostUnits) throws Failure {
        if (MOCK.equals(expected) && maximumCostUnits == 0) return;
        if (maximumCostUnits < 0 || maximumCostUnits > MAX_COST_UNITS) {
            throw problem(Code.INVALID_INPUT, "费用上限超出允许范围");
        }
        edit(registrations -> {
            Registration current = requireMatching(registrations, expected);
            if (maximumCostUnits < current.service().costUnits()) {
                throw problem(Code.CONFIRMATION_REQUIRED, "本次服务预算超出授权上限");
            }
            registrations.put(expected.id(), new Registration(current.service(), current.ciphertext(), maximumCostUnits));
        });
    }

    public void revoke(Service expected) throws Failure {
        edit(registrations -> {
            Registration current = requireMatching(registrations, expected);
            registrations.put(expected.id(), new Registration(current.service(), current.ciphertext(), DISARMED));
        });
    }

    public String execute(Service expected, String argument) throws Failure {
        if (argument == null || argument.trim().isEmpty() || argument.length() > MAX_ARGUMENT) {
            throw problem(Code.INVALID_INPUT, "服务输入应为 1–4000 个字符");
        }
        if (Thread.currentThread().isInterrupted()) throw problem(Code.CANCELLED, "请求已停止");
        if (MOCK.equals(expected)) {
            try { return AiSkills.lookupOrder(argument); }
            catch (IllegalArgumentException invalid) { throw problem(Code.INVALID_INPUT, invalid.getMessage()); }
        }
        String request;
        try { request = new JSONObject().put("input", argument).toString(); }
        catch (JSONException malformed) { throw problem(Code.INVALID_INPUT, "无效的服务输入"); }
        if (request.getBytes(StandardCharsets.UTF_8).length > MAX_WIRE_BYTES) {
            throw problem(Code.INVALID_INPUT, "服务输入编码后不能超过 16 KiB");
        }
        Registration grant;
        String token;
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        synchronized (REGISTRY_LOCK) {
            grant = requireMatching(read(), expected);
            if (grant.maximumCost() < grant.service().costUnits()) {
                throw problem(Code.CONFIRMATION_REQUIRED, "请先确认服务地址、权限与费用上限");
            }
            token = decrypt(grant);
            if (!token.isEmpty() && argument.contains(token)) {
                throw problem(Code.INVALID_INPUT, "服务输入中不能包含已登记的凭据");
            }
        }
        boolean paid = grant.service().costUnits() > 0;
        Response reply;
        try {
            reply = transport.post(grant.service().endpoint(), token, request, deadlineNanos);
        } catch (AiExtensionHttp.Failure failure) {
            if (paid && failure.mayHaveSent) throw unknown();
            if (failure.kind == AiExtensionHttp.Kind.CANCELLED) throw problem(Code.CANCELLED, "请求已停止");
            if (failure.kind == AiExtensionHttp.Kind.RESPONSE_LIMIT) {
                throw problem(Code.REJECTED, "服务回复不能超过 16 KiB");
            }
            throw problem(Code.UNAVAILABLE, "服务暂时无法完成请求");
        } catch (RuntimeException failure) {
            if (paid) throw unknown();
            throw problem(Code.UNAVAILABLE, "服务暂时无法完成请求");
        }
        if (reply.status() < 200 || reply.status() >= 300) {
            if (paid && (reply.status() == 408 || reply.status() >= 500)) throw unknown();
            throw problem(Code.REJECTED, "服务拒绝本次请求");
        }
        return resultText(reply, token);
    }

    private static Response post(String endpoint, String token, String request, long deadline)
            throws AiExtensionHttp.Failure {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json, text/plain");
        headers.put("Content-Type", "application/json; charset=utf-8");
        if (!token.isEmpty()) headers.put("Authorization", "Bearer " + token);
        return AiExtensionHttp.request(endpoint, "POST", headers, request.getBytes(StandardCharsets.UTF_8),
                deadline, MAX_WIRE_BYTES, (status, responseHeaders, input) ->
                        new Response(status, responseHeaders.get("Content-Type"), AiExtensionHttp.readUtf8(input)));
    }

    private static String resultText(Response reply, String token) throws Failure {
        String raw = reply.body();
        if (raw == null || raw.trim().isEmpty() || raw.getBytes(StandardCharsets.UTF_8).length > MAX_WIRE_BYTES) {
            throw problem(Code.REJECTED, "服务回复为空或超出大小限制");
        }
        rejectEcho(raw, token);
        String value = raw;
        String mediaType = reply.mediaType() == null ? "" : reply.mediaType().toLowerCase(Locale.ROOT);
        if (mediaType.contains("json") || raw.trim().startsWith("{")) {
            try {
                Object text = new JSONObject(raw).opt("text");
                if (!(text instanceof String) || ((String) text).trim().isEmpty()) throw new JSONException("text");
                value = (String) text;
            } catch (JSONException malformed) {
                throw problem(Code.REJECTED, "服务 JSON 回复缺少有效的 text 字段");
            }
        }
        rejectEcho(value, token);
        return value;
    }

    private static void rejectEcho(String value, String token) throws Failure {
        if (!token.isEmpty() && value.contains(token)) {
            throw problem(Code.REJECTED, "服务回复包含凭据，已拦截");
        }
    }

    private static void verifyNoMetadataSecret(Service service, String token) throws Failure {
        if (service.id().contains(token) || service.label().contains(token) || service.endpoint().contains(token)) {
            throw problem(Code.INVALID_INPUT, "服务名称、标识或地址不能包含凭据");
        }
    }

    private static Registration requireMatching(Map<String, Registration> entries, Service expected) throws Failure {
        Registration current = expected == null ? null : entries.get(expected.id());
        if (current == null || !sameBinding(expected, current.service())) {
            throw problem(Code.CONFIRMATION_REQUIRED, "服务地址或费用已经变化，请重新确认");
        }
        return current;
    }

    private static boolean sameBinding(Service expected, Service current) {
        return current.id().equals(expected.id()) && current.endpoint().equals(expected.endpoint())
                && current.costUnits() == expected.costUnits();
    }

    private static void validate(Service service) throws Failure {
        if (service == null || service.id() == null || !ID.matcher(service.id()).matches()
                || MOCK_ORDERS.equals(service.id()) || service.label() == null || service.label().trim().isEmpty()
                || service.label().length() > 80 || service.label().chars().anyMatch(Character::isISOControl)
                || service.costUnits() < 0 || service.costUnits() > MAX_COST_UNITS) {
            throw problem(Code.INVALID_INPUT, "填写有效的服务标识、名称和费用");
        }
        try { AiExtensionHttp.exactEndpoint(service.endpoint()); }
        catch (IllegalArgumentException invalid) { throw problem(Code.INVALID_INPUT, invalid.getMessage()); }
    }

    private LinkedHashMap<String, Registration> read() throws Failure {
        LinkedHashMap<String, Registration> registrations = new LinkedHashMap<>();
        try (InputStream input = Files.newInputStream(file)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > MAX_FILE_BYTES - bytes.size()) throw new IOException();
                bytes.write(buffer, 0, count);
            }
            JSONObject document = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            if (document.getInt("version") != VERSION) throw new JSONException("version");
            JSONArray stored = document.getJSONArray("services");
            if (stored.length() > MAX_SERVICES) throw new JSONException("size");
            for (int i = 0; i < stored.length(); i++) {
                JSONObject entry = stored.getJSONObject(i);
                Service service = new Service(entry.getString("id"), entry.getString("label"),
                        entry.getString("endpoint"), entry.getInt("costUnits"));
                validate(service);
                String ciphertext = entry.getString("ciphertext");
                int max = entry.getInt("maximumCost");
                if (ciphertext.length() > 4096 || max < DISARMED || max > MAX_COST_UNITS
                        || registrations.put(service.id(), new Registration(service, ciphertext, max)) != null) {
                    throw new JSONException("registration");
                }
            }
        } catch (NoSuchFileException absent) {
            return registrations;
        } catch (IOException | JSONException | RuntimeException corrupted) {
            throw problem(Code.STORAGE, "服务登记无法读取");
        }
        return registrations;
    }

    private void edit(Edit action) throws Failure {
        synchronized (REGISTRY_LOCK) {
            Path directory = file.getParent();
            try {
                Files.createDirectories(directory);
                try (FileChannel channel = FileChannel.open(directory.resolve("skill-services.lock"),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     FileLock lock = channel.lock()) {
                    LinkedHashMap<String, Registration> current = read();
                    action.apply(current);
                    write(current);
                }
            } catch (Failure failure) {
                throw failure;
            } catch (IOException | RuntimeException failed) {
                throw problem(Code.STORAGE, "无法保存服务登记");
            }
        }
    }

    private void write(Map<String, Registration> registrations) throws Failure {
        Path staged = null;
        try {
            JSONArray entries = new JSONArray();
            for (Registration item : registrations.values()) {
                Service service = item.service();
                entries.put(new JSONObject().put("id", service.id()).put("label", service.label())
                        .put("endpoint", service.endpoint()).put("costUnits", service.costUnits())
                        .put("ciphertext", item.ciphertext()).put("maximumCost", item.maximumCost()));
            }
            byte[] bytes = new JSONObject().put("version", VERSION).put("services", entries)
                    .toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_FILE_BYTES) throw new IOException();
            staged = Files.createTempFile(file.getParent(), "skill-services-", ".tmp");
            try (FileOutputStream stream = new FileOutputStream(staged.toFile())) {
                stream.write(bytes);
                stream.getFD().sync();
            }
            Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | JSONException | RuntimeException failed) {
            throw problem(Code.STORAGE, "无法保存服务登记");
        } finally {
            if (staged != null) {
                try { Files.deleteIfExists(staged); }
                catch (IOException ignored) { }
            }
        }
    }

    private String encrypt(Service service, String secret) throws Failure {
        try {
            return credentials.seal(CREDENTIAL_SCOPE, service.id(), service.endpoint(), secret);
        } catch (GeneralSecurityException failed) {
            throw problem(Code.CREDENTIAL_REQUIRED, "本机暂时无法保存凭据");
        }
    }

    private String decrypt(Registration registration) throws Failure {
        Service service = registration.service();
        try {
            return credentials.open(CREDENTIAL_SCOPE, service.id(), service.endpoint(), registration.ciphertext());
        } catch (GeneralSecurityException failed) {
            throw problem(Code.CREDENTIAL_REQUIRED, "无法读取本机凭据，请重新登记并确认授权");
        }
    }

    private static Failure unknown() { return problem(Code.UNKNOWN_RESULT, "请求结果未知，请先核实"); }
    private static Failure problem(Code code, String message) { return new Failure(code, message); }
}
