package ru.ntbank.bank.service;

import org.springframework.stereotype.Component;
import ru.ntbank.bank.domain.Operation;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Форматирование денег и дат по-русски.
 * В шаблонах вызывается как ${@fmt.money(...)}.
 */
@Component("fmt")
public class Fmt {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final char NBSP = ' ';

    private final ThreadLocal<DecimalFormat> moneyFormat = ThreadLocal.withInitial(() -> {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols();
        symbols.setGroupingSeparator(NBSP);
        symbols.setDecimalSeparator(',');
        DecimalFormat format = new DecimalFormat("#,##0.00", symbols);
        format.setGroupingUsed(true);
        return format;
    });

    public String symbol(String currency) {
        return switch (currency == null ? "" : currency) {
            case "RUB" -> "₽";
            case "USD" -> "$";
            case "EUR" -> "€";
            default -> currency;
        };
    }

    /** 1 234 567,89 ₽ для рублей и $ 1 250,00 для долларов. */
    public String money(BigDecimal amount, String currency) {
        if (amount == null) {
            return "—";
        }
        String number = moneyFormat.get().format(amount);
        if ("USD".equals(currency) || "EUR".equals(currency)) {
            return symbol(currency) + NBSP + number;
        }
        return number + NBSP + symbol(currency);
    }

    public String amount(BigDecimal value) {
        return value == null ? "—" : moneyFormat.get().format(value);
    }

    /** Сумма со знаком: +128 400,00 ₽ или −750,00 ₽ (минус — типографский). */
    public String signed(Operation operation) {
        String value = money(operation.getAmount(), operation.getCurrency());
        return (operation.incoming() ? "+" : "−") + value;
    }

    public String dateTime(LocalDateTime value) {
        return value == null ? "—" : value.format(DATE_TIME);
    }

    public String date(LocalDateTime value) {
        return value == null ? "—" : value.format(DATE);
    }

    public String date(LocalDate value) {
        return value == null ? "—" : value.format(DATE);
    }

    public String time(LocalDateTime value) {
        return value == null ? "—" : value.format(TIME);
    }

    /** +79000000042 -> +7 900 000 0042 */
    public String phone(String raw) {
        if (raw == null || raw.length() != 12 || !raw.startsWith("+7")) {
            return raw;
        }
        return raw.substring(0, 2) + " " + raw.substring(2, 5) + " "
                + raw.substring(5, 8) + " " + raw.substring(8);
    }
}
