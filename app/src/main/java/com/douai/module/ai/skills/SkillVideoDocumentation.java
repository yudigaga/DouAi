package com.douai.module.ai.skills;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Sources for video authoring, independently validated before they become executable facts. */
public final class SkillVideoDocumentation {
    private static final Pattern NEW_API = Pattern.compile("(?i)(?<![a-z0-9])new[\\s-]*api(?![a-z0-9])");
    private static final Pattern HTTPS_LINK = Pattern.compile("(?i)https://[^\\s<>，。；、！!）】)\\]}]+");
    private static final String NEW_API_FACTS = "/skill-documentation/new-api-video-facts.json";

    private SkillVideoDocumentation() { }

    public static SkillVideoFacts knownFacts(String request) {
        if (!NEW_API.matcher(request).find()) return SkillVideoFacts.empty();
        try (var input = SkillVideoDocumentation.class.getResourceAsStream(NEW_API_FACTS)) {
            if (input == null) return SkillVideoFacts.empty();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            int count;
            while ((count = input.read(buffer)) > 0) {
                if (bytes.size() + count > 16_000) return SkillVideoFacts.empty();
                bytes.write(buffer, 0, count);
            }
            JSONObject source = SkillAuthoring.readObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8), 16_000);
            return SkillVideoFacts.fromDocument(source.getJSONObject("facts"));
        } catch (IOException | org.json.JSONException | IllegalArgumentException unavailable) {
            return SkillVideoFacts.empty();
        }
    }

    public static String openApiLink(String request) {
        Matcher links = HTTPS_LINK.matcher(request);
        String selected = "";
        while (links.find()) {
            String link = links.group();
            if (!link.toLowerCase(java.util.Locale.ROOT).matches(".*\\.json(?:[?#].*)?")) continue;
            if (!selected.isEmpty()) throw new IllegalArgumentException("请一次指定一份 OpenAPI JSON 文档");
            selected = SkillDocumentationClient.normalize(link);
        }
        return selected;
    }
}
