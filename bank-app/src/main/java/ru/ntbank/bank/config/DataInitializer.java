package ru.ntbank.bank.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import ru.ntbank.bank.service.SeedService;

/**
 * При первом старте наполняет базу тестовыми данными.
 * Если данные уже есть (перезапуск контейнера), только перезаписывает test-data/users.csv.
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final SeedService seedService;
    private final AppProperties properties;

    public DataInitializer(SeedService seedService, AppProperties properties) {
        this.seedService = seedService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.seed().enabled()) {
            log.info("Генерация тестовых данных выключена (ntbank.seed.enabled=false)");
            return;
        }
        if (seedService.isEmpty()) {
            log.info("База пуста — создаю {} тестовых пользователей...", properties.seed().users());
            SeedService.SeedResult result = seedService.reset();
            log.info("Готово: пользователей {}, счетов {}, операций {}, услуг {} ({} мс)",
                    result.users(), result.accounts(), result.operations(),
                    result.services(), result.elapsedMs());
            seedService.growToConfiguredTotal();
        } else {
            log.info("Тестовые данные уже есть, повторная генерация не нужна");
            seedService.writeCsv();
            // если SEED_OPERATIONS_TOTAL увеличили и перезапустили контейнер — дорастим историю
            seedService.growToConfiguredTotal();
        }
    }
}
