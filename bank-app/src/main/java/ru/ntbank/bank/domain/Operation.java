package ru.ntbank.bank.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Строка истории операций по счёту (то, что видно в выписке). */
@Entity
@Table(name = "operation")
@Getter
@Setter
public class Operation {

    public static final String IN = "IN";
    public static final String OUT = "OUT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id")
    private Account account;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false, length = 4)
    private String direction;

    @Column(nullable = false, length = 24)
    private String kind;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 160)
    private String description;

    @Column(length = 120)
    private String counterparty;

    @Column(nullable = false, length = 16)
    private String status = "OK";

    public boolean incoming() {
        return IN.equals(direction);
    }

    /** Имя иконки из спрайта по типу операции. */
    public String icon() {
        return switch (kind) {
            case "PAYMENT_MOBILE" -> "i-mobile";
            case "PAYMENT_UTILITY" -> "i-house";
            case "PAYMENT" -> "i-receipt";
            case "TRANSFER" -> "i-transfer";
            case "CASH" -> "i-wallet";
            case "SALARY", "INTEREST", "REFUND" -> "i-in";
            default -> incoming() ? "i-in" : "i-out";
        };
    }

    public String kindTitle() {
        return switch (kind) {
            case "SALARY" -> "Поступление";
            case "INTEREST" -> "Проценты по счёту";
            case "REFUND" -> "Возврат";
            case "TRANSFER" -> incoming() ? "Перевод" : "Перевод";
            case "PAYMENT", "PAYMENT_MOBILE", "PAYMENT_UTILITY" -> "Оплата услуг";
            case "CASH" -> "Наличные";
            case "PURCHASE" -> "Покупка";
            case "FEE" -> "Комиссия";
            default -> "Операция";
        };
    }
}
