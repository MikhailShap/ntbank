package ru.ntbank.bank.chaos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Service;
import ru.ntbank.bank.async.TransferDispatcher;
import ru.ntbank.bank.async.TransferEventsConsumer;
import ru.ntbank.bank.client.AntifraudClient;
import ru.ntbank.bank.service.RuntimeInfoService;

/**
 * Применение дефектов к работающему приложению: пулы, консьюмер Kafka и внешний «Антифрод».
 * Остальные дефекты живут в {@link ChaosFilter} и в выписке.
 */
@Service
public class ChaosService {

    private static final Logger log = LoggerFactory.getLogger(ChaosService.class);

    /** Индекс, который удаляется и создаётся обратно в режиме «Нет индекса». */
    private static final String STATEMENT_INDEX = "idx_operation_account_date";
    private static final String CREATE_INDEX =
            "create index if not exists " + STATEMENT_INDEX
                    + " on operation (account_id, created_at desc)";

    private final ChaosSettings settings;
    private final JdbcTemplate jdbc;
    private final LeakStore leakStore;
    private final RateLimiter rateLimiter;
    private final RuntimeInfoService runtimeInfo;
    private final AntifraudClient antifraud;
    private final TransferDispatcher dispatcher;
    private final ObjectProvider<KafkaListenerEndpointRegistry> kafkaRegistry;

    private volatile int defaultPoolSize = -1;
    private volatile int defaultTomcatThreads = -1;

    public ChaosService(ChaosSettings settings, JdbcTemplate jdbc,
                        LeakStore leakStore, RateLimiter rateLimiter,
                        RuntimeInfoService runtimeInfo, AntifraudClient antifraud,
                        TransferDispatcher dispatcher,
                        ObjectProvider<KafkaListenerEndpointRegistry> kafkaRegistry) {
        this.settings = settings;
        this.jdbc = jdbc;
        this.leakStore = leakStore;
        this.rateLimiter = rateLimiter;
        this.runtimeInfo = runtimeInfo;
        this.antifraud = antifraud;
        this.dispatcher = dispatcher;
        this.kafkaRegistry = kafkaRegistry;
    }

    /**
     * При старте все дефекты считаются выключенными — значит, они должны быть выключены
     * и на самом деле. Часть состояния живёт вне JVM банка и переживает его перезапуск
     * (например, по OutOfMemoryError): удалённый индекс в базе и настройки заглушки «Антифрод».
     * Без этого страница показывала бы «всё выключено», а стенд продолжал бы тормозить.
     * <p>
     * Штатные размеры пулов запоминаем тоже здесь: во время создания бинов веб-сервер
     * ещё не поднят, а обращение к нему в этот момент ломает запуск.
     * <p>
     * ApplicationStartedEvent, а не ApplicationReadyEvent: так восстановление индекса (на миллионах
     * строк — секунды) идёт до того, как /actuator/health скажет UP и придут первые запросы.
     */
    @EventListener(ApplicationStartedEvent.class)
    public void captureDefaults() {
        defaultPoolSize = runtimeInfo.hikariMaxPoolSize();
        defaultTomcatThreads = runtimeInfo.tomcatMaxThreads();
        setIndexPresent(true);
        if (antifraudExternal()) {
            applyAntifraud(false, null, null, 0, 0);
        }
    }

    public ChaosSettings settings() {
        return settings;
    }

    // ------------------------------------------------------------------ медленный SQL

    /**
     * В режиме «Нет индекса» индекс физически удаляется из базы и создаётся обратно
     * при выключении. На больших объёмах создание занимает несколько секунд — это нормально.
     */
    public void applySlowSql(boolean enabled, SlowSqlMode mode) {
        settings.applySlowSql(enabled, mode);
        boolean indexNeeded = settings.effectiveSlowSqlMode() != SlowSqlMode.NO_INDEX;
        setIndexPresent(indexNeeded);
    }

    public boolean statementIndexPresent() {
        Integer count = jdbc.queryForObject(
                "select count(*) from pg_indexes where indexname = ?", Integer.class, STATEMENT_INDEX);
        return count != null && count > 0;
    }

    /** Примерное число операций в базе — из статистики планировщика, без тяжёлого count(*). */
    public long operationsEstimate() {
        Long rows = jdbc.queryForObject(
                "select greatest(reltuples, 0)::bigint from pg_class where relname = 'operation'",
                Long.class);
        return rows == null ? 0 : rows;
    }

    private void setIndexPresent(boolean present) {
        if (present == statementIndexPresent()) {
            return;
        }
        long startedAt = System.currentTimeMillis();
        if (present) {
            jdbc.execute(CREATE_INDEX);
            log.info("Индекс {} создан обратно за {} мс", STATEMENT_INDEX,
                    System.currentTimeMillis() - startedAt);
        } else {
            jdbc.execute("drop index if exists " + STATEMENT_INDEX);
            log.info("Индекс {} удалён — выписка пойдёт полным перебором таблицы", STATEMENT_INDEX);
        }
    }

    // ------------------------------------------------------------------ пулы

    /**
     * Пул HikariCP ограничен 90: у PostgreSQL по умолчанию 100 соединений, часть занимают
     * exporter и служебные процессы. Больший пул упёрся бы в «too many clients» самой базы.
     */
    public static final int HIKARI_MAX = 90;
    public static final int TOMCAT_MAX = 1000;

    public void applyPools(Integer hikariMaxPoolSize, Integer tomcatMaxThreads) {
        if (hikariMaxPoolSize != null && hikariMaxPoolSize > 0) {
            int size = Math.min(hikariMaxPoolSize, HIKARI_MAX);
            runtimeInfo.setHikariMaxPoolSize(size);
            log.info("Размер пула HikariCP изменён на {}", size);
        }
        if (tomcatMaxThreads != null && tomcatMaxThreads > 0) {
            int threads = Math.min(tomcatMaxThreads, TOMCAT_MAX);
            runtimeInfo.setTomcatMaxThreads(threads);
            log.info("Максимум потоков Tomcat изменён на {}", threads);
        }
    }

    // ------------------------------------------------------------------ Kafka

    public void applyKafka(boolean paused, Long consumerDelayMs) {
        settings.applyKafka(paused, consumerDelayMs);
        MessageListenerContainer container = listenerContainer();
        if (container == null) {
            return;
        }
        if (paused) {
            container.pause();
            log.info("Консьюмер переводов поставлен на паузу — лаг будет расти");
        } else {
            container.resume();
            log.info("Консьюмер переводов снят с паузы");
        }
    }

    /** Изменены ли размеры пулов относительно штатных — без обращения к базе. */
    public boolean poolsChanged() {
        if (defaultPoolSize < 0 || defaultTomcatThreads < 0) {
            return false;
        }
        return runtimeInfo.hikariMaxPoolSize() != defaultPoolSize
                || runtimeInfo.tomcatMaxThreads() != defaultTomcatThreads;
    }

    public boolean kafkaAvailable() {
        return "kafka".equals(dispatcher.mode()) && listenerContainer() != null;
    }

    public boolean kafkaContainerPaused() {
        MessageListenerContainer container = listenerContainer();
        return container != null && container.isPauseRequested();
    }

    private MessageListenerContainer listenerContainer() {
        KafkaListenerEndpointRegistry registry = kafkaRegistry.getIfAvailable();
        return registry == null ? null : registry.getListenerContainer(TransferEventsConsumer.LISTENER_ID);
    }

    // ------------------------------------------------------------------ антифрод

    /** Возвращает false, если внешнего сервиса нет (работают локальные правила). */
    public boolean applyAntifraud(boolean enabled, Long delayMinMs, Long delayMaxMs,
                                  Integer errorPercent, Integer rejectPercent) {
        settings.applyAntifraud(enabled, delayMinMs, delayMaxMs, errorPercent, rejectPercent);
        return antifraud.updateChaos(new AntifraudClient.Chaos(
                settings.isAntifraudEnabled(),
                settings.getAntifraudDelayMinMs(),
                settings.getAntifraudDelayMaxMs(),
                settings.getAntifraudErrorPercent(),
                settings.getAntifraudRejectPercent()));
    }

    public boolean antifraudExternal() {
        return "http".equals(antifraud.mode());
    }

    // ------------------------------------------------------------------ утечка

    public void clearLeaked() {
        leakStore.clear();
        log.info("Накопленные объекты утечки очищены");
    }

    // ------------------------------------------------------------------ сброс

    public void resetAll() {
        settings.reset();
        setIndexPresent(true);
        leakStore.clear();
        rateLimiter.reset();
        applyPools(defaultPoolSize, defaultTomcatThreads);
        applyKafka(false, 0L);
        applyAntifraud(false, null, null, 0, 0);
        log.info("Режим «Дефекты» полностью выключен");
    }

    // ------------------------------------------------------------------ состояние

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    public ChaosState state() {
        return new ChaosState(
                settings.anyEnabled(),
                settings.isLatencyEnabled(), settings.getLatencyTarget().name(),
                settings.getLatencyMinMs(), settings.getLatencyMaxMs(),
                settings.isErrorsEnabled(), settings.getErrorsTarget().name(), settings.getErrorPercent(),
                settings.isSlowSqlEnabled(), settings.getSlowSqlMode().name(),
                statementIndexPresent(), operationsEstimate(),
                settings.isLeakEnabled(), settings.getLeakTarget().name(),
                settings.getLeakKbPerRequest(), leakStore.leakedBytes(), leakStore.chunks(),
                usedHeapBytes(), Runtime.getRuntime().maxMemory(), leakStore.bytesPerSecond(),
                settings.isCpuEnabled(), settings.getCpuTarget().name(), settings.getCpuMillis(),
                runtimeInfo.hikariMaxPoolSize(), defaultPoolSize,
                runtimeInfo.tomcatMaxThreads(), defaultTomcatThreads,
                settings.isAntifraudEnabled(), settings.getAntifraudDelayMinMs(),
                settings.getAntifraudDelayMaxMs(), settings.getAntifraudErrorPercent(),
                settings.getAntifraudRejectPercent(), antifraud.mode(),
                settings.isKafkaConsumerPaused(), settings.getKafkaConsumerDelayMs(),
                dispatcher.mode(), kafkaAvailable(),
                settings.isRateLimitEnabled(), settings.getRateLimitRps(),
                rateLimiter.trackedClients());
    }

    /** Текущее состояние всех дефектов — и для страницы, и для REST. */
    public record ChaosState(
            boolean anyEnabled,
            boolean latencyEnabled, String latencyTarget, long latencyMinMs, long latencyMaxMs,
            boolean errorsEnabled, String errorsTarget, int errorPercent,
            boolean slowSqlEnabled, String slowSqlMode,
            boolean statementIndexPresent, long operationsEstimate,
            boolean leakEnabled, String leakTarget, int leakKbPerRequest,
            long leakedBytes, int leakedChunks, long heapUsedBytes, long heapMaxBytes,
            double leakBytesPerSecond,
            boolean cpuEnabled, String cpuTarget, int cpuMillis,
            int hikariMaxPoolSize, int hikariDefaultPoolSize,
            int tomcatMaxThreads, int tomcatDefaultThreads,
            boolean antifraudEnabled, long antifraudDelayMinMs, long antifraudDelayMaxMs,
            int antifraudErrorPercent, int antifraudRejectPercent, String antifraudMode,
            boolean kafkaConsumerPaused, long kafkaConsumerDelayMs,
            String asyncMode, boolean kafkaAvailable,
            boolean rateLimitEnabled, int rateLimitRps, int rateLimitClients) {

        /** Накопленная утечка в мегабайтах — для показа на странице. */
        public long leakedMb() {
            return leakedBytes / (1024 * 1024);
        }

        public long heapUsedMb() {
            return heapUsedBytes / (1024 * 1024);
        }

        public long heapMaxMb() {
            return heapMaxBytes / (1024 * 1024);
        }

        /** Скорость утечки в мегабайтах в минуту. */
        public double leakMbPerMinute() {
            return leakBytesPerSecond * 60 / (1024 * 1024);
        }

        /**
         * Сколько минут осталось до заполнения heap при текущей скорости утечки,
         * -1 — если утечки нет. Оценка грубая: сборщик мусора и обычные объекты
         * тоже занимают память, поэтому OutOfMemoryError наступит немного раньше.
         */
        public long minutesUntilHeapFull() {
            if (leakBytesPerSecond <= 0) {
                return -1;
            }
            long free = Math.max(0, heapMaxBytes - heapUsedBytes);
            return Math.round(free / leakBytesPerSecond / 60);
        }

        /** Доля heap, которую уже занимает утечка, в процентах. */
        public long leakedPercentOfHeap() {
            return heapMaxBytes <= 0 ? 0 : leakedBytes * 100 / heapMaxBytes;
        }
    }
}
