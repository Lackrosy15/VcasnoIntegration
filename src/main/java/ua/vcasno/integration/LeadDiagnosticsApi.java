package ua.vcasno.integration;

import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
public class LeadDiagnosticsApi {
    private final WebhookQueue queue;
    private final ReceiptService receipts;
    public LeadDiagnosticsApi(WebhookQueue queue, ReceiptService receipts) {
        this.queue = queue; this.receipts = receipts;
    }
    public record Diagnostics(long leadId, List<WebhookQueue.Job> webhookJobs, List<Domain.Result> operations) {}
    @GetMapping("/api/v1/leads/{leadId}/receipts/status")
    public Diagnostics status(@PathVariable long leadId) {
        if (leadId <= 0) throw Failure.invalid("leadId должен быть положительным");
        return new Diagnostics(leadId, queue.forLead(leadId), receipts.forLead(leadId));
    }
}
