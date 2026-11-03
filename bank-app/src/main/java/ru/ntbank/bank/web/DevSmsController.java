package ru.ntbank.bank.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.ntbank.bank.service.SmsService;
import ru.ntbank.bank.service.TransferService;

import java.time.LocalDateTime;

/** Страница «Тестовые СМС» — витрина кодов подтверждения. Доступна без входа. */
@Controller
public class DevSmsController {

    private final SmsService sms;

    public DevSmsController(SmsService sms) {
        this.sms = sms;
    }

    @GetMapping("/dev/sms")
    public String inbox(@RequestParam(required = false) String phone, Model model) {
        String normalized = null;
        if (phone != null && !phone.isBlank()) {
            try {
                normalized = TransferService.normalizePhone(phone);
            } catch (RuntimeException e) {
                normalized = phone.trim();
            }
        }
        model.addAttribute("phone", normalized);
        model.addAttribute("messages", sms.inbox(normalized));
        model.addAttribute("now", LocalDateTime.now());
        return "dev-sms";
    }
}
