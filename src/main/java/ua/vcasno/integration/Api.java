package ua.vcasno.integration;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import static ua.vcasno.integration.Domain.*;
import java.util.Map;

@RestController
public class Api {
    private final ReceiptService receipts;
    public Api(ReceiptService receipts) { this.receipts = receipts; }
    public record Request(@Pattern(regexp = "[a-zA-Z0-9_-]{1,64}") String fopId) {}
    @GetMapping("/health") public Map<String, String> health() { return Map.of("status", "UP"); }
    @PostMapping("/api/v1/leads/{leadId}/receipts/full")
    public ResponseEntity<Result> full(@PathVariable long leadId, @Valid @RequestBody(required = false) Request request) {
        return response(receipts.create(leadId, request == null ? null : request.fopId(), Kind.FULL));
    }
    @PostMapping("/api/v1/leads/{leadId}/receipts/prepayment")
    public ResponseEntity<Result> prepayment(@PathVariable long leadId, @Valid @RequestBody(required = false) Request request) {
        return response(receipts.create(leadId, request == null ? null : request.fopId(), Kind.PREPAYMENT));
    }
    @PostMapping("/api/v1/leads/{leadId}/receipts/postpayment")
    public ResponseEntity<Result> postpayment(@PathVariable long leadId, @Valid @RequestBody(required = false) Request request) {
        return response(receipts.create(leadId, request == null ? null : request.fopId(), Kind.POSTPAYMENT));
    }
    @GetMapping("/api/v1/operations/{id}")
    public Result status(@PathVariable String id) { return receipts.get(id); }
    @PostMapping("/api/v1/operations/{id}/retry")
    public ResponseEntity<Result> retry(@PathVariable String id) { return response(receipts.retry(id)); }
    private ResponseEntity<Result> response(Result result) {
        return ResponseEntity.status(switch (result.status()) {
            case "COMPLETED" -> 200;
            case "ACTION_REQUIRED" -> 409;
            default -> 202;
        }).body(result);
    }
}
