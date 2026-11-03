package ru.ntbank.bank.async;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import ru.ntbank.bank.config.AppProperties;
import ru.ntbank.bank.repo.TransferRepository;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Режим с Kafka: подтверждённый перевод уходит событием в топик,
 * а завершает его консьюмер {@link TransferEventsConsumer}.
 * <p>
 * Событие отправляется не сразу, а через 1–5 секунд — это та самая пауза,
 * которую пользователь видит как статус «В обработке».
 * <p>
 * Если Kafka недоступна, событие не теряется: перевод запоминается и досылается,
 * когда брокер вернётся. Деньги у отправителя к этому моменту уже списаны, поэтому
 * без досылки перевод навсегда остался бы в обработке — ни зачисления, ни возврата.
 */
public class KafkaTransferDispatcher implements TransferDispatcher {

    private static final Logger log = LoggerFactory.getLogger(KafkaTransferDispatcher.class);

    private final KafkaTemplate<String, TransferEvent> kafkaTemplate;
    private final TransferRepository transfers;
    private final TransferDelayScheduler scheduler;
    private final String topic;

    /** Переводы, событие по которым не ушло в Kafka. */
    private final Set<UUID> unsent = ConcurrentHashMap.newKeySet();

    public KafkaTransferDispatcher(KafkaTemplate<String, TransferEvent> kafkaTemplate,
                                   TransferRepository transfers,
                                   TransferDelayScheduler scheduler,
                                   AppProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.transfers = transfers;
        this.scheduler = scheduler;
        this.topic = properties.kafka().transfersTopic();
    }

    @Override
    public void dispatch(UUID transferId) {
        scheduler.schedule(() -> send(transferId));
    }

    /** false — отправка упала сразу, брокер недоступен. */
    private boolean send(UUID transferId) {
        try {
            TransferEvent event = transfers.findById(transferId)
                    .map(transfer -> new TransferEvent(transfer.getId(), transfer.getUserId(),
                            transfer.getToPhone(), transfer.getAmount(), transfer.getCurrency(),
                            transfer.getConfirmedAt()))
                    .orElse(null);
            if (event == null) {
                log.warn("Перевод {} не найден, событие не отправлено", transferId);
                unsent.remove(transferId);
                return true;
            }
            // Отправка асинхронная: при лежащем брокере ошибка приходит через delivery.timeout.ms
            kafkaTemplate.send(topic, transferId.toString(), event).whenComplete((result, error) -> {
                if (error != null) {
                    rememberUnsent(transferId, error);
                } else if (unsent.remove(transferId)) {
                    log.info("Событие по переводу {} дослано в Kafka", transferId);
                }
            });
            return true;
        } catch (Exception e) {
            rememberUnsent(transferId, e);
            return false;
        }
    }

    private void rememberUnsent(UUID transferId, Throwable error) {
        if (unsent.add(transferId)) {
            log.error("Не удалось отправить событие по переводу {}: {}. Перевод ждёт в обработке, "
                    + "событие уйдёт, когда Kafka станет доступна", transferId, error.toString());
        }
    }

    /** Досылка событий, которые не ушли, пока Kafka была недоступна. */
    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    public void resendUnsent() {
        for (UUID transferId : List.copyOf(unsent)) {
            if (!send(transferId)) {
                break; // брокер всё ещё недоступен — остальные подождут следующего круга
            }
        }
    }

    @Override
    public String mode() {
        return "kafka";
    }
}
