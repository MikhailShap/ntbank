package ru.ntbank.bank.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.ntbank.bank.domain.Card;

import java.util.List;

public interface CardRepository extends JpaRepository<Card, Long> {

    List<Card> findByUserIdOrderByIdAsc(Long userId);
}
