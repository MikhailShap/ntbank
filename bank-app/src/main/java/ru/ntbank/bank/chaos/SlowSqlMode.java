package ru.ntbank.bank.chaos;

/** Как именно «ломается» запрос выписки. */
public enum SlowSqlMode {

    HEAVY_COMPUTE("Тяжёлое вычисление в БД",
            "Условие по счёту завёрнуто в хеш-функцию: индекс не применяется, "
                    + "база читает всю таблицу и считает хеш для каждой строки"),

    NO_INDEX("Нет индекса",
            "Индекс по (account_id, created_at) удаляется из базы. "
                    + "Запрос тот же самый, но выполняется полным перебором таблицы"),

    N_PLUS_ONE("N+1 запросов",
            "Для каждой строки выписки остаток считается отдельным запросом: "
                    + "вместо одного обращения к базе их становится столько же, сколько строк на странице");

    private final String title;
    private final String description;

    SlowSqlMode(String title, String description) {
        this.title = title;
        this.description = description;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public static SlowSqlMode of(String name, SlowSqlMode fallback) {
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
