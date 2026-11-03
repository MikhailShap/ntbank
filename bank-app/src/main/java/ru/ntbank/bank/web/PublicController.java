package ru.ntbank.bank.web;

import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.ntbank.bank.exception.BusinessException;
import ru.ntbank.bank.service.RegistrationService;
import ru.ntbank.bank.web.form.RegisterForm;

/** Публичные страницы: лендинг, вход, регистрация. */
@Controller
public class PublicController {

    private final RegistrationService registrationService;

    public PublicController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @GetMapping("/login")
    public String login(Model model) {
        return "login";
    }

    @GetMapping("/register")
    public String registerForm(Model model) {
        model.addAttribute("form", new RegisterForm());
        return "register";
    }

    @PostMapping("/register")
    public String register(@Valid @ModelAttribute("form") RegisterForm form,
                           BindingResult binding, Model model, RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            return "register";
        }
        try {
            registrationService.register(form.getLogin(), form.getPassword(),
                    form.getFullName(), form.getPhone());
        } catch (BusinessException e) {
            // ошибку показываем у того поля, к которому она относится
            String field = switch (e.getCode()) {
                case "PHONE_INVALID", "PHONE_TAKEN" -> "phone";
                case "LOGIN_TAKEN" -> "login";
                default -> null;
            };
            if (field != null) {
                binding.rejectValue(field, e.getCode(), e.getMessage());
            } else {
                model.addAttribute("error", e.getMessage());
            }
            return "register";
        }
        redirect.addFlashAttribute("registered",
                "Регистрация завершена. Войдите под логином " + form.getLogin());
        return "redirect:/login";
    }
}
