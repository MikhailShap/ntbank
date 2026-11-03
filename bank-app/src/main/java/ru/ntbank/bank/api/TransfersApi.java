package ru.ntbank.bank.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.ntbank.bank.domain.Transfer;
import ru.ntbank.bank.domain.TransferType;
import ru.ntbank.bank.exception.BusinessException;
import ru.ntbank.bank.security.AppUserDetails;
import ru.ntbank.bank.service.IdempotencyService;
import ru.ntbank.bank.service.TransferService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** Переводы: создание, подтверждение кодом, опрос статуса. */
@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "3. Переводы",
        description = "Создание, подтверждение кодом и статус перевода")
public class TransfersApi {

    private static final String SCOPE = "transfer";

    private final TransferService transfers;
    private final IdempotencyService idempotency;

    public TransfersApi(TransferService transfers, IdempotencyService idempotency) {
        this.transfers = transfers;
        this.idempotency = idempotency;
    }

    @PostMapping
    @Operation(summary = "Создать перевод",
            description = "type = PHONE (по номеру телефона, нужен OTP) или OWN (между своими счетами, "
                    + "выполняется сразу). Повтор с тем же Idempotency-Key возвращает первый ответ.")
    public ResponseEntity<?> create(
            @AuthenticationPrincipal AppUserDetails me,
            @Parameter(description = "Ключ идемпотентности: повтор не создаст второй перевод")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateTransferRequest request) {

        TransferType type = parseType(request.type());
        if (type == TransferType.OWN && request.toAccountId() == null) {
            throw BusinessException.badRequest("TO_ACCOUNT_REQUIRED",
                    "Для перевода между своими счетами укажите toAccountId");
        }
        return idempotency.execute(me.getId(), SCOPE, idempotencyKey, () -> {
            Transfer transfer = type == TransferType.OWN
                    ? transfers.createOwn(me.getId(), request.fromAccountId(),
                            request.toAccountId(), request.amount())
                    : transfers.createPhoneDraft(me.getId(), request.fromAccountId(),
                            request.toPhone(), request.amount(), request.comment(), idempotencyKey);
            return toDto(transfer);
        });
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Подтвердить перевод кодом из СМС",
            description = "Код действует 10 минут, число попыток ввода ограничено")
    public TransferDto confirm(@AuthenticationPrincipal AppUserDetails me,
                               @PathVariable UUID id,
                               @Valid @RequestBody ConfirmRequest request) {
        return toDto(transfers.confirm(me.getId(), id, request.otp()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Статус перевода",
            description = "Статусы: DRAFT, PROCESSING, DONE, REJECTED, EXPIRED. "
                    + "Поле finalStatus = true, когда обработка завершена.")
    public TransferDto status(@AuthenticationPrincipal AppUserDetails me, @PathVariable UUID id) {
        return toDto(transfers.find(me.getId(), id));
    }

    private TransferType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return TransferType.PHONE;
        }
        try {
            return TransferType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BusinessException.badRequest("TYPE_INVALID", "Тип перевода должен быть PHONE или OWN");
        }
    }

    private TransferDto toDto(Transfer transfer) {
        String message = switch (transfer.getStatus()) {
            case DRAFT -> "Перевод №" + transfer.getId() + " создан, подтвердите его кодом из СМС";
            case PROCESSING -> "Перевод №" + transfer.getId() + " отправлен на обработку";
            case DONE -> "Перевод №" + transfer.getId() + " выполнен";
            case REJECTED -> "Перевод №" + transfer.getId() + " отклонён";
            case EXPIRED -> "Перевод №" + transfer.getId() + " истёк";
        };
        return new TransferDto(
                transfer.getId(),
                transfer.getType().name(),
                transfer.getStatus().name(),
                transfer.getStatus().titleRu(),
                transfer.getStatus().isFinal(),
                transfer.getStatus() == ru.ntbank.bank.domain.TransferStatus.DRAFT,
                transfer.getFromAccount().getId(),
                transfer.getToPhone(),
                transfer.getToName(),
                transfer.getAmount(),
                transfer.getCurrency(),
                transfer.getCreditedAmount(),
                transfer.getCreditedCurrency(),
                transfer.getComment(),
                transfer.getRejectReason(),
                transfer.getCreatedAt(),
                transfer.getConfirmedAt(),
                transfer.getCompletedAt(),
                message);
    }

    public record CreateTransferRequest(
            @io.swagger.v3.oas.annotations.media.Schema(example = "PHONE",
                    description = "PHONE или OWN, по умолчанию PHONE") String type,
            @NotNull(message = "Укажите fromAccountId") Long fromAccountId,
            @io.swagger.v3.oas.annotations.media.Schema(example = "+79000000042") String toPhone,
            Long toAccountId,
            @NotNull(message = "Укажите сумму")
            @Positive(message = "Сумма должна быть больше нуля") BigDecimal amount,
            String comment) {
    }

    public record ConfirmRequest(
            @NotBlank(message = "Укажите код подтверждения")
            @io.swagger.v3.oas.annotations.media.Schema(example = "123456") String otp) {
    }

    public record TransferDto(UUID transferId, String type, String status, String statusTitle,
                              boolean finalStatus, boolean otpRequired, Long fromAccountId,
                              String toPhone, String toName, BigDecimal amount, String currency,
                              BigDecimal creditedAmount, String creditedCurrency, String comment,
                              String rejectReason, LocalDateTime createdAt, LocalDateTime confirmedAt,
                              LocalDateTime completedAt, String message) {
    }
}
