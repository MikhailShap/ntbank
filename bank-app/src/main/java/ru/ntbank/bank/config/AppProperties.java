package ru.ntbank.bank.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/** Настройки стенда из секции ntbank.* в application.yml. */
@ConfigurationProperties(prefix = "ntbank")
public record AppProperties(
        String bankName,
        Seed seed,
        Jwt jwt,
        Otp otp,
        Transfer transfer,
        String asyncMode,
        Antifraud antifraud,
        Kafka kafka
) {

    /**
     * operationsTotal — до скольких операций нарастить историю после генерации (0 — не наращивать).
     * Нужно для дефекта «Нет индекса»: на 160 тысячах строк полный перебор почти незаметен.
     */
    public record Seed(boolean enabled, int users, String password, String csvPath,
                       long operationsTotal) {
    }

    public record Jwt(String secret, long accessTtlMinutes, long refreshTtlDays) {
    }

    public record Otp(int length, long ttlMinutes, int maxAttempts) {
    }

    public record Transfer(long minDelayMs, long maxDelayMs, BigDecimal limit) {
    }

    /** Внешняя антифрод-система. mode: auto | http | local. */
    public record Antifraud(String url, String mode, long connectTimeoutMs, long readTimeoutMs) {
    }

    public record Kafka(String transfersTopic, int partitions) {
    }
}
