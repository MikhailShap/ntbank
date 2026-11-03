package ru.ntbank.bank.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import ru.ntbank.bank.security.AppUserDetails;

/** Кладёт текущего пользователя в модель — шапка и меню берут его из ${me}. */
@ControllerAdvice(basePackages = "ru.ntbank.bank.web")
public class LayoutAdvice {

    @ModelAttribute("me")
    public AppUserDetails currentUser(@AuthenticationPrincipal AppUserDetails principal) {
        return principal;
    }
}
