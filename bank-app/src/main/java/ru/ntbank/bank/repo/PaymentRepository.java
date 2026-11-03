package ru.ntbank.bank.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.ntbank.bank.domain.Payment;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findTop10ByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<Payment> findByIdAndUserId(Long id, Long userId);

    Optional<Payment> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
}
