package ru.ntbank.bank.exception;

import java.time.LocalDateTime;
import java.util.Map;

/** Тело ошибки REST API. Поля стабильные — на них удобно ставить проверки в JMeter. */
public record ApiError(LocalDateTime timestamp, int status, String error, String code,
                       String message, String path, Map<String, String> fieldErrors) {
}
