package ru.ntbank.bank.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Перевод. Для типа PHONE живёт по циклу
 * DRAFT (создан, выслан OTP) -> PROCESSING (OTP подтверждён) -> DONE | REJECTED.
 */
@Entity
@Table(name = "transfer")
@Getter
@Setter
public class Transfer {

    @Id
    private UUID id = UUID.randomUUID();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TransferType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransferStatus status = TransferStatus.DRAFT;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private AppUser user;

    /** Только для чтения: нужен там, где нельзя трогать ленивую связь. */
    @Column(name = "user_id", insertable = false, updatable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "from_account_id")
    private Account fromAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_account_id")
    private Account toAccount;

    @Column(name = "to_phone", length = 20)
    private String toPhone;

    @Column(name = "to_user_id")
    private Long toUserId;

    @Column(name = "to_name", length = 160)
    private String toName;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "credited_amount", precision = 19, scale = 2)
    private BigDecimal creditedAmount;

    @Column(name = "credited_currency", length = 3)
    private String creditedCurrency;

    @Column(precision = 12, scale = 4)
    private BigDecimal rate;

    @Column(length = 160)
    private String comment;

    @Column(name = "otp_code", length = 6)
    private String otpCode;

    @Column(name = "otp_attempts", nullable = false)
    private int otpAttempts;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "reject_reason", length = 200)
    private String rejectReason;

    @Column(name = "idempotency_key", length = 120)
    private String idempotencyKey;

    public String shortId() {
        return id.toString().substring(0, 8);
    }
}
