# -*- coding: utf-8 -*-
"""Расширенная проверка стенда: то, чего нет в smoke_test.py.

Запуск:  python tools/audit_check.py

Веб: регистрация, перевод между своими счетами, оплата с шаблоном, ошибки рядом с полями,
выписка с фильтрами, CSRF, доступ к админке, коды ответа страниц ошибок, выход.
REST: все коды ошибок (400, 401, 403, 404, 409, 429, 500), исчерпание попыток OTP,
срок жизни JWT, Swagger. Данные: разброс истории операций после сброса.
"""
import base64
import json
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from http.cookiejar import CookieJar

BASE = "http://localhost:8080"
ADMIN = "Basic " + base64.b64encode(b"admin:admin").decode()
passed, failed = [], []


def check(name, condition, detail=""):
    (passed if condition else failed).append(name)
    print("  [%s] %s%s" % ("ОК  " if condition else "СБОЙ", name, (" — " + detail) if detail else ""))
    return condition


def section(title):
    print("\n" + title)
    print("-" * len(title))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class Browser:
    """Клиент с cookie: как браузер или JMeter с Cookie Manager."""

    def __init__(self, follow=True):
        self.jar = CookieJar()
        handlers = [urllib.request.HTTPCookieProcessor(self.jar)]
        if not follow:
            handlers.append(NoRedirect())
        self.opener = urllib.request.build_opener(*handlers)

    def get(self, path):
        return self._open(urllib.request.Request(BASE + path))

    def post(self, path, fields):
        data = urllib.parse.urlencode(fields).encode()
        request = urllib.request.Request(BASE + path, data=data)
        request.add_header("Content-Type", "application/x-www-form-urlencoded")
        return self._open(request)

    def _open(self, request):
        try:
            with self.opener.open(request, timeout=60) as response:
                return response.getcode(), response.read().decode("utf-8", "replace"), response.headers
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode("utf-8", "replace"), e.headers

    def csrf(self, path):
        code, html, _ = self.get(path)
        match = re.search(r'name="_csrf"\s+value="([^"]+)"', html)
        return match.group(1) if match else None, html

    def login(self, login, password):
        token, _ = self.csrf("/login")
        return self.post("/login", {"login": login, "password": password, "_csrf": token})


def api(path, payload=None, token=None, method=None, headers=None):
    data = json.dumps(payload).encode() if payload is not None else None
    request = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            body = response.read()
            return response.getcode(), json.loads(body) if body else None, response.headers
    except urllib.error.HTTPError as e:
        body = e.read()
        try:
            parsed = json.loads(body) if body else None
        except ValueError:
            parsed = body.decode("utf-8", "replace")
        return e.code, parsed, e.headers


def chaos(payload):
    return api("/api/admin/chaos", payload, headers={"Authorization": ADMIN})


def chaos_reset():
    api("/api/admin/chaos/reset", {}, method="POST", headers={"Authorization": ADMIN})


def field_error(html, field):
    """Текст ошибки рядом с полем: блок поля помечен field--invalid и содержит field__error."""
    block = re.search(r'<div class="field field--invalid">((?:(?!<div class="field).)*?name="%s".*?)</div>'
                      % field, html, re.S)
    if not block:
        return None
    message = re.search(r'class="field__error">([^<]+)<', block.group(1))
    return message.group(1).strip() if message else None


def top_alert(html):
    match = re.search(r'alert--danger">.*?class="alert__body">([^<]+)<', html, re.S)
    return match.group(1).strip() if match else None


def money(text):
    return float(text.replace(" ", "").replace(" ", "").replace(",", "."))


def main():
    chaos_reset()

    # ------------------------------------------------------------------ регистрация
    section("1. Регистрация через веб")
    login = "audit%s" % uuid.uuid4().hex[:6]
    phone = "+7955%07d" % (int(time.time()) % 10_000_000)
    browser = Browser()
    token, _ = browser.csrf("/register")
    code, html, _ = browser.post("/register", {"login": "ab", "password": "123", "fullName": "Я",
                                               "phone": phone, "_csrf": token})
    check("короткий логин — ошибка рядом с полем", "Логин должен быть от 4 до 32 символов" in html
          and "field--invalid" in html)
    token, _ = browser.csrf("/register")
    code, html, _ = browser.post("/register", {"login": login, "password": "Secret123",
                                               "fullName": "Аудитов Пётр Сергеевич",
                                               "phone": phone, "_csrf": token})
    check("регистрация -> страница входа с сообщением", "Регистрация завершена" in html, login)
    token, _ = browser.csrf("/register")
    code, html, _ = browser.post("/register", {"login": login, "password": "Secret123",
                                               "fullName": "Двойник", "phone": "+79550000001",
                                               "_csrf": token})
    check("повторный логин — понятная ошибка", "уже занят" in html)

    code, html, _ = browser.login(login, "Secret123")
    check("вход новым клиентом", "Здравствуйте, Аудитов Пётр Сергеевич" in html)
    balances = re.findall(r'class="acct__balance"[^>]*>([^<]+)<', html)
    check("открыты рублёвый и долларовый счета со стартовым балансом",
          len(balances) == 2 and "50 000,00 ₽" in balances[0] and "$ 500,00" in balances[1],
          " | ".join(balances))

    # ------------------------------------------------------------------ перевод между своими
    section("2. Перевод между своими счетами через веб")
    token, html = browser.csrf("/transfers/own")
    ids = re.findall(r'<option value="(\d+)"', html)
    rub_id, usd_id = ids[0], ids[1]
    code, html, _ = browser.post("/transfers/own", {"fromAccountId": rub_id, "toAccountId": rub_id,
                                                    "amount": "100", "_csrf": token})
    check("один и тот же счёт — ошибка рядом с полем «на счёт»",
          field_error(html, "toAccountId") == "Выберите разные счета", str(field_error(html, "toAccountId")))
    token, _ = browser.csrf("/transfers/own")
    code, html, _ = browser.post("/transfers/own", {"fromAccountId": rub_id, "toAccountId": usd_id,
                                                    "amount": "9250", "_csrf": token})
    check("перевод выполнен, текст успеха", re.search(r"Перевод №[0-9a-f-]{36} выполнен", html) is not None)
    code, html, _ = browser.get("/dashboard")
    balances = re.findall(r'class="acct__balance"[^>]*>([^<]+)<', html)
    check("конвертация по курсу: 9 250 ₽ -> $ 100", "40 750,00" in balances[0] and "600,00" in balances[1],
          " | ".join(balances))
    token, _ = browser.csrf("/transfers/own")
    code, html, _ = browser.post("/transfers/own", {"fromAccountId": rub_id, "toAccountId": usd_id,
                                                    "amount": "999999", "_csrf": token})
    check("не хватает денег — ошибка рядом с суммой",
          field_error(html, "amount") == "Недостаточно средств на счёте")

    # ------------------------------------------------------------------ перевод по телефону: ошибки
    section("3. Перевод по телефону: ошибки рядом с полями")
    token, _ = browser.csrf("/transfers/phone")
    code, html, _ = browser.post("/transfers/phone", {"fromAccountId": rub_id, "phone": "+79990000000",
                                                      "amount": "100", "_csrf": token})
    check("неизвестный получатель — ошибка у телефона", "не найден" in (field_error(html, "phone") or ""))
    check("введённый телефон сохранился", 'value="+79990000000"' in html)
    token, _ = browser.csrf("/transfers/phone")
    code, html, _ = browser.post("/transfers/phone", {"fromAccountId": rub_id, "phone": phone,
                                                      "amount": "100", "_csrf": token})
    check("перевод самому себе — ошибка у телефона", field_error(html, "phone") == "Нельзя перевести самому себе")

    # ------------------------------------------------------------------ OTP: попытки
    section("4. Код подтверждения: попытки ввода")
    token, _ = browser.csrf("/transfers/phone")
    code, html, _ = browser.post("/transfers/phone", {"fromAccountId": rub_id, "phone": "+79000000050",
                                                      "amount": "100", "_csrf": token})
    transfer_id = re.search(r"/transfers/([0-9a-f-]{36})/confirm", html).group(1)
    left = []
    for attempt in range(3):
        token, _ = browser.csrf("/transfers/" + transfer_id)
        code, html, _ = browser.post("/transfers/%s/confirm" % transfer_id, {"otp": "000000", "_csrf": token})
        left.append(re.search(r"Осталось попыток: (\d+)", html).group(1) if "Осталось попыток" in html else "-")
    check("неверный код — ошибка у поля, попытки уменьшаются",
          field_error(html, "otp") == "Неверный код подтверждения" or left[:2] == ["2", "1"],
          "осталось: %s" % ", ".join(left))
    check("третий неверный код сразу отклоняет перевод",
          "Превышено число попыток" in html and "Отклонён" in html)
    sms = api("/api/dev/sms/last?phone=" + urllib.parse.quote(phone))[1]["code"]
    # формы подтверждения у отклонённого перевода уже нет — токен берём с другой страницы
    token, _ = browser.csrf("/transfers/phone")
    code, html, _ = browser.post("/transfers/%s/confirm" % transfer_id, {"otp": sms, "_csrf": token})
    check("после этого и верный код не проходит", "Перевод нельзя подтвердить" in html
          and "отправлен на обработку" not in html)

    # ------------------------------------------------------------------ оплата и шаблоны
    section("5. Оплата услуги и шаблон через веб")
    code, html, _ = browser.get("/payments?query=" + urllib.parse.quote("Волна"))
    service_id = re.search(r'href="/payments/(\d+)"', html).group(1)
    token, html = browser.csrf("/payments/" + service_id)
    code, html, _ = browser.post("/payments/" + service_id, {"accountId": rub_id, "personalAccount": "9031234567",
                                                             "amount": "5", "_csrf": token})
    check("сумма меньше минимума — ошибка рядом с суммой", "Сумма должна быть от" in (field_error(html, "amount") or ""))
    token, _ = browser.csrf("/payments/" + service_id)
    code, html, _ = browser.post("/payments/" + service_id, {"accountId": rub_id, "personalAccount": "9031234567",
                                                             "amount": "300", "saveTemplate": "true",
                                                             "templateName": "Мой телефон", "_csrf": token})
    check("оплата выполнена, текст успеха", re.search(r"Платёж №\d+ на сумму 300,00 ₽ выполнен", html) is not None)
    code, html, _ = browser.get("/templates")
    template = re.search(r'/templates/(\d+)/pay', html)
    check("шаблон появился", template is not None and "Мой телефон" in html)
    if template:
        token, _ = browser.csrf("/templates")
        code, html, _ = browser.post("/templates/%s/pay" % template.group(1), {"_csrf": token})
        check("повтор по шаблону в один клик", re.search(r"Платёж №\d+ по шаблону выполнен", html) is not None)

    # ------------------------------------------------------------------ выписка
    section("6. Выписка: фильтр, сортировка, страницы")
    user = Browser()
    user.login("user0001", "Passw0rd!")
    code, html, _ = user.get("/accounts")
    account = re.search(r'/accounts/(\d+)/statement', html).group(1)
    code, html, _ = user.get("/accounts/%s/statement?sort=amount_desc&size=10&page=1" % account)
    amounts = [money(a.replace("−", "-").replace("+", "").replace("₽", ""))
               for a in re.findall(r'class="amount amount--(?:in|out)">([^<]+)<', html)]
    check("сортировка по сумме и вторая страница", code == 200 and "Страница <span>2</span>" in html
          and amounts == sorted(amounts, key=lambda v: -abs(v)) or amounts == sorted(amounts, reverse=True),
          "сумм на странице: %d" % len(amounts))
    code, html, _ = user.get("/accounts/%s/statement?from=2000-01-01&to=2000-01-31" % account)
    check("пустой период — дружелюбное пустое состояние", "За выбранный период операций нет" in html)

    # ------------------------------------------------------------------ безопасность и коды страниц
    section("7. CSRF, доступ и коды ответа страниц")
    code, html, _ = user.post("/transfers/phone", {"fromAccountId": account, "phone": "+79000000002", "amount": "1"})
    check("POST без CSRF-токена -> 403", code == 403, "код %s" % code)
    code, _, _ = user.get("/admin")
    check("обычный клиент на /admin -> 403", code == 403, "код %s" % code)
    code, _, _ = user.get("/admin/chaos")
    check("обычный клиент на /admin/chaos -> 403", code == 403, "код %s" % code)
    code, _, headers = Browser(follow=False).get("/dashboard")
    check("без входа /dashboard -> редирект на /login", code == 302 and "/login" in headers.get("Location", ""))
    code, html, _ = user.get("/transfers/" + str(uuid.uuid4()))
    check("чужой или несуществующий перевод -> 404", code == 404 and "не найден" in html, "код %s" % code)
    code, _, _ = user.get("/transfers/abc")
    check("неверный идентификатор в адресе -> 400", code == 400, "код %s" % code)
    code, _, _ = user.get("/accounts/999999/statement")
    check("чужой счёт -> 404", code == 404, "код %s" % code)
    user.get("/logout")
    code, _, _ = Browser(follow=False).get("/dashboard")
    plain = Browser(follow=False)
    plain.jar = user.jar
    plain.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(user.jar), NoRedirect())
    code, _, headers = plain.get("/dashboard")
    check("после выхода сессия недействительна", code == 302 and "/login" in headers.get("Location", ""))

    # ------------------------------------------------------------------ REST: коды ошибок
    section("8. REST: коды ошибок и формат ответа")
    tokens = api("/api/v1/auth/login", {"login": "user0002", "password": "Passw0rd!"})[1]
    token = tokens["accessToken"]
    payload = json.loads(base64.urlsafe_b64decode(token.split(".")[1] + "==="))
    check("access-токен живёт 15 минут", payload["exp"] - payload["iat"] == 900)
    refresh = json.loads(base64.urlsafe_b64decode(tokens["refreshToken"].split(".")[1] + "==="))
    check("refresh-токен живёт 7 дней", refresh["exp"] - refresh["iat"] == 7 * 86400)

    code, body, _ = api("/api/v1/auth/login", {"login": "user0002", "password": "wrong"})
    check("неверный пароль -> 401", code == 401 and body.get("code") == "BAD_CREDENTIALS")
    code, body, _ = api("/api/v1/transfers", {}, token)
    check("пустой запрос -> 400 с ошибками по полям",
          code == 400 and body.get("code") == "VALIDATION_ERROR" and "fromAccountId" in (body.get("fieldErrors") or {}))
    code, body, _ = api("/api/v1/accounts")
    check("без токена -> 401", code == 401)
    code, body, _ = api("/api/v1/accounts", token="abc.def.ghi")
    check("испорченный токен -> 401", code == 401 and body.get("code") == "INVALID_TOKEN")
    code, body, _ = api("/api/v1/accounts", token=tokens["refreshToken"])
    check("refresh вместо access -> 401", code == 401 and body.get("code") == "WRONG_TOKEN_TYPE")
    code, _, _ = api("/api/admin/chaos", headers={
        "Authorization": "Basic " + base64.b64encode(b"user0002:Passw0rd!").decode()})
    check("клиент в админском REST -> 403", code == 403, "код %s" % code)
    code, body, _ = api("/api/v1/transfers/" + str(uuid.uuid4()), token=token)
    check("несуществующий перевод -> 404", code == 404 and body.get("code") == "NOT_FOUND")
    rub = next(a for a in api("/api/v1/accounts", token=token)[1] if a["currency"] == "RUB")
    code, body, _ = api("/api/v1/transfers", {"type": "OWN", "fromAccountId": rub["id"],
                                              "toAccountId": rub["id"] + 1000, "amount": 99999999}, token)
    check("недостаточно средств -> 409", code == 409 and body.get("code") == "INSUFFICIENT_FUNDS",
          "код %s" % code)
    code, body, _ = api("/api/dev/sms/last?phone=%2B79990000000")
    check("СМС на неизвестный номер -> 404", code == 404)

    chaos({"rateLimitEnabled": True, "rateLimitRps": 1})
    codes = [api("/api/v1/accounts", token=token) for _ in range(6)]
    limited = [c for c in codes if c[0] == 429]
    check("rate limit -> 429 с Retry-After", limited and limited[0][2].get("Retry-After") == "1")
    check("429 в том же формате, что остальные ошибки",
          limited and isinstance(limited[0][1], dict) and limited[0][1].get("code") == "RATE_LIMITED",
          str(limited[0][1])[:120] if limited else "")
    chaos_reset()
    chaos({"errorsEnabled": True, "errorsTarget": "API", "errorPercent": 100})
    code, body, _ = api("/api/v1/accounts", token=token)
    check("дефект «Ошибки 500» -> 500", code == 500)
    check("500 в том же формате, что остальные ошибки",
          isinstance(body, dict) and body.get("code") == "CHAOS_ERROR", str(body)[:120])
    chaos_reset()

    # OTP через REST
    code, transfer, _ = api("/api/v1/transfers", {"fromAccountId": rub["id"], "toPhone": "+79000000051",
                                                  "amount": 10}, token)
    for _ in range(3):
        code, body, _ = api("/api/v1/transfers/%s/confirm" % transfer["transferId"], {"otp": "000000"}, token)
    status = api("/api/v1/transfers/" + transfer["transferId"], token=token)[1]["status"]
    check("REST: третий неверный код -> 409 и статус REJECTED",
          code == 409 and body.get("code") == "OTP_ATTEMPTS_EXCEEDED" and status == "REJECTED")

    # ------------------------------------------------------------------ документация
    section("9. Swagger")
    code, _, _ = Browser().get("/swagger-ui")
    check("Swagger UI открывается", code == 200)
    code, docs, _ = api("/v3/api-docs")
    check("описание API содержит все разделы", code == 200 and len(docs.get("paths", {})) >= 15,
          "путей: %d" % len(docs.get("paths", {})))

    # ------------------------------------------------------------------ данные
    section("10. Тестовые данные после сброса")
    code, body, _ = api("/api/dev/reset", {}, method="POST")
    check("сброс за несколько секунд", code == 200 and body["elapsedMs"] < 15000, "%d мс" % body["elapsedMs"])
    out = subprocess.run(["docker", "exec", "ntbank-postgres", "psql", "-U", "ntbank", "-d", "ntbank", "-tAc",
                          "select min(c), max(c) from (select a.user_id, count(*) c from operation o "
                          "join account a on a.id = o.account_id group by a.user_id) t"],
                         capture_output=True, text=True)
    low, high = (int(x) for x in out.stdout.strip().split("|"))
    check("история 50–200 операций на клиента", 50 <= low <= 70 and 180 <= high <= 200, "от %d до %d" % (low, high))
    with open("test-data/users.csv", "rb") as f:
        raw = f.read()
    check("users.csv: UTF-8 без BOM, заголовок и 1000 строк",
          not raw.startswith(b"\xef\xbb\xbf") and raw.startswith(b"login,password,phone\n")
          and raw.count(b"\n") == 1001)

    print("\n" + "=" * 50)
    print("Успешно: %d, с ошибками: %d" % (len(passed), len(failed)))
    for name in failed:
        print("  - " + name)
    return 1 if failed else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    finally:
        chaos_reset()
