package ru.ntbank.antifraud;

import org.springframework.stereotype.Component;

/**
 * Настройки «поломок» антифрода. Меняются на лету через REST, перезапуск не нужен.
 * Поля volatile: их пишет поток админского запроса, а читают потоки обработки.
 */
@Component
public class ChaosSettings {

    private volatile boolean enabled;
    private volatile long delayMinMs;
    private volatile long delayMaxMs;
    private volatile int errorPercent;
    private volatile int rejectPercent;

    public synchronized void apply(Boolean enabled, Long delayMinMs, Long delayMaxMs,
                                   Integer errorPercent, Integer rejectPercent) {
        if (enabled != null) {
            this.enabled = enabled;
        }
        if (delayMinMs != null) {
            this.delayMinMs = Math.max(0, delayMinMs);
        }
        if (delayMaxMs != null) {
            this.delayMaxMs = Math.max(0, delayMaxMs);
        }
        if (errorPercent != null) {
            this.errorPercent = clampPercent(errorPercent);
        }
        if (rejectPercent != null) {
            this.rejectPercent = clampPercent(rejectPercent);
        }
        if (this.delayMaxMs < this.delayMinMs) {
            this.delayMaxMs = this.delayMinMs;
        }
    }

    public synchronized void reset() {
        enabled = false;
        delayMinMs = 0;
        delayMaxMs = 0;
        errorPercent = 0;
        rejectPercent = 0;
    }

    private int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getDelayMinMs() {
        return delayMinMs;
    }

    public long getDelayMaxMs() {
        return delayMaxMs;
    }

    public int getErrorPercent() {
        return errorPercent;
    }

    public int getRejectPercent() {
        return rejectPercent;
    }

    public State state() {
        return new State(enabled, delayMinMs, delayMaxMs, errorPercent, rejectPercent);
    }

    public record State(boolean enabled, long delayMinMs, long delayMaxMs,
                        int errorPercent, int rejectPercent) {
    }
}
