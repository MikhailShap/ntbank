package ru.ntbank.bank.chaos;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.ntbank.bank.exception.ApiErrorWriter;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Дефекты, которые проявляются на уровне HTTP-запроса: задержка, ошибки 500,
 * нагрузка на CPU, утечка памяти и ограничение частоты запросов.
 * <p>
 * Фильтр стоит после цепочки Spring Security, поэтому знает пользователя —
 * это нужно для ограничения частоты запросов «на пользователя».
 * Админка, метрики и статика исключены: иначе включённый дефект
 * не дал бы его же и выключить.
 */
public class ChaosFilter extends OncePerRequestFilter {

    private final ChaosSettings settings;
    private final LeakStore leakStore;
    private final RateLimiter rateLimiter;
    private final ApiErrorWriter errorWriter;

    public ChaosFilter(ChaosSettings settings, LeakStore leakStore, RateLimiter rateLimiter,
                       ApiErrorWriter errorWriter) {
        this.settings = settings;
        this.leakStore = leakStore;
        this.rateLimiter = rateLimiter;
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (excluded(path)) {
            chain.doFilter(request, response);
            return;
        }

        if (settings.isRateLimitEnabled()
                && !rateLimiter.tryAcquire(clientKey(request), settings.getRateLimitRps())) {
            response.setHeader("Retry-After", "1");
            reject(request, response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Превышен лимит запросов, попробуйте позже");
            return;
        }

        if (settings.isLatencyEnabled() && settings.getLatencyTarget().matches(path)) {
            sleep(randomBetween(settings.getLatencyMinMs(), settings.getLatencyMaxMs()));
        }

        if (settings.isCpuEnabled() && settings.getCpuTarget().matches(path)) {
            burnCpu(settings.getCpuMillis());
        }

        if (settings.isLeakEnabled() && settings.getLeakTarget().matches(path)) {
            leakStore.leak(settings.getLeakKbPerRequest());
        }

        if (settings.isErrorsEnabled() && settings.getErrorsTarget().matches(path)
                && ThreadLocalRandom.current().nextInt(100) < settings.getErrorPercent()) {
            reject(request, response, HttpStatus.INTERNAL_SERVER_ERROR, "CHAOS_ERROR",
                    "Намеренная ошибка стенда (включён режим «Дефекты»)");
            return;
        }

        chain.doFilter(request, response);
    }

    /** REST получает ошибку в общем JSON-формате API, веб — страницу ошибки. */
    private void reject(HttpServletRequest request, HttpServletResponse response,
                        HttpStatus status, String code, String message) throws IOException {
        if (request.getRequestURI().startsWith("/api/")) {
            errorWriter.write(request, response, status, code, message);
        } else {
            response.sendError(status.value(), message);
        }
    }

    /** Эти адреса дефекты не трогают. */
    private boolean excluded(String path) {
        return path.startsWith("/admin")
                || path.startsWith("/api/admin")
                || path.startsWith("/api/dev")
                || path.startsWith("/actuator")
                || path.startsWith("/css")
                || path.startsWith("/js")
                || path.startsWith("/favicon")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.equals("/error");
    }

    /** Ключ для ограничения частоты: логин пользователя, а для анонимных — адрес клиента. */
    private String clientKey(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getName())) {
            return "user:" + authentication.getName();
        }
        return "ip:" + request.getRemoteAddr();
    }

    private long randomBetween(long min, long max) {
        return max > min ? ThreadLocalRandom.current().nextLong(min, max + 1) : min;
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Занимаем процессор настоящей работой: считаем хеш в цикле до истечения времени.
     * Результат складываем в поле, чтобы JIT не выбросил вычисления как бесполезные.
     */
    private void burnCpu(int millis) {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        long accumulator = 0;
        while (System.nanoTime() < deadline) {
            for (int i = 0; i < 10_000; i++) {
                accumulator += (long) Math.sqrt(i * 2654435761L % 1_000_003L);
            }
        }
        sink = accumulator;
    }

    @SuppressWarnings("unused")
    private volatile long sink;
}
