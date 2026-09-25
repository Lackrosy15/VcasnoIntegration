package ua.vcasno.integration;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import static ua.vcasno.integration.Domain.*;

@RestController
public class WebhookApi {
    private final WebhookQueue queue;
    public WebhookApi(WebhookQueue queue) { this.queue = queue; }
    @RequestMapping(value = "/webhooks/kommo/{type:full|prepayment|postpayment}", method = {RequestMethod.GET, RequestMethod.POST})
    public WebhookQueue.Job receive(@PathVariable String type, HttpServletRequest request) {
        String[] ids = request.getParameterValues("leadId");
        if (ids == null || ids.length != 1 || !ids[0].matches("[1-9][0-9]{0,18}"))
            throw new Failure(400, "INVALID_LEAD_ID", "Передайте один положительный leadId в URL");
        long lead;
        try { lead = Long.parseLong(ids[0]); }
        catch (NumberFormatException e) { throw new Failure(400, "INVALID_LEAD_ID", "Некорректный leadId"); }
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
