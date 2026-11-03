package ru.ntbank.bank.chaos;

import org.springframework.stereotype.Component;

/**
 * Состояние режима «Дефекты».
 * <p>
 * Все поля volatile: их пишет поток админского запроса, а читают потоки обработки запросов.
 * Значения применяются на лету, перезапуск не нужен.
 */
@Component
public class ChaosSettings {

    // --- Задержка ответа ---
    private volatile boolean latencyEnabled;
    private volatile ChaosTarget latencyTarget = ChaosTarget.ALL;
    private volatile long latencyMinMs = 200;
    private volatile long latencyMaxMs = 500;

    // --- Ошибки 500 ---
    private volatile boolean errorsEnabled;
    private volatile ChaosTarget errorsTarget = ChaosTarget.ALL;
    private volatile int errorPercent = 10;

    // --- Медленный SQL в выписке ---
    private volatile boolean slowSqlEnabled;
    private volatile SlowSqlMode slowSqlMode = SlowSqlMode.HEAVY_COMPUTE;

    // --- Утечка памяти ---
    private volatile boolean leakEnabled;
    private volatile ChaosTarget leakTarget = ChaosTarget.ALL;
    private volatile int leakKbPerRequest = 50;

    // --- Нагрузка на CPU ---
    private volatile boolean cpuEnabled;
    private volatile ChaosTarget cpuTarget = ChaosTarget.ALL;
    private volatile int cpuMillis = 200;

    // --- Внешний «Антифрод» ---
    private volatile boolean antifraudEnabled;
    private volatile long antifraudDelayMinMs = 1000;
    private volatile long antifraudDelayMaxMs = 3000;
    private volatile int antifraudErrorPercent;
    private volatile int antifraudRejectPercent;

    // --- Kafka ---
    private volatile boolean kafkaConsumerPaused;
    private volatile long kafkaConsumerDelayMs;

    // --- Ограничение частоты запросов ---
    private volatile boolean rateLimitEnabled;
    private volatile int rateLimitRps = 10;

    // ------------------------------------------------------------------ изменение

    public synchronized void applyLatency(boolean enabled, ChaosTarget target, Long minMs, Long maxMs) {
        this.latencyEnabled = enabled;
        this.latencyTarget = target;
        if (minMs != null) {
            this.latencyMinMs = Math.max(0, minMs);
        }
        if (maxMs != null) {
            this.latencyMaxMs = Math.max(0, maxMs);
        }
        if (this.latencyMaxMs < this.latencyMinMs) {
            this.latencyMaxMs = this.latencyMinMs;
        }
    }

    public synchronized void applyErrors(boolean enabled, ChaosTarget target, Integer percent) {
        this.errorsEnabled = enabled;
        this.errorsTarget = target;
        if (percent != null) {
            this.errorPercent = clampPercent(percent);
        }
    }

    public synchronized void applySlowSql(boolean enabled, SlowSqlMode mode) {
        this.slowSqlEnabled = enabled;
        if (mode != null) {
            this.slowSqlMode = mode;
        }
    }

    public synchronized void applyLeak(boolean enabled, ChaosTarget target, Integer kbPerRequest) {
        this.leakEnabled = enabled;
        this.leakTarget = target;
        if (kbPerRequest != null) {
            this.leakKbPerRequest = Math.max(1, Math.min(10240, kbPerRequest));
        }
    }

    public synchronized void applyCpu(boolean enabled, ChaosTarget target, Integer millis) {
        this.cpuEnabled = enabled;
        this.cpuTarget = target;
        if (millis != null) {
            this.cpuMillis = Math.max(1, Math.min(10000, millis));
        }
    }

    public synchronized void applyAntifraud(boolean enabled, Long delayMinMs, Long delayMaxMs,
                                            Integer errorPercent, Integer rejectPercent) {
        this.antifraudEnabled = enabled;
        if (delayMinMs != null) {
            this.antifraudDelayMinMs = Math.max(0, delayMinMs);
        }
        if (delayMaxMs != null) {
            this.antifraudDelayMaxMs = Math.max(0, delayMaxMs);
        }
        if (this.antifraudDelayMaxMs < this.antifraudDelayMinMs) {
            this.antifraudDelayMaxMs = this.antifraudDelayMinMs;
        }
        if (errorPercent != null) {
            this.antifraudErrorPercent = clampPercent(errorPercent);
        }
        if (rejectPercent != null) {
            this.antifraudRejectPercent = clampPercent(rejectPercent);
        }
    }

    public synchronized void applyKafka(boolean paused, Long consumerDelayMs) {
        this.kafkaConsumerPaused = paused;
        if (consumerDelayMs != null) {
            this.kafkaConsumerDelayMs = Math.max(0, Math.min(60000, consumerDelayMs));
        }
    }

    public synchronized void applyRateLimit(boolean enabled, Integer rps) {
        this.rateLimitEnabled = enabled;
        if (rps != null) {
            this.rateLimitRps = Math.max(1, Math.min(100000, rps));
        }
    }

    /** Выключить всё, значения полей вернуть к значениям по умолчанию. */
    public synchronized void reset() {
        latencyEnabled = false;
        latencyTarget = ChaosTarget.ALL;
        latencyMinMs = 200;
        latencyMaxMs = 500;

        errorsEnabled = false;
        errorsTarget = ChaosTarget.ALL;
        errorPercent = 10;

        slowSqlEnabled = false;
        slowSqlMode = SlowSqlMode.HEAVY_COMPUTE;

        leakEnabled = false;
        leakTarget = ChaosTarget.ALL;
        leakKbPerRequest = 50;

        cpuEnabled = false;
        cpuTarget = ChaosTarget.ALL;
        cpuMillis = 200;

        antifraudEnabled = false;
        antifraudDelayMinMs = 1000;
        antifraudDelayMaxMs = 3000;
        antifraudErrorPercent = 0;
        antifraudRejectPercent = 0;

        kafkaConsumerPaused = false;
        kafkaConsumerDelayMs = 0;

        rateLimitEnabled = false;
        rateLimitRps = 10;
    }

    /** Включён ли хоть один дефект — для плашки «стенд не в штатном режиме». */
    public boolean anyEnabled() {
        return latencyEnabled || errorsEnabled || slowSqlEnabled || leakEnabled || cpuEnabled
                || antifraudEnabled || kafkaConsumerPaused || kafkaConsumerDelayMs > 0
                || rateLimitEnabled;
    }

    private int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    // ------------------------------------------------------------------ чтение

    public boolean isLatencyEnabled() {
        return latencyEnabled;
    }

    public ChaosTarget getLatencyTarget() {
        return latencyTarget;
    }

    public long getLatencyMinMs() {
        return latencyMinMs;
    }

    public long getLatencyMaxMs() {
        return latencyMaxMs;
    }

    public boolean isErrorsEnabled() {
        return errorsEnabled;
    }

    public ChaosTarget getErrorsTarget() {
        return errorsTarget;
    }

    public int getErrorPercent() {
        return errorPercent;
    }

    public boolean isSlowSqlEnabled() {
        return slowSqlEnabled;
    }

    public SlowSqlMode getSlowSqlMode() {
        return slowSqlMode;
    }

    /** Режим выписки с учётом того, включён ли дефект вообще. */
    public SlowSqlMode effectiveSlowSqlMode() {
        return slowSqlEnabled ? slowSqlMode : null;
    }

    public boolean isLeakEnabled() {
        return leakEnabled;
    }

    public ChaosTarget getLeakTarget() {
        return leakTarget;
    }

    public int getLeakKbPerRequest() {
        return leakKbPerRequest;
    }

    public boolean isCpuEnabled() {
        return cpuEnabled;
    }

    public ChaosTarget getCpuTarget() {
        return cpuTarget;
    }

    public int getCpuMillis() {
        return cpuMillis;
    }

    public boolean isAntifraudEnabled() {
        return antifraudEnabled;
    }

    public long getAntifraudDelayMinMs() {
        return antifraudDelayMinMs;
    }

    public long getAntifraudDelayMaxMs() {
        return antifraudDelayMaxMs;
    }

    public int getAntifraudErrorPercent() {
        return antifraudErrorPercent;
    }

    public int getAntifraudRejectPercent() {
        return antifraudRejectPercent;
    }

    public boolean isKafkaConsumerPaused() {
        return kafkaConsumerPaused;
    }

    public long getKafkaConsumerDelayMs() {
        return kafkaConsumerDelayMs;
    }

    public boolean isRateLimitEnabled() {
        return rateLimitEnabled;
    }

    public int getRateLimitRps() {
        return rateLimitRps;
    }
}
