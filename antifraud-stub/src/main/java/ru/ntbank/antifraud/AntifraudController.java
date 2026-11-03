package ru.ntbank.antifraud;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Проверка переводов и управление «поломками» заглушки. */
@RestController
@RequestMapping("/api/antifraud")
public class AntifraudController {

    private static final Logger log = LoggerFactory.getLogger(AntifraudController.class);

    private final AntifraudApplication.AntifraudProperties properties;
    private final ChaosSettings chaos;
    private final Counter approvedCounter;
    private final Counter rejectedCounter;
    private final Counter failedCounter;

    public AntifraudController(AntifraudApplication.AntifraudProperties properties,
                               ChaosSettings chaos, MeterRegistry registry) {
        this.properties = properties;
        this.chaos = chaos;
        this.approvedCounter = Counter.builder("antifraud_checks_total")
                .tag("result", "approved").register(registry);
        this.rejectedCounter = Counter.builder("antifraud_checks_total")
                .tag("result", "rejected").register(registry);
        this.failedCounter = Counter.builder("antifraud_checks_total")
                .tag("result", "failed").register(registry);
    }

    @PostMapping("/check")
    public CheckResponse check(@Valid @RequestBody CheckRequest request) {
        sleepIfNeeded();

        if (chaos.isEnabled() && chaos.getErrorPercent() > 0
                && ThreadLocalRandom.current().nextInt(100) < chaos.getErrorPercent()) {
            failedCounter.increment();
            log.warn("Проверка {} провалена намеренно (режим дефектов)", request.transferId());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Сервис антифрода временно недоступен");
        }

        String checkId = UUID.randomUUID().toString();
        int riskScore = riskScore(request);

        if (request.amount() != null
                && request.amount().compareTo(properties.thresholdAmount()) >= 0) {
            rejectedCounter.increment();
            return CheckResponse.reject(checkId, riskScore,
                    "Сумма превышает порог антифрод-правила AF-17");
        }

        int rejectPercent = chaos.isEnabled() && chaos.getRejectPercent() > 0
                ? chaos.getRejectPercent()
                : properties.baseRejectPercent();
        if (ThreadLocalRandom.current().nextInt(100) < rejectPercent) {
            rejectedCounter.increment();
            return CheckResponse.reject(checkId, riskScore,
                    "Подозрение на нетипичную активность по счёту");
        }

        approvedCounter.increment();
        return CheckResponse.approve(checkId, riskScore);
    }

    @GetMapping("/chaos")
    public ChaosSettings.State chaos() {
        return chaos.state();
    }

    @PostMapping("/chaos")
    public ChaosSettings.State updateChaos(@RequestBody ChaosRequest request) {
        chaos.apply(request.enabled(), request.delayMinMs(), request.delayMaxMs(),
                request.errorPercent(), request.rejectPercent());
        log.info("Настройки дефектов антифрода изменены: {}", chaos.state());
        return chaos.state();
    }

    @PostMapping("/chaos/reset")
    public ChaosSettings.State resetChaos() {
        chaos.reset();
        log.info("Настройки дефектов антифрода сброшены");
        return chaos.state();
    }

    /** Базовая задержка плюс задержка из режима дефектов. */
    private void sleepIfNeeded() {
        long delay = properties.baseLatencyMs();
        if (chaos.isEnabled() && chaos.getDelayMaxMs() > 0) {
            long min = chaos.getDelayMinMs();
            long max = chaos.getDelayMaxMs();
            delay += (max > min) ? ThreadLocalRandom.current().nextLong(min, max + 1) : min;
        }
        if (delay <= 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Псевдо-оценка риска, чтобы ответ выглядел как у настоящей системы. */
    private int riskScore(CheckRequest request) {
        int base = request.amount() == null ? 0
                : request.amount().divide(BigDecimal.valueOf(2000), RoundingMode.DOWN).intValue();
        return Math.min(99, Math.max(1, base + ThreadLocalRandom.current().nextInt(10)));
    }

    public record CheckRequest(@NotNull String transferId, Long userId, String toPhone,
                               @NotNull BigDecimal amount, String currency) {
    }

    public record CheckResponse(boolean approved, String reason, String checkId, int riskScore) {

        public static CheckResponse approve(String checkId, int riskScore) {
            return new CheckResponse(true, null, checkId, riskScore);
        }

        public static CheckResponse reject(String checkId, int riskScore, String reason) {
            return new CheckResponse(false, reason, checkId, riskScore);
        }
    }

    public record ChaosRequest(Boolean enabled, Long delayMinMs, Long delayMaxMs,
                               Integer errorPercent, Integer rejectPercent) {
    }
}
