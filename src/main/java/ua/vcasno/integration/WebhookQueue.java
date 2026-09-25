package ua.vcasno.integration;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.dao.DuplicateKeyException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import static ua.vcasno.integration.Domain.*;

@Service
public class WebhookQueue {
    public record Job(String id, long leadId, Kind type, String fopId, String status, int attempts, String operationId, String error) {}
    private final JdbcTemplate db;
    private final KommoClient kommo;
    private final Settings settings;
    private final ReceiptService receipts;
    private final RowMapper<Job> mapper = (rs, n) -> new Job(rs.getString("id"), rs.getLong("lead_id"),
            Kind.valueOf(rs.getString("kind")), rs.getString("fop"), rs.getString("status"), rs.getInt("attempts"),
            rs.getString("operation_id"), rs.getString("last_error"));

    public WebhookQueue(JdbcTemplate db, KommoClient kommo, Settings settings, ReceiptService receipts) {
        this.db = db; this.kommo = kommo; this.settings = settings; this.receipts = receipts;
    }
    public Job accept(long leadId, Kind kind) {
        if (leadId <= 0) throw Failure.invalid("leadId должен быть положительным");
        String account = kommo.account(); // configuration only, no external HTTP calls on webhook thread
        String fop = settings.webhookFopId();
        if (fop != null && fop.isBlank()) fop = null;
        try {
            db.update("INSERT INTO webhook_job(id,account,lead_id,kind,fop,status) VALUES(?,?,?,?,?,'QUEUED')",
                    UUID.randomUUID().toString(), account, leadId, kind.name(), fop);
        } catch (DuplicateKeyException ignored) {
            // Delivery retries must not reset the attempt budget or re-arm failed jobs.
        }
        return db.query("SELECT * FROM webhook_job WHERE account=? AND lead_id=? AND kind=?", mapper,
                account, leadId, kind.name()).getFirst();
    }
    public Job get(String id) {
        return db.query("SELECT * FROM webhook_job WHERE account=? AND id=?", mapper, kommo.account(), id).stream()
                .findFirst().orElseThrow(() -> new Failure(404, "JOB_NOT_FOUND", "Вебхук не найден"));
    }
    public Job retry(String id) {
        Job job = get(id);
        if (job.status().equals("ACTION_REQUIRED"))
            db.update("UPDATE webhook_job SET status='QUEUED',attempts=0,last_error=NULL,next_attempt_at=CURRENT_TIMESTAMP WHERE id=? AND status='ACTION_REQUIRED'", id);
        return get(id);
    }
    public void recoverInterrupted() {
        db.update("UPDATE webhook_job SET status='RETRY',next_attempt_at=CURRENT_TIMESTAMP WHERE account=? AND status='RUNNING'", kommo.account());
    }
    public synchronized void processNext() {
        var jobs = db.query("""
                SELECT * FROM webhook_job WHERE account=? AND status IN ('QUEUED','RETRY')
                AND next_attempt_at<=CURRENT_TIMESTAMP ORDER BY created_at,id LIMIT 1
                """, mapper, kommo.account());
        if (jobs.isEmpty()) return;
        Job job = jobs.getFirst();
        if (db.update("UPDATE webhook_job SET status='RUNNING',attempts=attempts+1 WHERE id=? AND status IN ('QUEUED','RETRY')", job.id()) != 1) return;
        try {
            Result result = receipts.create(job.leadId(), job.fopId(), job.type());
            String status = switch (result.status()) {
                case "COMPLETED" -> "COMPLETED";
                case "ACTION_REQUIRED" -> "ACTION_REQUIRED";
                default -> "RETRY";
            };
            finish(job, status, result.operationId(), result.error());
        } catch (Failure error) {
            finish(job, error.status >= 500 ? "RETRY" : "ACTION_REQUIRED", null, error.code + ": " + error.getMessage());
        } catch (RuntimeException error) {
            finish(job, "RETRY", null, "INTERNAL_ERROR: повтор исходной операции");
        }
    }
    private void finish(Job job, String status, String operation, String error) {
        int attempts = job.attempts() + 1;
        if (status.equals("RETRY") && attempts >= 10) {
            status = "ACTION_REQUIRED";
            error = "RETRY_LIMIT: " + (error == null ? "Требуется проверка операции" : error);
        }
        long delay = Math.min(60, 5L << Math.min(attempts - 1, 4));
        db.update("UPDATE webhook_job SET status=?,operation_id=COALESCE(?,operation_id),last_error=?,next_attempt_at=? WHERE id=?",
                status, operation, error == null ? null : error.substring(0, Math.min(error.length(), 500)),
                Timestamp.from(Instant.now().plusSeconds(delay)), job.id());
    }
}
