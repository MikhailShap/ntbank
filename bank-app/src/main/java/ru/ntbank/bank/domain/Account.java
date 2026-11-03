package ru.ntbank.bank.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "account")
@Getter
@Setter
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @Column(nullable = false, unique = true, length = 20)
    private String number;

    @Column(nullable = false, length = 60)
    private String title;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "opened_at", nullable = false)
    private LocalDate openedAt = LocalDate.now();

    @Column(nullable = false)
    private boolean active = true;

    public String last4() {
        return number.length() <= 4 ? number : number.substring(number.length() - 4);
    }

    public String masked() {
        return "•••• " + last4();
    }
}
