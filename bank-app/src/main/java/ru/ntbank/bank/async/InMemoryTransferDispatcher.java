package ru.ntbank.bank.async;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import ru.ntbank.bank.service.DataResetGate;
import ru.ntbank.bank.service.TransferService;

import java.util.UUID;

/**
 * Обработка переводов во внутренней очереди — режим без Kafka.
 * Перевод уходит в PROCESSING и завершается через 1–5 секунд, как и в режиме с Kafka.
 */
public class InMemoryTransferDispatcher implements TransferDispatcher {

    private static final Logger log = LoggerFactory.getLogger(InMemoryTransferDispatcher.class);

    private final ObjectProvider<TransferService> transferService;
    private final TransferDelayScheduler scheduler;
    private final DataResetGate resetGate;

    public InMemoryTransferDispatcher(ObjectProvider<TransferService> transferService,
                                      TransferDelayScheduler scheduler, DataResetGate resetGate) {
        this.transferService = transferService;
        this.scheduler = scheduler;
        this.resetGate = resetGate;
    }

    @Override
    public void dispatch(UUID transferId) {
        scheduler.schedule(() -> {
            try {
                resetGate.shared(() -> transferService.getObject().process(transferId));
            } catch (Exception e) {
                log.error("Не удалось обработать перевод {}: {}", transferId, e.toString());
            }
        });
    }

    @Override
    public String mode() {
        return "memory";
    }
}
