package ru.ntbank.antifraud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.math.BigDecimal;

/**
 * «Антифрод» — заглушка внешней системы, к которой банк ходит по HTTP.
 * <p>
 * Сервис намеренно простой: он нужен как внешняя зависимость, которую можно затормозить
 * или уронить и посмотреть, что при этом происходит с банком под нагрузкой.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AntifraudApplication {

    public static void main(String[] args) {
        SpringApplication.run(AntifraudApplication.class, args);
    }

    @ConfigurationProperties(prefix = "antifraud")
    public record AntifraudProperties(BigDecimal thresholdAmount,
                                      int baseRejectPercent,
                                      long baseLatencyMs) {
    }
}
