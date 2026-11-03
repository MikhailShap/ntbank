package ru.ntbank.bank.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ntbank.bank.domain.ExchangeRate;
import ru.ntbank.bank.repo.ExchangeRateRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Курсы валют НТ-Банка. Хранятся в таблице exchange_rate. */
@Service
public class ExchangeRateService {

    private final ExchangeRateRepository repository;

    public ExchangeRateService(ExchangeRateRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public BigDecimal toRub(String currency) {
        return repository.findById(currency)
                .map(ExchangeRate::getRateToRub)
                .orElse(BigDecimal.ONE);
    }

    /** Сколько единиц валюты "to" дают за единицу валюты "from". */
    @Transactional(readOnly = true)
    public BigDecimal rate(String from, String to) {
        if (from.equals(to)) {
            return BigDecimal.ONE;
        }
        return toRub(from).divide(toRub(to), 4, RoundingMode.HALF_UP);
    }

    @Transactional(readOnly = true)
    /**
     * Пересчёт суммы. Делим в самом конце и округляем только результат: если сначала
     * округлить курс (1 / 92,5 = 0,0108 вместо 0,010810…), 9 250 ₽ превращались
     * в $99,90 вместо $100,00 — клиент терял 0,1% на каждом переводе.
     */
    public BigDecimal convert(BigDecimal amount, String from, String to) {
        if (from.equals(to)) {
            return amount.setScale(2, RoundingMode.HALF_UP);
        }
        return amount.multiply(toRub(from)).divide(toRub(to), 2, RoundingMode.HALF_UP);
    }

    @Transactional(readOnly = true)
    public List<ExchangeRate> all() {
        return repository.findAll();
    }
}
