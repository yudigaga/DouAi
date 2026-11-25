package com.douai.module.ai.skills;

import com.douai.module.ai.AiCredentialVault;
import com.douai.module.ai.AiExtensionHttp;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** One explicitly reviewed service call. The app exposes only a local simulation. */
public final class SkillCustomService {
    public enum Preset {
        MOCK_TEXT("mock_text_v1", "本地模拟服务", "https://skill-mock.invalid/v1/text", "input", "text", 0);

        private final String id;
        private final String label;
        private final String endpoint;
        private final String requestField;
        private final String responseField;
        private final int costUnits;

        Preset(String id, String label, String endpoint, String requestField, String responseField, int costUnits) {
            this.id = id;
            this.label = label;
            this.endpoint = AiExtensionHttp.exactEndpoint(endpoint).toASCIIString();
            this.requestField = requestField;
            this.responseField = responseField;
            this.costUnits = costUnits;
        }

        public String id() { return id; }
        public String label() { return label; }
        public String endpoint() { return endpoint; }
        public String requestField() { return requestField; }
        public String responseField() { return responseField; }
        public int costUnits() { return costUnits; }

        public static Preset fromId(String id) {
            for (Preset preset : values()) if (preset.id.equals(id)) return preset;
            throw new IllegalArgumentException("不支持此技能服务或数据映射");
        }
    }

    public enum Code {
        INVALID_INPUT("技能服务输入无效"),
        APPROVAL_REQUIRED("请先确认本次技能服务调用"),
        STALE("已保存的服务或技能发生变化，请重新核对"),
        CREDENTIAL_UNAVAILABLE("本机技能服务凭据不可用"),
        DEADLINE("技能服务确认或调用已超时"),
        CANCELLED("技能服务调用已取消"),
        UNKNOWN_RESULT("请求结果未知，请先核实"),
        REMOTE_REFUSED("服务拒绝了此次调用"),
        BAD_RESPONSE("服务返回格式或长度不合要求");

        private final String message;
        Code(String message) { this.message = message; }
    }

    public static final class Failure extends IOException {
        private final Code code;

        private Failure(Code code) {
            super(code.message);
            this.code = code;
        }

        public Code code() { return code; }
        public boolean resultUnknown() { return code == Code.UNKNOWN_RESULT; }
    }

    private static final String CIPHERTEXT_VERSION = "v1.";
    private static final int MIN_CIPHERTEXT_BYTES = 36;
    private static final int MAX_CIPHERTEXT_BYTES = 2076;
    private static final int MAX_INPUT_CHARS = 2000;
    private static final int MAX_TEXT_CHARS = 4000;
    private static final int MAX_REPLY_BYTES = 24_576;
    private static final int MAX_MAPPED_JSON_NODES = 4;
    private static final long REVIEW_NANOS = TimeUnit.MINUTES.toNanos(2);

    /** Trusted, local-only storage entry for a user-selected preset and its sealed credential. */
    public record Binding(String skillId, String revision, Preset preset, String encryptedBearer) {
        public Binding {
            if (!isUuid(skillId) || !isUuid(revision) || preset == null || !validCiphertext(encryptedBearer)) {
                throw new IllegalArgumentException("技能服务本机配置无效");
            }
        }

        @Override public String toString() { return "Binding[" + preset.id() + "]"; }
    }

    private enum State { PREVIEWED, APPROVED, CLOSED }

    public static final class Preview {
        private final SkillCustomService owner;
        private final Binding binding;
        private final String argument;
        private final byte[] body;
        private final String digest;
        private final long expiresAt;
        private final AtomicReference<State> state = new AtomicReference<>(State.PREVIEWED);

        private Preview(SkillCustomService owner, Binding binding, String argument, byte[] body, String digest,
                        long expiresAt) {
            this.owner = owner;
            this.binding = binding;
            this.argument = argument;
            this.body = body;
            this.digest = digest;
            this.expiresAt = expiresAt;
        }

        public Preset preset() { return binding.preset(); }
        public String argument() { return argument; }
        public String digest() { return digest; }
        public String skillId() { return binding.skillId(); }
        public String revision() { return binding.revision(); }
    }

    public static final class Approval {
        private final Preview preview;
        private Approval(Preview preview) { this.preview = preview; }
    }

    @FunctionalInterface interface Transport {
        Reply exchange(String endpoint, Map<String, String> headers, byte[] body, long deadlineNanos) throws IOException;
    }

    record Reply(int status, String contentType, byte[] body) {}

    private final AiCredentialVault credentials;
    private final Transport transport;
    private final LongSupplier clock;

    SkillCustomService(AiCredentialVault credentials, Transport transport, LongSupplier clock) {
        this.credentials = Objects.requireNonNull(credentials);
        this.transport = Objects.requireNonNull(transport);
        this.clock = Objects.requireNonNull(clock);
    }

    public static SkillCustomService simulation() {
        return new SkillCustomService(AiCredentialVault.forDevice(), SkillCustomService::simulate, System::nanoTime);
    }

    public Binding configure(String skillId, Preset preset, String optionalBearer) throws Failure {
        if (!isUuid(skillId) || preset == null) throw new Failure(Code.INVALID_INPUT);
        String revision = UUID.randomUUID().toString();
        if (optionalBearer == null || optionalBearer.isEmpty()) return new Binding(skillId, revision, preset, "");
        if (!AiCredentialVault.validBearer(optionalBearer)) throw new Failure(Code.CREDENTIAL_UNAVAILABLE);
        try {
            String sealed = credentials.seal(AiCredentialVault.Scope.SKILL_SERVICE, credentialId(skillId, revision),
                    preset.endpoint(), optionalBearer);
            Binding saved = new Binding(skillId, revision, preset, CIPHERTEXT_VERSION + sealed);
            if (!optionalBearer.equals(credential(saved))) throw new Failure(Code.CREDENTIAL_UNAVAILABLE);
            return saved;
        } catch (GeneralSecurityException | IllegalArgumentException unavailable) {
            throw new Failure(Code.CREDENTIAL_UNAVAILABLE);
        }
    }

    public Preview preview(Binding binding, String argument) throws Failure {
        if (binding == null || argument == null || argument.length() > MAX_INPUT_CHARS || argument.trim().isEmpty()
                || !validText(argument)) {
            throw new Failure(Code.INVALID_INPUT);
        }
        try { SkillAuthoring.rejectCredential(argument); }
        catch (IllegalArgumentException sensitive) { throw new Failure(Code.INVALID_INPUT); }
        rejectCredentialInInput(argument, credential(binding));
        byte[] body = json(binding.preset().requestField(), argument);
        return new Preview(this, binding, argument, body, digest(binding, body), clock.getAsLong() + REVIEW_NANOS);
    }

    public Approval approve(Preview preview) throws Failure {
        if (preview == null || preview.owner != this || !preview.state.compareAndSet(State.PREVIEWED, State.APPROVED)) {
            throw new Failure(Code.APPROVAL_REQUIRED);
        }
        try { checkDeadline(preview.expiresAt, false); }
        catch (Failure expired) {
            preview.state.set(State.CLOSED);
            throw expired;
        }
        return new Approval(preview);
    }

    public void cancel(Preview preview) {
        if (preview != null && preview.owner == this) preview.state.set(State.CLOSED);
    }

    /** The caller must supply the latest installed binding, immediately before starting execution. */
    public SkillResult.Text execute(Approval approval, Binding current, long deadlineNanos) throws Failure {
        Preview preview = approval == null ? null : approval.preview;
        if (preview == null || preview.owner != this || !preview.state.compareAndSet(State.APPROVED, State.CLOSED)) {
            throw new Failure(Code.APPROVAL_REQUIRED);
        }
        if (!preview.binding.equals(current)) throw new Failure(Code.STALE);
        checkDeadline(preview.expiresAt, false);
        checkDeadline(deadlineNanos, false);
        String bearer = credential(current);
        rejectCredentialInInput(preview.argument, bearer);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        if (!bearer.isEmpty()) headers.put("Authorization", "Bearer " + bearer);
        checkDeadline(deadlineNanos, false);

        Reply result;
        try {
            result = transport.exchange(current.preset().endpoint(), Collections.unmodifiableMap(headers),
                    preview.body.clone(), deadlineNanos);
        } catch (IOException | RuntimeException ambiguous) {
            throw new Failure(Code.UNKNOWN_RESULT);
        }
        checkDeadline(deadlineNanos, true);
        SkillResult.Text decoded = decode(current.preset(), result, bearer);
        checkDeadline(deadlineNanos, true);
        return decoded;
    }

    private String credential(Binding binding) throws Failure {
        if (binding.encryptedBearer().isEmpty()) return "";
        try {
            return credentials.open(AiCredentialVault.Scope.SKILL_SERVICE,
                    credentialId(binding.skillId(), binding.revision()), binding.preset().endpoint(),
                    binding.encryptedBearer().substring(CIPHERTEXT_VERSION.length()));
        } catch (GeneralSecurityException unavailable) {
            throw new Failure(Code.CREDENTIAL_UNAVAILABLE);
        }
    }

    private static void rejectCredentialInInput(String argument, String bearer) throws Failure {
        if (!bearer.isEmpty() && argument.contains(bearer)) throw new Failure(Code.INVALID_INPUT);
    }

    private static boolean validText(String value) {
        return StandardCharsets.UTF_8.newEncoder().canEncode(value)
                && value.codePoints().noneMatch(c -> Character.isISOControl(c) && c != '\t' && c != '\r' && c != '\n');
    }

    private static SkillResult.Text decode(Preset preset, Reply result, String bearer) throws Failure {
        if (result == null || result.status() < 200 || result.status() > 599) throw new Failure(Code.BAD_RESPONSE);
        if (result.status() == 408 || result.status() >= 500) throw new Failure(Code.UNKNOWN_RESULT);
        if (result.status() >= 300) throw new Failure(Code.REMOTE_REFUSED);
        if (!jsonType(result.contentType()) || result.body() == null || result.body().length > MAX_REPLY_BYTES) {
            throw new Failure(Code.BAD_RESPONSE);
        }
        try {
            String raw = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(result.body())).toString();
            JSONObject object = SkillAuthoring.readObject(raw, MAX_REPLY_BYTES, MAX_MAPPED_JSON_NODES);
            if (object.length() != 1 || !(object.opt(preset.responseField()) instanceof String text)
                    || text.length() > MAX_TEXT_CHARS || text.trim().isEmpty() || !validText(text)
                    || !bearer.isEmpty() && text.contains(bearer)) {
                throw new Failure(Code.BAD_RESPONSE);
            }
            return new SkillResult.Text(text);
        } catch (IllegalArgumentException | CharacterCodingException invalid) {
            throw new Failure(Code.BAD_RESPONSE);
        }
    }

    private static boolean jsonType(String value) {
        if (value == null) return false;
        int separator = value.indexOf(';');
        String media = separator < 0 ? value : value.substring(0, separator);
        return "application/json".equalsIgnoreCase(media.trim());
    }

    private void checkDeadline(long deadline, boolean dispatched) throws Failure {
        if (Thread.currentThread().isInterrupted()) throw new Failure(dispatched ? Code.UNKNOWN_RESULT : Code.CANCELLED);
        if (deadline - clock.getAsLong() <= 0) throw new Failure(dispatched ? Code.UNKNOWN_RESULT : Code.DEADLINE);
    }

    private static Reply simulate(String endpoint, Map<String, String> headers, byte[] body, long deadline) {
        Preset preset = Preset.MOCK_TEXT;
        if (!preset.endpoint().equals(endpoint)) throw new IllegalArgumentException("不支持此技能服务");
        JSONObject request = SkillAuthoring.readObject(new String(body, StandardCharsets.UTF_8), MAX_REPLY_BYTES,
                MAX_MAPPED_JSON_NODES);
        String argument = request.optString(preset.requestField());
        return new Reply(200, "application/json", json(preset.responseField(), "模拟服务：" + argument));
    }

    private static byte[] json(String key, String value) {
        return ("{" + JSONObject.quote(key) + ":" + JSONObject.quote(value) + "}").getBytes(StandardCharsets.UTF_8);
    }

    private static String digest(Binding binding, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Preset preset = binding.preset();
            String metadata = binding.skillId() + "\n" + binding.revision() + "\n" + preset.id() + "\n"
                    + preset.endpoint() + "\nPOST\n" + preset.requestField() + "\n" + preset.responseField()
                    + "\n" + preset.costUnits() + "\n";
            digest.update(metadata.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest(body));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("缺少技能服务确认所需的摘要算法");
        }
    }

    private static boolean isUuid(String value) {
        if (value == null || value.length() != 36) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private static boolean validCiphertext(String ciphertext) {
        if (ciphertext == null || ciphertext.length() > 3000) return false;
        if (ciphertext.isEmpty()) return true;
        if (!ciphertext.startsWith(CIPHERTEXT_VERSION)) return false;
        try {
            String base64 = ciphertext.substring(CIPHERTEXT_VERSION.length());
            byte[] bytes = Base64.getDecoder().decode(base64);
            return bytes.length >= MIN_CIPHERTEXT_BYTES && bytes.length <= MAX_CIPHERTEXT_BYTES
                    && Base64.getEncoder().encodeToString(bytes).equals(base64);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static String credentialId(String skillId, String revision) { return "custom/" + skillId + "/" + revision; }
}
