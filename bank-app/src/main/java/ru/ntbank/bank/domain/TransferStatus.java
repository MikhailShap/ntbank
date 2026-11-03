package ru.ntbank.bank.domain;

/** Статусы перевода. DRAFT -> PROCESSING -> DONE | REJECTED. */
public enum TransferStatus {
    DRAFT,
    PROCESSING,
    DONE,
    REJECTED,
    EXPIRED;

    public boolean isFinal() {
        return this == DONE || this == REJECTED || this == EXPIRED;
    }

    public String titleRu() {
        return switch (this) {
            case DRAFT -> "Ожидает подтверждения";
            case PROCESSING -> "В обработке";
            case DONE -> "Выполнен";
            case REJECTED -> "Отклонён";
            case EXPIRED -> "Истёк";
        };
    }

    /** CSS-модификатор бейджа для шаблонов. */
    public String badgeClass() {
        return switch (this) {
            case DRAFT -> "badge--muted";
            case PROCESSING -> "badge--warn";
            case DONE -> "badge--ok";
            case REJECTED, EXPIRED -> "badge--danger";
        };
    }
}
