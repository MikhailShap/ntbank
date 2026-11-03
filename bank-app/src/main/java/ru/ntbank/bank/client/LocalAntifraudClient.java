package ru.ntbank.bank.client;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Локальные антифрод-правила (этап 1, режим без внешнего сервиса).
 * Часть переводов отклоняется, чтобы в JMeter встречались оба финальных статуса.
 */
public class LocalAntifraudClient implements AntifraudClient {

    private static final BigDecimal SUSPICIOUS_AMOUNT = new BigDecimal("150000");

    @Override
    public Decision check(Request request) {
        String checkId = UUID.randomUUID().toString();
        if (request.amount().compareTo(SUSPICIOUS_AMOUNT) >= 0) {
            return Decision.reject("Сумма превышает порог антифрод-правила AF-17", checkId);
        }
        if (ThreadLocalRandom.current().nextInt(100) < 7) {
            return Decision.reject("Подозрение на нетипичную активность по счёту", checkId);
        }
        return Decision.approve(checkId);
    }

    @Override
    public String mode() {
        return "local";
    }
}
