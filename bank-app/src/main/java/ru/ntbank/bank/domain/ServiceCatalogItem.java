package ru.ntbank.bank.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Позиция каталога услуг (мобильная связь, интернет, ЖКХ и т.д.). */
@Entity
@Table(name = "service_catalog")
@Getter
@Setter
public class ServiceCatalogItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 40)
    private String category;

    @Column(nullable = false, length = 80)
    private String provider;

    @Column(name = "account_label", nullable = false, length = 60)
    private String accountLabel;

    @Column(name = "min_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal minAmount;

    @Column(name = "max_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal maxAmount;

    @Column(name = "commission_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal commissionPercent = BigDecimal.ZERO;

    @Column(nullable = false)
    private boolean active = true;

    public String icon() {
        return switch (category) {
            case "Мобильная связь" -> "i-mobile";
            case "Интернет и ТВ" -> "i-wifi";
            case "ЖКХ" -> "i-house";
            case "Транспорт" -> "i-transfer";
            case "Образование" -> "i-doc";
            case "Страхование" -> "i-shield";
            case "Налоги и штрафы" -> "i-alert";
            case "Игры и развлечения" -> "i-bolt";
            case "Охрана и безопасность" -> "i-shield";
            default -> "i-receipt";
        };
    }
}
