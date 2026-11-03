package ru.ntbank.bank.async;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.ntbank.bank.chaos.ChaosSettings;
import ru.ntbank.bank.service.DataResetGate;
import ru.ntbank.bank.service.TransferService;

/** Консьюмер событий о переводах: забирает событие из Kafka и завершает перевод. */
@Component
@ConditionalOnProperty(name = "ntbank.resolved.async-mode", havingValue = "kafka")
public class TransferEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransferEventsConsumer.class);

    public static final String LISTENER_ID = "transfer-events";

    private final TransferService transferService;
    private final ChaosSettings chaos;
    private final DataResetGate resetGate;

    public TransferEventsConsumer(TransferService transferService, ChaosSettings chaos,
                                  DataResetGate resetGate) {
        this.transferService = transferService;
        this.chaos = chaos;
        this.resetGate = resetGate;
    }

    @KafkaListener(id = LISTENER_ID,
            topics = "${ntbank.kafka.transfers-topic}",
            groupId = "nt-bank-processor",
            concurrency = "${ntbank.kafka.partitions:3}")
    public void onTransferConfirmed(TransferEvent event) {
        try {
            slowDownIfNeeded();
            resetGate.shared(() -> transferService.process(event.transferId()));
        } catch (Exception e) {
            log.error("Ошибка обработки события по переводу {}: {}", event.transferId(), e.toString());
        }
    }

    /** Дефект «Медленный консьюмер»: держим поток, из-за чего растёт лаг группы. */
    private void slowDownIfNeeded() {
        long delay = chaos.getKafkaConsumerDelayMs();
        if (delay <= 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
