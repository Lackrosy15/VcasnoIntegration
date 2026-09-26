package ua.vcasno.integration;

import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.UUID;
import static ua.vcasno.integration.Domain.*;

@Service
public class ReceiptService {
    private final Settings settings;
    private final KommoClient kommo;
    private final VchasnoClient vchasno;
    private final ReceiptBuilder builder;
    private final OperationStore store;
    private final ObjectMapper json;
    private final Object[] locks = new Object[256];

    public ReceiptService(Settings settings, KommoClient kommo, VchasnoClient vchasno,
                          ReceiptBuilder builder, OperationStore store, ObjectMapper json) {
        this.settings = settings; this.kommo = kommo; this.vchasno = vchasno;
        this.builder = builder; this.store = store; this.json = json;
        Arrays.setAll(locks, i -> new Object());
    }
    public Result create(long leadId, String fop, Kind kind) {
        if (leadId <= 0) throw Failure.invalid("leadId должен быть положительным");
        synchronized (locks[Math.floorMod(Long.hashCode(leadId), locks.length)]) {
            String account = kommo.account();
            Operation existing = store.find(account, leadId, kind).orElse(null);
            if (existing != null) {
                if (fop != null && !existing.fop().equals(fop)) throw Failure.conflict("Для этой сделки и типа чека уже выбрана другая касса");
                return process(existing);
            }
            checkOtherOperations(account, leadId, kind);
            if (fop == null) fop = kommo.resolveFop(leadId);
            Settings.CashRegister cashRegister = settings.register(fop);
            Lead lead = kommo.lead(leadId);
            if (!lead.finalUrl().isBlank()) throw Failure.conflict("В сделке уже указан итоговый чек");
            if (kind != Kind.POSTPAYMENT && !lead.advanceUrl().isBlank())
                throw Failure.conflict("В сделке уже указан чек предоплаты; используйте послеоплату");
            BigDecimal amount = builder.amount(lead, kind);
            Register register = vchasno.register(cashRegister);
            String prepaymentNumber = null;
            if (kind == Kind.POSTPAYMENT) {
                Operation advance = store.find(account, leadId, Kind.PREPAYMENT).orElse(null);
                if (advance != null) {
                    if (advance.fiscalNumber() == null) throw Failure.conflict("Сначала завершите операцию предоплаты");
                    if (!advance.registerId().equals(register.fiscalId())) throw Failure.conflict("Послеоплату нужно оформить на кассу предоплаты");
                    if (!lead.advanceUrl().isBlank() && !advance.fiscalNumber().equals(VchasnoClient.numberFromUrl(lead.advanceUrl())))
                        throw Failure.conflict("Чек предоплаты в сделке не совпадает с журналом");
                    prepaymentNumber = advance.fiscalNumber();
                } else {
                    if (lead.advanceUrl().isBlank()) throw Failure.invalid("В поле 2112654 отсутствует чек предоплаты");
                    prepaymentNumber = vchasno.validateAdvance(cashRegister, lead.advanceUrl(), register.fiscalId());
                }
            }
            String id = UUID.randomUUID().toString(), tag = "vcasno-" + id;
            String payload = json.writeValueAsString(builder.build(lead, kind, cashRegister, tag, prepaymentNumber));
            Operation operation = new Operation(id, account, leadId, kind, fop, register.fiscalId(), tag, amount, payload,
                    prepaymentNumber == null ? null : register.fiscalId() + ":" + prepaymentNumber,
                    "FISCAL_PENDING", null, null, false, false, null);
            store.create(operation);
            return process(operation);
        }
    }
    private void checkOtherOperations(String account, long lead, Kind kind) {
        if (kind == Kind.FULL) {
            if (store.find(account, lead, Kind.PREPAYMENT).isPresent() || store.find(account, lead, Kind.POSTPAYMENT).isPresent())
                throw Failure.conflict("Для сделки уже начат сценарий предоплаты/послеоплаты");
        } else if (store.find(account, lead, Kind.FULL).isPresent()) {
            throw Failure.conflict("Для сделки уже начата полная оплата");
        }
        if (kind == Kind.PREPAYMENT && store.find(account, lead, Kind.POSTPAYMENT).isPresent())
            throw Failure.conflict("Для сделки уже начата послеоплата");
    }
    public Result get(String id) { return Result.of(store.get(kommo.account(), id)); }
    public java.util.List<Result> forLead(long leadId) { return store.forLead(kommo.account(), leadId); }
    public Result retry(String id) {
        Operation operation = store.get(kommo.account(), id);
        synchronized (locks[Math.floorMod(Long.hashCode(operation.leadId()), locks.length)]) {
            return process(store.get(kommo.account(), id));
        }
    }
    private Result process(Operation operation) {
        if ("COMPLETED".equals(operation.status())) return Result.of(operation);
        if (operation.fiscalNumber() == null) {
            try {
                Settings.CashRegister register = settings.register(operation.fop());
                // Pin the actual PRRO identity, including across token rotation and process restart.
                if (!vchasno.register(register).fiscalId().equals(operation.registerId()))
                    throw Failure.conflict("Токен теперь относится к другой кассе; повторный выпуск заблокирован");
                store.fiscalized(operation, vchasno.issue(register, operation));
            } catch (Failure failure) {
                String status = failure.code.equals("VCHASNO_ACTION_REQUIRED") || failure.status == 409 || failure.status == 422
                        ? "ACTION_REQUIRED" : "FISCAL_PENDING";
                store.failed(operation.id(), status, failure);
                return Result.of(store.get(operation.account(), operation.id()));
            }
            operation = store.get(operation.account(), operation.id());
        }
        try {
            if (!operation.fieldSynced()) {
                kommo.writeLink(operation.leadId(), operation.kind().linkField(), operation.receiptUrl());
                store.fieldSynced(operation.id());
            }
            if (!operation.noteSynced()) {
                kommo.ensureNote(operation);
                store.noteSynced(operation.id());
            }
            store.completed(operation.id());
        } catch (Failure failure) {
            store.failed(operation.id(), "SYNC_PENDING", failure);
        }
        return Result.of(store.get(operation.account(), operation.id()));
    }
}
