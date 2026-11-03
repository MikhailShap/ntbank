package ru.ntbank.bank.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.ntbank.bank.chaos.ChaosSettings;
import ru.ntbank.bank.chaos.SlowSqlMode;
import ru.ntbank.bank.domain.Operation;
import ru.ntbank.bank.repo.OperationRepository;

import java.math.BigDecimal;
import java.sql.Timestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** Выписка по счёту: фильтр по датам, сортировка, постраничный вывод. */
@Service
public class StatementService {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 200;

    /** Границы по умолчанию, когда фильтр по датам не задан. */
    private static final LocalDateTime MIN_DATE = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final LocalDateTime MAX_DATE = LocalDateTime.of(2999, 12, 31, 23, 59, 59);

    /** Неоптимальный расчёт остатка — по отдельному запросу на каждую строку выписки. */
    private static final String RUNNING_BALANCE_SQL = """
            select coalesce(sum(case when direction = 'IN' then amount else -amount end), 0)
            from operation
            where account_id = ? and created_at <= ?
            """;

    private final OperationRepository operations;
    private final ChaosSettings chaos;
    private final JdbcTemplate jdbc;

    public StatementService(OperationRepository operations, ChaosSettings chaos, JdbcTemplate jdbc) {
        this.operations = operations;
        this.chaos = chaos;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Page<Operation> statement(Long accountId, LocalDate from, LocalDate to,
                                     String sort, int page, int size) {
        LocalDateTime fromAt = from == null ? MIN_DATE : from.atStartOfDay();
        LocalDateTime toAt = to == null ? MAX_DATE : to.atTime(LocalTime.MAX);
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);

        SlowSqlMode mode = chaos.effectiveSlowSqlMode();
        if (mode == SlowSqlMode.HEAVY_COMPUTE) {
            // Нативный запрос сам задаёт порядок сортировки, Pageable передаём без Sort
            return operations.statementSlow(accountId, fromAt, toAt,
                    PageRequest.of(safePage, safeSize));
        }

        // Режим NO_INDEX отдельного запроса не требует: индекс физически удалён из базы,
        // и тот же самый запрос превращается в полный перебор таблицы.
        Page<Operation> result = operations.statement(accountId, fromAt, toAt,
                PageRequest.of(safePage, safeSize, sortOf(sort)));

        if (mode == SlowSqlMode.N_PLUS_ONE) {
            loadBalancesOneByOne(accountId, result);
        }
        return result;
    }

    public Sort sortOf(String sort) {
        return switch (sort == null ? "" : sort) {
            case "date_asc" -> Sort.by(Sort.Direction.ASC, "createdAt").and(Sort.by("id"));
            case "amount_desc" -> Sort.by(Sort.Direction.DESC, "amount").and(Sort.by("id"));
            case "amount_asc" -> Sort.by(Sort.Direction.ASC, "amount").and(Sort.by("id"));
            default -> Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));
        };
    }

    /**
     * Дефект «N+1»: вместо одного запроса к базе делаем ещё по одному на каждую строку.
     * Под нагрузкой это умножает число обращений к базе на размер страницы
     * и выедает пул соединений.
     */
    private void loadBalancesOneByOne(Long accountId, Page<Operation> page) {
        for (Operation operation : page.getContent()) {
            jdbc.queryForObject(RUNNING_BALANCE_SQL, BigDecimal.class,
                    accountId, Timestamp.valueOf(operation.getCreatedAt()));
        }
    }

    public String sortTitle(String sort) {
        return switch (sort == null ? "" : sort) {
            case "date_asc" -> "Сначала старые";
            case "amount_desc" -> "Сумма по убыванию";
            case "amount_asc" -> "Сумма по возрастанию";
            default -> "Сначала новые";
        };
    }
}
