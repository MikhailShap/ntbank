package ru.ntbank.bank.chaos;

import org.springframework.stereotype.Component;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Утечка памяти: каждый запрос оставляет здесь кусок данных, который никто не удаляет.
 * Ровно так и ведут себя настоящие утечки — объект «забыли» в долгоживущей коллекции.
 * При длительном тесте приводит к росту heap, частым сборкам мусора и в итоге к OutOfMemoryError.
 */
@Component
public class LeakStore {

    private final Queue<byte[]> leaked = new ConcurrentLinkedQueue<>();
    private final AtomicLong leakedBytes = new AtomicLong();
    /** Когда утечка началась (после последней очистки), 0 — ещё не начиналась. */
    private final AtomicLong startedAtMillis = new AtomicLong();

    public void leak(int kilobytes) {
        byte[] chunk = new byte[kilobytes * 1024];
        // заполняем, чтобы JIT не выбросил «неиспользуемый» массив, а страницы реально выделились
        for (int i = 0; i < chunk.length; i += 4096) {
            chunk[i] = 1;
        }
        leaked.add(chunk);
        leakedBytes.addAndGet(chunk.length);
        startedAtMillis.compareAndSet(0, System.currentTimeMillis());
    }

    public long leakedBytes() {
        return leakedBytes.get();
    }

    public int chunks() {
        return leaked.size();
    }

    /** Средняя скорость роста утечки в байтах в секунду с момента её начала. */
    public double bytesPerSecond() {
        long started = startedAtMillis.get();
        if (started == 0) {
            return 0;
        }
        double seconds = Math.max(1, (System.currentTimeMillis() - started) / 1000.0);
        return leakedBytes.get() / seconds;
    }

    public void clear() {
        leaked.clear();
        leakedBytes.set(0);
        startedAtMillis.set(0);
    }
}
