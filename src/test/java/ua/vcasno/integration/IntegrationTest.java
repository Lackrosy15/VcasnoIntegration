package ua.vcasno.integration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static ua.vcasno.integration.Domain.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=",
        "integration.webhook-token=local-webhook-secret-at-least-32-characters",
        "integration.webhook-worker-enabled=false",
        "integration.webhook-fop-id=",
        "integration.api-key=local-test-key", "integration.kommo.token=kommo-test-token",
        "integration.kommo.product-catalog-id=7", "integration.cash-registers.test.token=cash-test-token",
        "integration.cash-registers.fop1.token=cash-other-token",
        "integration.kommo.fop-mappings[0].enum-id=901", "integration.kommo.fop-mappings[0].fop-id=test",
        "integration.kommo.fop-mappings[1].value=Second FOP", "integration.kommo.fop-mappings[1].fop-id=fop1",
        "spring.datasource.url=jdbc:h2:mem:integration;DB_CLOSE_DELAY=-1"})
class IntegrationTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpServer PROVIDER;
    static final List<JsonNode> ISSUED = new CopyOnWriteArrayList<>();
    static final Map<String, JsonNode> RECEIPTS = new ConcurrentHashMap<>();
    static final List<String> NOTES = new CopyOnWriteArrayList<>();
    static final Map<Long, String> LINKS = new ConcurrentHashMap<>();
    static final AtomicInteger ISSUE_CALLS = new AtomicInteger();
    static volatile boolean failPatch, failNoteAfterWrite, failIssueAfterWrite, rejectFiscal;
    static volatile String registerId = "99990001", cod = "1590.00", productPrice = "870.00";
    static volatile List<Map<String, Object>> fopValues;
    static volatile String qrOverride;
    static volatile String receiptNumberOverride, receiptRegisterOverride;
    static {
        try {
            PROVIDER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            PROVIDER.createContext("/", IntegrationTest::handle);
            PROVIDER.start();
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + PROVIDER.getAddress().getPort();
        registry.add("integration.kommo.base-url", () -> base);
        registry.add("integration.vchasno-url", () -> base);
    }
    @Autowired ReceiptService service;
    @Autowired WebhookQueue queue;
    @Autowired JdbcTemplate db;
    @Autowired Settings settings;
    @Autowired KommoClient kommo;
    @Autowired VchasnoClient vchasno;
    @Autowired ReceiptBuilder builder;
    @Autowired OperationStore store;
    @Autowired ObjectMapper mapper;
    @Value("${local.server.port}") int port;
    @BeforeEach void reset() {
        db.update("DELETE FROM webhook_job");
        db.update("DELETE FROM receipt_operation"); ISSUED.clear(); RECEIPTS.clear(); NOTES.clear(); LINKS.clear(); ISSUE_CALLS.set(0);
        failPatch = false; failNoteAfterWrite = false; failIssueAfterWrite = false; rejectFiscal = false;
        qrOverride = null; receiptNumberOverride = null; receiptRegisterOverride = null;
        registerId = "99990001"; cod = "1590.00"; productPrice = "870.00";
        fopValues = List.of(Map.of("enum_id", 901, "value", "Test FOP"));
    }
    @AfterAll static void stop() { PROVIDER.stop(0); }

    @Test void webhookCompletesWithQrThatIsNotAViewerUrl() {
        qrOverride = "QR content is not a viewer URL";
        var job = queue.accept(42, Kind.FULL);
        queue.processNext();
        var completed = queue.get(job.id());
        assertThat(completed.status()).isEqualTo("COMPLETED");
        Result receipt = service.get(completed.operationId());
        assertThat(receipt.receiptUrl()).isEqualTo("https://kasa.vchasno.ua/check-viewer/" + receipt.fiscalNumber());
        assertThat(LINKS.get(FINAL_LINK_FIELD)).isEqualTo(receipt.receiptUrl());
        assertThat(NOTES).singleElement().asString().contains(receipt.receiptUrl());
        assertThat(ISSUE_CALLS).hasValue(1);
    }
    @Test void missingQrDoesNotPreventSavingConfirmedReceipt() {
        qrOverride = "";
        assertThat(service.create(42, "test", Kind.FULL).status()).isEqualTo("COMPLETED");
        assertThat(ISSUE_CALLS).hasValue(1);
    }
    @Test void invalidFiscalIdentityStillPreventsSync() {
        receiptNumberOverride = "../wrong";
        Result pending = service.create(42, "test", Kind.FULL);
        assertThat(pending.error()).contains("VCHASNO_INVALID_RECEIPT");
        assertThat(pending.fiscalNumber()).isNull();
        assertThat(LINKS).isEmpty(); assertThat(NOTES).isEmpty();
    }
    @Test void wrongReceiptRegisterStillPreventsSync() {
        receiptRegisterOverride = "different-register";
        Result pending = service.create(42, "test", Kind.FULL);
        assertThat(pending.error()).contains("VCHASNO_INVALID_RECEIPT");
        assertThat(pending.fiscalNumber()).isNull();
        assertThat(LINKS).isEmpty(); assertThat(NOTES).isEmpty();
    }
    @Test void receiptUrlAcceptsViewerAndLegacyButRejectsOtherHostsAndPaths() {
        for (String path : List.of("/c/", "/check-viewer/"))
            assertThat(VchasnoClient.numberFromUrl("https://kasa.vchasno.ua" + path + "zuusZ8O35Wc"))
                    .isEqualTo("zuusZ8O35Wc");
        for (String url : List.of("https://evil.example/check-viewer/abc", "http://kasa.vchasno.ua/check-viewer/abc",
                "https://kasa.vchasno.ua/check-viewer/", "https://kasa.vchasno.ua/check-viewer/a/b",
                "https://evil@kasa.vchasno.ua/check-viewer/abc"))
            assertThatThrownBy(() -> VchasnoClient.numberFromUrl(url)).isInstanceOf(Failure.class);
    }
    @Test void recoveryReadsExistingReceiptAndNeverIssuesAgain() throws Exception {
        failIssueAfterWrite = true;
        Result pending = service.create(42, "test", Kind.FULL);
        Operation operation = store.get(kommo.account(), pending.operationId());
        String url = "https://kasa.vchasno.ua/check-viewer/TEST_" + operation.tag();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/operations/"
                + operation.id() + "/recover")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("receiptUrl", url))));
        var client = HttpClient.newHttpClient();
        assertThat(client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        var response = client.send(request.header("X-API-Key", "local-test-key").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(response.body()).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(LINKS.get(FINAL_LINK_FIELD)).isEqualTo(url);
        assertThat(service.recover(operation.id(), url).status()).isEqualTo("COMPLETED");
        assertThat(NOTES).hasSize(1);
        assertThat(ISSUE_CALLS).hasValue(1);
    }
    @Test void recoveryRejectsReceiptFromAnotherOperation() {
        failIssueAfterWrite = true;
        Result pending = service.create(42, "test", Kind.FULL);
        assertThatThrownBy(() -> service.recover(pending.operationId(),
                "https://kasa.vchasno.ua/check-viewer/TEST_wrong-operation")).isInstanceOf(Failure.class);
        assertThat(service.get(pending.operationId()).fiscalNumber()).isNull();
        assertThat(LINKS).isEmpty(); assertThat(NOTES).isEmpty();
        assertThat(ISSUE_CALLS).hasValue(1);
    }
    @Test void fullFlowWritesCorrectFieldAndNoteAndDeduplicates() {
        Result first = service.create(42, "test", Kind.FULL);
        assertThat(first.status()).isEqualTo("COMPLETED");
        assertThat(first.amount()).isEqualByComparingTo("1740.00");
        assertThat(LINKS.get(FINAL_LINK_FIELD)).isEqualTo(first.receiptUrl());
        assertThat(NOTES).singleElement().asString().contains("✅ Чек создан\nФОП: Тестовая касса\nЧек оплаты на 1740.00 грн\nСсылка на чек ➡️ ");
        assertThat(service.create(42, "test", Kind.FULL).operationId()).isEqualTo(first.operationId());
        assertThat(ISSUE_CALLS).hasValue(1);
        assertThatThrownBy(() -> service.create(42, "fop1", Kind.FULL)).isInstanceOf(Failure.class);
        assertThatThrownBy(() -> service.create(42, "test", Kind.PREPAYMENT)).isInstanceOf(Failure.class);
    }
    private HttpResponse<String> hook(String query, String method) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/webhooks/kommo/full?" + query)).method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    @Test void diagnosticsReadsStoredFailureWithoutRetryAndRequiresApiKey() throws Exception {
        failPatch = true;
        var job = queue.accept(42, Kind.FULL);
        queue.processNext();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/leads/42/receipts/status")).GET();
        HttpClient client = HttpClient.newHttpClient();
        assertThat(client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        var response = client.send(request.header("X-API-Key", "local-test-key").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.path("webhookJobs").path(0).path("id").asText()).isEqualTo(job.id());
        assertThat(body.path("operations").path(0).path("status").asText()).isEqualTo("SYNC_PENDING");
        assertThat(body.path("operations").path(0).path("error").asText()).contains("KOMMO_HTTP_ERROR");
        assertThat(response.body()).doesNotContain("cash-test-token", "kommo-test-token", "payload");
        assertThat(ISSUE_CALLS).hasValue(1);
    }
    private HttpResponse<String> hookBody(String query, String contentType, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/webhooks/kommo/full?key=local-webhook-secret-at-least-32-characters" + query))
                .header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    @Test void kommoFormWebhookSuppliesLeadWithoutUrlPlaceholder() throws Exception {
        for (String event : List.of("add", "update", "status")) {
            String body = "account%5Bid%5D=999&leads%5B" + event + "%5D%5B0%5D%5Bid%5D=42"
                    + "&leads%5B" + event + "%5D%5B0%5D%5Bstatus_id%5D=777";
            var response = hookBody("", "application/x-www-form-urlencoded", body);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(response.body()).path("leadId").asLong()).isEqualTo(42);
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM webhook_job", Integer.class)).isEqualTo(1);
        assertThat(ISSUE_CALLS).hasValue(0);
    }
    @Test void kommoJsonWebhookReadsOnlyLeadEntityIds() throws Exception {
        for (String body : List.of("{\"leads\":{\"status\":[{\"id\":42,\"status_id\":777}]},\"account\":{\"id\":999}}",
                "{\"lead\":{\"id\":42}}", "{\"leads\":[{\"id\":42}]}")) {
            var response = hookBody("", "application/json", body);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(response.body()).path("leadId").asLong()).isEqualTo(42);
        }
    }
    @Test void conflictingOrMissingBodyLeadIdsNeverQueueFiscalization() throws Exception {
        assertThat(hookBody("&leadId=43", "application/json", "{\"lead\":{\"id\":42}}").statusCode()).isEqualTo(400);
        assertThat(hookBody("", "application/json", "{\"leads\":[{\"id\":42},{\"id\":43}]}").statusCode()).isEqualTo(400);
        assertThat(hookBody("", "application/json", "{\"account\":{\"id\":42},\"contacts\":[{\"id\":42}]}").statusCode()).isEqualTo(400);
        assertThat(hookBody("", "application/json", "broken").statusCode()).isEqualTo(400);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM webhook_job", Integer.class)).isZero();
    }
    @Test void webhookNeedsOnlyUrlAndAcknowledgesBeforeAnyFiscalCall() throws Exception {
        String query = "key=local-webhook-secret-at-least-32-characters&leadId=42";
        HttpResponse<String> first = hook(query, "POST");
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(first.body()).path("status").asText()).isEqualTo("QUEUED");
        assertThat(ISSUE_CALLS).hasValue(0);
        HttpResponse<String> duplicate = hook(query, "GET");
        String jobId = JSON.readTree(first.body()).path("id").asText();
        assertThat(JSON.readTree(duplicate.body()).path("id").asText()).isEqualTo(jobId);
        queue.processNext();
        assertThat(queue.get(jobId).status()).isEqualTo("COMPLETED");
        hook(query, "POST"); queue.processNext();
        assertThat(ISSUE_CALLS).hasValue(1);
        assertThat(first.headers().firstValue("Cache-Control")).contains("no-store");
    }
    @Test void webhookRejectsMissingWrongDuplicateKeysAndUnsupportedMethods() throws Exception {
        assertThat(hook("leadId=42", "POST").statusCode()).isEqualTo(401);
        assertThat(hook("key=wrong&leadId=42", "GET").statusCode()).isEqualTo(401);
        String query = "key=local-webhook-secret-at-least-32-characters&leadId=42";
        assertThat(hook(query + "&key=wrong", "POST").statusCode()).isEqualTo(401);
        assertThat(hook(query, "HEAD").statusCode()).isEqualTo(405);
        assertThat(hook(query, "DELETE").statusCode()).isEqualTo(405);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM webhook_job", Integer.class)).isZero();
    }
    @Test void webhookRejectsAmbiguousOrInvalidLeadIds() throws Exception {
        String key = "key=local-webhook-secret-at-least-32-characters";
        for (String suffix : List.of("", "&leadId=0", "&leadId=-1", "&leadId=abc", "&leadId=9999999999999999999", "&leadId=42&leadId=43"))
            assertThat(hook(key + suffix, "POST").statusCode()).isEqualTo(400);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM webhook_job", Integer.class)).isZero();
    }
    @Test void webhookKeyCannotAccessNormalApiOrSelectRegisterThroughQuery() throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/v1/leads/42/receipts/full?key=local-webhook-secret-at-least-32-characters"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
        var accepted = hook("key=local-webhook-secret-at-least-32-characters&leadId=42&fopId=fop1", "POST");
        queue.processNext();
        WebhookQueue.Job job = queue.get(JSON.readTree(accepted.body()).path("id").asText());
        assertThat(service.get(job.operationId()).fopId()).isEqualTo("test");
    }
    @Test void queueRecoversInterruptedJobAndRetriesUncertainFiscalResult() {
        var job = queue.accept(42, Kind.FULL);
        db.update("UPDATE webhook_job SET status='RUNNING' WHERE id=?", job.id());
        queue.recoverInterrupted();
        failIssueAfterWrite = true;
        queue.processNext();
        assertThat(queue.get(job.id()).status()).isEqualTo("RETRY");
        failIssueAfterWrite = false;
        db.update("UPDATE webhook_job SET next_attempt_at=CURRENT_TIMESTAMP WHERE id=?", job.id());
        queue.processNext();
        assertThat(queue.get(job.id()).status()).isEqualTo("COMPLETED");
        assertThat(RECEIPTS).hasSize(1);
        assertThat(ISSUED.get(0)).isEqualTo(ISSUED.get(1));
    }
    @Test void queueDoesNotAutomaticallyRetryBusinessErrorsButAdminCanResume() {
        fopValues = null;
        var job = queue.accept(42, Kind.FULL);
        queue.processNext();
        assertThat(queue.get(job.id()).status()).isEqualTo("ACTION_REQUIRED");
        queue.accept(42, Kind.FULL); queue.processNext();
        assertThat(queue.get(job.id()).attempts()).isEqualTo(1);
        fopValues = List.of(Map.of("enum_id", 901));
        queue.retry(job.id()); queue.processNext();
        assertThat(queue.get(job.id()).status()).isEqualTo("COMPLETED");
    }
    @Test void queueLimitsAutomaticRetriesWithoutIssuingAnotherReceipt() {
        failPatch = true;
        var job = queue.accept(42, Kind.FULL);
        for (int i = 0; i < 10; i++) {
            db.update("UPDATE webhook_job SET next_attempt_at=CURRENT_TIMESTAMP WHERE id=?", job.id());
            queue.processNext();
        }
        assertThat(queue.get(job.id()).status()).isEqualTo("ACTION_REQUIRED");
        assertThat(queue.get(job.id()).attempts()).isEqualTo(10);
        assertThat(ISSUE_CALLS).hasValue(1);
    }
    @Test void automaticallyRoutesByEnumIdEvenAfterLabelRename() {
        fopValues = List.of(Map.of("enum_id", 901, "value", "Renamed FOP"));
        assertThat(service.create(42, null, Kind.FULL).fopId()).isEqualTo("test");
        assertThat(ISSUE_CALLS).hasValue(1);
    }
    @Test void automaticallyRoutesByExactTextValue() {
        fopValues = List.of(Map.of("value", "Second FOP"));
        assertThat(service.create(42, null, Kind.FULL).fopId()).isEqualTo("fop1");
    }
    @Test void missingUnknownAndMultipleFopsDoNotIssueReceipts() {
        fopValues = null;
        assertThatThrownBy(() -> service.create(42, null, Kind.FULL)).isInstanceOf(Failure.class).hasMessageContaining("2110073");
        fopValues = List.of(Map.of("enum_id", 999, "value", "Unknown"));
        assertThatThrownBy(() -> service.create(42, null, Kind.FULL)).isInstanceOf(Failure.class).hasMessageContaining("соответствия");
        fopValues = List.of(Map.of("enum_id", 901), Map.of("value", "Second FOP"));
        assertThatThrownBy(() -> service.create(42, null, Kind.FULL)).isInstanceOf(Failure.class).hasMessageContaining("ровно одно");
        assertThat(ISSUE_CALLS).hasValue(0);
    }
    @Test void automaticRetryKeepsOriginalRegisterWhenLeadFopChanges() {
        failIssueAfterWrite = true;
        Result pending = service.create(42, null, Kind.FULL);
        fopValues = List.of(Map.of("value", "Second FOP"));
        failIssueAfterWrite = false;
        Result recovered = service.create(42, null, Kind.FULL);
        assertThat(recovered.status()).isEqualTo("COMPLETED");
        assertThat(recovered.fopId()).isEqualTo("test");
        assertThat(recovered.operationId()).isEqualTo(pending.operationId());
        assertThat(RECEIPTS).hasSize(1);
    }
    @Test void apiSupportsNoBodyAndEmptyObjectForAutomaticRouting() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/leads/42/receipts/full"))
                .header("X-API-Key", "local-test-key").POST(HttpRequest.BodyPublishers.noBody());
        HttpResponse<String> first = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(first.body()).path("fopId").asText()).isEqualTo("test");
        request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"));
        assertThat(client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        assertThat(ISSUE_CALLS).hasValue(1);
    }
    @Test void prepaymentAndPostpaymentProduceTwoLinkedReceipts() {
        Result advance = service.create(42, "test", Kind.PREPAYMENT);
        assertThat(advance.amount()).isEqualByComparingTo("150");
        assertThat(LINKS.get(ADVANCE_LINK_FIELD)).isEqualTo(advance.receiptUrl());
        Result post = service.create(42, "test", Kind.POSTPAYMENT);
        assertThat(post.status()).isEqualTo("COMPLETED");
        assertThat(post.amount()).isEqualByComparingTo("1590");
        assertThat(ISSUED.get(1).path("post_payment_of_check_fn").asText()).isEqualTo(advance.fiscalNumber());
        assertThat(ISSUED.get(1).at("/fiscal/receipt/rows/0/disc").decimalValue()).isEqualByComparingTo("150");
        assertThat(NOTES).hasSize(2);
    }
    @Test void failedKommoWriteResumesAfterServiceRestartWithoutIssuingAgain() {
        failPatch = true;
        Result pending = service.create(42, "test", Kind.FULL);
        assertThat(pending.status()).isEqualTo("SYNC_PENDING");
        assertThat(pending.receiptUrl()).isNotBlank();
        failPatch = false;
        ReceiptService restarted = new ReceiptService(settings, kommo, vchasno, builder, store, mapper);
        assertThat(restarted.retry(pending.operationId()).status()).isEqualTo("COMPLETED");
        assertThat(ISSUE_CALLS).hasValue(1);
        assertThat(NOTES).hasSize(1);
    }
    @Test void noteAcceptedBeforeErrorIsNotDuplicatedOnRetry() {
        failNoteAfterWrite = true;
        Result pending = service.create(42, "test", Kind.FULL);
        assertThat(pending.status()).isEqualTo("SYNC_PENDING");
        failNoteAfterWrite = false;
        assertThat(service.retry(pending.operationId()).status()).isEqualTo("COMPLETED");
        assertThat(NOTES).hasSize(1);
        assertThat(ISSUE_CALLS).hasValue(1);
    }
    @Test void ambiguousFiscalResultReusesExactPayloadEvenIfLeadChanged() {
        failIssueAfterWrite = true;
        Result pending = service.create(42, "test", Kind.FULL);
        assertThat(pending.status()).isEqualTo("FISCAL_PENDING");
        assertThat(pending.fiscalNumber()).isNull();
        productPrice = "999"; failIssueAfterWrite = false;
        assertThat(service.retry(pending.operationId()).status()).isEqualTo("COMPLETED");
        assertThat(ISSUE_CALLS).hasValue(2);
        assertThat(RECEIPTS).hasSize(1);
        assertThat(ISSUED.get(0)).isEqualTo(ISSUED.get(1));
    }
    @Test void tokenChangedToDifferentRegisterCannotDuplicateAnUncertainReceipt() {
        failIssueAfterWrite = true;
        Result pending = service.create(42, "test", Kind.FULL);
        failIssueAfterWrite = false; registerId = "99990002";
        assertThat(service.retry(pending.operationId()).status()).isEqualTo("ACTION_REQUIRED");
        assertThat(ISSUE_CALLS).hasValue(1);
    }
    @Test void fiscalBusinessErrorInsideHttp200IsNotTreatedAsReceipt() {
        rejectFiscal = true;
        Result result = service.create(42, "test", Kind.FULL);
        assertThat(result.status()).isEqualTo("ACTION_REQUIRED");
        assertThat(result.fiscalNumber()).isNull();
        assertThat(LINKS).isEmpty(); assertThat(NOTES).isEmpty();
    }
    @Test void invalidBudgetAndMissingAdvanceNeverReachFiscalIssue() {
        productPrice = "871";
        assertThatThrownBy(() -> service.create(42, "test", Kind.FULL)).isInstanceOf(Failure.class);
        productPrice = "870";
        assertThatThrownBy(() -> service.create(42, "test", Kind.POSTPAYMENT)).isInstanceOf(Failure.class);
        assertThat(ISSUE_CALLS).hasValue(0);
    }
    @Test void codMismatchAndDifferentRegisterStopPostpayment() {
        service.create(42, "test", Kind.PREPAYMENT);
        cod = "1591";
        assertThatThrownBy(() -> service.create(42, "test", Kind.POSTPAYMENT)).isInstanceOf(Failure.class).hasMessageContaining("Наложенный");
        cod = "1590"; registerId = "99990002";
        assertThatThrownBy(() -> service.create(42, "test", Kind.POSTPAYMENT)).isInstanceOf(Failure.class).hasMessageContaining("кассу предоплаты");
        assertThat(ISSUE_CALLS).hasValue(1);
    }
    @Test void externalAdvanceIsReadAndValidatedThroughCashApi() {
        LINKS.put(ADVANCE_LINK_FIELD, "https://kasa.vchasno.ua/c/external-advance");
        Result result = service.create(42, "test", Kind.POSTPAYMENT);
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(ISSUED.getFirst().path("post_payment_of_check_fn").asText()).isEqualTo("external-advance");
    }
    @Test void concurrentDuplicateRequestsIssueOnce() throws Exception {
        try (ExecutorService workers = Executors.newFixedThreadPool(4)) {
            List<Future<Result>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) results.add(workers.submit(() -> service.create(42, "test", Kind.FULL)));
            Set<String> ids = new HashSet<>();
            for (Future<Result> result : results) ids.add(result.get(15, TimeUnit.SECONDS).operationId());
            assertThat(ids).hasSize(1);
            assertThat(ISSUE_CALLS).hasValue(1);
        }
    }
    @Test void apiRequiresKeyAndReturnsValidationErrors() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String url = "http://localhost:" + port + "/api/v1/leads/42/receipts/full";
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"fopId\":\"test\"}"));
        assertThat(client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(client.send(request.header("X-API-Key", "local-test-key").build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        request.POST(HttpRequest.BodyPublishers.ofString("{\"fopId\":\"\"}"));
        assertThat(client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
    }

    static void handle(HttpExchange x) throws IOException {
        String path = x.getRequestURI().getPath(), method = x.getRequestMethod();
        String token = x.getRequestHeaders().getFirst("Authorization");
        String text = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode body = text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text);
        if (path.startsWith("/api/v4/") && !"Bearer kommo-test-token".equals(token)) { reply(x, 401, Map.of()); return; }
        if (path.startsWith("/api/v3/") && !"cash-test-token".equals(token) && !"cash-other-token".equals(token)) { reply(x, 401, Map.of()); return; }
        if (path.equals("/api/v4/leads/42") && method.equals("GET")) {
            List<Object> fields = new ArrayList<>();
            fields.add(Map.of("field_id", COD_FIELD, "values", List.of(Map.of("value", cod))));
            if (fopValues != null) fields.add(Map.of("field_id", 2110073, "values", fopValues));
            LINKS.forEach((id, link) -> fields.add(Map.of("field_id", id, "values", List.of(Map.of("value", link)))));
            reply(x, 200, Map.of("id", 42, "price", 1740, "custom_fields_values", fields,
                    "_embedded", Map.of("catalog_elements", List.of(Map.of("id", 11, "catalog_id", 7, "quantity", 2),
                            Map.of("id", 99, "catalog_id", 999, "quantity", 1)))));
        } else if (path.equals("/api/v4/catalogs/7/elements/11")) {
            reply(x, 200, Map.of("id", 11, "name", "Лосьон", "custom_fields_values",
                    List.of(Map.of("field_code", "PRICE", "values", List.of(Map.of("value", productPrice))))));
        } else if (path.equals("/api/v4/leads") && method.equals("PATCH")) {
            if (failPatch) { reply(x, 500, Map.of()); return; }
            JsonNode field = body.path(0).path("custom_fields_values").path(0);
            LINKS.put(field.path("field_id").asLong(), field.path("values").path(0).path("value").asText());
            reply(x, 200, Map.of("_embedded", Map.of("leads", List.of(Map.of("id", 42)))));
        } else if (path.equals("/api/v4/leads/42/notes")) {
            reply(x, 200, Map.of("_embedded", Map.of("notes", NOTES.stream().map(n -> Map.of("params", Map.of("text", n))).toList())));
        } else if (path.equals("/api/v4/leads/notes") && method.equals("POST")) {
            NOTES.add(body.path(0).path("params").path("text").asText());
            reply(x, failNoteAfterWrite ? 500 : 200, Map.of("_embedded", Map.of("notes", List.of(Map.of("id", 1)))));
        } else if (path.equals("/api/v3/fiscal/execute")) {
            if (body.at("/fiscal/task").asInt() == 18) {
                reply(x, 200, Map.of("res", 0, "res_action", 0, "info", Map.of("fisid", registerId, "isFis", 0))); return;
            }
            ISSUE_CALLS.incrementAndGet(); ISSUED.add(body);
            if (rejectFiscal) { reply(x, 200, Map.of("res", 1001, "res_action", 3)); return; }
            String tag = body.path("tag").asText();
            JsonNode receipt = RECEIPTS.computeIfAbsent(tag, key -> JSON.valueToTree(Map.of("res", 0, "res_action", 0,
                    "info", Map.of("fisid", receiptRegisterOverride == null ? registerId : receiptRegisterOverride,
                            "doccode", receiptNumberOverride == null ? "TEST_" + tag : receiptNumberOverride,
                            "qr", qrOverride == null ? "https://kasa.vchasno.ua/check-viewer/TEST_" + tag : qrOverride))));
            reply(x, failIssueAfterWrite ? 500 : 200, receipt);
        } else if (path.startsWith("/api/v3/check-task/TEST_")) {
            String number = path.substring(path.lastIndexOf('/') + 1);
            ObjectNode task = (ObjectNode) ISSUED.getFirst().deepCopy();
            task.put("device", registerId);
            task.put("tag", number.substring("TEST_".length()));
            reply(x, 200, Map.of("fiscal_number", number, "task", task));
        } else if (path.equals("/api/v3/check-task/external-advance")) {
            reply(x, 200, Map.of("fiscal_number", "external-advance", "task", Map.of("device", registerId,
                    "fiscal", Map.of("task", 1, "subtask", 1, "receipt", Map.of("sum", 150)))));
        } else { reply(x, 404, Map.of("path", path)); }
    }
    static void reply(HttpExchange x, int status, Object body) throws IOException {
        byte[] data = JSON.writeValueAsBytes(body);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(status, data.length);
        try (var out = x.getResponseBody()) { out.write(data); }
    }
}
