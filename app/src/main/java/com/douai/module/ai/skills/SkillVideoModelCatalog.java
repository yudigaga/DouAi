package com.douai.module.ai.skills;

import com.douai.module.ai.AiCredentialVault;
import com.douai.module.ai.AiExtensionHttp;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** User-requested model catalogue for a recognized video submit route. */
public final class SkillVideoModelCatalog {
    private static final Pattern VERSIONED_SUBMIT = Pattern.compile("(.*/v[0-9]+)/videos(?:/generations)?");

    private SkillVideoModelCatalog() {}

    public static String resolveEndpoint(String exactSubmitEndpoint) throws IOException {
        return resolveEndpoint(submitEndpoint(exactSubmitEndpoint));
    }

    public static List<String> fetch(String exactSubmitEndpoint, String bearer) throws IOException {
        return fetch(exactSubmitEndpoint, bearer, SkillVideoModelCatalog::guardedGet);
    }

    static List<String> fetch(String exactSubmitEndpoint, String bearer, SkillImageModelCatalog.Transport transport)
            throws IOException {
        URI submit = submitEndpoint(exactSubmitEndpoint);
        if (bearer == null || !bearer.isEmpty() && !AiCredentialVault.validBearer(bearer)) {
            throw new IOException("请填写视频服务的有效密钥");
        }
        if (AiCredentialVault.imageEndpointContainsCredential(submit.toString(), bearer)) {
            throw new IOException("视频接口地址不能包含服务密钥");
        }
        return new SkillImageModelCatalog(transport).fetchVideoCatalogue(resolveEndpoint(submit), bearer);
    }

    private static URI submitEndpoint(String value) throws IOException {
        try { return URI.create(SkillCustomService.validateEndpoint(value)); }
        catch (SkillCustomService.Failure invalid) { throw new IOException("请填写有效的公网 HTTPS 视频接口地址"); }
    }

    private static String resolveEndpoint(URI submit) throws IOException {
        String path = SkillImageProviderAddress.withoutTrailingSlash(submit.getRawPath());
        Matcher known = VERSIONED_SUBMIT.matcher(path);
        if (!known.matches()) throw new IOException("此视频接口无法推导模型列表，请手动填写模型名称");
        return submitEndpoint("https://" + submit.getRawAuthority() + known.group(1) + "/models").toString();
    }

    private static SkillImageModelCatalog.Reply guardedGet(String endpoint, String method, Map<String, String> headers,
                                                           byte[] body, long deadline, int maximum,
                                                           AiExtensionHttp.ResponseReader<SkillImageModelCatalog.Reply> reader)
            throws IOException {
        if (!"GET".equals(method) || body != null) throw new IOException("模型列表只支持 GET");
        String authorization = headers.get("Authorization");
        String bearer = authorization == null ? "" : authorization.substring("Bearer ".length());
        SkillHttpsTransport.Response response;
        try { response = SkillHttpsTransport.get(endpoint, bearer, deadline, maximum, () -> {}); }
        catch (SkillHttpsTransport.Failure unavailable) {
            AiExtensionHttp.Kind kind = switch (unavailable.kind()) {
                case DEADLINE -> AiExtensionHttp.Kind.DEADLINE;
                case CANCELLED -> AiExtensionHttp.Kind.CANCELLED;
                case RESPONSE_LIMIT -> AiExtensionHttp.Kind.RESPONSE_LIMIT;
                default -> AiExtensionHttp.Kind.TRANSPORT;
            };
            throw new AiExtensionHttp.Failure(kind, unavailable.mayHaveSent());
        }
        try (var input = new ByteArrayInputStream(response.body())) {
            return reader.read(response.status(),
                    name -> "Content-Type".equalsIgnoreCase(name) ? response.contentType() : null, input);
        }
    }
}
