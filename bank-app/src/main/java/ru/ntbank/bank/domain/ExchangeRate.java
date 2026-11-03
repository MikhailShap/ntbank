package ru.ntbank.bank.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "exchange_rate")
@Getter
@Setter
public class ExchangeRate {

    @Id
    @Column(length = 3)
    private String currency;

    @Column(name = "rate_to_rub", nullable = false, precision = 12, scale = 4)
    private BigDecimal rateToRub;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
