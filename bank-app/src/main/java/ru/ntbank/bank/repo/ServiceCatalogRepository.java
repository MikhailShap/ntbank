package ru.ntbank.bank.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.ntbank.bank.domain.ServiceCatalogItem;

import java.util.List;

public interface ServiceCatalogRepository extends JpaRepository<ServiceCatalogItem, Long> {

    @Query("""
            select s from ServiceCatalogItem s
            where s.active = true
              and (:query is null or :query = ''
                   or lower(s.name) like lower(concat('%', :query, '%'))
                   or lower(s.provider) like lower(concat('%', :query, '%'))
                   or lower(s.category) like lower(concat('%', :query, '%')))
              and (:category is null or :category = '' or s.category = :category)
            order by s.category, s.name
            """)
    Page<ServiceCatalogItem> search(@Param("query") String query,
                                    @Param("category") String category,
                                    Pageable pageable);

    @Query("select distinct s.category from ServiceCatalogItem s where s.active = true order by s.category")
    List<String> categories();
}
