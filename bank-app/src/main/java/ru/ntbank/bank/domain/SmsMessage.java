package ru.ntbank.bank.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** «Отправленная» СМС. Реально никуда не уходит — видна на /dev/sms. */
@Entity
@Table(name = "sms_message")
@Getter
@Setter
public class SmsMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(nullable = false, length = 240)
    private String text;

    @Column(length = 6)
    private String code;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
