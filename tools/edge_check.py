# -*- coding: utf-8 -*-
"""Граничные случаи: то, что легко отправить из JMeter по ошибке или нарочно.

Запуск:  python tools/edge_check.py

Неверные суммы и длины полей, нестандартные методы и типы содержимого, неверный JSON,
повторы с Idempotency-Key для платежа по шаблону, конвертация копеек, длинное ФИО
при регистрации, ошибки форм рядом с полями, ссылки пагинации с особыми символами.
Ни один случай не должен давать 500 — только понятные 4xx.
"""
import re
import sys
import time
import urllib.parse
import uuid

from audit_check import Browser, api, check, field_error, section, top_alert

USER, SECOND, RECIPIENT_PHONE = "user0701", "user0702", "+79000000703"
PASSWORD = "Passw0rd!"


def token_of(login):
    return api("/api/v1/auth/login", {"login": login, "password": PASSWORD})[1]["accessToken"]


def accounts_of(token):
    accounts = api("/api/v1/accounts", token=token)[1]
    return ({a["currency"]: a for a in accounts}["RUB"]["id"],
            {a["currency"]: a for a in accounts}["USD"]["id"])


def is_client_error(code, body):
    return 400 <= code < 500 and isinstance(body, dict) and body.get("code") and body.get("message")


def main():
    token = token_of(USER)
    rub, usd = accounts_of(token)
    other_token = token_of(SECOND)
    other_rub, _ = accounts_of(other_token)

    # ------------------------------------------------------------------ протокол
    section("1. Метод, адрес и формат запроса")
    code, body, _ = api("/api/v1/nothing-here", token=token)
    check("неизвестный адрес API -> 404 в формате API", code == 404 and is_client_error(code, body),
          "%s %s" % (code, str(body)[:100]))
    code, body, _ = api("/api/v1/accounts", {}, token=token, method="DELETE")
    check("неподдерживаемый метод -> 405 в формате API", code == 405 and is_client_error(code, body),
          "%s %s" % (code, str(body)[:100]))
    code, body, _ = api("/api/v1/transfers", token=token, method="POST",
                        headers={"Content-Type": "text/plain"})
    check("POST без JSON -> 400 или 415 в формате API",
          code in (400, 415) and is_client_error(code, body), "%s %s" % (code, str(body)[:100]))
    code, body, _ = api("/api/v1/transfers", {"fromAccountId": rub, "toPhone": RECIPIENT_PHONE,
                                              "amount": "сто"}, token=token)
    check("сумма строкой -> 400 с понятным текстом",
          code == 400 and is_client_error(code, body) and "JSON parse" not in body["message"],
          "%s %s" % (code, str(body)[:140]))
    code, body, _ = api("/api/v1/transfers/not-a-uuid", token=token)
    check("transferId не UUID -> 400 с понятным текстом",
          code == 400 and is_client_error(code, body) and "Failed to convert" not in body["message"],
          "%s %s" % (code, str(body)[:140]))
    code, body, _ = api("/api/v1/accounts/%s/statement?from=2026-13-45" % rub, token=token)
    check("дата 2026-13-45 -> 400", code == 400 and is_client_error(code, body), "%s" % code)
    code, body, _ = api("/api/v1/accounts/%s/statement?page=-3&size=100000" % rub, token=token)
    check("page=-3, size=100000 -> 200, размер ограничен",
          code == 200 and len(body["content"]) <= 200, "%s" % code)
    code, body, _ = api("/api/v1/accounts/%s/statement" % other_rub, token=token)
    check("выписка по чужому счёту -> 404", code == 404, "%s" % code)

    # ------------------------------------------------------------------ суммы и длины
    section("2. Суммы и длины полей")
    for amount in ("0.001", "100.555", "-5", "0"):
        code, body, _ = api("/api/v1/transfers", {"fromAccountId": rub, "toPhone": RECIPIENT_PHONE,
                                                  "amount": float(amount)}, token=token)
        check("перевод на сумму %s -> 400" % amount, code == 400, "%s %s" % (code, str(body)[:110]))
    code, body, _ = api("/api/v1/transfers", {"fromAccountId": rub, "toPhone": RECIPIENT_PHONE,
                                              "amount": 10, "comment": "к" * 500}, token=token)
    check("комментарий 500 символов -> 400", code == 400 and is_client_error(code, body),
          "%s %s" % (code, str(body)[:110]))
    code, body, _ = api("/api/v1/transfers", {"fromAccountId": rub, "toPhone": RECIPIENT_PHONE,
                                              "amount": 10}, token=token,
                        headers={"Idempotency-Key": "k" * 300})
    check("Idempotency-Key 300 символов -> 400", code == 400 and is_client_error(code, body),
          "%s %s" % (code, str(body)[:110]))
    code, body, _ = api("/api/v1/payments", {"serviceId": 1, "accountId": rub,
                                             "personalAccount": "1" * 100, "amount": 100}, token=token)
    check("лицевой счёт 100 символов -> 400", code == 400 and is_client_error(code, body),
          "%s %s" % (code, str(body)[:110]))
    balance_before = api("/api/v1/accounts/%s" % rub, token=token)[1]["balance"]
    code, body, _ = api("/api/v1/payments", {"serviceId": 1, "accountId": rub, "personalAccount": "12345",
                                             "amount": 100, "saveTemplate": True,
                                             "templateName": "ш" * 300}, token=token)
    balance_after = api("/api/v1/accounts/%s" % rub, token=token)[1]["balance"]
    check("имя шаблона 300 символов -> 400 и деньги не списаны",
          code == 400 and balance_after == balance_before,
          "%s, баланс %s -> %s" % (code, balance_before, balance_after))
    code, body, _ = api("/api/v1/transfers", {"type": "OWN", "fromAccountId": rub, "toAccountId": usd,
                                              "amount": 0.01}, token=token)
    check("1 копейка в доллары (меньше цента) -> 400, а не списание без зачисления",
          code == 400, "%s %s" % (code, str(body)[:120]))
    code, body, _ = api("/api/v1/transfers", {"type": "OWN", "fromAccountId": usd, "toAccountId": rub,
                                              "amount": 1.5}, token=token)
    check("$1,50 в рубли -> 201 и зачислено 138,75 ₽",
          code == 201 and body.get("creditedAmount") == 138.75, "%s %s" % (code, body.get("creditedAmount")))

    # ------------------------------------------------------------------ идемпотентность
    section("3. Платёж по шаблону и Idempotency-Key")
    templates = api("/api/v1/templates", token=token)[1]
    if not templates:
        api("/api/v1/payments", {"serviceId": 1, "accountId": rub, "personalAccount": "555",
                                 "amount": 50, "saveTemplate": True}, token=token)
        templates = api("/api/v1/templates", token=token)[1]
    key = str(uuid.uuid4())
    first = api("/api/v1/templates/%s/pay" % templates[0]["id"], {}, token=token,
                headers={"Idempotency-Key": key})
    second = api("/api/v1/templates/%s/pay" % templates[0]["id"], {}, token=token,
                 headers={"Idempotency-Key": key})
    check("повтор оплаты по шаблону с тем же ключом -> тот же платёж",
          first[1].get("paymentId") and first[1].get("paymentId") == second[1].get("paymentId"),
          "%s и %s" % (first[1].get("paymentId"), second[1].get("paymentId")))

    # ------------------------------------------------------------------ OTP
    section("4. Код подтверждения")
    code, transfer, _ = api("/api/v1/transfers", {"fromAccountId": rub, "toPhone": "8 (900) 000-07-03",
                                                  "amount": 10}, token=token)
    check("телефон в формате 8 (900) 000-07-03 принят", code == 201, "%s" % code)
    transfer_id = transfer["transferId"]
    codes = [api("/api/v1/transfers/%s/confirm" % transfer_id, {"otp": "000000"}, token=token)
             for _ in range(3)]
    status = api("/api/v1/transfers/" + transfer_id, token=token)[1]["status"]
    check("третий неверный код сразу отклоняет перевод",
          codes[2][0] == 409 and codes[2][1].get("code") == "OTP_ATTEMPTS_EXCEEDED" and status == "REJECTED",
          "коды %s, статус %s" % ([c[0] for c in codes], status))
    check("в ошибке неверного кода видно, сколько попыток осталось",
          "осталось" in codes[0][1].get("message", "").lower(), codes[0][1].get("message", ""))

    # ------------------------------------------------------------------ веб
    section("5. Веб-формы")
    browser = Browser()
    browser.login(USER, PASSWORD)
    token_csrf, _ = browser.csrf("/transfers/phone")
    code, html, _ = browser.post("/transfers/phone", {"fromAccountId": rub, "phone": RECIPIENT_PHONE,
                                                      "amount": "100,50", "_csrf": token_csrf})
    check("сумма с запятой «100,50» принимается",
          code == 200 and "/transfers/" in html and "Код подтверждения отправлен" in html,
          "%s %s" % (code, (top_alert(html) or field_error(html, "amount") or "")[:80]))
    token_csrf, _ = browser.csrf("/transfers/phone")
    code, html, _ = browser.post("/transfers/phone", {"fromAccountId": rub, "phone": RECIPIENT_PHONE,
                                                      "amount": "", "_csrf": token_csrf})
    check("пустая сумма -> ошибка у поля, а не страница 400",
          code == 200 and field_error(html, "amount"), "%s %s" % (code, field_error(html, "amount")))
    token_csrf, _ = browser.csrf("/transfers/phone")
    code, html, _ = browser.post("/transfers/phone", {"fromAccountId": rub, "phone": RECIPIENT_PHONE,
                                                      "amount": "10", "comment": "к" * 500,
                                                      "_csrf": token_csrf})
    check("комментарий 500 символов -> ошибка у поля", code == 200 and field_error(html, "comment"),
          "%s %s" % (code, field_error(html, "comment")))
    token_csrf, _ = browser.csrf("/payments/1")
    code, html, _ = browser.post("/payments/1", {"accountId": rub, "personalAccount": "1" * 100,
                                                 "amount": "100", "_csrf": token_csrf})
    check("лицевой счёт 100 символов -> ошибка у поля", code == 200 and field_error(html, "personalAccount"),
          "%s %s" % (code, field_error(html, "personalAccount")))
    code, html, _ = browser.get("/payments?category=" + urllib.parse.quote("Интернет и ТВ"))
    links = re.findall(r'class="pager__item[^"]*"\s+href="([^"]+)"', html) + \
        re.findall(r'href="([^"]+)"\s+class="pager__item', html)
    check("ссылки пагинации каталога закодированы", links and all(" " not in link for link in links),
          links[0] if links else "ссылок нет")

    section("6. Регистрация")
    stamp = int(time.time()) % 1000000
    reg = Browser()
    token_csrf, _ = reg.csrf("/register")
    code, html, _ = reg.post("/register", {"login": "edge%d" % stamp, "password": "Secret123",
                                           "fullName": "Константинопольская-Ахмадуллина Анастасия-Виктория "
                                                       "Александровна-Владимировна",
                                           "phone": "+7955%07d" % stamp, "_csrf": token_csrf})
    check("длинное ФИО (больше 60 символов) -> регистрация проходит",
          code == 200 and "Регистрация завершена" in html, "%s" % code)
    token_csrf, _ = reg.csrf("/register")
    code, html, _ = reg.post("/register", {"login": "edgx%d" % stamp, "password": "Secret123",
                                           "fullName": "Тестов Тест", "phone": "+7 12",
                                           "_csrf": token_csrf})
    check("неверный телефон -> ошибка у поля телефона", code == 200 and field_error(html, "phone"),
          "%s %s" % (code, field_error(html, "phone") or top_alert(html)))

    print("\n" + "=" * 50)
    from audit_check import passed, failed
    print("Успешно: %d, с ошибками: %d" % (len(passed), len(failed)))
    for name in failed:
        print("  - " + name)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
