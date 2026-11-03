package ru.ntbank.bank.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.ui.Model;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import ru.ntbank.bank.exception.BusinessException;

/**
 * Ошибки на веб-страницах показываем аккуратной страницей, а не стектрейсом.
 * <p>
 * Код ответа обязательно соответствует ошибке: страница «не найдено» с кодом 200
 * в JMeter выглядела бы как успешный запрос.
 */
@ControllerAdvice(basePackages = "ru.ntbank.bank.web")
public class WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(WebExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public String business(BusinessException e, Model model, HttpServletResponse response) {
        return page(response, model, e.getStatus().value(), e.getMessage());
    }

    /** Неверный тип параметра: например, /transfers/abc вместо идентификатора перевода. */
    @ExceptionHandler(TypeMismatchException.class)
    public String typeMismatch(TypeMismatchException e, Model model, HttpServletResponse response) {
        return page(response, model, 400,
                "Неверное значение параметра «" + e.getPropertyName() + "»");
    }

    /** Поле формы не отправлено совсем: так бывает в JMeter, если потерять параметр при записи. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public String missingParameter(MissingServletRequestParameterException e, Model model,
                                   HttpServletResponse response) {
        return page(response, model, 400, "Не заполнено поле «" + e.getParameterName() + "»");
    }

    @ExceptionHandler(Exception.class)
    public String unexpected(Exception e, HttpServletRequest request, Model model,
                             HttpServletResponse response) {
        // Ошибки самого Spring MVC (нет параметра, не тот метод, нет страницы) знают свой код
        if (e instanceof ErrorResponse errorResponse) {
            int status = errorResponse.getStatusCode().value();
            String detail = errorResponse.getBody().getDetail();
            return page(response, model, status, detail != null ? detail : "Запрос не может быть обработан");
        }
        log.error("Ошибка при обработке {} {}", request.getMethod(), request.getRequestURI(), e);
        return page(response, model, 500, "Внутренняя ошибка сервера: " + e.getMessage());
    }

    private String page(HttpServletResponse response, Model model, int status, String message) {
        response.setStatus(status);
        model.addAttribute("status", status);
        model.addAttribute("message", message);
        return "error";
    }
}
