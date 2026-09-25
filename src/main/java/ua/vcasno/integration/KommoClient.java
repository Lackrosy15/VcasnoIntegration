package ua.vcasno.integration;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import static ua.vcasno.integration.Domain.*;

@Component
public class KommoClient {
    private final Settings settings;
    private final JsonHttp http;
    public KommoClient(Settings settings, JsonHttp http) { this.settings = settings; this.http = http; }
    public String account() {
        String base = settings.kommo().baseUrl();
        if (base == null || base.isBlank()) throw Failure.invalid("Не настроен KOMMO_BASE_URL");
        return base.replaceAll("/+$", "").toLowerCase(java.util.Locale.ROOT);
    }
    private JsonNode call(String path, String method, Object body) {
        String token = settings.kommo().token();
        if (token == null || token.isBlank()) throw Failure.invalid("Не настроен KOMMO_TOKEN");
        return http.call("KOMMO", account(), "/api/v4" + path, "Bearer " + token, method, body);
    }
    public JsonNode rawLead(long id) {
        JsonNode node = call("/leads/" + id + "?with=catalog_elements", "GET", null);
        if (node.path("id").asLong() != id) throw new Failure(404, "LEAD_NOT_FOUND", "Сделка не найдена в Kommo");
        return node;
    }
    public String resolveFop(long leadId) {
        long fieldId = settings.kommo().fopFieldId();
        JsonNode lead = rawLead(leadId);
        for (JsonNode field : lead.path("custom_fields_values")) {
            if (field.path("field_id").asLong() != fieldId) continue;
            JsonNode values = field.path("values");
            if (!values.isArray() || values.size() != 1)
                throw Failure.invalid("Поле ФОП " + fieldId + " должно содержать ровно одно значение");
            JsonNode selected = values.path(0);
            long enumId = selected.path("enum_id").asLong(0);
            String value = selected.path("value").asText("").trim();
            if (enumId <= 0 && value.isBlank()) throw Failure.invalid("Поле ФОП " + fieldId + " пустое");
            List<Settings.FopMapping> mappings = settings.kommo().fopMappings();
            java.util.Set<String> matches = new java.util.HashSet<>();
            if (mappings != null) for (Settings.FopMapping mapping : mappings) {
                // enum_id is stable when an option label is renamed. Text is an alternative selector.
                boolean matched = mapping.enumId() != null
                        ? mapping.enumId() > 0 && mapping.enumId() == enumId
                        : mapping.value() != null && !mapping.value().isBlank() && mapping.value().trim().equals(value);
                if (matched) {
                    if (mapping.fopId() == null || mapping.fopId().isBlank())
                        throw Failure.invalid("В соответствии ФОП не указана касса");
                    matches.add(mapping.fopId());
                }
            }
            if (matches.size() != 1)
                throw Failure.invalid("Для значения поля ФОП " + fieldId + " нет однозначного соответствия кассе");
            return matches.iterator().next();
        }
        throw Failure.invalid("В сделке отсутствует поле ФОП " + fieldId);
    }
    public Lead lead(long id) {
        JsonNode lead = rawLead(id);
        BigDecimal budget = decimal(lead.path("price").asText(""), "бюджет сделки", 2);
        if (budget.signum() <= 0) throw Failure.invalid("Бюджет должен быть положительным");
        long catalogId = settings.kommo().productCatalogId();
        if (catalogId <= 0) throw Failure.invalid("Укажите KOMMO_PRODUCT_CATALOG_ID, чтобы отличать товары от других списков");
        List<Product> products = new ArrayList<>();
        for (JsonNode linked : lead.path("_embedded").path("catalog_elements")) {
            JsonNode metadata = linked.path("metadata");
            long linkedCatalog = linked.path("catalog_id").asLong(metadata.path("catalog_id").asLong());
            if (linkedCatalog != catalogId) continue;
            long elementId = linked.path("id").asLong();
            if (elementId <= 0) throw Failure.invalid("У товара отсутствует ID");
            JsonNode product = call("/catalogs/" + catalogId + "/elements/" + elementId, "GET", null);
            if (product.path("id").asLong() != elementId || product.path("is_deleted").asBoolean())
                throw Failure.invalid("Товар отсутствует или удалён: " + elementId);
            String name = product.path("name").asText("").trim();
            if (name.isEmpty()) throw Failure.invalid("У товара отсутствует название: " + elementId);
            // Kommo exposes quantity on the link, not on the catalog product itself.
            JsonNode quantityNode = linked.has("quantity") ? linked.path("quantity") : metadata.path("quantity");
            BigDecimal quantity = decimal(quantityNode.asText(""), "количество товара " + elementId, 3);
            BigDecimal price = decimal(field(product, settings.kommo().priceFieldId(), settings.kommo().priceFieldCode()),
                    "цена товара " + elementId, 2);
            if (quantity.signum() <= 0 || price.signum() <= 0) throw Failure.invalid("Цена и количество товара должны быть положительными");
            products.add(new Product(Long.toString(elementId), name, quantity, price));
        }
        if (products.isEmpty()) throw Failure.invalid("В сделке нет товаров выбранного каталога");
        if (products.size() > 200) throw Failure.invalid("В сделке более 200 товарных строк");
        return new Lead(id, budget, field(lead, COD_FIELD, null), field(lead, ADVANCE_LINK_FIELD, null),
                field(lead, FINAL_LINK_FIELD, null), List.copyOf(products));
    }
    public void writeLink(long lead, long fieldId, String url) {
        String current = field(rawLead(lead), fieldId, null);
        if (!current.isBlank() && !current.equals(url)) throw Failure.conflict("В поле " + fieldId + " уже записан другой чек");
        if (current.equals(url)) return;
        call("/leads", "PATCH", List.of(Map.of("id", lead, "custom_fields_values",
                List.of(Map.of("field_id", fieldId, "values", List.of(Map.of("value", url)))))));
    }
    public void ensureNote(Operation operation) {
        String marker = "[VcasnoIntegration:" + operation.id() + "]";
        // Read all pages before retrying a note after an ambiguous response.
        for (int page = 1; page <= 1000; page++) {
            JsonNode response = call("/leads/" + operation.leadId() + "/notes?limit=250&page=" + page, "GET", null);
            JsonNode notes = response.path("_embedded").path("notes");
            for (JsonNode note : notes) {
                if (note.path("params").path("text").asText("").contains(marker)) return;
            }
            if (!response.path("_links").has("next")) {
                String text = operation.kind().label + ": створено чек на " + operation.amount().toPlainString()
                        + " грн. ФОП: " + operation.fop() + ". Фіскальний номер: " + operation.fiscalNumber()
                        + "\n" + operation.receiptUrl() + "\n" + marker;
                call("/leads/notes", "POST", List.of(Map.of("entity_id", operation.leadId(), "note_type", "common",
                        "params", Map.of("text", text))));
                return;
            }
        }
        throw new Failure(502, "NOTE_SCAN_LIMIT", "Не удалось проверить все примечания сделки; требуется ручная проверка");
    }
}
