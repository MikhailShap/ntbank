package ru.ntbank.bank.web.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegisterForm {

    @NotBlank(message = "Укажите логин")
    @Size(min = 4, max = 32, message = "Логин должен быть от 4 до 32 символов")
    @Pattern(regexp = "[A-Za-z0-9_.-]+", message = "Только латиница, цифры и символы . _ -")
    private String login;

    @NotBlank(message = "Укажите пароль")
    @Size(min = 6, max = 72, message = "Пароль должен быть не короче 6 символов")
    private String password;

    @NotBlank(message = "Укажите ФИО")
    @Size(min = 3, max = 160, message = "ФИО должно быть от 3 до 160 символов")
    private String fullName;

    @NotBlank(message = "Укажите телефон")
    @Size(min = 10, max = 20, message = "Телефон указан неверно")
    private String phone;
}
