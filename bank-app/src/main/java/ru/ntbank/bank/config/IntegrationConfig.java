package ru.ntbank.bank.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import ru.ntbank.bank.async.*;
import ru.ntbank.bank.client.AntifraudClient;
import ru.ntbank.bank.client.HttpAntifraudClient;
import ru.ntbank.bank.client.LocalAntifraudClient;
import ru.ntbank.bank.repo.TransferRepository;
import ru.ntbank.bank.service.DataResetGate;
import ru.ntbank.bank.service.TransferService;

import java.time.Duration;

/**
 * Выбор реализаций внешних зависимостей.
 * <p>
 * Режимы вычисляются до старта контекста в {@link IntegrationModeResolver} и попадают
 * в свойства ntbank.resolved.*, поэтому облегчённый запуск без Kafka и «Антифрода»
 * работает без правки конфигов.
 */
@Configuration
public class IntegrationConfig {

    private static final Logger log = LoggerFactory.getLogger(IntegrationConfig.class);

    // ------------------------------------------------------- обработка переводов

    @Configuration
    @ConditionalOnProperty(name = "ntbank.resolved.async-mode", havingValue = "kafka")
    static class KafkaMode {

        @Bean
        public NewTopic transfersTopic(AppProperties properties) {
            return TopicBuilder.name(properties.kafka().transfersTopic())
                    .partitions(properties.kafka().partitions())
                    .replicas(1)
                    .build();
        }

        @Bean
        public TransferDispatcher transferDispatcher(KafkaTemplate<String, TransferEvent> kafkaTemplate,
                                                     TransferRepository transfers,
                                                     TransferDelayScheduler scheduler,
                                                     AppProperties properties) {
            log.info("Обработка переводов: Kafka, топик {}", properties.kafka().transfersTopic());
            return new KafkaTransferDispatcher(kafkaTemplate, transfers, scheduler, properties);
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "ntbank.resolved.async-mode", havingValue = "memory",
            matchIfMissing = true)
    static class InMemoryMode {

        @Bean
        public TransferDispatcher transferDispatcher(ObjectProvider<TransferService> transferService,
                                                     TransferDelayScheduler scheduler,
                                                     DataResetGate resetGate) {
            log.info("Обработка переводов: внутренняя очередь в памяти");
            return new InMemoryTransferDispatcher(transferService, scheduler, resetGate);
        }
    }

    // ------------------------------------------------------------------ антифрод

    @Bean
    @ConditionalOnProperty(name = "ntbank.resolved.antifraud-mode", havingValue = "http")
    public AntifraudClient httpAntifraudClient(AppProperties properties) {
        AppProperties.Antifraud settings = properties.antifraud();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(settings.connectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(settings.readTimeoutMs()));

        RestClient restClient = RestClient.builder()
                .baseUrl(settings.url())
                .requestFactory(factory)
                .build();
        log.info("Антифрод: внешний сервис {}", settings.url());
        return new HttpAntifraudClient(restClient);
    }

    @Bean
    @ConditionalOnProperty(name = "ntbank.resolved.antifraud-mode", havingValue = "local",
            matchIfMissing = true)
    public AntifraudClient localAntifraudClient() {
        log.info("Антифрод: локальные правила (внешний сервис не используется)");
        return new LocalAntifraudClient();
    }
}
