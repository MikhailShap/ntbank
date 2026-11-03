package ru.ntbank.bank.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.ntbank.bank.domain.SmsMessage;

import java.util.List;
import java.util.Optional;

public interface SmsMessageRepository extends JpaRepository<SmsMessage, Long> {

    Optional<SmsMessage> findTop1ByPhoneOrderByCreatedAtDescIdDesc(String phone);

    List<SmsMessage> findTop20ByPhoneOrderByCreatedAtDescIdDesc(String phone);

    List<SmsMessage> findTop30ByOrderByCreatedAtDescIdDesc();
}
