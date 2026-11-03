package ru.ntbank.bank.async;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import ru.ntbank.bank.config.AppProperties;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Единая задержка обработки перевода: 1–5 секунд, как видит её клиент.
 * Используется обоими режимами — и очередью в памяти, и Kafka,
 * поэтому поведение стенда не зависит от того, поднят ли брокер.
 */
@Component
public class TransferDelayScheduler {

    private final AppProperties properties;
    private final ScheduledExecutorService executor =
            Executors.newScheduledThreadPool(4, runnable -> {
                Thread thread = new Thread(runnable, "transfer-delay");
                thread.setDaemon(true);
                return thread;
            });

    public TransferDelayScheduler(AppProperties properties) {
        this.properties = properties;
    }

    public void schedule(Runnable task) {
        executor.schedule(task, nextDelayMs(), TimeUnit.MILLISECONDS);
    }

    public long nextDelayMs() {
        long min = properties.transfer().minDelayMs();
        long max = Math.max(properties.transfer().maxDelayMs(), min + 1);
        return ThreadLocalRandom.current().nextLong(min, max);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
