package ru.ntbank.bank.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import ru.ntbank.bank.exception.ApiErrorWriter;
import ru.ntbank.bank.security.JsonAuthEntryPoint;
import ru.ntbank.bank.security.JwtAuthFilter;
import ru.ntbank.bank.security.JwtService;

/**
 * Два независимых контура безопасности:
 * <ul>
 *   <li>/api/v1/** — stateless, JWT в заголовке Authorization, CSRF выключен;</li>
 *   <li>всё остальное — сессия на cookie JSESSIONID, форма входа, CSRF-токен в каждой форме.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final JsonAuthEntryPoint jsonAuthEntryPoint;

    public SecurityConfig(JwtService jwtService, JsonAuthEntryPoint jsonAuthEntryPoint,
                          ApiErrorWriter apiErrorWriter) {
        this.jwtAuthFilter = new JwtAuthFilter(jwtService, apiErrorWriter);
        this.jsonAuthEntryPoint = jsonAuthEntryPoint;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Админский REST: HTTP Basic (admin/admin), без сессии и CSRF.
     * Отдельной цепочкой — чтобы Basic не перехватывал вход в веб-интерфейс.
     */
    @Bean
    @Order(0)
    public SecurityFilterChain adminApiSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/admin/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("ADMIN"))
                // 401 остаётся с вызовом Basic, чтобы браузер спросил пароль; 403 — в формате API
                .exceptionHandling(ex -> ex.accessDeniedHandler(jsonAuthEntryPoint))
                .httpBasic(basic -> {
                })
                .formLogin(form -> form.disable());
        return http.build();
    }

    /** REST API: JWT, без сессии и без CSRF. */
    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/v1/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(jsonAuthEntryPoint)
                        .accessDeniedHandler(jsonAuthEntryPoint))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable());
        return http.build();
    }

    /** Веб-интерфейс, служебные и админские эндпоинты. */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public SecurityFilterChain webSecurity(HttpSecurity http) throws Exception {
        http
                // CSRF нужен в HTML-формах (его и предстоит коррелировать в JMeter),
                // но мешает служебным REST-вызовам — поэтому для /api/** выключен.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/login", "/register", "/error",
                                "/css/**", "/js/**", "/favicon.ico").permitAll()
                        .requestMatchers("/dev/**", "/api/dev/**").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/swagger-ui", "/swagger-ui/**", "/swagger-ui.html",
                                "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .usernameParameter("login")
                        .passwordParameter("password")
                        .defaultSuccessUrl("/dashboard", true)
                        .failureUrl("/login?error")
                        .permitAll())
                // Выход сделан обычной ссылкой (GET) — так его проще записать рекордером JMeter.
                .logout(logout -> logout
                        .logoutRequestMatcher(request -> "/logout".equals(request.getRequestURI()))
                        .logoutSuccessUrl("/?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .permitAll());
        return http.build();
    }
}
