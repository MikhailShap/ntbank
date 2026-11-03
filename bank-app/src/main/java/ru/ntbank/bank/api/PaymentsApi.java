package ru.ntbank.bank.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.ntbank.bank.domain.Payment;
import ru.ntbank.bank.domain.PaymentTemplate;
import ru.ntbank.bank.domain.ServiceCatalogItem;
import ru.ntbank.bank.security.AppUserDetails;
import ru.ntbank.bank.service.IdempotencyService;
import ru.ntbank.bank.service.PaymentService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Каталог услуг, оплата и шаблоны. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "4. Услуги и платежи")
public class PaymentsApi {

    private static final String SCOPE = "payment";

    private final PaymentService payments;
    private final IdempotencyService idempotency;

    public PaymentsApi(PaymentService payments, IdempotencyService idempotency) {
        this.payments = payments;
        this.idempotency = idempotency;
    }

    @GetMapping("/services")
    @Operation(summary = "Каталог услуг с поиском",
            description = "query ищет по названию, поставщику и категории; нумерация страниц с нуля")
    public PageDto<ServiceDto> services(@RequestParam(required = false) String query,
                                        @RequestParam(required = false) String category,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        Page<ServiceCatalogItem> result = payments.search(query, category, page, size);
        return PageDto.of(result, PaymentsApi::toDto);
    }

    @GetMapping("/services/categories")
    @Operation(summary = "Список категорий услуг")
    public List<String> categories() {
        return payments.categories();
    }

    @PostMapping("/payments")
    @Operation(summary = "Оплатить услугу",
            description = "Повтор с тем же Idempotency-Key возвращает первый ответ и не списывает деньги дважды")
    public ResponseEntity<?> pay(
            @AuthenticationPrincipal AppUserDetails me,
            @Parameter(description = "Ключ идемпотентности")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {

        return idempotency.execute(me.getId(), SCOPE, idempotencyKey, () -> toDto(
                payments.pay(me.getId(), request.serviceId(), request.accountId(),
                        request.personalAccount(), request.amount(), idempotencyKey,
                        Boolean.TRUE.equals(request.saveTemplate()), request.templateName())));
    }

    @GetMapping("/payments/{id}")
    @Operation(summary = "Платёж по идентификатору")
    public PaymentDto payment(@AuthenticationPrincipal AppUserDetails me, @PathVariable Long id) {
        return toDto(payments.payment(me.getId(), id));
    }

    @GetMapping("/templates")
    @Operation(summary = "Шаблоны платежей клиента")
    public List<TemplateDto> templates(@AuthenticationPrincipal AppUserDetails me) {
        return payments.templates(me.getId()).stream().map(PaymentsApi::toDto).toList();
    }

    @PostMapping("/templates/{id}/pay")
    @Operation(summary = "Повторить платёж по шаблону",
            description = "Повтор с тем же Idempotency-Key возвращает первый ответ и не списывает деньги дважды")
    public ResponseEntity<?> payByTemplate(
            @AuthenticationPrincipal AppUserDetails me, @PathVariable Long id,
            @Parameter(description = "Ключ идемпотентности")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey) {
        return idempotency.execute(me.getId(), SCOPE + ":template", idempotencyKey, HttpStatus.OK,
                () -> toDto(payments.payByTemplate(me.getId(), id, idempotencyKey)));
    }

    @DeleteMapping("/templates/{id}")
    @Operation(summary = "Удалить шаблон")
    public ResponseEntity<Void> deleteTemplate(@AuthenticationPrincipal AppUserDetails me,
                                               @PathVariable Long id) {
        payments.deleteTemplate(me.getId(), id);
        return ResponseEntity.noContent().build();
    }

    private static ServiceDto toDto(ServiceCatalogItem service) {
        return new ServiceDto(service.getId(), service.getCode(), service.getName(),
                service.getCategory(), service.getProvider(), service.getAccountLabel(),
                service.getMinAmount(), service.getMaxAmount(), service.getCommissionPercent());
    }

    private static PaymentDto toDto(Payment payment) {
        return new PaymentDto(payment.getId(), payment.getService().getId(),
                payment.getService().getName(), payment.getPersonalAccount(), payment.getAmount(),
                payment.getCurrency(), payment.getAccount().getId(), payment.getStatus(),
                payment.getCreatedAt(),
                "Платёж №" + payment.getId() + " выполнен");
    }

    private static TemplateDto toDto(PaymentTemplate template) {
        return new TemplateDto(template.getId(), template.getName(), template.getService().getId(),
                template.getService().getName(), template.getPersonalAccount(), template.getAmount(),
                template.getAccount().getId(), template.getCreatedAt(), template.getLastUsedAt());
    }

    public record CreatePaymentRequest(
            @NotNull(message = "Укажите serviceId") Long serviceId,
            @NotNull(message = "Укажите accountId") Long accountId,
            @NotBlank(message = "Укажите номер лицевого счёта") String personalAccount,
            @NotNull(message = "Укажите сумму")
            @Positive(message = "Сумма должна быть больше нуля") BigDecimal amount,
            Boolean saveTemplate,
            String templateName) {
    }

    public record ServiceDto(Long id, String code, String name, String category, String provider,
                             String accountLabel, BigDecimal minAmount, BigDecimal maxAmount,
                             BigDecimal commissionPercent) {
    }

    public record PaymentDto(Long paymentId, Long serviceId, String serviceName,
                             String personalAccount, BigDecimal amount, String currency,
                             Long accountId, String status, LocalDateTime createdAt, String message) {
    }

    public record TemplateDto(Long id, String name, Long serviceId, String serviceName,
                              String personalAccount, BigDecimal amount, Long accountId,
                              LocalDateTime createdAt, LocalDateTime lastUsedAt) {
    }
}
