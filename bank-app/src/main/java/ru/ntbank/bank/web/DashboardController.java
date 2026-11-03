package ru.ntbank.bank.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import ru.ntbank.bank.domain.Account;
import ru.ntbank.bank.security.AppUserDetails;
import ru.ntbank.bank.service.AccountService;
import ru.ntbank.bank.service.ExchangeRateService;
import ru.ntbank.bank.service.TransferService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Главная страница после входа. */
@Controller
public class DashboardController {

    private final AccountService accounts;
    private final TransferService transfers;
    private final ExchangeRateService rates;

    public DashboardController(AccountService accounts, TransferService transfers,
                               ExchangeRateService rates) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.rates = rates;
    }

    @GetMapping("/dashboard")
    public String dashboard(@AuthenticationPrincipal AppUserDetails me, Model model) {
        List<Account> list = accounts.accountsOf(me.getId());
        Map<Long, BigDecimal> rubEquivalent = new HashMap<>();
        for (Account account : list) {
            rubEquivalent.put(account.getId(),
                    rates.convert(account.getBalance(), account.getCurrency(), "RUB"));
        }

        model.addAttribute("accounts", list);
        model.addAttribute("rubEquivalent", rubEquivalent);
        model.addAttribute("cards", accounts.cardsOf(me.getId()));
        model.addAttribute("operations", accounts.lastOperations(me.getId(), 5));
        model.addAttribute("transfers", transfers.recent(me.getId()));
        model.addAttribute("totalRub", accounts.totalInRub(list));
        model.addAttribute("rateUsd", rates.toRub("USD"));
        model.addAttribute("today", LocalDate.now());
        return "dashboard";
    }
}
