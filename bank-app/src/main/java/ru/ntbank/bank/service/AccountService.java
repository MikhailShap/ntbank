package ru.ntbank.bank.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ntbank.bank.domain.Account;
import ru.ntbank.bank.domain.Card;
import ru.ntbank.bank.domain.Operation;
import ru.ntbank.bank.exception.BusinessException;
import ru.ntbank.bank.repo.AccountRepository;
import ru.ntbank.bank.repo.CardRepository;
import ru.ntbank.bank.repo.OperationRepository;

import java.math.BigDecimal;
import java.util.List;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final CardRepository cards;
    private final OperationRepository operations;
    private final ExchangeRateService rates;

    public AccountService(AccountRepository accounts, CardRepository cards,
                          OperationRepository operations, ExchangeRateService rates) {
        this.accounts = accounts;
        this.cards = cards;
        this.operations = operations;
        this.rates = rates;
    }

    @Transactional(readOnly = true)
    public List<Account> accountsOf(Long userId) {
        return accounts.findByUserIdOrderByIdAsc(userId);
    }

    @Transactional(readOnly = true)
    public Account accountOf(Long userId, Long accountId) {
        return accounts.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> BusinessException.notFound("Счёт №" + accountId + " не найден"));
    }

    @Transactional(readOnly = true)
    public List<Card> cardsOf(Long userId) {
        return cards.findByUserIdOrderByIdAsc(userId);
    }

    @Transactional(readOnly = true)
    public List<Operation> lastOperations(Long userId, int limit) {
        List<Long> ids = accounts.findByUserIdOrderByIdAsc(userId).stream().map(Account::getId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return operations.recent(ids, PageRequest.of(0, limit));
    }

    /** Суммарный баланс всех счетов в рублях — для плашки на главной. */
    @Transactional(readOnly = true)
    public BigDecimal totalInRub(List<Account> list) {
        BigDecimal total = BigDecimal.ZERO;
        for (Account account : list) {
            total = total.add(rates.convert(account.getBalance(), account.getCurrency(), "RUB"));
        }
        return total;
    }
}
