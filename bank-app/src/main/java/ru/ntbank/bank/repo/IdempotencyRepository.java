package ru.ntbank.bank.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.ntbank.bank.domain.IdempotencyRecord;

import java.util.Optional;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByUserIdAndScopeAndIdemKey(Long userId, String scope, String idemKey);
}
