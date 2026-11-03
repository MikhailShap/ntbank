package ru.ntbank.bank.async;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ru.ntbank.bank.domain.TransferStatus;
import ru.ntbank.bank.repo.TransferRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Досылка переводов, застрявших в статусе PROCESSING после перезапуска.
 * <p>
 * Событие в обработку уходит с задержкой 1–5 секунд, и если приложение остановилось
 * в этот момент, событие теряется. При старте такие переводы отправляются заново.
 * Повторная обработка безопасна: process() блокирует строку и пропускает
 * переводы, которые уже получили финальный статус.
 */
@Component
public class TransferRecovery {

    private static final Logger log = LoggerFactory.getLogger(TransferRecovery.class);

    private final TransferRepository transfers;
    private final TransferDispatcher dispatcher;

    public TransferRecovery(TransferRepository transfers, TransferDispatcher dispatcher) {
        this.transfers = transfers;
        this.dispatcher = dispatcher;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void redispatchStuckTransfers() {
        List<UUID> stuck = transfers.findIdsByStatusConfirmedBefore(
                TransferStatus.PROCESSING, LocalDateTime.now().minusSeconds(30));
        if (stuck.isEmpty()) {
            return;
        }
        stuck.forEach(dispatcher::dispatch);
        log.info("Отправлено заново {} переводов, застрявших в обработке после перезапуска", stuck.size());
    }
}
