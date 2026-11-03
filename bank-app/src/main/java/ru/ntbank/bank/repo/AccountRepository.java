package ru.ntbank.bank.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.ntbank.bank.domain.Account;

import java.math.BigDecimal;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findByUserIdOrderByIdAsc(Long userId);

    Optional<Account> findByIdAndUserId(Long id, Long userId);

    Optional<Account> findFirstByUserIdAndCurrencyOrderByIdAsc(Long userId, String currency);

    /** Атомарное списание: вернёт 0, если денег не хватило. Нужно, чтобы под нагрузкой не терялись обновления. */
    @Modifying(flushAutomatically = true)
    @Query("update Account a set a.balance = a.balance - :amount where a.id = :id and a.balance >= :amount")
    int debitIfEnough(@Param("id") Long id, @Param("amount") BigDecimal amount);

    @Modifying(flushAutomatically = true)
    @Query("update Account a set a.balance = a.balance + :amount where a.id = :id")
    int credit(@Param("id") Long id, @Param("amount") BigDecimal amount);
}
