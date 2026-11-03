package ru.ntbank.bank.async;

import java.util.UUID;

/**
 * Отправляет подтверждённый перевод на асинхронную обработку.
 * Этап 1 — очередь в памяти, этап 2 — события в Kafka. Для вызывающего кода разницы нет.
 */
public interface TransferDispatcher {

    void dispatch(UUID transferId);

    /** Название режима для админки и логов: memory или kafka. */
    String mode();
}
