package ru.ntbank.bank.exception;

import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ошибки REST, до которых не дошёл ни один контроллер: неизвестный адрес (404), не тот метод (405),
 * вход в админский REST без пароля (401). Spring Boot отдаёт их через /error в своём формате —
 * здесь для адресов /api/** формат приводится к {@link ApiError}, как у остальных ошибок API.
 * Страницы веб-интерфейса не затрагиваются.
 */
@Component
public class ApiErrorAttributes extends DefaultErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
        Map<String, Object> attributes = super.getErrorAttributes(webRequest, options);
        if (!(attributes.get("path") instanceof String path) || !path.startsWith("/api/")) {
            return attributes;
        }
        int status = attributes.get("status") instanceof Integer value ? value : 500;
        HttpStatus httpStatus = HttpStatus.resolve(status);
        String method = webRequest instanceof ServletWebRequest servlet
                ? servlet.getRequest().getMethod() : "";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", status);
        body.put("error", httpStatus != null ? httpStatus.getReasonPhrase() : "Error");
        body.put("code", httpStatus != null ? httpStatus.name() : "ERROR");
        body.put("message", message(status, method, path));
        body.put("path", path);
        return body;
    }

    private static String message(int status, String method, String path) {
        return switch (status) {
            case 400 -> "Неверный запрос";
            case 401 -> path.startsWith("/api/admin")
                    ? "Нужен вход администратора: заголовок Authorization: Basic (admin / admin)"
                    : "Требуется заголовок Authorization: Bearer <token>";
            case 403 -> "Недостаточно прав для этой операции";
            case 404 -> "Адрес не найден: " + method + " " + path;
            case 405 -> "Метод " + method + " не поддерживается для этого адреса";
            case 415 -> "Тело запроса должно быть JSON: нужен заголовок Content-Type: application/json";
            default -> status >= 500 ? "Внутренняя ошибка сервера" : "Запрос не может быть обработан";
        };
    }
}
