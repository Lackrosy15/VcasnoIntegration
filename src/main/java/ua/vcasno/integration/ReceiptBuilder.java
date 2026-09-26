package ua.vcasno.integration;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import static ua.vcasno.integration.Domain.*;

@Component
public class ReceiptBuilder {
    public BigDecimal amount(Lead lead, Kind kind) {
        BigDecimal total = lead.products().stream().map(Product::cost).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(lead.budget()) != 0)
            throw Failure.invalid("Сумма товаров " + total + " грн не равна бюджету " + lead.budget() + " грн");
        if (lead.products().isEmpty() || lead.products().stream().anyMatch(p -> p.cost().signum() <= 0))
            throw Failure.invalid("Товарные строки должны иметь положительную сумму");
        BigDecimal advance = kind == Kind.FULL ? BigDecimal.ZERO : advanceAmount(lead);
        BigDecimal amount = switch (kind) {
            case FULL -> lead.budget();
            case PREPAYMENT -> advance;
            case POSTPAYMENT -> lead.budget().subtract(advance);
        };
        if (amount.signum() <= 0) throw Failure.invalid("Сумма чека должна быть больше нуля");
        return amount;
    }
    public BigDecimal advanceAmount(Lead lead) {
        BigDecimal advance = lead.cod() == null || lead.cod().isBlank() ? ADVANCE
                : lead.budget().subtract(decimal(lead.cod(), "наложенный платёж (2109361)", 2));
        if (advance.signum() <= 0 || advance.compareTo(lead.budget()) > 0)
            throw Failure.invalid("Предоплата должна быть больше нуля и не превышать бюджет; проверьте поле 2109361");
        return advance;
    }
    public Map<String, Object> build(Lead lead, Kind kind, Settings.CashRegister register, String tag, String advanceNumber) {
        BigDecimal amount = amount(lead, kind);
        Map<String, Object> receipt = new LinkedHashMap<>();
        List<Map<String, Object>> goods = new ArrayList<>();
        for (Product p : lead.products()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", p.code()); row.put("name", p.name()); row.put("cnt", p.quantity());
            row.put("price", p.price()); row.put("cost", p.cost()); row.put("disc", BigDecimal.ZERO);
            row.put("taxgrp", register.taxGroup()); goods.add(row);
        }
        String description = String.join("; ", lead.products().stream()
                .map(p -> p.name() + " × " + p.quantity().stripTrailingZeros().toPlainString() + ", " + p.cost() + " грн").toList());
        if (kind == Kind.PREPAYMENT) {
            receipt.put("rows", List.of(Map.of("name", "Передплата", "cnt", 0, "price", amount,
                    "disc", 0, "taxgrp", register.taxGroup())));
            receipt.put("rows_pre_payment", goods);
            receipt.put("comment_down", "Передплата по товарах: " + description);
        } else {
            if (kind == Kind.POSTPAYMENT) {
                if (advanceNumber == null || advanceNumber.isBlank()) throw Failure.invalid("Отсутствует чек предоплаты");
                List<BigDecimal> deductions = allocateAdvance(lead.products(), lead.budget(), lead.budget().subtract(amount));
                for (int i = 0; i < goods.size(); i++) {
                    goods.get(i).put("disc", deductions.get(i));
                    goods.get(i).put("disc_apply_type", 1);
                }
                receipt.put("comment_down", "Післяплата по товарах: " + description + ". Чек передплати: " + advanceNumber);
            }
            receipt.put("rows", goods);
        }
        receipt.put("sum", amount); receipt.put("round", 0);
        receipt.put("pays", List.of(Map.of("type", register.paymentType(), "sum", amount, "change", 0)));
        Map<String, Object> fiscal = new LinkedHashMap<>();
        fiscal.put("task", 1); fiscal.put("receipt", receipt);
        if (kind != Kind.FULL) fiscal.put("subtask", kind == Kind.PREPAYMENT ? 1 : 2);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "VcasnoIntegration"); payload.put("tag", tag); payload.put("fiscal", fiscal);
        if (kind == Kind.POSTPAYMENT) payload.put("post_payment_of_check_fn", advanceNumber);
        return payload;
    }
    // Allocate exact kopecks proportionally, retaining at least one kopeck on every row.
    static List<BigDecimal> allocateAdvance(List<Product> products, BigDecimal total, BigDecimal advance) {
        long remaining = advance.movePointRight(2).longValueExact();
        long[] allocations = new long[products.size()];
        long[] capacities = new long[products.size()];
        for (int i = 0; i < products.size(); i++) {
            capacities[i] = products.get(i).cost().movePointRight(2).longValueExact() - 1;
            long share = advance.multiply(products.get(i).cost()).divide(total, 2, RoundingMode.DOWN).movePointRight(2).longValueExact();
            allocations[i] = Math.min(share, capacities[i]); remaining -= allocations[i];
        }
        while (remaining > 0) {
            boolean changed = false;
            for (int i = 0; i < allocations.length && remaining > 0; i++) {
                if (allocations[i] < capacities[i]) { allocations[i]++; remaining--; changed = true; }
            }
            if (!changed) throw Failure.invalid("Недостаточно суммы для распределения предоплаты по товарным строкам");
        }
        List<BigDecimal> result = new ArrayList<>();
        for (long value : allocations) result.add(BigDecimal.valueOf(value, 2));
        return result;
    }
}
