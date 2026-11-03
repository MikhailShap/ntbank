package ru.ntbank.bank.chaos;

import java.util.function.Predicate;

/**
 * К каким запросам применять дефект.
 * Набор фиксированный — так на странице это выпадающий список, а не поле для ошибок.
 */
public enum ChaosTarget {

    ALL("Все запросы", path -> true),
    WEB("Только веб-страницы", path -> !path.startsWith("/api/")),
    API("Только REST API", path -> path.startsWith("/api/v1/")),
    LOGIN("Вход: /login", path -> path.equals("/login")),
    DASHBOARD("Главная: /dashboard", path -> path.startsWith("/dashboard")),
    ACCOUNTS("Счета: /accounts", path -> path.startsWith("/accounts") || path.startsWith("/api/v1/accounts")),
    STATEMENT("Выписка: /accounts/*/statement", path -> path.contains("/statement")),
    TRANSFERS("Переводы: /transfers", path -> path.startsWith("/transfers") || path.startsWith("/api/v1/transfers")),
    PAYMENTS("Платежи: /payments", path -> path.startsWith("/payments") || path.startsWith("/api/v1/payments"));

    private final String title;
    private final Predicate<String> matcher;

    ChaosTarget(String title, Predicate<String> matcher) {
        this.title = title;
        this.matcher = matcher;
    }

    public String getTitle() {
        return title;
    }

    public boolean matches(String path) {
        return matcher.test(path);
    }

    public static ChaosTarget of(String name, ChaosTarget fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
