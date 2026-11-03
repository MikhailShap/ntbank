package ru.ntbank.bank.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.ntbank.bank.exception.ApiErrorWriter;

import java.io.IOException;

/**
 * Достаёт Bearer-токен из заголовка Authorization и кладёт пользователя в SecurityContext.
 * Бином объявлен в SecurityConfig, а не через @Component — иначе Spring Boot зарегистрировал бы
 * его ещё и как глобальный сервлет-фильтр, в том числе для страниц веб-интерфейса.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final ApiErrorWriter errorWriter;

    public JwtAuthFilter(JwtService jwtService, ApiErrorWriter errorWriter) {
        this.jwtService = jwtService;
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(7).trim();
        try {
            Claims claims = jwtService.parse(token);
            if (!jwtService.isAccess(claims)) {
                reject(request, response, "WRONG_TOKEN_TYPE", "Нужен access-токен, получен refresh");
                return;
            }
            AppUserDetails principal = jwtService.toPrincipal(claims);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtException | IllegalArgumentException e) {
            reject(request, response, "INVALID_TOKEN", "Токен недействителен или истёк");
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response,
                        String code, String message) throws IOException {
        errorWriter.write(request, response, HttpStatus.UNAUTHORIZED, code, message);
    }
}
