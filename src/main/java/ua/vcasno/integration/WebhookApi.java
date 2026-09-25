package ua.vcasno.integration;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import static ua.vcasno.integration.Domain.*;

@RestController
public class WebhookApi {
    private final WebhookQueue queue;
    private final WebhookLeadId leadIds;
    public WebhookApi(WebhookQueue queue, WebhookLeadId leadIds) { this.queue = queue; this.leadIds = leadIds; }
    @RequestMapping(value = "/webhooks/kommo/{type:full|prepayment|postpayment}", method = {RequestMethod.GET, RequestMethod.POST})
    public WebhookQueue.Job receive(@PathVariable String type, HttpServletRequest request) {
        long lead = leadIds.read(request);
        Kind kind = switch (type) {
            case "full" -> Kind.FULL;
            case "prepayment" -> Kind.PREPAYMENT;
            default -> Kind.POSTPAYMENT;
        };
        // 200 acknowledges durable receipt, not completion of fiscalization.
        return queue.accept(lead, kind);
    }
    @GetMapping("/api/v1/webhook-jobs/{id}")
    public WebhookQueue.Job status(@PathVariable String id) { return queue.get(id); }
    @PostMapping("/api/v1/webhook-jobs/{id}/retry")
    public WebhookQueue.Job retry(@PathVariable String id) { return queue.retry(id); }
}
