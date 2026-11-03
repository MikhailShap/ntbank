package ru.ntbank.bank.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ntbank.bank.domain.Account;
import ru.ntbank.bank.domain.AppUser;
import ru.ntbank.bank.domain.Card;
import ru.ntbank.bank.exception.BusinessException;
import ru.ntbank.bank.repo.AccountRepository;
import ru.ntbank.bank.repo.AppUserRepository;
import ru.ntbank.bank.repo.CardRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Регистрация нового клиента: пользователь + рублёвый и долларовый счета + карта. */
@Service
public class RegistrationService {

    private static final BigDecimal START_RUB = new BigDecimal("50000.00");
    private static final BigDecimal START_USD = new BigDecimal("500.00");

    private final AppUserRepository users;
    private final AccountRepository accounts;
    private final CardRepository cards;
    private final PasswordEncoder passwordEncoder;

    public RegistrationService(AppUserRepository users, AccountRepository accounts,
                               CardRepository cards, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.accounts = accounts;
        this.cards = cards;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AppUser register(String login, String password, String fullName, String rawPhone) {
        String phone = TransferService.normalizePhone(rawPhone);
        if (users.existsByLogin(login)) {
            throw BusinessException.conflict("LOGIN_TAKEN", "Логин " + login + " уже занят");
        }
        if (users.existsByPhone(phone)) {
            throw BusinessException.conflict("PHONE_TAKEN", "Этот номер телефона уже зарегистрирован");
        }

        AppUser user = new AppUser();
        user.setLogin(login);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setFullName(fullName.trim());
        user.setPhone(phone);
        user.setRole(AppUser.ROLE_USER);
        user.setEnabled(true);
        user.setCreatedAt(LocalDateTime.now());
        try {
            users.save(user);
            users.flush();
        } catch (DataIntegrityViolationException e) {
            // проверка выше не спасает от одновременной регистрации одного логина —
            // её ловит уникальный индекс, и это тот же конфликт, а не внутренняя ошибка
            throw BusinessException.conflict("LOGIN_TAKEN",
                    "Логин или телефон уже заняты, выберите другие");
        }

        Account rub = openAccount(user, "40817810", user.getId(), "Текущий счёт", "RUB", START_RUB);
        openAccount(user, "40817840", user.getId() * 31 + 7, "Валютный счёт", "USD", START_USD);

        Card card = new Card();
        card.setAccount(rub);
        card.setUser(user);
        card.setPanMasked("5536 •••• •••• " + rub.last4());
        card.setHolder(user.getFullName());
        card.setExpires(String.format("%02d/%d", 1 + (rub.getId() % 12), 28 + (rub.getId() % 3)));
        card.setKind("DEBIT");
        card.setStatus("ACTIVE");
        cards.save(card);

        return user;
    }

    private Account openAccount(AppUser user, String prefix, long tail, String title,
                                String currency, BigDecimal balance) {
        Account account = new Account();
        account.setUser(user);
        account.setNumber(prefix + String.format("%012d", tail));
        account.setTitle(title);
        account.setCurrency(currency);
        account.setBalance(balance);
        account.setOpenedAt(LocalDate.now());
        account.setActive(true);
        accounts.save(account);
        accounts.flush();
        return account;
    }
}
