package ru.ntbank.bank.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ntbank.bank.domain.SmsMessage;
import ru.ntbank.bank.repo.SmsMessageRepository;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * «Отправка» СМС. Сообщения никуда не уходят — они видны на странице /dev/sms
 * и через GET /api/dev/sms/last?phone=..., чтобы было что коррелировать в JMeter.
 */
@Service
public class SmsService {

    private final SmsMessageRepository repository;
    private final SecureRandom random = new SecureRandom();

    public SmsService(SmsMessageRepository repository) {
        this.repository = repository;
    }

    public String generateCode(int length) {
        int bound = (int) Math.pow(10, length);
        return String.format("%0" + length + "d", random.nextInt(bound));
    }

    @Transactional
    public SmsMessage send(String phone, String text, String code) {
        SmsMessage message = new SmsMessage();
        message.setPhone(phone);
        message.setText(text);
        message.setCode(code);
        message.setCreatedAt(LocalDateTime.now());
        return repository.save(message);
    }

    @Transactional(readOnly = true)
    public Optional<SmsMessage> last(String phone) {
        return repository.findTop1ByPhoneOrderByCreatedAtDescIdDesc(phone);
    }

    @Transactional(readOnly = true)
    public List<SmsMessage> inbox(String phone) {
        if (phone == null || phone.isBlank()) {
            return repository.findTop30ByOrderByCreatedAtDescIdDesc();
        }
        return repository.findTop20ByPhoneOrderByCreatedAtDescIdDesc(phone);
    }
}
