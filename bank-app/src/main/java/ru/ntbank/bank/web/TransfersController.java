package ru.ntbank.bank.web;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.ntbank.bank.config.AppProperties;
import ru.ntbank.bank.domain.Transfer;
import ru.ntbank.bank.exception.BusinessException;
import ru.ntbank.bank.repo.TransferRepository;
import ru.ntbank.bank.security.AppUserDetails;
import ru.ntbank.bank.service.AccountService;
import ru.ntbank.bank.service.ExchangeRateService;
import ru.ntbank.bank.service.Inputs;
import ru.ntbank.bank.service.TransferService;

import java.util.UUID;

/** Переводы: между своими счетами и по номеру телефона с подтверждением кодом. */
@Controller
@RequestMapping("/transfers")
public class TransfersController {

    private final TransferService transfers;
    private final TransferRepository transferRepository;
    private final AccountService accounts;
    private final ExchangeRateService rates;
    private final AppProperties properties;

    public TransfersController(TransferService transfers, TransferRepository transferRepository,
                               AccountService accounts, ExchangeRateService rates,
                               AppProperties properties) {
        this.transfers = transfers;
        this.transferRepository = transferRepository;
        this.accounts = accounts;
        this.rates = rates;
        this.properties = properties;
    }

    @GetMapping
    public String list(@AuthenticationPrincipal AppUserDetails me,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("page", transferRepository.findByUserIdOrderByCreatedAtDesc(
                me.getId(), PageRequest.of(Math.max(page, 0), 15)));
        return "transfers";
    }

    // ------------------------------------------------------- между своими счетами

    @GetMapping("/own")
    public String ownForm(@AuthenticationPrincipal AppUserDetails me, Model model) {
        model.addAttribute("accounts", accounts.accountsOf(me.getId()));
        model.addAttribute("rateUsd", rates.toRub("USD"));
        return "transfer-own";
    }

    @PostMapping("/own")
    public String own(@AuthenticationPrincipal AppUserDetails me,
                      @RequestParam Long fromAccountId,
                      @RequestParam Long toAccountId,
                      @RequestParam(required = false) String amount,
                      RedirectAttributes redirect) {
        try {
            Transfer transfer = transfers.createOwn(me.getId(), fromAccountId, toAccountId,
                    Inputs.parseAmount(amount));
            redirect.addFlashAttribute("success",
                    "Перевод №" + transfer.getId() + " выполнен");
        } catch (BusinessException e) {
            FormErrors.flash(redirect, e);
            redirect.addFlashAttribute("amount", amount);
            redirect.addFlashAttribute("fromAccountId", fromAccountId);
            redirect.addFlashAttribute("toAccountId", toAccountId);
            return "redirect:/transfers/own";
        }
        return "redirect:/accounts";
    }

    // ------------------------------------------------------- по номеру телефона

    @GetMapping("/phone")
    public String phoneForm(@AuthenticationPrincipal AppUserDetails me,
                            @RequestParam(required = false) Long from, Model model) {
        model.addAttribute("accounts", accounts.accountsOf(me.getId()));
        if (from != null || !model.containsAttribute("selectedAccountId")) {
            // после ошибки счёт уже лежит в модели из flash-атрибутов — не затираем его
            model.addAttribute("selectedAccountId", from);
        }
        model.addAttribute("limit", properties.transfer().limit());
        return "transfer-phone";
    }

    @PostMapping("/phone")
    public String phone(@AuthenticationPrincipal AppUserDetails me,
                        @RequestParam Long fromAccountId,
                        @RequestParam(required = false) String phone,
                        @RequestParam(required = false) String amount,
                        @RequestParam(required = false) String comment,
                        RedirectAttributes redirect) {
        Transfer transfer;
        try {
            transfer = transfers.createPhoneDraft(me.getId(), fromAccountId, phone,
                    Inputs.parseAmount(amount), comment, null);
        } catch (BusinessException e) {
            FormErrors.flash(redirect, e);
            redirect.addFlashAttribute("selectedAccountId", fromAccountId);
            redirect.addFlashAttribute("phone", phone);
            redirect.addFlashAttribute("amount", amount);
            redirect.addFlashAttribute("comment", comment);
            return "redirect:/transfers/phone";
        }
        redirect.addFlashAttribute("info",
                "Код подтверждения отправлен по СМС. Посмотреть его можно на странице «Тестовые СМС».");
        return "redirect:/transfers/" + transfer.getId();
    }

    // ------------------------------------------------------- просмотр и подтверждение

    @GetMapping("/{id}")
    public String view(@AuthenticationPrincipal AppUserDetails me,
                       @PathVariable UUID id, Model model) {
        model.addAttribute("transfer", transfers.find(me.getId(), id));
        model.addAttribute("maxAttempts", properties.otp().maxAttempts());
        return "transfer-view";
    }

    @PostMapping("/{id}/confirm")
    public String confirm(@AuthenticationPrincipal AppUserDetails me,
                          @PathVariable UUID id,
                          @RequestParam(required = false) String otp,
                          RedirectAttributes redirect) {
        try {
            Transfer transfer = transfers.confirm(me.getId(), id, otp);
            redirect.addFlashAttribute("success",
                    "Перевод №" + transfer.getId() + " отправлен на обработку");
        } catch (BusinessException e) {
            FormErrors.flash(redirect, e);
        }
        return "redirect:/transfers/" + id;
    }
}
