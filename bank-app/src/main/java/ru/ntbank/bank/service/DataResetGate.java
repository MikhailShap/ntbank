package ru.ntbank.bank.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Пауза в работе стенда на время сброса данных.
 * <p>
 * TRUNCATE под нагрузкой встаёт во взаимоблокировки с переводами, которые уже держат одну
 * таблицу и ждут другую. Поэтому сброс сначала дожидается, пока закончатся текущие запросы
 * и обработка переводов, а новые придерживает на те несколько секунд, что идёт пересоздание.
 * Блокировка справедливая: пока сброс ждёт, новые запросы встают в очередь за ним.
 */
@Component
public class DataResetGate {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    /** Обычная работа: запрос или обработка перевода. Таких может идти сколько угодно параллельно. */
    public void enter() {
        lock.readLock().lock();
    }

    public void exit() {
        lock.readLock().unlock();
    }

    public void shared(Runnable action) {
        enter();
        try {
            action.run();
        } finally {
            exit();
        }
    }

    /** Сброс: выполняется, когда никакой другой работы нет. */
    public <T> T exclusive(Supplier<T> action) {
        lock.writeLock().lock();
        try {
            return action.get();
        } finally {
            lock.writeLock().unlock();
        }
    }
}
