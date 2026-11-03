package ru.ntbank.bank.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

/**
 * Обращение к внешнему сервису «Антифрод» по HTTP.
 * Если сервис не ответил, перевод отклоняется с понятной причиной —
 * так отказ внешней системы виден и в статусах переводов, и на дашборде.
 */
public class HttpAntifraudClient implements AntifraudClient {

    private static final Logger log = LoggerFactory.getLogger(HttpAntifraudClient.class);

    private final RestClient restClient;

    public HttpAntifraudClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public Decision check(Request request) {
        try {
            CheckResponse response = restClient.post()
                    .uri("/api/antifraud/check")
                    .body(request)
                    .retrieve()
                    .body(CheckResponse.class);

            if (response == null) {
                return Decision.reject("Антифрод вернул пустой ответ", null);
            }
            return response.approved()
                    ? Decision.approve(response.checkId())
                    : Decision.reject(response.reason(), response.checkId());
        } catch (Exception e) {
            log.warn("Антифрод недоступен для перевода {}: {}", request.transferId(), e.toString());
            return Decision.reject("Сервис антифрода недоступен", null);
        }
    }

    @Override
    public boolean updateChaos(Chaos chaos) {
        try {
            restClient.post()
                    .uri("/api/antifraud/chaos")
                    .body(chaos)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("Не удалось передать настройки дефектов в антифрод: {}", e.toString());
            return false;
        }
    }

    @Override
    public String mode() {
        return "http";
    }

    public record CheckResponse(boolean approved, String reason, String checkId,
                                BigDecimal riskScore) {
    }
}
