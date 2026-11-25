package com.douai.module.ai.skills;

import com.douai.module.ai.AiCredentialVault;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** A fixed JSON service operation for one explicitly reviewed HTTPS request. */
public final class SkillCustomService {
    public static final String REQUEST_FIELD = "input";
    public static final String RESPONSE_FIELD = "text";

    public enum Code {
        INVALID_INPUT("技能服务输入无效"),
        ENDPOINT_INVALID("请填写有效的公网 HTTPS 接口地址，地址不得包含账户信息、查询参数或片段"),
        ADDRESS_BLOCKED("技能服务的实际连接地址未通过公网校验，请核对接口地址"),
        APPROVAL_REQUIRED("请先确认本次技能服务调用"),
        STALE("已保存的服务或技能发生变化，请重新核对"),
        CREDENTIAL_UNAVAILABLE("本机技能服务凭据不可用"),
        DEADLINE("技能服务确认或调用已超时"),
        CANCELLED("技能服务调用已取消"),
        RATE_LIMITED("技能服务调用过于频繁，每分钟最多尝试 6 次，请稍后重新确认"),
        TRANSPORT("未能发送技能服务请求，请核对地址和网络"),
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
    private static final long MAX_CALL_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final RateGate DEVICE_RATE = new RateGate();

    /** Private catalog entry with the canonical HTTPS endpoint and its sealed credential. */
    public record Binding(String skillId, String revision, String endpoint, String encryptedBearer) {
        public Binding {
            if (!isUuid(skillId) || !isUuid(revision) || !validCiphertext(encryptedBearer)) {
                throw new IllegalArgumentException("技能服务本机配置无效");
            }
            try {
                if (!validateEndpoint(endpoint).equals(endpoint)) throw new Failure(Code.ENDPOINT_INVALID);
            } catch (Failure invalid) {
                throw new IllegalArgumentException(invalid.getMessage());
            }
        }

        @Override public String toString() { return "SkillCustomService.Binding"; }
    }

    private enum State { PREVIEWED, APPROVED, CONNECTING, CLOSED }

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

        public String endpoint() { return binding.endpoint(); }
        public String argument() { return argument; }
        public String digest() { return digest; }
        public String skillId() { return binding.skillId(); }
        public String revision() { return binding.revision(); }
        public boolean matches(Binding current) { return binding.equals(current); }
    }

    public static final class Approval {
        private final Preview preview;
        private Approval(Preview preview) { this.preview = preview; }
    }

    @FunctionalInterface public interface Admission { void check() throws Exception; }
    @FunctionalInterface interface WriteAdmission { void check() throws IOException; }

    @FunctionalInterface interface Transport {
        Reply exchange(String endpoint, String bearer, byte[] body, long deadlineNanos, WriteAdmission admission)
                throws IOException;
    }
    record Reply(int status, String contentType, byte[] body) {}

    static final class RateGate {
        private static final long WINDOW_NANOS = TimeUnit.MINUTES.toNanos(1);
        private final long[] attempts = new long[6];
        private int next;
        private int count;

        synchronized boolean acquire(long now) {
            if (count == attempts.length && now - attempts[next] < WINDOW_NANOS) return false;
            attempts[next] = now;
            next = (next + 1) % attempts.length;
            if (count < attempts.length) count++;
            return true;
        }
    }

    private final AiCredentialVault credentials;
    private final Transport transport;
    private final LongSupplier clock;
    private final RateGate rate;

    SkillCustomService(AiCredentialVault credentials, Transport transport, LongSupplier clock) {
        this(credentials, transport, clock, new RateGate());
    }

    SkillCustomService(AiCredentialVault credentials, Transport transport, LongSupplier clock, RateGate rate) {
        this.credentials = Objects.requireNonNull(credentials);
        this.transport = Objects.requireNonNull(transport);
        this.clock = Objects.requireNonNull(clock);
        this.rate = Objects.requireNonNull(rate);
    }

    public static SkillCustomService forDevice() {
        return new SkillCustomService(AiCredentialVault.forDevice(), SkillCustomService::post, System::nanoTime,
                DEVICE_RATE);
    }

    public static String validateEndpoint(String endpoint) throws Failure {
        try {
            String canonical = SkillHttpsTransport.validateEndpoint(endpoint);
            URI address = URI.create(canonical);
            String host = address.getHost().toLowerCase(Locale.ROOT);
            if (host.equals("invalid") || host.endsWith(".invalid")) throw new Failure(Code.ENDPOINT_INVALID);
            SkillAuthoring.rejectCredential(canonical);
            SkillAuthoring.rejectCredential(address.getPath());
            return canonical;
        } catch (SkillHttpsTransport.Failure | IllegalArgumentException invalid) {
            throw new Failure(Code.ENDPOINT_INVALID);
        }
    }

    public Binding configure(String skillId, String endpoint, String optionalBearer) throws Failure {
        if (!isUuid(skillId)) throw new Failure(Code.INVALID_INPUT);
        String canonical = validateEndpoint(endpoint);
        String revision = UUID.randomUUID().toString();
        if (optionalBearer == null || optionalBearer.isEmpty()) return new Binding(skillId, revision, canonical, "");
        if (!AiCredentialVault.validBearer(optionalBearer)
                || AiCredentialVault.imageEndpointContainsCredential(canonical, optionalBearer)) {
            throw new Failure(Code.CREDENTIAL_UNAVAILABLE);
        }
        try {
            String sealed = credentials.seal(AiCredentialVault.Scope.SKILL_SERVICE, credentialId(skillId, revision),
                    canonical, optionalBearer);
            Binding saved = new Binding(skillId, revision, canonical, CIPHERTEXT_VERSION + sealed);
            if (!optionalBearer.equals(credential(saved))) throw new Failure(Code.CREDENTIAL_UNAVAILABLE);
            return saved;
        } catch (GeneralSecurityException | IllegalArgumentException unavailable) {
            throw new Failure(Code.CREDENTIAL_UNAVAILABLE);
        }
    }

    /** Refuse a changed URL that would store the existing credential as ordinary route text. */
    public void verifyEndpointOmitsCredential(Binding existing, String endpoint) throws Failure {
        String canonical = validateEndpoint(endpoint);
        if (existing == null || existing.endpoint().equals(canonical) || existing.encryptedBearer().isEmpty()) return;
        if (AiCredentialVault.imageEndpointContainsCredential(canonical, credential(existing))) {
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
        byte[] body = json(REQUEST_FIELD, argument);
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

    SkillResult.Text execute(Approval approval, Binding current, long deadlineNanos) throws Failure {
        return execute(approval, current, deadlineNanos, () -> {});
    }

    /** The caller supplies the current binding and rechecks it at transport admission. */
    public SkillResult.Text execute(Approval approval, Binding current, long deadlineNanos, Admission admission)
            throws Failure {
        Preview preview = approval == null ? null : approval.preview;
        if (preview == null || preview.owner != this || !preview.state.compareAndSet(State.APPROVED, State.CONNECTING)) {
            throw new Failure(Code.APPROVAL_REQUIRED);
        }
        try {
            if (admission == null || !preview.matches(current)) throw new Failure(Code.STALE);
            checkDeadline(preview.expiresAt, false);
            checkDeadline(deadlineNanos, false);
            long start = clock.getAsLong();
            long deadline = start + Math.min(deadlineNanos - start, MAX_CALL_NANOS);
            String bearer = credential(current);
            rejectCredentialInInput(preview.argument, bearer);
            checkDeadline(deadline, false);
            if (preview.state.get() != State.CONNECTING) throw new Failure(Code.CANCELLED);
            if (!rate.acquire(clock.getAsLong())) throw new Failure(Code.RATE_LIMITED);
            return exchange(preview, current.endpoint(), bearer, deadline, admission);
        } finally {
            preview.state.set(State.CLOSED);
        }
    }

    private SkillResult.Text exchange(Preview preview, String endpoint, String bearer, long deadline,
                                      Admission current) throws Failure {
        AtomicReference<Failure> denied = new AtomicReference<>();
        AtomicBoolean admitted = new AtomicBoolean();
        Reply result;
        try {
            result = transport.exchange(endpoint, bearer, preview.body.clone(), deadline, () -> {
                try {
                    admit(preview, deadline, current);
                    admitted.set(true);
                } catch (Failure failure) {
                    denied.set(failure);
                    throw failure;
                }
            });
        } catch (IOException | RuntimeException failure) {
            if (denied.get() != null) throw denied.get();
            if (failure instanceof Failure known) throw known;
            throw new Failure(admitted.get() ? Code.UNKNOWN_RESULT : Code.TRANSPORT);
        }
        if (denied.get() != null) throw denied.get();
        if (!admitted.get()) throw new Failure(Code.TRANSPORT);
        checkDeadline(deadline, true);
        SkillResult.Text decoded = decode(result, bearer);
        checkDeadline(deadline, true);
        return decoded;
    }

    private void admit(Preview preview, long deadline, Admission current) throws Failure {
        if (preview.state.get() != State.CONNECTING) throw new Failure(Code.CANCELLED);
        checkDeadline(preview.expiresAt, false);
        checkDeadline(deadline, false);
        try { current.check(); }
        catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new Failure(Code.CANCELLED);
        } catch (Exception stale) {
            checkDeadline(deadline, false);
            throw new Failure(Code.STALE);
        }
        checkDeadline(preview.expiresAt, false);
        checkDeadline(deadline, false);
        if (!preview.state.compareAndSet(State.CONNECTING, State.CLOSED)) throw new Failure(Code.CANCELLED);
    }

    private String credential(Binding binding) throws Failure {
        if (binding.encryptedBearer().isEmpty()) return "";
        try {
            String bearer = credentials.open(AiCredentialVault.Scope.SKILL_SERVICE,
                    credentialId(binding.skillId(), binding.revision()), binding.endpoint(),
                    binding.encryptedBearer().substring(CIPHERTEXT_VERSION.length()));
            if (AiCredentialVault.imageEndpointContainsCredential(binding.endpoint(), bearer)) {
                throw new Failure(Code.CREDENTIAL_UNAVAILABLE);
            }
            return bearer;
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

    private static SkillResult.Text decode(Reply result, String bearer) throws Failure {
        if (result == null || result.status() < 200 || result.status() > 599) throw new Failure(Code.BAD_RESPONSE);
        if (result.status() == 408 || result.status() >= 500) throw new Failure(Code.UNKNOWN_RESULT);
        if (result.status() >= 300) throw new Failure(Code.REMOTE_REFUSED);
        if (!jsonType(result.contentType()) || result.body() == null || result.body().length > MAX_REPLY_BYTES) {
            throw new Failure(Code.BAD_RESPONSE);
        }
        try {
            String raw = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(result.body())).toString();
            JSONObject object = SkillAuthoring.readObject(raw, MAX_REPLY_BYTES, MAX_MAPPED_JSON_NODES);
            if (object.length() != 1 || !(object.opt(RESPONSE_FIELD) instanceof String text)
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

    private static Reply post(String endpoint, String bearer, byte[] body, long deadline, WriteAdmission admission)
            throws Failure {
        try {
            SkillHttpsTransport.Response response = SkillHttpsTransport.post(endpoint, bearer, body, deadline,
                    MAX_REPLY_BYTES, admission::check);
            return new Reply(response.status(), response.contentType(), response.body());
        } catch (SkillHttpsTransport.Failure failure) {
            if (failure.mayHaveSent()) throw new Failure(Code.UNKNOWN_RESULT);
            throw new Failure(switch (failure.kind()) {
                case ENDPOINT -> Code.ENDPOINT_INVALID;
                case ADDRESS -> Code.ADDRESS_BLOCKED;
                case DEADLINE -> Code.DEADLINE;
                case CANCELLED -> Code.CANCELLED;
                case ADMISSION -> Code.STALE;
                default -> Code.TRANSPORT;
            });
        }
    }

    private static byte[] json(String key, String value) {
        return ("{" + JSONObject.quote(key) + ":" + JSONObject.quote(value) + "}").getBytes(StandardCharsets.UTF_8);
    }

    private static String digest(Binding binding, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String metadata = "custom-https-v1\n" + binding.skillId() + "\n" + binding.revision() + "\n"
                    + binding.endpoint() + "\nPOST\n" + REQUEST_FIELD + "\n" + RESPONSE_FIELD + "\n";
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
