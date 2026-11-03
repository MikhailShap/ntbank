package ru.ntbank.bank.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ntbank.bank.chaos.ChaosFilter;
import ru.ntbank.bank.chaos.ChaosSettings;
import ru.ntbank.bank.chaos.LeakStore;
import ru.ntbank.bank.chaos.RateLimiter;
import ru.ntbank.bank.exception.ApiErrorWriter;

/**
 * Регистрация фильтра дефектов.
 * <p>
 * Порядок 0 — после цепочки Spring Security (она регистрируется с порядком -100),
 * чтобы ограничение частоты запросов знало, от какого пользователя пришёл запрос.
 */
@Configuration
public class ChaosFilterConfig {

    @Bean
    public FilterRegistrationBean<ChaosFilter> chaosFilterRegistration(ChaosSettings settings,
                                                                       LeakStore leakStore,
                                                                       RateLimiter rateLimiter,
                                                                       ApiErrorWriter errorWriter) {
        FilterRegistrationBean<ChaosFilter> registration =
                new FilterRegistrationBean<>(new ChaosFilter(settings, leakStore, rateLimiter, errorWriter));
        registration.setName("chaosFilter");
        registration.addUrlPatterns("/*");
        registration.setOrder(0);
        return registration;
    }
}
