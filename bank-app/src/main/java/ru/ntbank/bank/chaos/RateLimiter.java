package ru.ntbank.bank.chaos;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ограничение частоты запросов на пользователя — «корзина токенов».
 * Токены копятся со скоростью rps в секунду, каждый запрос забирает один.
 * Когда корзина пуста, клиент получает 429.
 */
@Component
public class RateLimiter {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public boolean tryAcquire(String key, int rps) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(rps));
        return bucket.tryAcquire(rps);
    }

    public int trackedClients() {
        return buckets.size();
    }

    public void reset() {
        buckets.clear();
    }

    /** Чистим корзины неактивных клиентов, чтобы карта не росла бесконечно. */
    @Scheduled(fixedDelay = 60_000)
    public void evictIdle() {
        long deadline = System.nanoTime() - 120_000_000_000L;
        buckets.entrySet().removeIf(entry -> entry.getValue().lastSeenNanos() < deadline);
    }

    private static final class Bucket {

        private double tokens;
        private long lastRefillNanos = System.nanoTime();

        private Bucket(int rps) {
            this.tokens = rps;
        }

        synchronized boolean tryAcquire(int rps) {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
            lastRefillNanos = now;
            tokens = Math.min(rps, tokens + elapsedSeconds * rps);
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }

        synchronized long lastSeenNanos() {
            return lastRefillNanos;
        }
    }
}
