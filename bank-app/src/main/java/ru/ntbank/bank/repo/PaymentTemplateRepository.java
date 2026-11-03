package ru.ntbank.bank.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.ntbank.bank.domain.PaymentTemplate;

import java.util.List;
import java.util.Optional;

public interface PaymentTemplateRepository extends JpaRepository<PaymentTemplate, Long> {

    List<PaymentTemplate> findByUserIdOrderByIdDesc(Long userId);

    Optional<PaymentTemplate> findByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);
}
