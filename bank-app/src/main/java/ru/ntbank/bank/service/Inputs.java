package ru.ntbank.bank.service;

import ru.ntbank.bank.exception.BusinessException;

import java.math.BigDecimal;

/**
 * Проверки пользовательского ввода, общие для веб-форм и REST.
 * <p>
 * JMeter отправит что угодно: сумму с тремя знаками после запятой, комментарий на тысячу символов.
 * Без этих проверок база округляла бы сумму молча, а слишком длинная строка давала бы 500.
 */
public final class Inputs {

    public static final int COMMENT_MAX = 160;
    public static final int PERSONAL_ACCOUNT_MAX = 40;
    public static final int TEMPLATE_NAME_MAX = 80;
    public static final int IDEMPOTENCY_KEY_MAX = 100;

    private Inputs() {
    }

    /** Сумма больше нуля и не больше двух знаков после запятой. */
    public static BigDecimal amount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.badRequest("AMOUNT_INVALID", "Сумма должна быть больше нуля");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw BusinessException.badRequest("AMOUNT_INVALID",
                    "Сумма может содержать не больше двух знаков после запятой");
        }
        return amount;
    }

    /** Сумма из HTML-формы: «1 234,50» и «1234.50» читаются одинаково. */
    public static BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BusinessException.badRequest("AMOUNT_INVALID", "Укажите сумму");
        }
        String normalized = raw.replaceAll("[\\s  ]", "").replace(',', '.');
        try {
            return amount(new BigDecimal(normalized));
        } catch (NumberFormatException e) {
            throw BusinessException.badRequest("AMOUNT_INVALID", "Сумма указана неверно: «" + raw.trim() + "»");
        }
    }

    public static void maxLength(String value, int max, String code, String what) {
        if (value != null && value.length() > max) {
            throw BusinessException.badRequest(code, what + " — не длиннее " + max + " символов");
        }
    }

    public static void idempotencyKey(String key) {
        maxLength(key, IDEMPOTENCY_KEY_MAX, "IDEMPOTENCY_KEY_INVALID", "Idempotency-Key");
    }
}
