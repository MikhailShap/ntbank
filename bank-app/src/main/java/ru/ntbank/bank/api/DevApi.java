package ru.ntbank.bank.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ru.ntbank.bank.domain.SmsMessage;
import ru.ntbank.bank.exception.BusinessException;
import ru.ntbank.bank.service.SeedService;
import ru.ntbank.bank.service.SmsService;
import ru.ntbank.bank.service.TransferService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Служебные эндпоинты стенда: тестовые СМС и сброс данных.
 * Доступны без авторизации — так их проще использовать из JMeter.
 */
@RestController
@RequestMapping("/api/dev")
@Tag(name = "5. Служебное (без авторизации)",
        description = "Тестовые СМС и сброс данных стенда")
@SecurityRequirements
public class DevApi {

    private final SmsService sms;
    private final SeedService seedService;

    public DevApi(SmsService sms, SeedService seedService) {
        this.sms = sms;
        this.seedService = seedService;
    }

    @GetMapping("/sms/last")
    @Operation(summary = "Последняя СМС на номер")
    public SmsDto last(@RequestParam String phone) {
        String normalized = TransferService.normalizePhone(phone);
        SmsMessage message = sms.last(normalized).orElseThrow(() ->
                BusinessException.notFound("Сообщений на номер " + normalized + " не найдено"));
        return toDto(message);
    }

    @GetMapping("/sms")
    @Operation(summary = "Последние СМС (по номеру или все)")
    public List<SmsDto> inbox(@RequestParam(required = false) String phone) {
        String normalized = (phone == null || phone.isBlank())
                ? null : TransferService.normalizePhone(phone);
        return sms.inbox(normalized).stream().map(DevApi::toDto).toList();
    }

    @PostMapping("/reset")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Сбросить базу к исходному состоянию",
            description = "Пересоздаёт пользователей, счета, карты и историю операций. "
                    + "Каталог услуг и курсы валют не меняются.")
    public ResetResponse reset() {
        SeedService.SeedResult result = seedService.reset();
        SeedService.HistoryResult history = seedService.growToConfiguredTotal();
        if (history != null) {
            result = new SeedService.SeedResult(result.users(), result.accounts(), history.after(),
                    result.services(), result.elapsedMs() + history.elapsedMs(), result.csvPath());
        }
        return new ResetResponse(result.users(), result.accounts(), result.operations(),
                result.services(), result.elapsedMs(), result.csvPath(),
                "Тестовые данные пересозданы за " + result.elapsedMs() + " мс");
    }

    private static SmsDto toDto(SmsMessage message) {
        return new SmsDto(message.getId(), message.getPhone(), message.getCode(),
                message.getText(), message.getCreatedAt());
    }

    public record SmsDto(Long id, String phone, String code, String text, LocalDateTime createdAt) {
    }

    public record ResetResponse(int users, long accounts, long operations, long services,
                                long elapsedMs, String csvPath, String message) {
    }
}
