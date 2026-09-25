package ua.vcasno.integration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "integration.webhook-worker-enabled", havingValue = "true", matchIfMissing = true)
public class WebhookWorker {
    private final WebhookQueue queue;
    private final Settings settings;
    public WebhookWorker(WebhookQueue queue, Settings settings) { this.queue = queue; this.settings = settings; }
    @EventListener(ApplicationReadyEvent.class) void recover() {
        if (configured()) queue.recoverInterrupted();
    }
    private boolean configured() {
        return settings.kommo().baseUrl() != null && !settings.kommo().baseUrl().isBlank();
    }
    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public void run() { if (configured()) queue.processNext(); }
}
