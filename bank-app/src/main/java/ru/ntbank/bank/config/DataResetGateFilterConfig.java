package ru.ntbank.bank.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.ntbank.bank.service.DataResetGate;

import java.io.IOException;

/**
 * Пропускает запросы через {@link DataResetGate}: во время сброса данных они ждут его окончания,
 * а не падают с ошибками. Порядок -101 — раньше цепочки Spring Security, потому что она тоже
 * читает пользователей из базы.
 * <p>
 * Админка, служебные адреса, метрики и статика идут мимо: сброс запускается именно оттуда
 * и не должен ждать сам себя.
 */
@Configuration
public class DataResetGateFilterConfig {

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> dataResetGateFilter(DataResetGate gate) {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                String path = request.getRequestURI();
                return path.startsWith("/admin")
                        || path.startsWith("/api/admin")
                        || path.startsWith("/api/dev")
                        || path.startsWith("/actuator")
                        || path.startsWith("/css")
                        || path.startsWith("/js")
                        || path.startsWith("/favicon")
                        || path.startsWith("/swagger-ui")
                        || path.startsWith("/v3/api-docs");
            }

            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain) throws ServletException, IOException {
                gate.enter();
                try {
                    chain.doFilter(request, response);
                } finally {
                    gate.exit();
                }
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setName("dataResetGateFilter");
        registration.addUrlPatterns("/*");
        registration.setOrder(-101);
        return registration;
    }
}
