package ua.vcasno.integration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import static ua.vcasno.integration.Domain.*;

@Repository
public class OperationStore {
    private final JdbcTemplate db;
    private final RowMapper<Operation> mapper = (rs, row) -> new Operation(rs.getString("id"), rs.getString("account"),
            rs.getLong("lead_id"), Kind.valueOf(rs.getString("kind")), rs.getString("fop"), rs.getString("register_id"),
            rs.getString("tag"), rs.getBigDecimal("amount"), rs.getString("payload"), rs.getString("prepayment_ref"),
            rs.getString("status"), rs.getString("fiscal_number"), rs.getString("receipt_url"),
            rs.getBoolean("field_synced"), rs.getBoolean("note_synced"), rs.getString("last_error"));
    public OperationStore(JdbcTemplate db) { this.db = db; }
    public java.util.List<Result> forLead(String account, long leadId) {
        return db.query("SELECT * FROM receipt_operation WHERE account=? AND lead_id=? ORDER BY created_at", mapper,
                account, leadId).stream().map(Result::of).toList();
    }
    public Optional<Operation> find(String account, long leadId, Kind kind) {
        return db.query("SELECT * FROM receipt_operation WHERE account=? AND lead_id=? AND kind=?", mapper,
                account, leadId, kind.name()).stream().findFirst();
    }
    public Operation get(String account, String id) {
        return db.query("SELECT * FROM receipt_operation WHERE account=? AND id=?", mapper, account, id).stream().findFirst()
                .orElseThrow(() -> new Failure(404, "OPERATION_NOT_FOUND", "Операция не найдена"));
    }
    // Each call commits independently. Never wrap the fiscal HTTP request in a DB transaction:
    // a rollback must not erase the request identity after an external side effect.
    public void create(Operation o) {
        try {
            db.update("""
                INSERT INTO receipt_operation(id,account,lead_id,kind,fop,register_id,tag,amount,payload,prepayment_ref,status)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, o.id(), o.account(), o.leadId(), o.kind().name(), o.fop(), o.registerId(), o.tag(), o.amount(),
                    o.payload(), o.prepaymentRef(), o.status());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw Failure.conflict("Операция уже существует или чек предоплаты уже использован для послеоплаты");
        }
    }
    public void fiscalized(Operation o, Receipt receipt) {
        db.update("UPDATE receipt_operation SET fiscal_number=?,receipt_url=?,status='SYNC_PENDING',last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                receipt.fiscalNumber(), receipt.url(), o.id());
    }
    public void fieldSynced(String id) {
        db.update("UPDATE receipt_operation SET field_synced=TRUE,updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
    }
    public void noteSynced(String id) {
        db.update("UPDATE receipt_operation SET note_synced=TRUE,updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
    }
    public void completed(String id) {
        db.update("UPDATE receipt_operation SET status='COMPLETED',last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
    }
    public void failed(String id, String status, Failure failure) {
        db.update("UPDATE receipt_operation SET status=?,last_error=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", status,
                failure.code + ": " + failure.getMessage(), id);
    }
}
