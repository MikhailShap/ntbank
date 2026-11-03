package ru.ntbank.bank.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.ntbank.bank.domain.Account;
import ru.ntbank.bank.security.AppUserDetails;
import ru.ntbank.bank.service.AccountService;
import ru.ntbank.bank.service.StatementService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Счета и выписка. */
@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "2. Счета и выписка")
public class AccountsApi {

    private final AccountService accounts;
    private final StatementService statements;

    public AccountsApi(AccountService accounts, StatementService statements) {
        this.accounts = accounts;
        this.statements = statements;
    }

    @GetMapping
    @Operation(summary = "Список счетов текущего клиента")
    public List<AccountDto> list(@AuthenticationPrincipal AppUserDetails me) {
        return accounts.accountsOf(me.getId()).stream().map(AccountsApi::toDto).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Один счёт")
    public AccountDto one(@AuthenticationPrincipal AppUserDetails me, @PathVariable Long id) {
        return toDto(accounts.accountOf(me.getId(), id));
    }

    @GetMapping("/{id}/statement")
    @Operation(summary = "Выписка по счёту",
            description = "Фильтр по датам (from, to в формате ГГГГ-ММ-ДД), сортировка "
                    + "date_desc | date_asc | amount_desc | amount_asc, нумерация страниц с нуля")
    public PageDto<OperationDto> statement(
            @AuthenticationPrincipal AppUserDetails me,
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "date_desc") String sort,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        accounts.accountOf(me.getId(), id);
        Page<ru.ntbank.bank.domain.Operation> result =
                statements.statement(id, from, to, sort, page, size);
        return PageDto.of(result, operation -> new OperationDto(
                operation.getId(), operation.getCreatedAt(), operation.getDirection(),
                operation.getKind(), operation.getAmount(), operation.getCurrency(),
                operation.getDescription(), operation.getCounterparty(), operation.getStatus()));
    }

    private static AccountDto toDto(Account account) {
        return new AccountDto(account.getId(), account.getNumber(), account.getTitle(),
                account.getCurrency(), account.getBalance(), account.getOpenedAt(), account.isActive());
    }

    public record AccountDto(Long id, String number, String title, String currency,
                             BigDecimal balance, LocalDate openedAt, boolean active) {
    }

    public record OperationDto(Long id, LocalDateTime createdAt, String direction, String kind,
                               BigDecimal amount, String currency, String description,
                               String counterparty, String status) {
    }
}
