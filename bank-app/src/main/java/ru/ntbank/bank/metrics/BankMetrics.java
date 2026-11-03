package ru.ntbank.bank.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.ntbank.bank.domain.TransferStatus;
import ru.ntbank.bank.repo.TransferRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Бизнес-метрики банка для Prometheus.
 * <ul>
 *   <li>{@code ntbank_transfers{status}} — сколько переводов в каждом статусе прямо сейчас;</li>
 *   <li>{@code ntbank_transfers_created_total{type}} и {@code ntbank_transfers_finished_total{status}};</li>
 *   <li>{@code ntbank_transfer_processing_seconds} — от подтверждения до финального статуса.
 *       На нём хорошо видны пауза консьюмера Kafka и тормозящий «Антифрод».</li>
 * </ul>
 */
@Component
public class BankMetrics {

    private final MeterRegistry registry;
    private final TransferRepository transfers;
    private final Map<TransferStatus, AtomicLong> byStatus = new EnumMap<>(TransferStatus.class);
    private final Timer processing;

    public BankMetrics(MeterRegistry registry, TransferRepository transfers) {
        this.registry = registry;
        this.transfers = transfers;
        for (TransferStatus status : TransferStatus.values()) {
            AtomicLong value = new AtomicLong();
            byStatus.put(status, value);
            Gauge.builder("ntbank.transfers", value, AtomicLong::get)
                    .description("Число переводов в каждом статусе")
                    .tag("status", status.name())
                    .register(registry);
        }
        this.processing = Timer.builder("ntbank.transfer.processing")
                .description("Время от подтверждения перевода до финального статуса")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(100))
                .maximumExpectedValue(Duration.ofMinutes(5))
                .register(registry);
    }

    /** Счётчики по статусам читаем из базы раз в 5 секунд, а не на каждый опрос Prometheus. */
    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void refreshStatusCounts() {
        Map<TransferStatus, Long> counts = new EnumMap<>(TransferStatus.class);
        List<Object[]> rows = transfers.countGroupedByStatus();
        for (Object[] row : rows) {
            counts.put((TransferStatus) row[0], ((Number) row[1]).longValue());
        }
        byStatus.forEach((status, value) -> value.set(counts.getOrDefault(status, 0L)));
    }

    public void transferCreated(String type) {
        Counter.builder("ntbank.transfers.created")
                .description("Созданные переводы")
                .tag("type", type)
                .register(registry)
                .increment();
    }

    /** Перевод получил финальный статус. confirmedAt — когда его подтвердили (может быть null). */
    public void transferFinished(TransferStatus status, LocalDateTime confirmedAt) {
        Counter.builder("ntbank.transfers.finished")
                .description("Переводы, получившие финальный статус")
                .tag("status", status.name())
                .register(registry)
                .increment();
        if (confirmedAt != null) {
            processing.record(Duration.between(confirmedAt, LocalDateTime.now()));
        }
    }
}
