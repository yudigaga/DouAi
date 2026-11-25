package com.douai.module.ai.skills;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;

import com.douai.module.ai.AiCredentialVault;
import com.douai.module.ai.AiExtensionHttp;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.feature.ChatImage;
import com.douai.module.feature.ChatImageLoader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** User-registered one-call Skill APIs with endpoint-bound approval and credentials. */
public final class SkillServices {
    public static final String MOCK_ORDERS = "mock-orders";
    public static final String MOCK_IMAGE = "mock-image";
    public static final int MAX_COST_UNITS = 1_000_000;
    public enum Adapter { GENERIC, OPENAI_IMAGES, MOCK_IMAGE }

    public record Service(String id, String label, String endpoint, int costUnits, SkillResult.Type outputType,
                          Adapter adapter, String modelName, String skillName, String modelsEndpoint) {
        public Service(String id, String label, String endpoint, int costUnits, SkillResult.Type outputType,
                       Adapter adapter, String modelName, String skillName) {
            this(id, label, endpoint, costUnits, outputType, adapter, modelName, skillName, "");
        }
        public Service(String id, String label, String endpoint, int costUnits, SkillResult.Type outputType) {
            this(id, label, endpoint, costUnits, outputType, Adapter.GENERIC, "", "");
        }
        public Service(String id, String label, String endpoint, int costUnits) {
            this(id, label, endpoint, costUnits, SkillResult.Type.TEXT);
        }
        private boolean scoped() { return !skillName.isEmpty(); }
    }

    public enum Code {
        CONFIRMATION_REQUIRED, INVALID_INPUT, STORAGE, CREDENTIAL_REQUIRED,
        UNAVAILABLE, REJECTED, UNKNOWN_RESULT, CANCELLED, IMAGE_MISSING, IMAGE_INVALID, IMAGE_TOO_LARGE
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
        Response post(String endpoint, String token, String request, long deadlineNanos, SkillResult.Type type)
                throws AiExtensionHttp.Failure;
    }

    record Response(int status, String mediaType, byte[] body) {}
    record ReviewedPayload(String body, String proof) {
        @Override public String toString() { return "SkillServices.ReviewedPayload"; }
    }
    private record Registration(Service service, String ciphertext, int maximumCost) {}
    private interface Edit { void apply(LinkedHashMap<String, Registration> registrations) throws Failure; }

    private static final Service MOCK = new Service(MOCK_ORDERS, "模拟订单", "", 0);
    private static final Service MOCK_IMAGE_SERVICE =
            new Service(MOCK_IMAGE, "模拟图片", "", 0, SkillResult.Type.IMAGE);
    private static final int VERSION = 1;
    private static final int MAX_SERVICES = 30;
    private static final int MAX_SCOPED_SERVICES = 256;
    private static final int MAX_ARGUMENT = 4_000;
    private static final int MAX_WIRE_BYTES = 16 * 1024;
    private static final int MAX_IMAGE_WIRE_BYTES = 6 * 1024 * 1024;
    private static final int MAX_BASE64_CHARS = ((ChatImage.MAX_BYTES + 2) / 3) * 4;
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final int DISARMED = -1;
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,47}");
    private static final Pattern SCOPED_ID = Pattern.compile("skill-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static final Pattern SKILL_NAME = Pattern.compile("[\\p{L}\\p{N}_-]{1,24}");
    private static final Pattern SCOPED_BEARER_PREFIX = Pattern.compile("(?i)\\ABearer[\\p{Zs}\\t]+");
    private static final AiCredentialVault.Scope CREDENTIAL_SCOPE = AiCredentialVault.Scope.SKILL_SERVICE;
    // All instances targeting the same host app file share a lock. Never hold it during I/O to a service.
    private static final Object REGISTRY_LOCK = new Object();

    private final Path file;
    private final AiCredentialVault credentials;
    private final Transport transport;
    private final SkillImageModelCatalog imageModels;

    private SkillServices(Context app) {
        this(new File(app.getNoBackupFilesDir(), "douai-ai"), AiCredentialVault.forDevice(), SkillServices::post);
    }

    SkillServices(File directory, AiCredentialVault credentials, Transport transport) {
        this(directory, credentials, transport, new SkillImageModelCatalog());
    }

    SkillServices(File directory, AiCredentialVault credentials, Transport transport,
                  SkillImageModelCatalog imageModels) {
        this.file = directory.toPath().resolve("skill-services.json");
        this.credentials = credentials;
        this.transport = transport;
        this.imageModels = imageModels;
    }

    public static SkillServices forContext(Context context) {
        Context application = context.getApplicationContext();
        return new SkillServices(application == null ? context : application);
    }

    public List<Service> list() throws Failure {
        return listForSkill("");
    }

    public List<Service> listForSkill(String skillName) throws Failure {
        synchronized (REGISTRY_LOCK) {
            List<Service> result = new ArrayList<>();
            result.add(MOCK);
            result.add(MOCK_IMAGE_SERVICE);
            for (Registration registration : read().values()) {
                Service service = registration.service();
                if (!MOCK_IMAGE.equals(service.id())
                        && (!service.scoped() || service.skillName().equals(skillName))) result.add(service);
            }
            return Collections.unmodifiableList(result);
        }
    }

    public Service find(String id) throws Failure {
        if (MOCK_ORDERS.equals(id)) return MOCK;
        if (MOCK_IMAGE.equals(id)) return MOCK_IMAGE_SERVICE;
        synchronized (REGISTRY_LOCK) {
            Registration found = read().get(id);
            if (found == null || found.service().scoped()) throw problem(Code.INVALID_INPUT, "服务不存在");
            return found.service();
        }
    }

    public Service scoped(String id, String skillName) throws Failure {
        synchronized (REGISTRY_LOCK) {
            Registration found = id == null ? null : read().get(id);
            if (found == null || !found.service().scoped() || !found.service().skillName().equals(skillName)) return null;
            return found.service();
        }
    }

    public boolean hasCredential(Service expected) {
        synchronized (REGISTRY_LOCK) {
            try {
                Registration found = expected == null ? null : read().get(expected.id());
                return found != null && sameBinding(expected, found.service()) && !decrypt(found).isEmpty();
            } catch (Failure unavailable) {
                return false;
            }
        }
    }

    /** Saves a Skill-owned profile without approving its destination, model or cost. */
    public Service configureForSkill(String id, String skillName, Adapter adapter, String endpoint,
                                     String modelName, int costUnits, String bearer) throws Failure {
        return configureForSkill(id, skillName, adapter, endpoint, null, modelName, costUnits, bearer);
    }

    public Service configureForSkill(String id, String skillName, Adapter adapter, String endpoint,
                                     String modelsEndpoint, String modelName, int costUnits, String bearer) throws Failure {
        boolean create = id == null || id.isEmpty();
        String serviceId = create ? "skill-" + UUID.randomUUID() : id;
        Service input = new Service(serviceId, skillName, value(endpoint).trim(), costUnits, SkillResult.Type.IMAGE,
                adapter, value(modelName).trim(), skillName, value(modelsEndpoint).trim());
        validateScoped(input);
        requireImageOperation(input);
        String token = normalizeScopedBearer(bearer);
        if (!token.isEmpty() && (input.endpoint().isEmpty() || input.adapter() == Adapter.MOCK_IMAGE)) {
            throw problem(Code.INVALID_INPUT, "请先填写凭据所属的 HTTPS 地址");
        }
        Service[] saved = {input};
        edit(registrations -> {
            Registration previous = registrations.get(input.id());
            if (!create && (previous == null || !previous.service().skillName().equals(skillName))) {
                throw problem(Code.INVALID_INPUT, "该配置不属于当前技能或已不存在");
            }
            if (create && (previous != null || scopedCount(registrations) >= MAX_SCOPED_SERVICES)) {
                throw problem(Code.INVALID_INPUT, "技能服务配置已达到上限");
            }
            boolean sameDestination = previous != null && previous.service().adapter() == input.adapter()
                    && previous.service().endpoint().equals(input.endpoint());
            String models = modelsEndpoint == null && sameDestination
                    ? previous.service().modelsEndpoint() : input.modelsEndpoint();
            Service service = new Service(input.id(), input.label(), input.endpoint(), input.costUnits(), input.outputType(),
                    input.adapter(), input.modelName(), input.skillName(), models);
            String ciphertext = sameDestination ? previous.ciphertext() : "";
            if (!token.isEmpty()) {
                verifyNoMetadataSecret(service, token);
                ciphertext = encrypt(service, token);
                if (scopedOpenAi(service)) verifyImageSave(service, ciphertext, token);
            } else if (!ciphertext.isEmpty() && (!previous.service().modelName().equals(service.modelName())
                    || !previous.service().modelsEndpoint().equals(service.modelsEndpoint()))) {
                verifyNoMetadataSecret(service, decrypt(previous));
            }
            int approval = previous != null && sameBinding(service, previous.service()) && token.isEmpty()
                    ? previous.maximumCost() : DISARMED;
            registrations.put(service.id(), new Registration(service, ciphertext, approval));
            saved[0] = service;
        });
        return saved[0];
    }

    /** Updates catalogue-only metadata for one existing approved image binding. */
    Service updateImageModelsEndpoint(Service expected, String override) throws Failure {
        if (expected == null || !scopedOpenAi(expected)) {
            throw problem(Code.INVALID_INPUT, "只能修改已授权技能图片服务的模型列表地址");
        }
        Service updated = new Service(expected.id(), expected.label(), expected.endpoint(), expected.costUnits(),
                expected.outputType(), expected.adapter(), expected.modelName(), expected.skillName(), value(override).trim());
        validateScoped(updated);
        edit(registrations -> {
            Registration current = requireMatching(registrations, expected);
            if (current.maximumCost() < current.service().costUnits()
                    || !current.service().modelsEndpoint().equals(expected.modelsEndpoint())) {
                throw problem(Code.CONFIRMATION_REQUIRED, "模型列表配置或授权已变化，请重新打开技能配置");
            }
            verifyNoMetadataSecret(updated, decrypt(current));
            registrations.put(updated.id(), new Registration(updated, current.ciphertext(), current.maximumCost()));
        });
        return updated;
    }

    List<String> discoverImageModels(String name, Service saved, String generationEndpoint, String modelsEndpoint,
                                    String typedBearer) throws IOException {
        DouAiLog.Operation trace = DouAiLog.current();
        if (trace != null) trace.stage("catalogue_target", value(modelsEndpoint).trim().isEmpty()
                ? "address_mode=derived" : "address_mode=explicit");
        String endpoint = value(generationEndpoint).trim();
        String resolved = SkillImageModelCatalog.resolveEndpoint(endpoint, modelsEndpoint);
        if (trace != null) trace.stage("credential_input", "");
        String token = normalizeScopedBearer(typedBearer);
        String source = "typed";
        if (token.isEmpty()) {
            source = "saved_scoped";
            boolean exact = saved != null && saved.adapter() == Adapter.OPENAI_IMAGES && name.equals(saved.skillName())
                    && endpoint.equals(saved.endpoint());
            if (trace != null) trace.stage("credential_scope", exact ? "binding=exact" : "binding=missing_or_mismatch");
            if (!exact) {
                throw problem(Code.CREDENTIAL_REQUIRED, "请输入此图片服务的 API 密钥");
            }
            synchronized (REGISTRY_LOCK) {
                token = decrypt(requireMatching(read(), saved));
            }
        }
        if (trace != null) trace.stage("credential_ready", token.isEmpty() ? "source=missing" : "source=" + source);
        if (token.isEmpty()) throw problem(Code.CREDENTIAL_REQUIRED, "请输入此图片服务的 API 密钥");
        if (AiCredentialVault.imageEndpointContainsCredential(endpoint, token)
                || AiCredentialVault.imageEndpointContainsCredential(resolved, token)) {
            throw problem(Code.INVALID_INPUT, "图片服务和模型列表地址不能包含凭据");
        }
        return imageModels.fetch(resolved, token);
    }

    /** An empty credential field keeps the existing key only for the exact same HTTPS endpoint. */
    public void register(Service service, String bearerToken) throws Failure {
        validate(service);
        String token = value(bearerToken);
        validateToken(token);
        edit(registrations -> {
            Registration previous = registrations.get(service.id());
            if (previous != null && previous.service().scoped()) {
                throw problem(Code.INVALID_INPUT, "技能专用配置只能从所属技能管理");
            }
            int externalCount = sharedCount(registrations);
            if (previous == null && externalCount == MAX_SERVICES) {
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
            if (expected != null && sameBinding(expected, MOCK_IMAGE_SERVICE) && maximumCostUnits == 0) {
                registrations.put(MOCK_IMAGE, new Registration(MOCK_IMAGE_SERVICE, "", 0));
                return;
            }
            Registration current = requireMatching(registrations, expected);
            if (maximumCostUnits < current.service().costUnits()) {
                throw problem(Code.CONFIRMATION_REQUIRED, "本次服务预算超出授权上限");
            }
            if (current.service().adapter() == Adapter.MOCK_IMAGE && maximumCostUnits != 0) {
                throw problem(Code.INVALID_INPUT, "本地模拟的费用上限必须为零");
            }
            requireComplete(current);
            if (current.service().adapter() == Adapter.OPENAI_IMAGES) decrypt(current);
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
        if (expected != null && expected.outputType() == SkillResult.Type.IMAGE) {
            throw problem(Code.INVALID_INPUT, "此服务需要支持图片输出的入口");
        }
        return ((SkillResult.Text) executeResult(expected, argument)).text();
    }

    public SkillResult executeResult(Service expected, String argument) throws Failure {
        return executeResult(expected, argument, null, null);
    }

    SkillResult executeReviewed(Service expected, String argument, SkillHttpsTransport.Admission storeAdmission) throws Failure {
        return executeReviewed(expected, argument, null, storeAdmission);
    }

    SkillResult executeReviewed(Service expected, String argument, String proof,
                                SkillHttpsTransport.Admission storeAdmission) throws Failure {
        if (storeAdmission == null) throw staleApproval();
        return executeResult(expected, argument, proof, storeAdmission);
    }

    private SkillResult executeResult(Service expected, String argument, String proof,
                                      SkillHttpsTransport.Admission storeAdmission) throws Failure {
        if (argument == null || argument.trim().isEmpty() || argument.length() > MAX_ARGUMENT) {
            throw problem(Code.INVALID_INPUT, "服务输入应为 1–4000 个字符");
        }
        if (Thread.currentThread().isInterrupted()) throw problem(Code.CANCELLED, "请求已停止");
        if (MOCK.equals(expected)) {
            try { return new SkillResult.Text(AiSkills.lookupOrder(argument)); }
            catch (IllegalArgumentException invalid) { throw problem(Code.INVALID_INPUT, invalid.getMessage()); }
        }
        Registration grant;
        String token;
        boolean expectsImage = expected != null && expected.outputType() == SkillResult.Type.IMAGE;
        boolean reviewed = storeAdmission != null;
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(expectsImage ? 60 : 15);
        synchronized (REGISTRY_LOCK) {
            grant = requireAuthorized(read(), expected);
            if (proof != null && !proof.equals(approvalProof(grant))) throw staleApproval();
            token = decrypt(grant);
            if (AiCredentialVault.containsImageCredential(argument, token)) {
                throw problem(Code.INVALID_INPUT, "服务输入中不能包含已登记的凭据");
            }
        }
        if (MOCK_IMAGE_SERVICE.equals(grant.service()) || grant.service().adapter() == Adapter.MOCK_IMAGE) {
            return mockImageResult();
        }
        String request = requestBody(grant.service(), argument);
        boolean paid = grant.service().costUnits() > 0;
        Response reply;
        try {
            reply = reviewed ? postReviewed(grant, token, request, deadlineNanos, storeAdmission)
                    : transport.post(grant.service().endpoint(), token, request, deadlineNanos, grant.service().outputType());
        } catch (AiExtensionHttp.Failure failure) {
            if (paid && failure.mayHaveSent) throw unknown();
            if (failure.kind == AiExtensionHttp.Kind.CANCELLED) throw problem(Code.CANCELLED, "请求已停止");
            if (failure.kind == AiExtensionHttp.Kind.RESPONSE_LIMIT) {
                if (expectsImage) throw imageTooLarge();
                throw problem(Code.REJECTED, "服务回复不能超过 16 KiB");
            }
            throw problem(Code.UNAVAILABLE, "服务暂时无法完成请求");
        } catch (RuntimeException failure) {
            if (paid) throw unknown();
            throw problem(Code.UNAVAILABLE, "服务暂时无法完成请求");
        }
        if (reply.status() < 200 || reply.status() >= 300) {
            if (reviewed && !definiteRefusal(reply.status()) || paid && (reply.status() == 408 || reply.status() >= 500)) {
                throw unknown();
            }
            if (reply.status() == 401 || reply.status() == 403) {
                throw problem(Code.CREDENTIAL_REQUIRED, "服务拒绝认证，请核对凭据与权限");
            }
            throw problem(Code.REJECTED, "服务拒绝本次请求");
        }
        try {
            return expectsImage ? resultImageWithDiagnostics(reply, token) : new SkillResult.Text(resultText(reply, token));
        } catch (Failure invalid) {
            if (reviewed) throw unknown();
            throw invalid;
        }
    }

    ReviewedPayload previewReviewed(Service service, String argument) throws Failure {
        synchronized (REGISTRY_LOCK) {
            Registration grant = requireAuthorized(read(), service);
            if (AiCredentialVault.containsImageCredential(argument, decrypt(grant))) {
                throw problem(Code.INVALID_INPUT, "服务输入中不能包含已登记的凭据");
            }
            return new ReviewedPayload(requestBody(grant.service(), argument), approvalProof(grant));
        }
    }

    private static Registration requireAuthorized(Map<String, Registration> entries, Service service) throws Failure {
        Registration grant = requireMatching(entries, service);
        if (grant.maximumCost() < grant.service().costUnits()) {
            throw problem(Code.CONFIRMATION_REQUIRED, "请先确认服务地址、权限与费用上限");
        }
        requireComplete(grant);
        requireImageOperation(grant.service());
        return grant;
    }

    private static String approvalProof(Registration grant) {
        try {
            byte[] value = (grant.maximumCost() + "\n" + grant.ciphertext()).getBytes(StandardCharsets.UTF_8);
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("系统无法校验服务授权");
        }
    }

    private Response postReviewed(Registration grant, String token, String request, long deadline,
                                  SkillHttpsTransport.Admission storeAdmission) throws Failure {
        Service service = grant.service();
        boolean image = service.outputType() == SkillResult.Type.IMAGE;
        try {
            SkillHttpsTransport.Response reply = SkillHttpsTransport.postReviewed(service.endpoint(), token,
                    request.getBytes(StandardCharsets.UTF_8), deadline, image ? MAX_IMAGE_WIRE_BYTES : MAX_WIRE_BYTES,
                    image, scopedOpenAi(service), () -> {
                        storeAdmission.check();
                        synchronized (REGISTRY_LOCK) {
                            Registration active = requireAuthorized(read(), service);
                            if (active.maximumCost() != grant.maximumCost()
                                    || !active.ciphertext().equals(grant.ciphertext())) throw staleApproval();
                        }
                    });
            return new Response(reply.status(), reply.contentType(), reply.body());
        } catch (SkillHttpsTransport.Failure failure) {
            if (failure.mayHaveSent()) throw unknown();
            throw switch (failure.kind()) {
                case ADMISSION -> staleApproval();
                case CANCELLED -> problem(Code.CANCELLED, "请求已停止");
                case ENDPOINT, ADDRESS -> problem(Code.UNAVAILABLE, "服务地址或实际连接未通过公网 HTTPS 校验");
                default -> problem(Code.UNAVAILABLE, "服务暂时无法完成请求");
            };
        }
    }

    private static Failure staleApproval() {
        return problem(Code.CONFIRMATION_REQUIRED, "技能配置、服务授权或凭据已发生变化，请重新确认");
    }

    private static boolean definiteRefusal(int status) {
        return status == 400 || status == 401 || status == 403 || status == 404 || status == 405
                || status == 413 || status == 415 || status == 422;
    }

    private static String requestBody(Service service, String argument) throws Failure {
        try {
            JSONObject body = new JSONObject();
            if (service.adapter() == Adapter.OPENAI_IMAGES) body.put("model", service.modelName()).put("prompt", argument);
            else body.put("input", argument);
            String request = body.toString();
            if (request.getBytes(StandardCharsets.UTF_8).length > MAX_WIRE_BYTES) {
                throw problem(Code.INVALID_INPUT, "服务输入编码后不能超过 16 KiB");
            }
            return request;
        } catch (JSONException malformed) {
            throw problem(Code.INVALID_INPUT, "无效的服务输入");
        }
    }

    private static Response post(String endpoint, String token, String request, long deadline, SkillResult.Type type)
            throws AiExtensionHttp.Failure {
        boolean image = type == SkillResult.Type.IMAGE;
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", image ? "image/png, image/jpeg, image/webp, image/gif, application/json"
                : "application/json, text/plain");
        headers.put("Content-Type", "application/json; charset=utf-8");
        if (!token.isEmpty()) headers.put("Authorization", "Bearer " + token);
        return AiExtensionHttp.request(endpoint, "POST", headers, request.getBytes(StandardCharsets.UTF_8),
                deadline, image ? MAX_IMAGE_WIRE_BYTES : MAX_WIRE_BYTES, (status, responseHeaders, input) ->
                        new Response(status, responseHeaders.get("Content-Type"), AiExtensionHttp.readBytes(input)),
                image ? DouAiLog.current() : null);
    }

    private static SkillResult.Image resultImageWithDiagnostics(Response reply, String token) throws Failure {
        DouAiLog.Operation trace = DouAiLog.current();
        if (trace == null) return resultImage(reply, token);

        long started = System.nanoTime();
        String status = "http_status=" + reply.status();
        trace.stage("image_decode", status + ";elapsed_ms=0");
        String outcome = "outcome=failed;code=UNEXPECTED";
        try {
            SkillResult.Image image = resultImage(reply, token);
            outcome = "outcome=success";
            return image;
        } catch (Failure failure) {
            outcome = "outcome=failed;code=" + failure.code().name();
            throw failure;
        } finally {
            trace.stage("image_decode_result", outcome + ";" + status + ";elapsed_ms="
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }

    private static String resultText(Response reply, String token) throws Failure {
        byte[] body = reply.body();
        if (body == null || body.length == 0 || body.length > MAX_WIRE_BYTES) {
            throw problem(Code.REJECTED, "服务回复为空或超出大小限制");
        }
        String raw;
        try { raw = utf8(body); }
        catch (CharacterCodingException malformed) { throw problem(Code.REJECTED, "服务回复不是有效的 UTF-8 文本"); }
        if (raw.trim().isEmpty()) throw problem(Code.REJECTED, "服务回复为空");
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

    private static SkillResult.Image resultImage(Response reply, String token) throws Failure {
        byte[] bytes = reply.body();
        if (bytes == null || bytes.length == 0) throw imageMissing();
        if (bytes.length > MAX_IMAGE_WIRE_BYTES) throw imageTooLarge();
        rejectEchoBytes(bytes, token);
        String mime = mediaType(reply.mediaType());
        if ("application/json".equals(mime) || mime.endsWith("+json")) {
            try {
                JSONTokener reader = new JSONTokener(utf8(bytes));
                Object item = reader.nextValue();
                if (!(item instanceof JSONObject json) || reader.nextClean() != 0) throw imageInvalid();
                Object encoded = json.opt("imageBase64");
                if (encoded != null) {
                    mime = mediaType(json.optString("mimeType", ""));
                    if (!supportedImageMime(mime)) throw imageInvalid();
                } else {
                    JSONArray data = json.optJSONArray("data");
                    JSONObject first = data == null ? null : data.optJSONObject(0);
                    encoded = first == null ? null : first.opt("b64_json");
                    mime = "";
                }
                if (encoded == null || JSONObject.NULL.equals(encoded)) throw imageMissing();
                if (!(encoded instanceof String base64)) throw imageInvalid();
                if (base64.isEmpty()) throw imageMissing();
                if (base64.length() > MAX_BASE64_CHARS) throw imageTooLarge();
                bytes = Base64.getDecoder().decode(base64);
                rejectEchoBytes(bytes, token);
            } catch (JSONException | CharacterCodingException | IllegalArgumentException invalid) {
                throw imageInvalid();
            }
        } else if (!supportedImageMime(mime)) {
            throw imageMissing();
        }
        if (bytes.length > ChatImage.MAX_BYTES) throw imageTooLarge();
        String actual = rasterMime(bytes);
        if (actual.isEmpty() || (!mime.isEmpty() && !actual.equals(mime))) throw imageInvalid();
        try {
            return new SkillResult.Image(ChatImageLoader.decode(bytes));
        } catch (CancellationException cancelled) {
            throw problem(Code.CANCELLED, "请求已停止");
        } catch (Exception malformed) {
            throw imageInvalid();
        }
    }

    private static void rejectEchoBytes(byte[] bytes, String token) throws Failure {
        if (!token.isEmpty() && AiCredentialVault.containsImageCredential(
                new String(bytes, StandardCharsets.ISO_8859_1), token)) {
            throw problem(Code.REJECTED, "服务回复包含凭据，已拦截");
        }
    }

    private static String utf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static String mediaType(String header) {
        if (header == null) return "";
        int parameters = header.indexOf(';');
        return (parameters < 0 ? header : header.substring(0, parameters)).trim().toLowerCase(Locale.ROOT);
    }

    private static boolean supportedImageMime(String mime) {
        return "image/jpeg".equals(mime) || "image/png".equals(mime)
                || "image/webp".equals(mime) || "image/gif".equals(mime);
    }

    private static String rasterMime(byte[] bytes) {
        if (hasPrefix(bytes, 0, 0xff, 0xd8, 0xff)) return "image/jpeg";
        if (hasPrefix(bytes, 0, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) return "image/png";
        if (hasPrefix(bytes, 0, 'R', 'I', 'F', 'F') && hasPrefix(bytes, 8, 'W', 'E', 'B', 'P')) return "image/webp";
        if (hasPrefix(bytes, 0, 'G', 'I', 'F', '8', '7', 'a') || hasPrefix(bytes, 0, 'G', 'I', 'F', '8', '9', 'a')) {
            return "image/gif";
        }
        return "";
    }

    private static boolean hasPrefix(byte[] bytes, int offset, int... values) {
        if (bytes.length - offset < values.length) return false;
        for (int i = 0; i < values.length; i++) if ((bytes[offset + i] & 255) != values[i]) return false;
        return true;
    }

    /** Shared picture for the explicit local simulator and a network-free Skill trial. */
    static SkillResult.Image mockImageResult() throws Failure {
        if (Thread.currentThread().isInterrupted()) throw problem(Code.CANCELLED, "请求已停止");
        Bitmap bitmap = Bitmap.createBitmap(320, 192, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setShader(new LinearGradient(0, 0, 0, 192, 0xFF26466A, 0xFF92B8AC, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, 320, 192, paint);
            paint.setShader(null);
            paint.setColor(0xFFF7DC9D);
            canvas.drawCircle(229, 63, 24, paint);
            paint.setColor(0xFF527E82);
            canvas.drawOval(-63, 112, 283, 235, paint);
            paint.setColor(0xFF214D59);
            canvas.drawOval(63, 143, 375, 250, paint);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) throw imageInvalid();
            return new SkillResult.Image(new ChatImage("image/png", bytes.toByteArray()));
        } finally {
            bitmap.recycle();
        }
    }

    private static Failure imageMissing() { return problem(Code.IMAGE_MISSING, "服务回复没有内联图片"); }
    private static Failure imageInvalid() { return problem(Code.IMAGE_INVALID, "服务回复的图片格式无效或无法解码"); }
    private static Failure imageTooLarge() { return problem(Code.IMAGE_TOO_LARGE, "服务回复的图片超过大小限制"); }

    private static void rejectEcho(String value, String token) throws Failure {
        if (!token.isEmpty() && value.contains(token)) {
            throw problem(Code.REJECTED, "服务回复包含凭据，已拦截");
        }
    }

    private static void verifyNoMetadataSecret(Service service, String token) throws Failure {
        if (AiCredentialVault.containsImageCredential(service.id(), token)
                || AiCredentialVault.containsImageCredential(service.label(), token)
                || AiCredentialVault.imageEndpointContainsCredential(service.endpoint(), token)
                || AiCredentialVault.imageEndpointContainsCredential(service.modelsEndpoint(), token)
                || AiCredentialVault.containsImageCredential(service.modelName(), token)
                || AiCredentialVault.containsImageCredential(service.skillName(), token)) {
            throw problem(Code.INVALID_INPUT, "服务名称、标识、地址或模型不能包含凭据");
        }
    }

    private static Registration requireMatching(Map<String, Registration> entries, Service expected) throws Failure {
        Registration current = expected == null ? null : entries.get(expected.id());
        if (current == null || !sameBinding(expected, current.service())) {
            throw problem(Code.CONFIRMATION_REQUIRED, "服务配置已经变化，请重新确认");
        }
        return current;
    }

    static boolean sameBinding(Service expected, Service current) {
        return current.id().equals(expected.id()) && current.endpoint().equals(expected.endpoint())
                && current.costUnits() == expected.costUnits() && current.outputType() == expected.outputType()
                && current.adapter() == expected.adapter() && current.modelName().equals(expected.modelName())
                && current.skillName().equals(expected.skillName());
    }

    private static void validate(Service service) throws Failure {
        if (service == null || service.id() == null || !ID.matcher(service.id()).matches()
                || MOCK_ORDERS.equals(service.id()) || MOCK_IMAGE.equals(service.id())
                || (service.outputType() != SkillResult.Type.TEXT && service.outputType() != SkillResult.Type.IMAGE)
                || service.label() == null || service.label().trim().isEmpty()
                || service.label().length() > 80 || service.label().chars().anyMatch(Character::isISOControl)
                || service.adapter() != Adapter.GENERIC || !"".equals(service.modelName()) || !"".equals(service.skillName())
                || !"".equals(service.modelsEndpoint())
                || service.costUnits() < 0 || service.costUnits() > MAX_COST_UNITS) {
            throw problem(Code.INVALID_INPUT, "填写有效的服务标识、名称和费用");
        }
        validateEndpoint(service.endpoint());
    }

    private static void validateScoped(Service service) throws Failure {
        if (service.id() == null || !SCOPED_ID.matcher(service.id()).matches()
                || service.skillName() == null || !SKILL_NAME.matcher(service.skillName()).matches()
                || !service.skillName().equals(service.label()) || service.outputType() != SkillResult.Type.IMAGE
                || service.endpoint() == null || service.modelName() == null || service.modelsEndpoint() == null
                || service.costUnits() < 0 || service.costUnits() > MAX_COST_UNITS) {
            throw problem(Code.INVALID_INPUT, "技能专用服务配置无效");
        }
        if (service.adapter() == Adapter.MOCK_IMAGE) {
            if (!service.endpoint().isEmpty() || !service.modelsEndpoint().isEmpty()
                    || !service.modelName().isEmpty() || service.costUnits() != 0) {
                throw problem(Code.INVALID_INPUT, "本地模拟不需要地址、模型或费用");
            }
        } else if (service.adapter() == Adapter.OPENAI_IMAGES) {
            if (!service.endpoint().isEmpty()) validateEndpoint(service.endpoint());
            if (!service.modelsEndpoint().isEmpty()) {
                try { SkillImageModelCatalog.validateOverride(service.endpoint(), service.modelsEndpoint()); }
                catch (SkillImageModelCatalog.Failure invalid) { throw problem(Code.INVALID_INPUT, invalid.getMessage()); }
            }
            if (service.modelName().length() > 256 || service.modelName().chars().anyMatch(Character::isISOControl)) {
                throw problem(Code.INVALID_INPUT, "填写有效的图片模型名称");
            }
        } else {
            throw problem(Code.INVALID_INPUT, "请选择支持图片输出的适配器");
        }
    }

    private static void requireComplete(Registration registration) throws Failure {
        Service service = registration.service();
        if (service.adapter() != Adapter.OPENAI_IMAGES) return;
        if (service.endpoint().isEmpty() || service.modelName().trim().isEmpty()) {
            throw problem(Code.INVALID_INPUT, "请填写生图接口地址和模型名称");
        }
        if (registration.ciphertext().isEmpty()) {
            throw problem(Code.CREDENTIAL_REQUIRED, "请先保存生图接口的 Bearer 密钥");
        }
    }

    private static boolean scopedOpenAi(Service service) {
        return service.scoped() && service.adapter() == Adapter.OPENAI_IMAGES;
    }

    private static void requireImageOperation(Service service) throws Failure {
        if (scopedOpenAi(service) && SkillImageProviderAddress.isNonImageOperation(service.endpoint())) {
            throw problem(Code.INVALID_INPUT, "请填写生图接口地址，聊天或模型列表接口不能用于生图");
        }
    }

    private static void validateEndpoint(String endpoint) throws Failure {
        try { AiExtensionHttp.exactEndpoint(endpoint); }
        catch (IllegalArgumentException invalid) { throw problem(Code.INVALID_INPUT, invalid.getMessage()); }
    }

    private static String value(String value) { return value == null ? "" : value; }

    static String normalizeScopedBearer(String value) throws Failure {
        String input = value(value);
        int first = 0, last = input.length();
        while (first < last && clipboardSpace(input.charAt(first))) first++;
        while (last > first && clipboardSpace(input.charAt(last - 1))) last--;
        Matcher prefix = SCOPED_BEARER_PREFIX.matcher(input).region(first, input.length());
        boolean prefixed = prefix.lookingAt();
        if (prefixed && prefix.end() >= last) throw invalidImageKey();
        String token = input.substring(prefixed ? prefix.end() : first, last);
        if (!token.isEmpty() && !AiCredentialVault.validImageBearer(token)) throw invalidImageKey();
        return token;
    }

    static boolean hasScopedBearerInput(String value) {
        if (value == null) return false;
        for (int i = 0; i < value.length(); i++) if (!clipboardSpace(value.charAt(i))) return true;
        return false;
    }

    private static Failure invalidImageKey() {
        return problem(Code.INVALID_INPUT, "图片服务密钥应为 1–4000 位有效 Bearer 字符");
    }

    private static boolean clipboardSpace(char value) {
        return Character.isWhitespace(value) || Character.isSpaceChar(value);
    }

    private static void validateToken(String token) throws Failure {
        if (!token.isEmpty() && !AiCredentialVault.validBearer(token)) {
            throw problem(Code.INVALID_INPUT, "Bearer 密钥应为 8–2048 位有效字符");
        }
    }

    private static int sharedCount(Map<String, Registration> registrations) {
        int count = 0;
        for (Registration item : registrations.values()) {
            if (!item.service().scoped() && !MOCK_IMAGE.equals(item.service().id())) count++;
        }
        return count;
    }

    private static int scopedCount(Map<String, Registration> registrations) {
        int count = 0;
        for (Registration item : registrations.values()) if (item.service().scoped()) count++;
        return count;
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
            if (stored.length() > MAX_SERVICES + MAX_SCOPED_SERVICES + 1) throw new JSONException("size");
            for (int i = 0; i < stored.length(); i++) {
                JSONObject entry = stored.getJSONObject(i);
                SkillResult.Type outputType = entry.has("outputType")
                        ? SkillResult.Type.fromWire(entry.getString("outputType")) : SkillResult.Type.TEXT;
                Service service = new Service(entry.getString("id"), entry.getString("label"),
                        entry.getString("endpoint"), entry.getInt("costUnits"), outputType,
                        Adapter.valueOf(entry.optString("adapter", Adapter.GENERIC.name())),
                        entry.optString("modelName", ""), entry.optString("skillName", ""),
                        entry.optString("modelsEndpoint", ""));
                String ciphertext = entry.getString("ciphertext");
                int max = entry.getInt("maximumCost");
                if (MOCK_IMAGE.equals(service.id())) {
                    if (!MOCK_IMAGE_SERVICE.equals(service) || !ciphertext.isEmpty() || max > 0) throw new IOException();
                } else if (service.scoped()) {
                    validateScoped(service);
                    if ((!ciphertext.isEmpty() && service.endpoint().isEmpty())
                            || (service.adapter() == Adapter.MOCK_IMAGE && max > 0)) throw new IOException();
                } else {
                    validate(service);
                }
                Registration item = new Registration(service, ciphertext, max);
                if (service.scoped() && max >= 0) requireComplete(item);
                int maximumCiphertext = scopedOpenAi(service) ? AiCredentialVault.MAX_IMAGE_CIPHERTEXT : 4096;
                if (ciphertext.length() > maximumCiphertext || max < DISARMED || max > MAX_COST_UNITS
                        || registrations.put(service.id(), item) != null) {
                    throw new JSONException("registration");
                }
            }
            if (sharedCount(registrations) > MAX_SERVICES || scopedCount(registrations) > MAX_SCOPED_SERVICES) {
                throw new IOException();
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
                JSONObject entry = new JSONObject().put("id", service.id()).put("label", service.label())
                        .put("endpoint", service.endpoint()).put("costUnits", service.costUnits())
                        .put("outputType", service.outputType().wireName())
                        .put("adapter", service.adapter().name()).put("modelName", service.modelName())
                        .put("skillName", service.skillName())
                        .put("ciphertext", item.ciphertext()).put("maximumCost", item.maximumCost());
                if (!service.modelsEndpoint().isEmpty()) entry.put("modelsEndpoint", service.modelsEndpoint());
                entries.put(entry);
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
            if (scopedOpenAi(service)) return credentials.sealImage(service.id(), service.endpoint(), secret);
            return credentials.seal(CREDENTIAL_SCOPE, service.id(), service.endpoint(), secret);
        } catch (GeneralSecurityException failed) {
            throw problem(Code.CREDENTIAL_REQUIRED, "本机暂时无法保存凭据");
        }
    }

    private void verifyImageSave(Service service, String ciphertext, String secret) throws Failure {
        try {
            if (secret.equals(credentials.openImage(service.id(), service.endpoint(), ciphertext))) return;
        } catch (GeneralSecurityException unavailable) {
            // The just-created record must reopen before it can replace any saved configuration.
        }
        throw problem(Code.CREDENTIAL_REQUIRED, "密钥保存校验失败，未写入配置");
    }

    private String decrypt(Registration registration) throws Failure {
        Service service = registration.service();
        try {
            if (scopedOpenAi(service)) {
                return credentials.openImage(service.id(), service.endpoint(), registration.ciphertext());
            }
            return credentials.open(CREDENTIAL_SCOPE, service.id(), service.endpoint(), registration.ciphertext());
        } catch (GeneralSecurityException failed) {
            throw problem(Code.CREDENTIAL_REQUIRED, "无法读取本机凭据，请重新登记并确认授权");
        }
    }

    private static Failure unknown() { return problem(Code.UNKNOWN_RESULT, "请求结果未知，请先核实"); }
    private static Failure problem(Code code, String message) { return new Failure(code, message); }
}
