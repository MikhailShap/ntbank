package ru.ntbank.bank.client;

import java.math.BigDecimal;

/**
 * Проверка перевода во внешней антифрод-системе.
 * Этап 1 — локальная реализация, этап 2 — HTTP-вызов сервиса antifraud-stub.
 */
public interface AntifraudClient {

    Decision check(Request request);

    String mode();

    /**
     * Передать настройки дефектов во внешний сервис.
     * Возвращает false, если сервиса нет и настройки применить некуда.
     */
    default boolean updateChaos(Chaos chaos) {
        return false;
    }

    record Chaos(boolean enabled, long delayMinMs, long delayMaxMs,
                 int errorPercent, int rejectPercent) {
    }

    record Request(String transferId, Long userId, String toPhone,
                   BigDecimal amount, String currency) {
    }

    record Decision(boolean approved, String reason, String checkId) {

        public static Decision approve(String checkId) {
            return new Decision(true, null, checkId);
        }

        public static Decision reject(String reason, String checkId) {
            return new Decision(false, reason, checkId);
        }
    }
}
