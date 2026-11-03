package ru.ntbank.bank.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "card")
@Getter
@Setter
public class Card {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "account_id")
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @Column(name = "pan_masked", nullable = false, length = 25)
    private String panMasked;

    @Column(nullable = false, length = 160)
    private String holder;

    @Column(nullable = false, length = 5)
    private String expires;

    @Column(nullable = false, length = 16)
    private String kind = "DEBIT";

    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";
}
