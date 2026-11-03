package ru.ntbank.bank.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.ntbank.bank.domain.Transfer;
import ru.ntbank.bank.domain.TransferStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByIdAndUserId(UUID id, Long userId);

    List<Transfer> findTop5ByUserIdOrderByCreatedAtDesc(Long userId);

    Page<Transfer> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Optional<Transfer> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    long countByStatus(TransferStatus status);

    @Query("select t.status, count(t) from Transfer t group by t.status")
    List<Object[]> countGroupedByStatus();

    /**
     * Перевод с блокировкой строки (select ... for update). Kafka доставляет сообщения
     * «как минимум один раз»: без блокировки повторная доставка того же события
     * могла бы зачислить деньги дважды.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transfer t where t.id = :id")
    Optional<Transfer> findByIdForUpdate(@Param("id") UUID id);

    /** Переводы, застрявшие в обработке — например, после перезапуска приложения. */
    @Query("select t.id from Transfer t where t.status = :status and t.confirmedAt < :before")
    List<UUID> findIdsByStatusConfirmedBefore(@Param("status") TransferStatus status,
                                             @Param("before") LocalDateTime before);
}
