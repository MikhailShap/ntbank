package ru.ntbank.bank.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ntbank.bank.domain.*;
import ru.ntbank.bank.exception.BusinessException;
import ru.ntbank.bank.repo.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Оплата услуг из каталога и шаблоны платежей. */
@Service
public class PaymentService {

    public static final int CATALOG_PAGE_SIZE = 12;

    private final ServiceCatalogRepository catalog;
    private final PaymentRepository payments;
    private final PaymentTemplateRepository templates;
    private final AccountRepository accounts;
    private final OperationRepository operations;
    private final AppUserRepository users;
    private final Fmt fmt;

    public PaymentService(ServiceCatalogRepository catalog, PaymentRepository payments,
                          PaymentTemplateRepository templates, AccountRepository accounts,
                          OperationRepository operations, AppUserRepository users, Fmt fmt) {
        this.catalog = catalog;
        this.payments = payments;
        this.templates = templates;
        this.accounts = accounts;
        this.operations = operations;
        this.users = users;
        this.fmt = fmt;
    }

    // ------------------------------------------------------------------ каталог

    @Transactional(readOnly = true)
    public Page<ServiceCatalogItem> search(String query, String category, int page, int size) {
        return catalog.search(likeLiteral(query == null ? "" : query.trim()), category,
                PageRequest.of(Math.max(page, 0), size <= 0 ? CATALOG_PAGE_SIZE : Math.min(size, 100)));
    }

    /** «%» и «_» в поиске — обычные символы, а не шаблоны LIKE: иначе запрос «%» находил всё. */
    private static String likeLiteral(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Transactional(readOnly = true)
    public List<String> categories() {
        return catalog.categories();
    }

    @Transactional(readOnly = true)
    public ServiceCatalogItem service(Long id) {
        return catalog.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Услуга №" + id + " не найдена"));
    }

    // ------------------------------------------------------------------ оплата

    /**
     * Оплата и, если попросили, сохранение шаблона — одной транзакцией: иначе ошибка
     * при сохранении шаблона оставила бы списанные деньги при ответе «ошибка».
     */
    @Transactional
    public Payment pay(Long userId, Long serviceId, Long accountId, String personalAccount,
                       BigDecimal amount, String idempotencyKey, boolean saveTemplate, String templateName) {
        if (saveTemplate) {
            Inputs.maxLength(templateName == null ? null : templateName.trim(), Inputs.TEMPLATE_NAME_MAX,
                    "TEMPLATE_NAME_TOO_LONG", "Название шаблона");
        }
        Payment payment = pay(userId, serviceId, accountId, personalAccount, amount, idempotencyKey);
        if (saveTemplate) {
            saveTemplate(userId, templateName, serviceId, accountId, personalAccount, amount);
        }
        return payment;
    }

    @Transactional
    public Payment pay(Long userId, Long serviceId, Long accountId, String personalAccount,
                       BigDecimal amount, String idempotencyKey) {
        ServiceCatalogItem service = service(serviceId);
        if (!service.isActive()) {
            throw BusinessException.badRequest("SERVICE_INACTIVE", "Услуга временно недоступна");
        }
        if (personalAccount == null || personalAccount.isBlank()) {
            throw BusinessException.badRequest("ACCOUNT_REQUIRED",
                    "Укажите «" + service.getAccountLabel() + "»");
        }
        Inputs.maxLength(personalAccount.trim(), Inputs.PERSONAL_ACCOUNT_MAX, "PERSONAL_ACCOUNT_TOO_LONG",
                "«" + service.getAccountLabel() + "»");
        Inputs.amount(amount);
        if (amount.compareTo(service.getMinAmount()) < 0 || amount.compareTo(service.getMaxAmount()) > 0) {
            throw BusinessException.badRequest("AMOUNT_OUT_OF_RANGE",
                    "Сумма должна быть от " + fmt.money(service.getMinAmount(), "RUB")
                            + " до " + fmt.money(service.getMaxAmount(), "RUB"));
        }

        Account account = accounts.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> BusinessException.notFound("Счёт №" + accountId + " не найден"));
        if (!"RUB".equals(account.getCurrency())) {
            throw BusinessException.badRequest("CURRENCY_NOT_SUPPORTED",
                    "Оплата услуг возможна только с рублёвого счёта");
        }
        if (accounts.debitIfEnough(account.getId(), amount) == 0) {
            throw BusinessException.insufficientFunds();
        }

        Payment payment = new Payment();
        payment.setUser(users.getReferenceById(userId));
        payment.setAccount(account);
        payment.setService(service);
        payment.setPersonalAccount(personalAccount.trim());
        payment.setAmount(amount);
        payment.setCurrency(account.getCurrency());
        payment.setStatus("DONE");
        payment.setCreatedAt(LocalDateTime.now());
        payment.setIdempotencyKey(idempotencyKey);
        payments.save(payment);

        Operation operation = new Operation();
        operation.setAccount(account);
        operation.setCreatedAt(LocalDateTime.now());
        operation.setDirection(Operation.OUT);
        operation.setKind(operationKind(service.getCategory()));
        operation.setAmount(amount);
        operation.setCurrency(account.getCurrency());
        operation.setDescription(service.getName());
        operation.setCounterparty(service.getProvider() + " · " + personalAccount.trim());
        operations.save(operation);

        return payment;
    }

    private String operationKind(String category) {
        return switch (category) {
            case "Мобильная связь" -> "PAYMENT_MOBILE";
            case "ЖКХ" -> "PAYMENT_UTILITY";
            default -> "PAYMENT";
        };
    }

    @Transactional(readOnly = true)
    public Payment payment(Long userId, Long id) {
        return payments.findByIdAndUserId(id, userId)
                .orElseThrow(() -> BusinessException.notFound("Платёж №" + id + " не найден"));
    }

    @Transactional(readOnly = true)
    public List<Payment> recentPayments(Long userId) {
        return payments.findTop10ByUserIdOrderByCreatedAtDesc(userId);
    }

    // ------------------------------------------------------------------ шаблоны

    @Transactional(readOnly = true)
    public List<PaymentTemplate> templates(Long userId) {
        return templates.findByUserIdOrderByIdDesc(userId);
    }

    @Transactional(readOnly = true)
    public PaymentTemplate template(Long userId, Long id) {
        return templates.findByIdAndUserId(id, userId)
                .orElseThrow(() -> BusinessException.notFound("Шаблон №" + id + " не найден"));
    }

    @Transactional
    public PaymentTemplate saveTemplate(Long userId, String name, Long serviceId, Long accountId,
                                        String personalAccount, BigDecimal amount) {
        ServiceCatalogItem service = service(serviceId);
        Account account = accounts.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> BusinessException.notFound("Счёт №" + accountId + " не найден"));
        Inputs.maxLength(name == null ? null : name.trim(), Inputs.TEMPLATE_NAME_MAX,
                "TEMPLATE_NAME_TOO_LONG", "Название шаблона");

        PaymentTemplate template = new PaymentTemplate();
        template.setUser(users.getReferenceById(userId));
        template.setName(name == null || name.isBlank() ? service.getName() : name.trim());
        template.setService(service);
        template.setAccount(account);
        template.setPersonalAccount(personalAccount.trim());
        template.setAmount(amount);
        template.setCreatedAt(LocalDateTime.now());
        return templates.save(template);
    }

    /** Повтор платежа по шаблону — «в один клик». */
    @Transactional
    public Payment payByTemplate(Long userId, Long templateId, String idempotencyKey) {
        Inputs.idempotencyKey(idempotencyKey);
        PaymentTemplate template = template(userId, templateId);
        Payment payment = pay(userId, template.getService().getId(), template.getAccount().getId(),
                template.getPersonalAccount(), template.getAmount(), idempotencyKey);
        template.setLastUsedAt(LocalDateTime.now());
        return payment;
    }

    @Transactional
    public void deleteTemplate(Long userId, Long templateId) {
        templates.delete(template(userId, templateId));
    }
}
