package ua.vcasno.integration;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Map;
import static ua.vcasno.integration.Domain.*;

@Component
public class VchasnoClient {
    private final JsonHttp http;
    private final Settings settings;
    private final ObjectMapper json;
    public VchasnoClient(JsonHttp http, Settings settings, ObjectMapper json) {
        this.http = http; this.settings = settings; this.json = json;
    }
    private JsonNode execute(Settings.CashRegister register, Object payload) {
        JsonNode response = http.call("VCHASNO", settings.vchasnoUrl(), "/api/v3/fiscal/execute", register.token(), "POST", payload);
        if (!response.path("res").isIntegralNumber() || !response.path("res_action").isIntegralNumber())
            throw new Failure(502, "VCHASNO_INVALID_RESPONSE", "Касса не вернула статус операции; повторите исходный запрос");
        int code = response.path("res").asInt(), action = response.path("res_action").asInt();
        if (code != 0 || action != 0)
            throw new Failure(action == 3 ? 422 : 502, action == 3 ? "VCHASNO_ACTION_REQUIRED" : "VCHASNO_RETRY_REQUIRED",
                    "Касса: res=" + code + ", res_action=" + action + (action == 3 ? "; требуется проверка кассы и данных" : "; повторите исходную операцию"));
        return response;
    }
    public Register register(Settings.CashRegister register) {
        JsonNode response = execute(register, Map.of("fiscal", Map.of("task", 18)));
        String id = response.path("info").path("fisid").asText("");
        if (id.isBlank()) throw new Failure(502, "VCHASNO_INVALID_RESPONSE", "Касса не вернула фискальный номер ПРРО");
        return new Register(id, response.path("info").path("isFis").asInt(-1) == 0);
    }
    public Receipt issue(Settings.CashRegister register, Operation operation) {
        JsonNode response = execute(register, json.readTree(operation.payload()));
        JsonNode info = response.path("info");
        String number = info.path("doccode").asText(""), url = info.path("qr").asText("");
        if (number.isBlank() || !operation.registerId().equals(info.path("fisid").asText())
                || !number.equals(numberFromUrl(url)))
            throw new Failure(502, "VCHASNO_INVALID_RECEIPT", "Ответ кассы не содержит ожидаемых реквизитов чека; повторите исходную операцию");
        return new Receipt(number, url);
    }
    public Receipt recover(Settings.CashRegister register, Operation operation, String url) {
        String number = numberFromUrl(url);
        JsonNode response = http.call("VCHASNO", settings.vchasnoUrl(), "/api/v3/check-task/" + number, register.token(), "GET", null);
        JsonNode task = response.path("task"), fiscal = task.path("fiscal");
        JsonNode expected = json.readTree(operation.payload()).path("fiscal");
        if (!number.equals(response.path("fiscal_number").asText())
                || !operation.registerId().equals(task.path("device").asText())
                || !operation.tag().equals(task.path("tag").asText())
                || fiscal.path("task").asInt(-1) != expected.path("task").asInt(-2)
                || fiscal.path("subtask").asInt(0) != expected.path("subtask").asInt(0)
                || decimal(fiscal.path("receipt").path("sum").asText(""), "сумма существующего чека", 2)
                    .compareTo(operation.amount()) != 0)
            throw Failure.conflict("Существующий чек не соответствует сохранённой операции (номер, касса, tag, тип или сумма)");
        return new Receipt(number, url);
    }
    public String validateAdvance(Settings.CashRegister register, String url, String registerId) {
        String number = numberFromUrl(url);
        JsonNode response = http.call("VCHASNO", settings.vchasnoUrl(), "/api/v3/check-task/" + number, register.token(), "GET", null);
        JsonNode task = response.path("task"), fiscal = task.path("fiscal");
        if (!number.equals(response.path("fiscal_number").asText()) || !registerId.equals(task.path("device").asText())
                || fiscal.path("task").asInt() != 1 || fiscal.path("subtask").asInt() != 1
                || decimal(fiscal.path("receipt").path("sum").asText(""), "сумма чека предоплаты", 2).compareTo(ADVANCE) != 0)
            throw Failure.invalid("Ссылка должна указывать на чек предоплаты 150 грн, выпущенный этой кассой");
        return number;
    }
    public static String numberFromUrl(String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || !"kasa.vchasno.ua".equals(uri.getHost()) || uri.getUserInfo() != null
                    || uri.getPort() != -1 || !uri.getPath().matches("/(?:c|check-viewer)/[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            return uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1);
        } catch (RuntimeException e) { throw Failure.invalid("Некорректная ссылка на чек Вчасно.Каса"); }
    }
}
