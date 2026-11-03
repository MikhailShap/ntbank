package ru.ntbank.bank.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.ntbank.bank.async.TransferDispatcher;
import ru.ntbank.bank.client.AntifraudClient;
import ru.ntbank.bank.config.AppProperties;
import ru.ntbank.bank.domain.*;
import ru.ntbank.bank.exception.BusinessException;
import ru.ntbank.bank.metrics.BankMetrics;
import ru.ntbank.bank.repo.AccountRepository;
import ru.ntbank.bank.repo.AppUserRepository;
import ru.ntbank.bank.repo.OperationRepository;
import ru.ntbank.bank.repo.TransferRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Переводы.
 * <p>
 * Между своими счетами — сразу, с конвертацией по курсу.
 * По номеру телефона — через черновик с OTP: DRAFT -> (OTP) -> PROCESSING -> DONE | REJECTED.
 * Деньги списываются в момент подтверждения, зачисляются или возвращаются при завершении обработки.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final TransferRepository transfers;
    private final AccountRepository accounts;
    private final AppUserRepository users;
    private final OperationRepository operations;
    private final ExchangeRateService rates;
    private final SmsService sms;
    private final TransferDispatcher dispatcher;
    private final AntifraudClient antifraud;
    private final AppProperties properties;
    private final Fmt fmt;
    private final BankMetrics metrics;

    public TransferService(TransferRepository transfers, AccountRepository accounts,
                           AppUserRepository users, OperationRepository operations,
                           ExchangeRateService rates, SmsService sms,
                           TransferDispatcher dispatcher, AntifraudClient antifraud,
                           AppProperties properties, Fmt fmt, BankMetrics metrics) {
        this.transfers = transfers;
        this.accounts = accounts;
        this.users = users;
        this.operations = operations;
        this.rates = rates;
        this.sms = sms;
        this.dispatcher = dispatcher;
        this.antifraud = antifraud;
        this.properties = properties;
        this.fmt = fmt;
        this.metrics = metrics;
    }

    // ------------------------------------------------------------------ чтение

    @Transactional(readOnly = true)
    public Transfer find(Long userId, UUID id) {
        return transfers.findByIdAndUserId(id, userId)
                .orElseThrow(() -> BusinessException.notFound("Перевод №" + id + " не найден"));
    }

    @Transactional(readOnly = true)
    public List<Transfer> recent(Long userId) {
        return transfers.findTop5ByUserIdOrderByCreatedAtDesc(userId);
    }

    // ------------------------------------------------- перевод между своими счетами

    @Transactional
    public Transfer createOwn(Long userId, Long fromAccountId, Long toAccountId, BigDecimal amount) {
        validateAmount(amount);
        if (fromAccountId.equals(toAccountId)) {
            throw BusinessException.badRequest("SAME_ACCOUNT", "Выберите разные счета");
        }
        Account from = account(userId, fromAccountId);
        Account to = account(userId, toAccountId);

        BigDecimal rate = rates.rate(from.getCurrency(), to.getCurrency());
        BigDecimal credited = rates.convert(amount, from.getCurrency(), to.getCurrency());
        if (credited.signum() <= 0) {
            // 1 копейка — это 0,0001 доллара: списали бы рубли и не зачислили ничего
            throw BusinessException.badRequest("AMOUNT_TOO_SMALL",
                    "Сумма слишком мала для перевода в " + to.getCurrency());
        }
        if (accounts.debitIfEnough(from.getId(), amount) == 0) {
            throw BusinessException.insufficientFunds();
        }
        accounts.credit(to.getId(), credited);

        Transfer transfer = new Transfer();
        transfer.setType(TransferType.OWN);
        transfer.setStatus(TransferStatus.DONE);
        transfer.setUser(users.getReferenceById(userId));
        transfer.setFromAccount(from);
        transfer.setToAccount(to);
        transfer.setToName(to.getTitle());
        transfer.setAmount(amount);
        transfer.setCurrency(from.getCurrency());
        transfer.setCreditedAmount(credited);
        transfer.setCreditedCurrency(to.getCurrency());
        transfer.setRate(rate);
        transfer.setCreatedAt(LocalDateTime.now());
        transfer.setConfirmedAt(LocalDateTime.now());
        transfer.setCompletedAt(LocalDateTime.now());
        transfers.save(transfer);

        addOperation(from, Operation.OUT, "TRANSFER", amount, from.getCurrency(),
                "Перевод на " + to.getTitle(), to.masked());
        addOperation(to, Operation.IN, "TRANSFER", credited, to.getCurrency(),
                "Перевод с " + from.getTitle(), from.masked());
        metrics.transferCreated("OWN");
        metrics.transferFinished(TransferStatus.DONE, null);
        return transfer;
    }

    // ------------------------------------------------- перевод по номеру телефона

    /** Шаг 1: черновик перевода и OTP «по СМС». */
    @Transactional
    public Transfer createPhoneDraft(Long userId, Long fromAccountId, String rawPhone,
                                     BigDecimal amount, String comment, String idempotencyKey) {
        validateAmount(amount);
        Inputs.maxLength(comment, Inputs.COMMENT_MAX, "COMMENT_TOO_LONG", "Комментарий");
        Account from = account(userId, fromAccountId);
        String phone = normalizePhone(rawPhone);

        AppUser sender = users.findById(userId)
                .orElseThrow(() -> BusinessException.notFound("Пользователь не найден"));
        if (phone.equals(sender.getPhone())) {
            throw BusinessException.badRequest("SELF_TRANSFER", "Нельзя перевести самому себе");
        }
        AppUser recipient = users.findByPhone(phone).orElseThrow(() -> BusinessException.badRequest(
                "RECIPIENT_NOT_FOUND", "Клиент с номером " + fmt.phone(phone) + " не найден"));

        BigDecimal limit = properties.transfer().limit();
        if (amount.compareTo(limit) > 0) {
            throw BusinessException.badRequest("LIMIT_EXCEEDED",
                    "Сумма перевода превышает лимит " + fmt.money(limit, from.getCurrency()));
        }
        if (from.getBalance().compareTo(amount) < 0) {
            throw BusinessException.insufficientFunds();
        }

        String otp = sms.generateCode(properties.otp().length());

        Transfer transfer = new Transfer();
        transfer.setType(TransferType.PHONE);
        transfer.setStatus(TransferStatus.DRAFT);
        transfer.setUser(sender);
        transfer.setFromAccount(from);
        transfer.setToPhone(phone);
        transfer.setToUserId(recipient.getId());
        transfer.setToName(recipient.shortName());
        transfer.setAmount(amount);
        transfer.setCurrency(from.getCurrency());
        transfer.setComment(comment == null || comment.isBlank() ? null : comment.trim());
        transfer.setOtpCode(otp);
        transfer.setCreatedAt(LocalDateTime.now());
        transfer.setIdempotencyKey(idempotencyKey);
        transfers.save(transfer);

        sms.send(sender.getPhone(),
                "НТ-Банк: код " + otp + " для перевода " + fmt.money(amount, from.getCurrency())
                        + " получателю " + fmt.phone(phone) + ". Никому не сообщайте код.",
                otp);

        metrics.transferCreated("PHONE");
        log.debug("Создан черновик перевода {} на {}", transfer.getId(), phone);
        return transfer;
    }

    /**
     * Шаг 2: подтверждение кодом. Деньги списываются здесь, дальше перевод уходит в обработку.
     * noRollbackFor — чтобы счётчик попыток ввода OTP и смена статуса сохранялись вместе с ошибкой.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public Transfer confirm(Long userId, UUID transferId, String otp) {
        // Блокировка строки обязательна: без неё одновременные подтверждения (двойной клик,
        // повтор запроса в JMeter) видели статус DRAFT и списывали деньги по несколько раз.
        // С блокировкой второе подтверждение ждёт первое и получает 409.
        Transfer transfer = transfers.findByIdForUpdate(transferId)
                .filter(found -> userId.equals(found.getUserId()))
                .orElseThrow(() -> BusinessException.notFound("Перевод №" + transferId + " не найден"));

        if (transfer.getStatus() != TransferStatus.DRAFT) {
            String message = switch (transfer.getStatus()) {
                case PROCESSING, DONE -> "Перевод уже подтверждён, текущий статус: "
                        + transfer.getStatus().titleRu();
                default -> "Перевод нельзя подтвердить, текущий статус: " + transfer.getStatus().titleRu()
                        + (transfer.getRejectReason() != null ? " — " + transfer.getRejectReason() : "");
            };
            throw new BusinessException(HttpStatus.CONFLICT, "WRONG_STATE", message);
        }
        LocalDateTime deadline = transfer.getCreatedAt().plusMinutes(properties.otp().ttlMinutes());
        if (LocalDateTime.now().isAfter(deadline)) {
            transfer.setStatus(TransferStatus.EXPIRED);
            transfer.setRejectReason("Истёк срок действия кода подтверждения");
            metrics.transferFinished(TransferStatus.EXPIRED, null);
            throw new BusinessException(HttpStatus.CONFLICT, "OTP_EXPIRED",
                    "Код подтверждения истёк, создайте перевод заново");
        }
        if (transfer.getOtpAttempts() >= properties.otp().maxAttempts()) {
            transfer.setStatus(TransferStatus.REJECTED);
            transfer.setRejectReason("Превышено число попыток ввода кода");
            metrics.transferFinished(TransferStatus.REJECTED, null);
            throw new BusinessException(HttpStatus.CONFLICT, "OTP_ATTEMPTS_EXCEEDED",
                    "Превышено число попыток ввода кода, перевод отклонён");
        }
        if (otp == null || !otp.trim().equals(transfer.getOtpCode())) {
            int attempts = transfer.getOtpAttempts() + 1;
            transfer.setOtpAttempts(attempts);
            int left = properties.otp().maxAttempts() - attempts;
            if (left > 0) {
                throw BusinessException.badRequest("OTP_INVALID",
                        "Неверный код подтверждения. Осталось попыток: " + left);
            }
            // последняя попытка израсходована — отклоняем сразу, а не при следующем вводе
            transfer.setStatus(TransferStatus.REJECTED);
            transfer.setRejectReason("Превышено число попыток ввода кода");
            transfer.setCompletedAt(LocalDateTime.now());
            metrics.transferFinished(TransferStatus.REJECTED, null);
            throw new BusinessException(HttpStatus.CONFLICT, "OTP_ATTEMPTS_EXCEEDED",
                    "Превышено число попыток ввода кода, перевод отклонён");
        }

        Account from = transfer.getFromAccount();
        if (accounts.debitIfEnough(from.getId(), transfer.getAmount()) == 0) {
            transfer.setStatus(TransferStatus.REJECTED);
            transfer.setRejectReason("Недостаточно средств на счёте");
            metrics.transferFinished(TransferStatus.REJECTED, null);
            transfer.setCompletedAt(LocalDateTime.now());
            throw BusinessException.insufficientFunds();
        }

        transfer.setStatus(TransferStatus.PROCESSING);
        transfer.setConfirmedAt(LocalDateTime.now());
        transfer.setOtpCode(null);

        addOperation(from, Operation.OUT, "TRANSFER", transfer.getAmount(), transfer.getCurrency(),
                "Перевод по номеру телефона " + fmt.phone(transfer.getToPhone()), transfer.getToName());

        dispatchAfterCommit(transfer.getId());
        return transfer;
    }

    /**
     * Завершение перевода: проверка в антифроде, зачисление получателю или возврат отправителю.
     * Вызывается асинхронно — из очереди в памяти (этап 1) или из консьюмера Kafka (этап 2).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(UUID transferId) {
        // Блокируем строку: повторная доставка события из Kafka подождёт и увидит финальный статус
        Transfer transfer = transfers.findByIdForUpdate(transferId).orElse(null);
        if (transfer == null || transfer.getStatus() != TransferStatus.PROCESSING) {
            return;
        }

        AntifraudClient.Decision decision = antifraud.check(new AntifraudClient.Request(
                transfer.getId().toString(), transfer.getUser().getId(), transfer.getToPhone(),
                transfer.getAmount(), transfer.getCurrency()));

        if (decision.approved()) {
            Account target = recipientAccount(transfer);
            BigDecimal credited = rates.convert(transfer.getAmount(), transfer.getCurrency(), target.getCurrency());
            accounts.credit(target.getId(), credited);
            addOperation(target, Operation.IN, "TRANSFER", credited, target.getCurrency(),
                    "Перевод от " + transfer.getUser().shortName(), fmt.phone(transfer.getUser().getPhone()));

            transfer.setCreditedAmount(credited);
            transfer.setCreditedCurrency(target.getCurrency());
            transfer.setStatus(TransferStatus.DONE);
        } else {
            accounts.credit(transfer.getFromAccount().getId(), transfer.getAmount());
            addOperation(transfer.getFromAccount(), Operation.IN, "REFUND", transfer.getAmount(),
                    transfer.getCurrency(), "Возврат по отклонённому переводу №" + transfer.shortId(),
                    "НТ-Банк");
            transfer.setStatus(TransferStatus.REJECTED);
            transfer.setRejectReason(decision.reason());
        }
        transfer.setCompletedAt(LocalDateTime.now());
        metrics.transferFinished(transfer.getStatus(), transfer.getConfirmedAt());
        log.debug("Перевод {} завершён со статусом {}", transferId, transfer.getStatus());
    }

    // ------------------------------------------------------------------ вспомогательное

    /** Отдаём перевод в обработку только после коммита — иначе воркер не увидит статус PROCESSING. */
    private void dispatchAfterCommit(UUID transferId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatcher.dispatch(transferId);
                }
            });
        } else {
            dispatcher.dispatch(transferId);
        }
    }

    private Account recipientAccount(Transfer transfer) {
        Long recipientId = transfer.getToUserId();
        return accounts.findFirstByUserIdAndCurrencyOrderByIdAsc(recipientId, transfer.getCurrency())
                .or(() -> accounts.findFirstByUserIdAndCurrencyOrderByIdAsc(recipientId, "RUB"))
                .orElseThrow(() -> BusinessException.notFound("У получателя нет подходящего счёта"));
    }

    private Account account(Long userId, Long accountId) {
        return accounts.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> BusinessException.notFound("Счёт №" + accountId + " не найден"));
    }

    private void validateAmount(BigDecimal amount) {
        Inputs.amount(amount);
    }

    private void addOperation(Account account, String direction, String kind, BigDecimal amount,
                              String currency, String description, String counterparty) {
        Operation operation = new Operation();
        operation.setAccount(account);
        operation.setCreatedAt(LocalDateTime.now());
        operation.setDirection(direction);
        operation.setKind(kind);
        operation.setAmount(amount);
        operation.setCurrency(currency);
        operation.setDescription(description);
        operation.setCounterparty(counterparty);
        operations.save(operation);
    }

    /** 8 900 000-00-42, +7(900)0000042 и прочее приводим к виду +79000000042. */
    public static String normalizePhone(String raw) {
        if (raw == null) {
            throw BusinessException.badRequest("PHONE_INVALID", "Укажите номер телефона получателя");
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() == 11 && (digits.startsWith("8") || digits.startsWith("7"))) {
            digits = "7" + digits.substring(1);
        } else if (digits.length() == 10) {
            digits = "7" + digits;
        }
        if (digits.length() != 11 || !digits.startsWith("7")) {
            throw BusinessException.badRequest("PHONE_INVALID",
                    "Номер телефона должен быть в формате +7 900 000 0000");
        }
        return "+" + digits;
    }
}
