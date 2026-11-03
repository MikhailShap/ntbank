package ru.ntbank.bank.async;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Событие «перевод подтверждён и ждёт обработки».
 * Уходит в топик Kafka, консьюмер забирает его и завершает перевод.
 */
public record TransferEvent(UUID transferId,
                            Long userId,
                            String toPhone,
                            BigDecimal amount,
                            String currency,
                            LocalDateTime confirmedAt) {
}
