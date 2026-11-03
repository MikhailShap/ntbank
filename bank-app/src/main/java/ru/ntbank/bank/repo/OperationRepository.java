package ru.ntbank.bank.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.ntbank.bank.domain.Operation;

import java.time.LocalDateTime;
import java.util.List;

public interface OperationRepository extends JpaRepository<Operation, Long> {

    /**
     * Выписка по счёту с фильтром по датам — использует индекс (account_id, created_at desc).
     * Границы периода всегда заданы (сервис подставляет значения по умолчанию): PostgreSQL
     * не может вывести тип параметра в конструкции «:param is null».
     */
    @Query("""
            select o from Operation o
            where o.account.id = :accountId
              and o.createdAt >= :from
              and o.createdAt <= :to
            """)
    Page<Operation> statement(@Param("accountId") Long accountId,
                              @Param("from") LocalDateTime from,
                              @Param("to") LocalDateTime to,
                              Pageable pageable);

    /**
     * Дефект «Медленный SQL»: результат тот же, но условие по account_id завёрнуто в функцию,
     * поэтому индекс idx_operation_account_date не применяется. PostgreSQL читает всю таблицу
     * операций и считает хеш для каждой строки — около 100 мс против 10 мс у штатного запроса
     * на 160 000 операций, и дальше время растёт вместе с объёмом истории.
     * Под нагрузкой это съедает процессор и роняет пропускную способность.
     * Сортировка в этом режиме фиксированная: по дате, по убыванию.
     */
    @Query(value = """
            select * from operation o
            where md5(md5(md5(cast(o.account_id as text)))) = md5(md5(md5(cast(:accountId as text))))
              and o.created_at >= :from
              and o.created_at <= :to
            order by o.created_at desc, o.id desc
            """,
            countQuery = """
            select count(*) from operation o
            where md5(md5(md5(cast(o.account_id as text)))) = md5(md5(md5(cast(:accountId as text))))
              and o.created_at >= :from
              and o.created_at <= :to
            """,
            nativeQuery = true)
    Page<Operation> statementSlow(@Param("accountId") Long accountId,
                                  @Param("from") LocalDateTime from,
                                  @Param("to") LocalDateTime to,
                                  Pageable pageable);

    @Query("select o from Operation o where o.account.id in :accountIds order by o.createdAt desc")
    List<Operation> recent(@Param("accountIds") List<Long> accountIds, Pageable pageable);

    long countByAccountId(Long accountId);
}
