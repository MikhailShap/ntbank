package ru.ntbank.bank.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ntbank.bank.config.AppProperties;
import ru.ntbank.bank.exception.BusinessException;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Наполнение базы тестовыми данными.
 * <p>
 * Всё делается пакетными SQL-запросами (generate_series), а пароль хешируется один раз на всех —
 * поэтому 1000 пользователей, 2000 счетов и ~125 000 операций создаются за несколько секунд,
 * и {@code POST /api/dev/reset} отрабатывает почти мгновенно.
 */
@Service
public class SeedService {

    private static final Logger log = LoggerFactory.getLogger(SeedService.class);

    private static final String TRUNCATE = """
            truncate table operation, card, transfer, payment, payment_template,
                           sms_message, idempotency_record, account, app_user
            restart identity cascade
            """;

    private static final String INSERT_ADMIN = """
            insert into app_user (login, password_hash, full_name, phone, role, enabled, created_at)
            values ('admin', cast(? as varchar(100)), 'Администратор стенда',
                    '+79000000000', 'ADMIN', true, now())
            """;

    private static final String INSERT_USERS = """
            with names as (
                select array['Иванов','Петров','Сидоров','Кузнецов','Смирнов','Попов','Васильев',
                             'Новиков','Фёдоров','Морозов','Волков','Алексеев','Лебедев','Семёнов',
                             'Егоров','Павлов','Козлов','Степанов','Николаев','Орлов']::text[] as ln,
                       array['Иван','Пётр','Сергей','Андрей','Алексей','Дмитрий','Михаил','Николай',
                             'Александр','Владимир','Егор','Артём','Максим','Роман','Денис','Кирилл',
                             'Олег','Павел','Тимур','Глеб']::text[] as mn,
                       array['Анна','Мария','Елена','Ольга','Татьяна','Наталья','Ирина','Светлана',
                             'Екатерина','Юлия','Дарья','Ксения','Полина','Алина','Вера','Любовь',
                             'Марина','София','Виктория','Галина']::text[] as fn,
                       array['Иванов','Петров','Сергеев','Андреев','Алексеев','Дмитриев','Михайлов',
                             'Николаев','Александров','Владимиров']::text[] as pn
            )
            insert into app_user (login, password_hash, full_name, phone, role, enabled, created_at)
            select 'user' || lpad(g::text, 4, '0'),
                   cast(? as varchar(100)),
                   case when g % 2 = 0
                        then n.ln[1 + (g * 3) % array_length(n.ln, 1)] || 'а ' ||
                             n.fn[1 + (g * 7) % array_length(n.fn, 1)] || ' ' ||
                             n.pn[1 + (g * 11) % array_length(n.pn, 1)] || 'на'
                        else n.ln[1 + (g * 3) % array_length(n.ln, 1)] || ' ' ||
                             n.mn[1 + (g * 7) % array_length(n.mn, 1)] || ' ' ||
                             n.pn[1 + (g * 11) % array_length(n.pn, 1)] || 'ич'
                   end,
                   '+7900' || lpad(g::text, 7, '0'),
                   'USER', true,
                   now() - ((g % 400) || ' days')::interval
            from generate_series(1, cast(? as integer)) g, names n
            """;

    private static final String INSERT_RUB_ACCOUNTS = """
            insert into account (user_id, number, title, currency, balance, opened_at, active)
            select u.id, '40817810' || lpad(u.id::text, 12, '0'), 'Текущий счёт', 'RUB',
                   round((50000 + random() * 1450000)::numeric, 2),
                   current_date - ((u.id % 900)::int), true
            from app_user u
            where u.role = 'USER'
            """;

    private static final String INSERT_USD_ACCOUNTS = """
            insert into account (user_id, number, title, currency, balance, opened_at, active)
            select u.id, '40817840' || lpad((u.id * 31 + 7)::text, 12, '0'), 'Валютный счёт', 'USD',
                   round((200 + random() * 9800)::numeric, 2),
                   current_date - ((u.id % 900)::int), true
            from app_user u
            where u.role = 'USER'
            """;

    private static final String INSERT_CARDS = """
            insert into card (account_id, user_id, pan_masked, holder, expires, kind, status)
            select a.id, a.user_id, '5536 •••• •••• ' || right(a.number, 4), u.full_name,
                   lpad((1 + (a.id % 12))::text, 2, '0') || '/' || (28 + (a.id % 3))::text,
                   'DEBIT', 'ACTIVE'
            from account a
            join app_user u on u.id = a.user_id
            where a.currency = 'RUB'
            """;

    /**
     * 25–100 операций на счёт, то есть 50–200 на пользователя (у каждого два счёта).
     * Число считается в подзапросе по строке счёта: random() внутри LATERAL без ссылки
     * на строку PostgreSQL вычислил бы один раз, и у всех счетов было бы одинаковое число.
     */
    private static final String INSERT_OPERATIONS = """
            insert into operation (account_id, created_at, direction, kind, amount, currency,
                                   description, counterparty, status)
            select a.id,
                   now() - (random() * 180) * interval '1 day',
                   d.direction,
                   d.kind,
                   case when a.currency = 'USD'
                        then round(((d.lo + random() * (d.hi - d.lo)) / 90.0)::numeric, 2)
                        else round((d.lo + random() * (d.hi - d.lo))::numeric, 2)
                   end,
                   a.currency,
                   d.description,
                   d.counterparty,
                   'OK'
            from (select acc.*, 25 + floor(random() * 76)::int as op_count from account acc) a
            cross join lateral generate_series(1, a.op_count) g
            cross join lateral (
                select t.* from (values
                    (1,  'IN',  'SALARY',          'Зарплата, ООО «Вектор-Т»',            'ООО «Вектор-Т»',        40000, 180000),
                    (2,  'IN',  'SALARY',          'Аванс, ООО «Вектор-Т»',               'ООО «Вектор-Т»',        20000,  70000),
                    (3,  'IN',  'TRANSFER',        'Перевод от клиента НТ-Банка',          'Клиент НТ-Банка',        1000,  60000),
                    (4,  'IN',  'INTEREST',        'Проценты по счёту',                    'НТ-Банк',                 100,   4000),
                    (5,  'IN',  'REFUND',          'Возврат покупки',                      'Интернет-магазин «Полка»', 500, 20000),
                    (6,  'OUT', 'PAYMENT_MOBILE',  'Мобильная связь, «Волна Мобайл»',      'Волна Мобайл',            200,   2500),
                    (7,  'OUT', 'PAYMENT_UTILITY', 'ЖКХ, «Горсвет»',                       'Горсвет',                 800,   9000),
                    (8,  'OUT', 'PAYMENT',         'Интернет, «ГигаНет»',                  'ГигаНет',                 500,   2000),
                    (9,  'OUT', 'TRANSFER',        'Перевод по номеру телефона',           'Клиент НТ-Банка',        1000,  50000),
                    (10, 'OUT', 'PURCHASE',        'Покупка, супермаркет «Берёзка»',       'Супермаркет «Берёзка»',   300,  12000),
                    (11, 'OUT', 'PURCHASE',        'Покупка, кафе «Чайная ложка»',         'Кафе «Чайная ложка»',     200,   4000),
                    (12, 'OUT', 'CASH',            'Снятие наличных в банкомате',          'Банкомат НТ-Банка',      1000,  30000)
                ) as t(n, direction, kind, description, counterparty, lo, hi)
                where t.n = 1 + ((g * 7 + a.id) % 12)
            ) d
            """;

    /**
     * Наращивание истории: те же шаблоны операций, но фиксированное число на счёт
     * и даты за два года. Собирается из INSERT_OPERATIONS, чтобы не дублировать список шаблонов.
     */
    private static final String INSERT_HISTORY = replaceOnce(
            replaceOnce(INSERT_OPERATIONS,
                    "generate_series(1, a.op_count) g",
                    "generate_series(1, cast(? as integer)) g"),
            "now() - (random() * 180) * interval '1 day'",
            "now() - (random() * 730) * interval '1 day'");

    private static final String STATEMENT_INDEX = "idx_operation_account_date";
    private static final long HISTORY_LIMIT = 10_000_000L;
    private static final int HISTORY_BATCH_PER_ACCOUNT = 100;
    private static final int RESET_ATTEMPTS = 20;

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties properties;
    private final TransactionTemplate transactions;
    private final DataResetGate resetGate;
    private final java.util.concurrent.atomic.AtomicBoolean growing =
            new java.util.concurrent.atomic.AtomicBoolean();

    public SeedService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, AppProperties properties,
                       PlatformTransactionManager transactionManager, DataResetGate resetGate) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.resetGate = resetGate;
    }

    public boolean isEmpty() {
        Long count = jdbc.queryForObject("select count(*) from app_user", Long.class);
        return count == null || count == 0;
    }

    /**
     * Полный сброс к исходному состоянию. Каталог услуг и курсы валют не трогаем — они из миграций.
     * <p>
     * Сброс могут нажать прямо под нагрузкой, поэтому он идёт через {@link DataResetGate}:
     * дожидается окончания текущих запросов и придерживает новые. На случай посторонних
     * соединений (например, psql) TRUNCATE ждёт каждую блокировку не дольше {@code lock_timeout},
     * отпускает всё и пробует снова.
     */
    public SeedResult reset() {
        if (growing.get()) {
            // TRUNCATE посреди пакетной вставки истории всё равно ждал бы её и упал по lock_timeout
            throw BusinessException.conflict("HISTORY_IN_PROGRESS",
                    "Сейчас наращивается история операций — сбросьте данные, когда это закончится");
        }
        return resetGate.exclusive(this::resetWithRetries);
    }

    private SeedResult resetWithRetries() {
        for (int attempt = 1; ; attempt++) {
            try {
                SeedResult result = transactions.execute(status -> resetOnce());
                if (attempt > 1) {
                    log.info("Сброс данных под нагрузкой прошёл с попытки {}", attempt);
                }
                return result;
            } catch (DataAccessException e) {
                if (!isLockConflict(e) || attempt >= RESET_ATTEMPTS) {
                    throw e;
                }
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextLong(50, 250));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** 55P03 — не дождались блокировки за lock_timeout, 40P01 — взаимоблокировка. */
    private static boolean isLockConflict(DataAccessException e) {
        if (e instanceof PessimisticLockingFailureException) {
            return true;
        }
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql
                    && ("55P03".equals(sql.getSQLState()) || "40P01".equals(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }

    private SeedResult resetOnce() {
        long startedAt = System.currentTimeMillis();
        jdbc.execute("set local lock_timeout = '500ms'");
        int userCount = properties.seed().users();

        // Хешируем пароль один раз: BCrypt на 1000 пользователей занял бы больше минуты.
        String sharedHash = passwordEncoder.encode(properties.seed().password());
        String adminHash = passwordEncoder.encode("admin");

        jdbc.execute(TRUNCATE);
        jdbc.update(INSERT_ADMIN, adminHash);
        jdbc.update(INSERT_USERS, sharedHash, userCount);
        jdbc.update(INSERT_RUB_ACCOUNTS);
        jdbc.update(INSERT_USD_ACCOUNTS);
        jdbc.update(INSERT_CARDS);
        jdbc.update(INSERT_OPERATIONS);
        jdbc.execute("analyze operation");

        long accounts = count("account");
        long operations = count("operation");
        long services = count("service_catalog");
        long elapsed = System.currentTimeMillis() - startedAt;

        String csv = writeCsv();

        log.info("Тестовые данные пересозданы за {} мс: пользователей {}, счетов {}, операций {}",
                elapsed, userCount, accounts, operations);
        return new SeedResult(userCount, accounts, operations, services, elapsed, csv);
    }

    /** Наращивание истории до значения из SEED_OPERATIONS_TOTAL, если оно задано. */
    public HistoryResult growToConfiguredTotal() {
        long target = properties.seed().operationsTotal();
        return target > 0 ? growHistory(target) : null;
    }

    /**
     * Наращивает историю операций до targetTotal строк.
     * Вставка идёт пачками по ~200 тысяч строк, каждая в своей транзакции.
     * Индекс выписки на время загрузки снимается и создаётся заново — так быстрее
     * в несколько раз; если индекса не было (включён дефект «Нет индекса»), он не создаётся.
     */
    public HistoryResult growHistory(long targetTotal) {
        // Не урезаем молча: лишний ноль в запросе означал бы 10 млн строк и несколько минут работы
        if (targetTotal < 0 || targetTotal > HISTORY_LIMIT) {
            throw BusinessException.badRequest("HISTORY_TOTAL_INVALID",
                    "Число операций должно быть от 0 до " + HISTORY_LIMIT);
        }
        long target = targetTotal;
        if (!growing.compareAndSet(false, true)) {
            throw BusinessException.conflict("HISTORY_IN_PROGRESS", "Наращивание истории уже выполняется");
        }
        try {
            long startedAt = System.currentTimeMillis();
            long before = count("operation");
            long accounts = count("account");
            if (before >= target || accounts == 0) {
                return new HistoryResult(before, before, 0, System.currentTimeMillis() - startedAt);
            }

            boolean hadIndex = indexPresent();
            if (hadIndex) {
                jdbc.execute("drop index if exists " + STATEMENT_INDEX);
            }

            long current = before;
            while (current < target) {
                long missingPerAccount = (target - current + accounts - 1) / accounts;
                int batch = (int) Math.min(HISTORY_BATCH_PER_ACCOUNT, missingPerAccount);
                current += jdbc.update(INSERT_HISTORY, batch);
                log.info("История операций: {} из {}", current, target);
            }

            if (hadIndex) {
                jdbc.execute("create index if not exists " + STATEMENT_INDEX
                        + " on operation (account_id, created_at desc)");
            }
            jdbc.execute("analyze operation");

            long elapsed = System.currentTimeMillis() - startedAt;
            log.info("История операций наращена с {} до {} за {} мс", before, current, elapsed);
            return new HistoryResult(before, current, current - before, elapsed);
        } finally {
            growing.set(false);
        }
    }

    private boolean indexPresent() {
        Integer found = jdbc.queryForObject(
                "select count(*) from pg_indexes where indexname = ?", Integer.class, STATEMENT_INDEX);
        return found != null && found > 0;
    }

    private static String replaceOnce(String source, String from, String to) {
        if (!source.contains(from)) {
            throw new IllegalStateException("В SQL не найден фрагмент: " + from);
        }
        return source.replace(from, to);
    }

    public record HistoryResult(long before, long after, long added, long elapsedMs) {
    }

    private long count(String table) {
        Long value = jdbc.queryForObject("select count(*) from " + table, Long.class);
        return value == null ? 0 : value;
    }

    /**
     * Выгрузка логинов в CSV для JMeter (CSV Data Set Config).
     * Файл пишется в каталог, примонтированный к контейнеру как /test-data.
     */
    public String writeCsv() {
        String path = properties.seed().csvPath();
        if (path == null || path.isBlank()) {
            return null;
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select login, phone from app_user where role = 'USER' order by id");
        String password = properties.seed().password();
        Path target = Path.of(path);
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                writer.write("login,password,phone");
                writer.write("\n");
                for (Map<String, Object> row : rows) {
                    writer.write(String.valueOf(row.get("login")));
                    writer.write(",");
                    writer.write(password);
                    writer.write(",");
                    writer.write(String.valueOf(row.get("phone")));
                    writer.write("\n");
                }
            }
            log.info("Файл с тестовыми пользователями записан: {} ({} строк)", target, rows.size());
            return target.toString();
        } catch (IOException e) {
            log.warn("Не удалось записать {} — файл из репозитория останется прежним: {}", target, e.getMessage());
            return null;
        }
    }

    public record SeedResult(int users, long accounts, long operations, long services,
                             long elapsedMs, String csvPath) {
    }
}
