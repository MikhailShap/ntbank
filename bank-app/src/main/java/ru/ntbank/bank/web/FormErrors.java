package ru.ntbank.bank.web;

import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.ntbank.bank.exception.BusinessException;

import java.util.Map;

/**
 * Показывает ошибку рядом с полем формы, к которому она относится.
 * Если ошибка не связана с конкретным полем — плашкой над формой.
 * Текст ошибки в обоих случаях попадает в HTML — на него можно ставить Response Assertion.
 */
final class FormErrors {

    /** Код ошибки бизнес-логики -> имя поля формы. */
    private static final Map<String, String> FIELDS = Map.ofEntries(
            Map.entry("PHONE_INVALID", "phone"),
            Map.entry("RECIPIENT_NOT_FOUND", "phone"),
            Map.entry("SELF_TRANSFER", "phone"),
            Map.entry("AMOUNT_INVALID", "amount"),
            Map.entry("AMOUNT_TOO_SMALL", "amount"),
            Map.entry("COMMENT_TOO_LONG", "comment"),
            Map.entry("PERSONAL_ACCOUNT_TOO_LONG", "personalAccount"),
            Map.entry("TEMPLATE_NAME_TOO_LONG", "templateName"),
            Map.entry("AMOUNT_OUT_OF_RANGE", "amount"),
            Map.entry("LIMIT_EXCEEDED", "amount"),
            Map.entry("INSUFFICIENT_FUNDS", "amount"),
            Map.entry("SAME_ACCOUNT", "toAccountId"),
            Map.entry("ACCOUNT_REQUIRED", "personalAccount"),
            Map.entry("CURRENCY_NOT_SUPPORTED", "accountId"),
            Map.entry("OTP_INVALID", "otp"));

    private FormErrors() {
    }

    static void flash(RedirectAttributes redirect, BusinessException e) {
        String field = FIELDS.get(e.getCode());
        if (field == null) {
            redirect.addFlashAttribute("error", e.getMessage());
        } else {
            redirect.addFlashAttribute("fieldErrors", Map.of(field, e.getMessage()));
        }
    }
}
