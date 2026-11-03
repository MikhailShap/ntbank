# -*- coding: utf-8 -*-
"""Проверка стенда НТ-Банк: веб-сценарии и REST API.

Запуск (нужен только Python 3, сторонних библиотек нет):

    python tools/smoke_test.py
    python tools/smoke_test.py http://localhost:8080

Скрипт проходит те же шаги, что и будущий JMeter-сценарий, и печатает результат по каждому.
"""
import io
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from http.cookiejar import CookieJar

BASE = sys.argv[1].rstrip("/") if len(sys.argv) > 1 else "http://localhost:8080"
LOGIN = "user0001"
PASSWORD = "Passw0rd!"
PHONE = "+79000000001"
RECIPIENT = "+79000000042"

passed = []
failed = []


def check(name, condition, detail=""):
    if condition:
        passed.append(name)
        print("  [ OK ] %s%s" % (name, (" — " + detail) if detail else ""))
    else:
        failed.append(name)
        print("  [FAIL] %s%s" % (name, (" — " + detail) if detail else ""))
    return condition


def request(url, data=None, headers=None, method=None, opener=None):
    """Возвращает (код ответа, тело как строка)."""
    if data is not None and not isinstance(data, bytes):
        data = data.encode("utf-8")
    req = urllib.request.Request(BASE + url, data=data, method=method)
    for key, value in (headers or {}).items():
        req.add_header(key, value)
    target = opener or urllib.request.urlopen
    try:
        response = target(req) if opener else urllib.request.urlopen(req)
        return response.getcode(), response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def api(url, payload=None, token=None, method=None, extra_headers=None):
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    headers.update(extra_headers or {})
    body = json.dumps(payload, ensure_ascii=False) if payload is not None else None
    code, text = request(url, body, headers, method)
    try:
        return code, json.loads(text)
    except ValueError:
        return code, text


def section(title):
    print("\n" + title)
    print("-" * len(title))


def main():
    print("Стенд: %s" % BASE)

    # ------------------------------------------------------------------ здоровье
    section("1. Доступность")
    code, body = api("/actuator/health")
    check("GET /actuator/health", code == 200 and body.get("status") == "UP", str(body)[:60])

    # ------------------------------------------------------------------ REST
    section("2. REST API")
    code, tokens = api("/api/v1/auth/login", {"login": LOGIN, "password": PASSWORD})
    ok = check("POST /api/v1/auth/login", code == 200 and "accessToken" in tokens, "код %s" % code)
    if not ok:
        print("\nДальше без токена нет смысла:", tokens)
        return summary()
    token = tokens["accessToken"]

    code, refreshed = api("/api/v1/auth/refresh", {"refreshToken": tokens["refreshToken"]})
    check("POST /api/v1/auth/refresh", code == 200 and "accessToken" in refreshed)

    code, bad = api("/api/v1/accounts")
    check("GET /api/v1/accounts без токена -> 401", code == 401, "код %s" % code)

    code, accounts = api("/api/v1/accounts", token=token)
    ok = check("GET /api/v1/accounts", code == 200 and len(accounts) >= 2,
               "счетов: %s" % (len(accounts) if isinstance(accounts, list) else "?"))
    if not ok:
        return summary()
    rub = [a for a in accounts if a["currency"] == "RUB"][0]

    code, statement = api("/api/v1/accounts/%s/statement?page=0&size=10" % rub["id"], token=token)
    check("GET /api/v1/accounts/{id}/statement",
          code == 200 and statement.get("totalElements", 0) > 0,
          "операций: %s" % statement.get("totalElements"))

    # ------------------------------------------------------- перевод по телефону
    section("3. Перевод по телефону (OTP + ожидание статуса)")
    idem_key = "smoke-%d" % int(time.time())
    code, transfer = api("/api/v1/transfers",
                         {"type": "PHONE", "fromAccountId": rub["id"],
                          "toPhone": RECIPIENT, "amount": 1234.56, "comment": "smoke"},
                         token=token, extra_headers={"Idempotency-Key": idem_key})
    ok = check("POST /api/v1/transfers", code == 201 and transfer.get("status") == "DRAFT",
               "transferId=%s" % transfer.get("transferId"))
    if not ok:
        print("   ответ:", transfer)
        return summary()
    transfer_id = transfer["transferId"]

    code, repeated = api("/api/v1/transfers",
                         {"type": "PHONE", "fromAccountId": rub["id"],
                          "toPhone": RECIPIENT, "amount": 1234.56},
                         token=token, extra_headers={"Idempotency-Key": idem_key})
    check("Повтор с тем же Idempotency-Key -> тот же transferId",
          repeated.get("transferId") == transfer_id)

    code, sms = api("/api/dev/sms/last?phone=" + urllib.parse.quote(PHONE))
    otp = sms.get("code") if isinstance(sms, dict) else None
    check("GET /api/dev/sms/last", code == 200 and otp and len(otp) == 6, "код: %s" % otp)

    code, bad_otp = api("/api/v1/transfers/%s/confirm" % transfer_id, {"otp": "000000"}, token=token)
    check("Подтверждение неверным кодом -> 400", code == 400, "код %s" % code)

    code, confirmed = api("/api/v1/transfers/%s/confirm" % transfer_id, {"otp": otp}, token=token)
    check("POST /api/v1/transfers/{id}/confirm",
          code == 200 and confirmed.get("status") == "PROCESSING",
          "статус: %s" % confirmed.get("status"))

    status = None
    started = time.time()
    while time.time() - started < 20:
        code, current = api("/api/v1/transfers/%s" % transfer_id, token=token)
        status = current.get("status")
        if current.get("finalStatus"):
            break
        time.sleep(0.5)
    check("Асинхронная обработка завершилась", status in ("DONE", "REJECTED"),
          "статус: %s за %.1f с" % (status, time.time() - started))

    # ------------------------------------------------------------------ платежи
    section("4. Услуги и платежи")
    code, services = api("/api/v1/services?query=" + urllib.parse.quote("Горсвет"), token=token)
    ok = check("GET /api/v1/services?query=", code == 200 and services.get("totalElements", 0) > 0,
               "найдено: %s" % services.get("totalElements"))
    if ok:
        service_id = services["content"][0]["id"]
        pay_key = "smoke-pay-%d" % int(time.time())
        code, payment = api("/api/v1/payments",
                            {"serviceId": service_id, "accountId": rub["id"],
                             "personalAccount": "770041289", "amount": 1500,
                             "saveTemplate": True, "templateName": "Смоук-шаблон"},
                            token=token, extra_headers={"Idempotency-Key": pay_key})
        check("POST /api/v1/payments", code == 201 and payment.get("paymentId"),
              "paymentId=%s" % payment.get("paymentId"))

        code, again = api("/api/v1/payments",
                          {"serviceId": service_id, "accountId": rub["id"],
                           "personalAccount": "770041289", "amount": 1500},
                          token=token, extra_headers={"Idempotency-Key": pay_key})
        check("Повтор платежа с тем же ключом -> тот же paymentId",
              again.get("paymentId") == payment.get("paymentId"))

        code, templates = api("/api/v1/templates", token=token)
        check("GET /api/v1/templates", code == 200 and len(templates) > 0,
              "шаблонов: %s" % (len(templates) if isinstance(templates, list) else "?"))

    code, conflict = api("/api/v1/transfers",
                         {"type": "OWN", "fromAccountId": rub["id"],
                          "toAccountId": rub["id"], "amount": 10}, token=token)
    check("Перевод на тот же счёт -> 400", code == 400, "код %s" % code)

    # ------------------------------------------------------------------ веб
    section("5. Веб-интерфейс (HTML-формы и CSRF)")
    jar = CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))

    code, html = request("/login", opener=opener.open)
    match = re.search(r'name="_csrf"\s+value="([^"]+)"', html)
    check("GET /login содержит CSRF-токен", code == 200 and match is not None)
    if not match:
        return summary()

    form = urllib.parse.urlencode({"login": LOGIN, "password": PASSWORD, "_csrf": match.group(1)})
    code, html = request("/login", form,
                         {"Content-Type": "application/x-www-form-urlencoded"},
                         opener=opener.open)
    check("POST /login -> главная", code == 200 and "Здравствуйте," in html,
          "длина ответа %d" % len(html))

    code, html = request("/accounts", opener=opener.open)
    check("GET /accounts", code == 200 and "Счета и карты" in html)

    code, html = request("/accounts/%s/statement?page=0&size=20" % rub["id"], opener=opener.open)
    check("GET /accounts/{id}/statement", code == 200 and "Выписка" in html)

    code, html = request("/transfers/phone", opener=opener.open)
    csrf = re.search(r'name="_csrf"\s+value="([^"]+)"', html)
    check("GET /transfers/phone содержит CSRF-токен", code == 200 and csrf is not None)

    if csrf:
        form = urllib.parse.urlencode({"fromAccountId": rub["id"], "phone": RECIPIENT,
                                       "amount": "777.77", "comment": "web smoke",
                                       "_csrf": csrf.group(1)})
        code, html = request("/transfers/phone", form,
                             {"Content-Type": "application/x-www-form-urlencoded"},
                             opener=opener.open)
        web_id = re.search(r'([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})', html)
        check("POST /transfers/phone -> страница перевода",
              code == 200 and web_id is not None,
              "transferId=%s" % (web_id.group(1) if web_id else "не найден"))

        code, sms_page = request("/dev/sms?phone=" + urllib.parse.quote(PHONE))
        otp_match = re.search(r'class="sms__code"[^>]*>(\d{6})<', sms_page)
        check("GET /dev/sms показывает код", code == 200 and otp_match is not None,
              "код: %s" % (otp_match.group(1) if otp_match else "не найден"))

        if web_id and otp_match:
            page_csrf = re.search(r'name="_csrf"\s+value="([^"]+)"', html)
            form = urllib.parse.urlencode({"otp": otp_match.group(1),
                                           "_csrf": page_csrf.group(1)})
            code, html = request("/transfers/%s/confirm" % web_id.group(1), form,
                                 {"Content-Type": "application/x-www-form-urlencoded"},
                                 opener=opener.open)
            check("POST /transfers/{id}/confirm -> текст успеха",
                  code == 200 and "отправлен на обработку" in html)

    code, html = request("/payments?query=" + urllib.parse.quote("мобильная"), opener=opener.open)
    check("GET /payments с поиском", code == 200 and "Найдено услуг" in html)

    code, html = request("/logout", opener=opener.open)
    check("GET /logout", code == 200)

    # --------------------------------------------------- внешние зависимости
    section("6. Внешние зависимости (полный стенд)")
    antifraud = antifraud_metrics()
    if antifraud is None:
        print("  [ -- ] Сервис «Антифрод» не отвечает на :8081 — похоже, запущен "
              "облегчённый профиль, проверка пропущена")
    else:
        check("Сервис «Антифрод» отвечает", True, "проверок всего: %d" % antifraud)
        check("Банк обращался в «Антифрод»", antifraud > 0,
              "счётчик antifraud_checks_total = %d" % antifraud)

    return summary()


def antifraud_metrics():
    """Сумма счётчика antifraud_checks_total из метрик заглушки, либо None, если её нет."""
    try:
        request_obj = urllib.request.Request("http://localhost:8081/actuator/prometheus")
        with urllib.request.urlopen(request_obj, timeout=3) as response:
            body = response.read().decode("utf-8", "replace")
    except Exception:
        return None
    total = 0.0
    for line in body.splitlines():
        if line.startswith("antifraud_checks_total"):
            try:
                total += float(line.rsplit(" ", 1)[1])
            except (IndexError, ValueError):
                pass
    return int(total)


def summary():
    print("\n" + "=" * 50)
    print("Успешно: %d, с ошибками: %d" % (len(passed), len(failed)))
    if failed:
        print("Не прошли:")
        for name in failed:
            print("  - " + name)
    print("=" * 50)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
