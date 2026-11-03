package ru.ntbank.bank.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.ntbank.bank.exception.BusinessException;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Поддержка заголовка Idempotency-Key: повтор запроса с тем же ключом не создаёт
 * вторую операцию, а получает ответ первого.
 * <p>
 * Ключ занимается ДО выполнения операции — вставкой с {@code on conflict do nothing}
 * в таблицу с уникальным индексом (user_id, scope, idem_key). Иначе одновременные запросы
 * с одним ключом (повтор в JMeter, двойной клик) не видели бы друг друга и выполняли
 * операцию каждый — так и было в первой версии: 8 запросов списывали деньги 8 раз.
 * <p>
 * Запрос, который ключ не занял, ждёт результата первого и отдаёт его же ответ.
 * Если первая попытка завершилась ошибкой, ключ освобождается и повтор выполняется заново.
 */
@Service
public class IdempotencyService {

    public static final String HEADER = "Idempotency-Key";
    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    /** Сколько ждать завершения запроса, занявшего тот же ключ. */
    private static final int WAIT_ATTEMPTS = 100;
    private static final long WAIT_STEP_MS = 100;

    /** http_status = 0 — запрос с этим ключом ещё выполняется. */
    private static final String CLAIM = """
            insert into idempotency_record (user_id, scope, idem_key, http_status, response_body, created_at)
            values (?, ?, ?, 0, '', now())
            on conflict (user_id, scope, idem_key) do nothing
            """;
    private static final String LOAD = """
            select http_status, response_body from idempotency_record
            where user_id = ? and scope = ? and idem_key = ?
            """;
    private static final String COMPLETE = """
            update idempotency_record set http_status = ?, response_body = ?
            where user_id = ? and scope = ? and idem_key = ?
            """;
    private static final String RELEASE = """
            delete from idempotency_record
            where user_id = ? and scope = ? and idem_key = ? and http_status = 0
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Выполняет операцию не больше одного раза на ключ и возвращает ответ 201.
     * Без ключа операция просто выполняется.
     */
    public ResponseEntity<?> execute(Long userId, String scope, String key, Supplier<Object> operation) {
        return execute(userId, scope, key, HttpStatus.CREATED, operation);
    }

    /** То же с другим кодом успешного ответа — например, 200 для повтора платежа по шаблону. */
    public ResponseEntity<?> execute(Long userId, String scope, String key, HttpStatus status,
                                     Supplier<Object> operation) {
        if (key == null || key.isBlank()) {
            return ResponseEntity.status(status).body(operation.get());
        }
        Inputs.idempotencyKey(key);

        while (true) {
            if (jdbc.update(CLAIM, userId, scope, key) == 1) {
                return runClaimed(userId, scope, key, status, operation);
            }
            Stored stored = waitForResult(userId, scope, key);
            if (stored == null) {
                continue; // первая попытка завершилась ошибкой и освободила ключ — пробуем занять снова
            }
            return ResponseEntity.status(stored.status())
                    .header("Idempotency-Replayed", "true")
                    .body(stored.body());
        }
    }

    private ResponseEntity<?> runClaimed(Long userId, String scope, String key, HttpStatus status,
                                         Supplier<Object> operation) {
        Object result;
        try {
            result = operation.get();
        } catch (RuntimeException e) {
            jdbc.update(RELEASE, userId, scope, key);
            throw e;
        }
        try {
            jdbc.update(COMPLETE, status.value(), objectMapper.writeValueAsString(result),
                    userId, scope, key);
        } catch (Exception e) {
            // операция уже выполнена — ответ клиенту важнее сохранения для повторов
            log.warn("Не удалось сохранить ответ по ключу {}: {}", key, e.getMessage());
        }
        return ResponseEntity.status(status).body(result);
    }

    /** Ждёт, пока запрос, занявший ключ, завершится. null — ключ освобождён после ошибки. */
    private Stored waitForResult(Long userId, String scope, String key) {
        for (int attempt = 0; attempt < WAIT_ATTEMPTS; attempt++) {
            List<Stored> rows = jdbc.query(LOAD, (rs, i) -> new Stored(rs.getInt(1), parse(rs.getString(2))),
                    userId, scope, key);
            if (rows.isEmpty()) {
                return null;
            }
            if (rows.get(0).status() != 0) {
                return rows.get(0);
            }
            try {
                Thread.sleep(WAIT_STEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw BusinessException.conflict("IDEMPOTENCY_IN_PROGRESS",
                "Запрос с этим Idempotency-Key ещё выполняется, повторите позже");
    }

    private Map<String, Object> parse(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("Не удалось прочитать сохранённый ответ: {}", e.getMessage());
            return null;
        }
    }

    private record Stored(int status, Map<String, Object> body) {
    }
}
