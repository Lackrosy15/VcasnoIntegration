package ua.vcasno.integration;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@Component
public class WebhookLeadId {
    private final ObjectMapper json;
    public WebhookLeadId(ObjectMapper json) { this.json = json; }

    public long read(HttpServletRequest request) {
        Set<Long> ids = new HashSet<>();
        request.getParameterMap().forEach((name, values) -> {
            if (name.equals("leadId") || name.equals("lead_id")
                    || name.matches("leads?\\[id]")
                    || name.matches("leads?\\[[a-z_]+]\\[\\d+]\\[id]")) {
                if (values.length != 1) throw invalid();
                add(ids, values[0]);
            }
        });
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("application/json")) {
            JsonNode body;
            try {
                byte[] bytes = request.getInputStream().readNBytes(262145);
                if (bytes.length > 262144) throw new Failure(413, "WEBHOOK_TOO_LARGE", "Слишком большой вебхук");
                body = bytes.length == 0 ? json.createObjectNode() : json.readTree(bytes);
            } catch (IOException | tools.jackson.core.JacksonException e) { throw invalid(); }
            if (body == null || !body.isObject()) throw invalid();
            if (body.has("leadId")) add(ids, body.path("leadId").asText(""));
            if (body.has("lead_id")) add(ids, body.path("lead_id").asText(""));
            for (String root : new String[]{"lead", "leads"}) {
                JsonNode entity = body.path(root);
                if (entity.isArray() || entity.has("id")) collect(ids, entity);
                else if (entity.isObject()) for (JsonNode event : entity) collect(ids, event);
            }
        }
        if (ids.size() != 1) throw invalid();
        return ids.iterator().next();
    }
    private void collect(Set<Long> ids, JsonNode entity) {
        if (entity.isArray()) {
            for (JsonNode item : entity) collect(ids, item);
        } else if (entity.isObject() && entity.has("id")) {
            add(ids, entity.path("id").asText(""));
        }
    }
    private void add(Set<Long> ids, String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw invalid();
        try { ids.add(Long.parseLong(value)); }
        catch (NumberFormatException e) { throw invalid(); }
    }
    private Failure invalid() {
        return new Failure(400, "INVALID_LEAD_ID", "Нужен ID одной сделки: leadId в URL или lead/leads в теле вебхука Kommo");
    }
}
