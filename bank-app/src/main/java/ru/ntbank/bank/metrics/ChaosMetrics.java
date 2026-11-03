package ru.ntbank.bank.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.ntbank.bank.chaos.ChaosService;
import ru.ntbank.bank.chaos.ChaosSettings;
import ru.ntbank.bank.chaos.LeakStore;
import ru.ntbank.bank.chaos.SlowSqlMode;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Состояние режима «Дефекты» в виде метрик.
 * <p>
 * {@code ntbank_chaos_active{defect}} — 1, пока дефект включён. На дашборде это полоса
 * на временной шкале: сразу видно, какой дефект был включён, когда графики поехали.
 */
@Component
public class ChaosMetrics {

    private final ChaosService chaosService;
    private final AtomicLong operationsEstimate = new AtomicLong();

    public ChaosMetrics(MeterRegistry registry, ChaosSettings settings, ChaosService chaosService,
                        LeakStore leakStore) {
        this.chaosService = chaosService;

        flag(registry, "latency", settings::isLatencyEnabled);
        flag(registry, "errors", settings::isErrorsEnabled);
        flag(registry, "slow_sql_no_index", () -> settings.effectiveSlowSqlMode() == SlowSqlMode.NO_INDEX);
        flag(registry, "slow_sql_n_plus_one", () -> settings.effectiveSlowSqlMode() == SlowSqlMode.N_PLUS_ONE);
        flag(registry, "slow_sql_heavy", () -> settings.effectiveSlowSqlMode() == SlowSqlMode.HEAVY_COMPUTE);
        flag(registry, "memory_leak", settings::isLeakEnabled);
        flag(registry, "cpu", settings::isCpuEnabled);
        flag(registry, "small_pools", chaosService::poolsChanged);
        flag(registry, "antifraud", settings::isAntifraudEnabled);
        flag(registry, "kafka_paused", settings::isKafkaConsumerPaused);
        flag(registry, "kafka_slow_consumer", () -> settings.getKafkaConsumerDelayMs() > 0);
        flag(registry, "rate_limit", settings::isRateLimitEnabled);

        Gauge.builder("ntbank.chaos.leaked", leakStore, LeakStore::leakedBytes)
                .description("Память, накопленная дефектом «Утечка памяти»")
                .baseUnit("bytes")
                .register(registry);

        Gauge.builder("ntbank.operations.estimate", operationsEstimate, AtomicLong::get)
                .description("Примерное число операций в истории — для понимания объёма данных")
                .register(registry);
    }

    /**
     * strongReference(true) обязателен: по умолчанию Gauge держит слабую ссылку на объект
     * состояния, а лямбду больше никто не держит — сборщик мусора её удаляет,
     * и метрика начинает отдавать NaN.
     */
    private void flag(MeterRegistry registry, String defect, BooleanSupplier enabled) {
        Gauge.builder("ntbank.chaos.active", enabled, s -> s.getAsBoolean() ? 1 : 0)
                .description("1 — дефект включён")
                .tag("defect", defect)
                .strongReference(true)
                .register(registry);
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 10000)
    public void refreshOperationsEstimate() {
        operationsEstimate.set(chaosService.operationsEstimate());
    }
}
