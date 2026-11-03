package ru.ntbank.bank.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.ntbank.bank.domain.ExchangeRate;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, String> {
}
