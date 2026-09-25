package ua.vcasno.integration;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.io.IOException;

@Component
public class JsonHttp {
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    public JsonHttp(ObjectMapper json) { this.json = json; }

    public JsonNode call(String provider, String base, String path, String authorization, String method, Object body) {
        URI uri;
        try {
            uri = URI.create(base + path);
            boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
            if ((!"https".equals(uri.getScheme()) && !(local && "http".equals(uri.getScheme())))
                    || uri.getUserInfo() != null || uri.getHost() == null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) { throw Failure.invalid("Некорректный URL " + provider); }
        if (authorization == null || authorization.isBlank()) throw Failure.invalid("Не настроен токен " + provider);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("Authorization", authorization).header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new Failure(502, provider + "_HTTP_ERROR", provider + " вернул HTTP " + response.statusCode());
            if (response.body().isBlank()) return json.createObjectNode();
            try { return json.readTree(response.body()); }
            catch (RuntimeException e) { throw new Failure(502, provider + "_INVALID_RESPONSE", provider + " вернул некорректный JSON"); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(503, provider + "_INTERRUPTED", "Запрос прерван; повторите исходную операцию");
        } catch (IOException e) {
            throw new Failure(502, provider + "_UNAVAILABLE", "Нет подтверждения от " + provider + "; повторите исходную операцию");
        }
    }
}
