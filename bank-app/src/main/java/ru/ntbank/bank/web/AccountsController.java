package ru.ntbank.bank.web;

import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;
import ru.ntbank.bank.domain.Account;
import ru.ntbank.bank.domain.Operation;
import ru.ntbank.bank.repo.OperationRepository;
import ru.ntbank.bank.security.AppUserDetails;
import ru.ntbank.bank.service.AccountService;
import ru.ntbank.bank.service.ExchangeRateService;
import ru.ntbank.bank.service.StatementService;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Счета, карты и выписка. */
@Controller
public class AccountsController {

    private final AccountService accounts;
    private final StatementService statements;
    private final ExchangeRateService rates;
    private final OperationRepository operations;

    public AccountsController(AccountService accounts, StatementService statements,
                              ExchangeRateService rates, OperationRepository operations) {
        this.accounts = accounts;
        this.statements = statements;
        this.rates = rates;
        this.operations = operations;
    }

    @GetMapping("/accounts")
    public String accounts(@AuthenticationPrincipal AppUserDetails me, Model model) {
        List<Account> list = accounts.accountsOf(me.getId());
        Map<Long, Long> counts = new HashMap<>();
        for (Account account : list) {
            counts.put(account.getId(), operations.countByAccountId(account.getId()));
        }
        model.addAttribute("accounts", list);
        model.addAttribute("operationCount", counts);
        model.addAttribute("cards", accounts.cardsOf(me.getId()));
        model.addAttribute("totalRub", accounts.totalInRub(list));
        model.addAttribute("rates", rates.all());
        return "accounts";
    }

    @GetMapping("/accounts/{id}/statement")
    public String statement(@AuthenticationPrincipal AppUserDetails me,
                            @PathVariable Long id,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                            @RequestParam(required = false, defaultValue = "date_desc") String sort,
                            @RequestParam(required = false, defaultValue = "0") int page,
                            @RequestParam(required = false, defaultValue = "20") int size,
                            Model model) {
        Account account = accounts.accountOf(me.getId(), id);
        Page<Operation> result = statements.statement(id, from, to, sort, page, size);

        UriComponentsBuilder base = UriComponentsBuilder.fromPath("/accounts/" + id + "/statement");
        if (from != null) {
            base.queryParam("from", from);
        }
        if (to != null) {
            base.queryParam("to", to);
        }
        base.queryParam("sort", sort).queryParam("size", size);

        model.addAttribute("account", account);
        model.addAttribute("page", result);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("sort", sort);
        model.addAttribute("size", size);
        model.addAttribute("baseUrl", base.build().encode().toUriString());
        return "statement";
    }
}
