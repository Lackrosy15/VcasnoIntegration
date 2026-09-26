package ua.vcasno.integration;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static ua.vcasno.integration.Domain.*;

class ReceiptBuilderTest {
    private final ReceiptBuilder builder = new ReceiptBuilder();
    private final Settings.CashRegister register = new Settings.CashRegister("test-token", 2, 1);
    private final JsonMapper json = JsonMapper.builder().build();
    private Product product(String name, String qty, String price) {
        return new Product(name, name, new BigDecimal(qty), new BigDecimal(price));
    }
    private Lead lead(String budget, String cod, Product... products) {
        return new Lead(1, new BigDecimal(budget), cod, "", "", List.of(products));
    }
    @Test void fullReceiptMatchesExample() {
        JsonNode payload = json.valueToTree(builder.build(lead("1240.00", "", product("Наволочка", "1", "1240.00")), Kind.FULL, register, "tag", null));
        assertThat(payload.at("/fiscal/receipt/sum").decimalValue()).isEqualByComparingTo("1240");
        assertThat(payload.at("/fiscal/receipt/rows/0/name").asText()).isEqualTo("Наволочка");
        assertThat(payload.at("/fiscal/receipt/pays/0/type").asInt()).isEqualTo(1);
        assertThat(payload.at("/fiscal/subtask").isMissingNode()).isTrue();
    }
    @Test void prepaymentUsesDocumentedZeroCountAndProductDetails() {
        JsonNode payload = json.valueToTree(builder.build(lead("4700", "4550", product("Набір", "1", "4700")), Kind.PREPAYMENT, register, "tag", null));
        assertThat(payload.at("/fiscal/subtask").asInt()).isEqualTo(1);
        assertThat(payload.at("/fiscal/receipt/rows/0/cnt").asInt()).isZero();
        assertThat(payload.at("/fiscal/receipt/rows/0/price").decimalValue()).isEqualByComparingTo("150");
        assertThat(payload.at("/fiscal/receipt/rows_pre_payment/0/name").asText()).isEqualTo("Набір");
        assertThat(payload.at("/fiscal/receipt/comment_down").asText()).contains("Набір", "4700.00");
    }
    @Test void postpaymentMatchesExampleAndReferencesAdvance() {
        JsonNode payload = json.valueToTree(builder.build(lead("1740", "1 590,00", product("Лосьон", "2", "870")), Kind.POSTPAYMENT, register, "tag", "advance-1"));
        assertThat(payload.path("post_payment_of_check_fn").asText()).isEqualTo("advance-1");
        assertThat(payload.at("/fiscal/subtask").asInt()).isEqualTo(2);
        assertThat(payload.at("/fiscal/receipt/sum").decimalValue()).isEqualByComparingTo("1590");
        assertThat(payload.at("/fiscal/receipt/rows/0/disc").decimalValue()).isEqualByComparingTo("150");
        assertThat(payload.at("/fiscal/receipt/rows/0/disc_apply_type").asInt()).isEqualTo(1);
    }
    @Test void proportionalAllocationAlwaysAccountsForEveryKopeck() {
        List<Product> products = List.of(product("a", "1", "100.01"), product("b", "1", "100.02"), product("c", "1", "100.03"));
        List<BigDecimal> discounts = ReceiptBuilder.allocateAdvance(products, new BigDecimal("300.06"), ADVANCE);
        assertThat(discounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("150.00");
        for (int i = 0; i < discounts.size(); i++) assertThat(discounts.get(i)).isLessThan(products.get(i).cost());
    }
    @Test void tinyProductNeverBecomesFree() {
        List<Product> products = List.of(product("a", "1", "0.01"), product("b", "1", "150.01"));
        assertThat(ReceiptBuilder.allocateAdvance(products, new BigDecimal("150.02"), ADVANCE)).containsExactly(new BigDecimal("0.00"), new BigDecimal("150.00"));
    }
    @Test void fractionalQuantityIsRoundedOnlyAtRowTotal() {
        Lead lead = lead("174.00", "24.00", product("Товар", "0.200", "870.00"));
        assertThat(builder.amount(lead, Kind.POSTPAYMENT)).isEqualByComparingTo("24.00");
    }
    @Test void mismatchedBudgetOrCodStopsReceipt() {
        assertThatThrownBy(() -> builder.amount(lead("1000", "", product("a", "1", "1100")), Kind.FULL))
                .isInstanceOf(Failure.class).hasMessageContaining("не равна бюджету");
        assertThat(builder.amount(lead("1740", "1589.99", product("a", "2", "870")), Kind.POSTPAYMENT)).isEqualByComparingTo("1589.99");
        assertThat(builder.amount(lead("1740", "", product("a", "2", "870")), Kind.POSTPAYMENT)).isEqualByComparingTo("1590");
    }
    @Test void variableAdvanceChangesRowsAndPayments() {
        Lead lead = lead("1000", "700,25", product("a", "1", "400"), product("b", "1", "600"));
        JsonNode pre = json.valueToTree(builder.build(lead, Kind.PREPAYMENT, register, "a", null));
        assertThat(pre.at("/fiscal/receipt/rows/0/price").decimalValue()).isEqualByComparingTo("299.75");
        assertThat(pre.at("/fiscal/receipt/pays/0/sum").decimalValue()).isEqualByComparingTo("299.75");
        JsonNode post = json.valueToTree(builder.build(lead, Kind.POSTPAYMENT, register, "b", "advance"));
        assertThat(post.at("/fiscal/receipt/sum").decimalValue()).isEqualByComparingTo("700.25");
        assertThat(post.at("/fiscal/receipt/rows/0/disc").decimalValue()
                .add(post.at("/fiscal/receipt/rows/1/disc").decimalValue())).isEqualByComparingTo("299.75");
    }
    @Test void emptyCodDefaultsButInvalidCodDoesNot() {
        for (String cod : new String[]{null, "", "  "}) {
            Lead lead = lead("1000", cod, product("a", "1", "1000"));
            assertThat(builder.amount(lead, Kind.PREPAYMENT)).isEqualByComparingTo("150");
            assertThat(builder.amount(lead, Kind.POSTPAYMENT)).isEqualByComparingTo("850");
        }
        for (String cod : List.of("-1", "1001", "1000", "oops", "700.001"))
            for (Kind kind : List.of(Kind.PREPAYMENT, Kind.POSTPAYMENT))
                assertThatThrownBy(() -> builder.amount(lead("1000", cod, product("a", "1", "1000")), kind)).isInstanceOf(Failure.class);
        assertThat(builder.amount(lead("1000", "0", product("a", "1", "1000")), Kind.PREPAYMENT)).isEqualByComparingTo("1000");
    }
    @Test void rejectsInsufficientBudgetAndExtraPrecision() {
        assertThatThrownBy(() -> builder.amount(lead("149", "", product("a", "1", "149")), Kind.PREPAYMENT)).isInstanceOf(Failure.class);
        assertThatThrownBy(() -> builder.amount(lead("150", "0", product("a", "1", "150")), Kind.POSTPAYMENT)).isInstanceOf(Failure.class);
        assertThatThrownBy(() -> decimal("150.001", "сумма", 2)).isInstanceOf(Failure.class);
    }
    @Test void foreignReceiptUrlsAreRejected() {
        assertThat(VchasnoClient.numberFromUrl("https://kasa.vchasno.ua/c/TEST_abc?sm=150")).isEqualTo("TEST_abc");
        assertThatThrownBy(() -> VchasnoClient.numberFromUrl("https://evil.test/c/abc")).isInstanceOf(Failure.class);
        assertThatThrownBy(() -> VchasnoClient.numberFromUrl("https://kasa.vchasno.ua/c/a/b")).isInstanceOf(Failure.class);
    }
}
