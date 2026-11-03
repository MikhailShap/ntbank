package ru.ntbank.bank.web;

import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;
import ru.ntbank.bank.domain.Account;
import ru.ntbank.bank.domain.Payment;
import ru.ntbank.bank.domain.ServiceCatalogItem;
import ru.ntbank.bank.exception.BusinessException;
import ru.ntbank.bank.security.AppUserDetails;
import ru.ntbank.bank.service.AccountService;
import ru.ntbank.bank.service.Fmt;
import ru.ntbank.bank.service.Inputs;
import ru.ntbank.bank.service.PaymentService;

import java.math.BigDecimal;
import java.util.List;

/** Каталог услуг, оплата и шаблоны. */
@Controller
public class PaymentsController {

    private final PaymentService payments;
    private final AccountService accounts;
    private final Fmt fmt;

    public PaymentsController(PaymentService payments, AccountService accounts, Fmt fmt) {
        this.payments = payments;
        this.accounts = accounts;
        this.fmt = fmt;
    }

    @GetMapping("/payments")
    public String catalog(@RequestParam(required = false) String query,
                          @RequestParam(required = false) String category,
                          @RequestParam(defaultValue = "0") int page,
                          Model model) {
        Page<ServiceCatalogItem> result = payments.search(query, category, page, PaymentService.CATALOG_PAGE_SIZE);

        UriComponentsBuilder base = UriComponentsBuilder.fromPath("/payments");
        if (query != null && !query.isBlank()) {
            base.queryParam("query", query);
        }
        if (category != null && !category.isBlank()) {
            base.queryParam("category", category);
        }

        model.addAttribute("page", result);
        model.addAttribute("query", query);
        model.addAttribute("category", category);
        model.addAttribute("categories", payments.categories());
        // encode(): «&», «#» и пробелы в поиске иначе ломали бы ссылки пагинации
        model.addAttribute("baseUrl", base.build().encode().toUriString());
        return "payments";
    }

    @GetMapping("/payments/{serviceId}")
    public String form(@AuthenticationPrincipal AppUserDetails me,
                       @PathVariable Long serviceId, Model model) {
        model.addAttribute("service", payments.service(serviceId));
        model.addAttribute("accounts", rubAccounts(me.getId()));
        return "payment-form";
    }

    @PostMapping("/payments/{serviceId}")
    public String pay(@AuthenticationPrincipal AppUserDetails me,
                      @PathVariable Long serviceId,
                      @RequestParam Long accountId,
                      @RequestParam(required = false) String personalAccount,
                      @RequestParam(required = false) String amount,
                      @RequestParam(required = false) Boolean saveTemplate,
                      @RequestParam(required = false) String templateName,
                      RedirectAttributes redirect) {
        try {
            BigDecimal sum = Inputs.parseAmount(amount);
            Payment payment = payments.pay(me.getId(), serviceId, accountId, personalAccount, sum, null,
                    Boolean.TRUE.equals(saveTemplate), templateName);
            redirect.addFlashAttribute("success",
                    "Платёж №" + payment.getId() + " на сумму "
                            + fmt.money(sum, "RUB") + " выполнен");
            return "redirect:/payments";
        } catch (BusinessException e) {
            FormErrors.flash(redirect, e);
            redirect.addFlashAttribute("selectedAccountId", accountId);
            redirect.addFlashAttribute("personalAccount", personalAccount);
            redirect.addFlashAttribute("amount", amount);
            redirect.addFlashAttribute("saveTemplate", Boolean.TRUE.equals(saveTemplate));
            redirect.addFlashAttribute("templateName", templateName);
            return "redirect:/payments/" + serviceId;
        }
    }

    // ------------------------------------------------------------------ шаблоны

    @GetMapping("/templates")
    public String templates(@AuthenticationPrincipal AppUserDetails me, Model model) {
        model.addAttribute("templates", payments.templates(me.getId()));
        return "templates";
    }

    @PostMapping("/templates/{id}/pay")
    public String payByTemplate(@AuthenticationPrincipal AppUserDetails me,
                                @PathVariable Long id, RedirectAttributes redirect) {
        try {
            Payment payment = payments.payByTemplate(me.getId(), id, null);
            redirect.addFlashAttribute("success",
                    "Платёж №" + payment.getId() + " по шаблону выполнен");
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/templates";
    }

    @PostMapping("/templates/{id}/delete")
    public String deleteTemplate(@AuthenticationPrincipal AppUserDetails me,
                                 @PathVariable Long id, RedirectAttributes redirect) {
        try {
            payments.deleteTemplate(me.getId(), id);
            redirect.addFlashAttribute("success", "Шаблон удалён");
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/templates";
    }

    private List<Account> rubAccounts(Long userId) {
        return accounts.accountsOf(userId).stream()
                .filter(account -> "RUB".equals(account.getCurrency()))
                .toList();
    }
}
