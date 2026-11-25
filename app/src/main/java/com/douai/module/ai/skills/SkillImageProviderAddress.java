package com.douai.module.ai.skills;

import com.douai.module.ai.AiCredentialVault;
import com.douai.module.ai.AiExtensionHttp;
import com.douai.module.config.AiModelConfig;
import com.douai.module.feature.ChatCompletionClient;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

/** Resolves a provider base or an explicit image route to the exact HTTPS POST address. */
public final class SkillImageProviderAddress {
    static final String GENERATION_SUFFIX = "/images/generations";
    private static final String CHAT_COMPLETIONS = "/chat/completions";
    private static final String RESPONSES = "/responses";
    private static final String MODEL_DETAILS = "/models/";

    public record Form(String address, boolean complete) { }

    private SkillImageProviderAddress() { }

    /** Returns only a reusable ordinary provider base, with no credentials or model-catalogue metadata. */
    public static String fromAiProvider(AiModelConfig config) {
        if (config == null || (!AiModelConfig.CHAT.equals(config.protocol)
                && !AiModelConfig.RESPONSES.equals(config.protocol))) return "";
        try {
            AiExtensionHttp.exactEndpoint(config.apiEndpoint);
            String base = ChatCompletionClient.providerBase(config);
            URI address = AiExtensionHttp.exactEndpoint(base);
            String path = withoutTrailingSlash(address.getPath()).toLowerCase(Locale.ROOT);
            if (nonImagePath(path) || path.endsWith(GENERATION_SUFFIX) || providerCredentialInBase(config, base)) return "";
            resolve(base, false); // The derived image endpoint must also fit the HTTPS address contract.
            return base;
        } catch (IllegalArgumentException incompatible) {
            return "";
        }
    }

    private static boolean providerCredentialInBase(AiModelConfig config, String base) {
        if (AiCredentialVault.imageEndpointContainsCredential(base, config.apiKey)) return true;
        for (Map.Entry<String, String> header : config.headers().entrySet()) {
            String name = header.getKey().toLowerCase(Locale.ROOT);
            String value = header.getValue().trim();
            switch (name) {
                case "authorization":
                    String[] parts = value.split("\\s+", 2);
                    if (parts.length == 2 && AiCredentialVault.imageEndpointContainsCredential(base, parts[1])) return true;
                    if (AiCredentialVault.imageEndpointContainsCredential(base, value)) return true;
                    break;
                case "api-key":
                case "x-api-key":
                case "x-goog-api-key":
                    if (AiCredentialVault.imageEndpointContainsCredential(base, value)) return true;
                    break;
                default:
                    break;
            }
        }
        return false;
    }

    public static Form fromSaved(String generationEndpoint) {
        String saved = generationEndpoint == null ? "" : generationEndpoint;
        if (saved.trim().isEmpty()) return new Form("", false);
        if (saved.endsWith(GENERATION_SUFFIX)) {
            String base = saved.substring(0, saved.length() - GENERATION_SUFFIX.length());
            try {
                if (saved.equals(resolve(base, false))) return new Form(base, false);
            } catch (IllegalArgumentException invalid) {
                // Keep unsupported or older stored addresses visible for correction.
            }
        }
        return new Form(saved, true);
    }

    public static String resolve(String address, boolean complete) {
        String value = address == null ? "" : address.trim();
        if (value.isEmpty()) return "";
        URI uri = AiExtensionHttp.exactEndpoint(value);
        String rawPath = withoutTrailingSlash(uri.getRawPath());
        if (rawPath.endsWith(GENERATION_SUFFIX)) return value;

        String base = withoutTrailingSlash(value);
        if (nonImagePath(uri.getPath())) {
            if (!complete && rawPath.endsWith(CHAT_COMPLETIONS)) {
                base = base.substring(0, base.length() - CHAT_COMPLETIONS.length());
            } else if (!complete && rawPath.endsWith(RESPONSES)) {
                base = base.substring(0, base.length() - RESPONSES.length());
            } else {
                throw new IllegalArgumentException("生图地址不能使用聊天或模型列表接口");
            }
        }
        if (complete) return value;
        return AiExtensionHttp.exactEndpoint(base + GENERATION_SUFFIX).toString();
    }

    public static boolean isNonImageOperation(String normalizedEndpoint) {
        String value = normalizedEndpoint == null ? "" : normalizedEndpoint.trim();
        if (value.isEmpty()) return false;
        try { return nonImagePath(AiExtensionHttp.exactEndpoint(value).getPath()); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private static boolean nonImagePath(String path) {
        String terminal = withoutTrailingSlash(path).toLowerCase(Locale.ROOT);
        int modelPath = terminal.lastIndexOf(MODEL_DETAILS);
        return terminal.endsWith(CHAT_COMPLETIONS) || terminal.endsWith(RESPONSES)
                || terminal.endsWith("/messages") || terminal.endsWith("/models")
                || (modelPath >= 0 && terminal.indexOf('/', modelPath + MODEL_DETAILS.length()) < 0);
    }

    static String withoutTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') end--;
        return value.substring(0, end);
    }
}
