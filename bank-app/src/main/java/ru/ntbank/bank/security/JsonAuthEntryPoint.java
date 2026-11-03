package ru.ntbank.bank.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import ru.ntbank.bank.exception.ApiErrorWriter;

import java.io.IOException;

/** 401/403 в JSON вместо редиректа на форму входа — для REST-контура. */
@Component
public class JsonAuthEntryPoint implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ApiErrorWriter errorWriter;

    public JsonAuthEntryPoint(ApiErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        errorWriter.write(request, response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                "Требуется заголовок Authorization: Bearer <token>");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        errorWriter.write(request, response, HttpStatus.FORBIDDEN, "FORBIDDEN",
                "Недостаточно прав для этой операции");
    }
}
