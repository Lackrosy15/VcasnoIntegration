package ua.vcasno.integration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import tools.jackson.databind.JsonNode;

public final class Domain {
    private Domain() {}
    public static final BigDecimal ADVANCE = new BigDecimal("150.00");
    public static final long COD_FIELD = 2109361, ADVANCE_LINK_FIELD = 2112654, FINAL_LINK_FIELD = 2112656;
    public enum Kind {
        FULL("Повна оплата"), PREPAYMENT("Передплата"), POSTPAYMENT("Післяплата");
        public final String label;
        Kind(String label) { this.label = label; }
        public long linkField() { return this == PREPAYMENT ? ADVANCE_LINK_FIELD : FINAL_LINK_FIELD; }
    }
    public record Product(String code, String name, BigDecimal quantity, BigDecimal price) {
        public BigDecimal cost() { return price.multiply(quantity).setScale(2, RoundingMode.HALF_UP); }
    }
    public record Lead(long id, BigDecimal budget, String cod, String advanceUrl, String finalUrl, List<Product> products) {}
    public record Receipt(String fiscalNumber, String url) {}
    public record Register(String fiscalId, boolean test) {}
    public record Operation(String id, String account, long leadId, Kind kind, String fop, String registerId,
                            String tag, BigDecimal amount, String payload, String prepaymentRef, String status,
                            String fiscalNumber, String receiptUrl, boolean fieldSynced, boolean noteSynced, String lastError) {}
    public record Result(String operationId, long leadId, Kind type, String fopId, BigDecimal amount,
                         String status, String fiscalNumber, String receiptUrl, String error) {
        public static Result of(Operation o) {
            return new Result(o.id(), o.leadId(), o.kind(), o.fop(), o.amount(), o.status(), o.fiscalNumber(), o.receiptUrl(), o.lastError());
        }
    }
    public static BigDecimal decimal(String value, String label, int scale) {
        try {
            if (value == null || value.isBlank()) throw new NumberFormatException();
            return new BigDecimal(value.trim().replace("\u00a0", "").replace(" ", "").replace(',', '.'))
                    .setScale(scale, RoundingMode.UNNECESSARY);
        } catch (NumberFormatException | ArithmeticException e) {
            throw Failure.invalid("Некорректное значение: " + label);
        }
    }
    public static String field(JsonNode entity, long id, String code) {
        for (JsonNode field : entity.path("custom_fields_values")) {
            if ((id > 0 && field.path("field_id").asLong() == id)
                    || (id == 0 && code != null && code.equals(field.path("field_code").asText()))) {
                JsonNode values = field.path("values");
                if (values.size() != 1 || values.path(0).path("value").isNull())
                    throw Failure.invalid("Поле " + (id > 0 ? id : code) + " должно содержать одно значение");
                return values.path(0).path("value").asText("");
            }
        }
        return "";
    }
}
