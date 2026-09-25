package ua.vcasno.integration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.List;

@Validated
@ConfigurationProperties("integration")
public record Settings(@NotBlank String apiKey, String webhookToken, String webhookFopId, Kommo kommo, String vchasnoUrl,
                       Map<String, CashRegister> cashRegisters) {
    public record Kommo(String baseUrl, String token, long productCatalogId, long priceFieldId, String priceFieldCode,
                        long fopFieldId, List<FopMapping> fopMappings) {}
    public record FopMapping(Long enumId, String value, String fopId) {}
    public record CashRegister(String token, int taxGroup, int paymentType) {}

    public CashRegister register(String id) {
        CashRegister value = cashRegisters.get(id);
        if (value == null || value.token() == null || value.token().isBlank())
            throw new Failure(422, "REGISTER_NOT_CONFIGURED", "Касса для выбранного ФОП не настроена");
        if (value.taxGroup() < 1 || value.taxGroup() > 9 || value.paymentType() < 0)
            throw new Failure(422, "REGISTER_CONFIG_INVALID", "Проверьте налоговую группу и тип оплаты кассы");
        return value;
    }
}
