package ru.ntbank.bank.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import ru.ntbank.bank.chaos.ChaosService;
import ru.ntbank.bank.chaos.ChaosSettings;
import ru.ntbank.bank.chaos.ChaosTarget;
import ru.ntbank.bank.chaos.SlowSqlMode;
import ru.ntbank.bank.config.OpenApiConfig;

/**
 * Управление режимом «Дефекты» из REST — то же, что делает страница /admin/chaos.
 * Доступ по HTTP Basic (admin / admin).
 */
@RestController
@RequestMapping("/api/admin/chaos")
@Tag(name = "6. Режим «Дефекты» (admin/admin)",
        description = "Включение и выключение проблем стенда без перезапуска")
@SecurityRequirement(name = OpenApiConfig.ADMIN_SCHEME)
public class ChaosApi {

    private final ChaosService chaosService;
    private final ChaosSettings settings;

    public ChaosApi(ChaosService chaosService, ChaosSettings settings) {
        this.chaosService = chaosService;
        this.settings = settings;
    }

    @GetMapping
    @Operation(summary = "Текущее состояние всех дефектов")
    public ChaosService.ChaosState state() {
        return chaosService.state();
    }

    @PostMapping
    @Operation(summary = "Изменить настройки",
            description = "Поля необязательные: меняется только то, что передано. "
                    + "Например {\"latencyEnabled\":true,\"latencyMinMs\":300,\"latencyMaxMs\":900}")
    public ChaosService.ChaosState update(@RequestBody ChaosUpdate update) {
        apply(update);
        return chaosService.state();
    }

    @PostMapping("/reset")
    @Operation(summary = "Выключить всё и вернуть штатный режим")
    public ChaosService.ChaosState reset() {
        chaosService.resetAll();
        return chaosService.state();
    }

    private void apply(ChaosUpdate u) {
        if (any(u.latencyEnabled(), u.latencyTarget(), u.latencyMinMs(), u.latencyMaxMs())) {
            settings.applyLatency(flag(u.latencyEnabled(), settings.isLatencyEnabled()),
                    ChaosTarget.of(u.latencyTarget(), settings.getLatencyTarget()),
                    u.latencyMinMs(), u.latencyMaxMs());
        }
        if (any(u.errorsEnabled(), u.errorsTarget(), u.errorPercent())) {
            settings.applyErrors(flag(u.errorsEnabled(), settings.isErrorsEnabled()),
                    ChaosTarget.of(u.errorsTarget(), settings.getErrorsTarget()),
                    u.errorPercent());
        }
        if (any(u.slowSqlEnabled(), u.slowSqlMode())) {
            chaosService.applySlowSql(flag(u.slowSqlEnabled(), settings.isSlowSqlEnabled()),
                    SlowSqlMode.of(u.slowSqlMode(), settings.getSlowSqlMode()));
        }
        if (any(u.leakEnabled(), u.leakTarget(), u.leakKbPerRequest())) {
            settings.applyLeak(flag(u.leakEnabled(), settings.isLeakEnabled()),
                    ChaosTarget.of(u.leakTarget(), settings.getLeakTarget()),
                    u.leakKbPerRequest());
        }
        if (Boolean.TRUE.equals(u.clearLeaked())) {
            chaosService.clearLeaked();
        }
        if (any(u.cpuEnabled(), u.cpuTarget(), u.cpuMillis())) {
            settings.applyCpu(flag(u.cpuEnabled(), settings.isCpuEnabled()),
                    ChaosTarget.of(u.cpuTarget(), settings.getCpuTarget()),
                    u.cpuMillis());
        }
        if (any(u.hikariMaxPoolSize(), u.tomcatMaxThreads())) {
            chaosService.applyPools(u.hikariMaxPoolSize(), u.tomcatMaxThreads());
        }
        if (any(u.antifraudEnabled(), u.antifraudDelayMinMs(), u.antifraudDelayMaxMs(),
                u.antifraudErrorPercent(), u.antifraudRejectPercent())) {
            chaosService.applyAntifraud(flag(u.antifraudEnabled(), settings.isAntifraudEnabled()),
                    u.antifraudDelayMinMs(), u.antifraudDelayMaxMs(),
                    u.antifraudErrorPercent(), u.antifraudRejectPercent());
        }
        if (any(u.kafkaConsumerPaused(), u.kafkaConsumerDelayMs())) {
            chaosService.applyKafka(flag(u.kafkaConsumerPaused(), settings.isKafkaConsumerPaused()),
                    u.kafkaConsumerDelayMs());
        }
        if (any(u.rateLimitEnabled(), u.rateLimitRps())) {
            settings.applyRateLimit(flag(u.rateLimitEnabled(), settings.isRateLimitEnabled()),
                    u.rateLimitRps());
        }
    }

    private boolean any(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return true;
            }
        }
        return false;
    }

    private boolean flag(Boolean value, boolean current) {
        return value != null ? value : current;
    }

    /** Все поля необязательные: передавайте только то, что меняете. */
    public record ChaosUpdate(
            Boolean latencyEnabled, String latencyTarget, Long latencyMinMs, Long latencyMaxMs,
            Boolean errorsEnabled, String errorsTarget, Integer errorPercent,
            Boolean slowSqlEnabled, String slowSqlMode,
            Boolean leakEnabled, String leakTarget, Integer leakKbPerRequest, Boolean clearLeaked,
            Boolean cpuEnabled, String cpuTarget, Integer cpuMillis,
            Integer hikariMaxPoolSize, Integer tomcatMaxThreads,
            Boolean antifraudEnabled, Long antifraudDelayMinMs, Long antifraudDelayMaxMs,
            Integer antifraudErrorPercent, Integer antifraudRejectPercent,
            Boolean kafkaConsumerPaused, Long kafkaConsumerDelayMs,
            Boolean rateLimitEnabled, Integer rateLimitRps) {
    }
}
