# -*- coding: utf-8 -*-
"""Проверка Idempotency-Key для переводов и платежей.

Запуск:  python tools/idempotency_check.py

1. Восемь одновременных запросов с одним ключом — деньги списываются один раз,
   все восемь получают 201 и один и тот же идентификатор операции.
2. Повтор после завершения — тот же ответ с заголовком Idempotency-Replayed: true.
3. Если первая попытка завершилась ошибкой, повтор с тем же ключом выполняется заново,
   а не отдаёт сохранённую ошибку как успех.
4. Разные ключи — разные операции.
"""
import json
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = "http://localhost:8080"
passed, failed = [], []


def check(name, condition, detail=""):
    (passed if condition else failed).append(name)
    print("  [%s] %s%s" % ("ОК  " if condition else "СБОЙ", name, (" — " + detail) if detail else ""))


def call(path, payload=None, token=None, headers=None):
    data = json.dumps(payload).encode() if payload is not None else None
    request = urllib.request.Request(BASE + path, data=data)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.getcode(), json.loads(response.read() or b"null"), dict(response.headers)
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"null"), dict(e.headers)


def main():
    token = call("/api/v1/auth/login", {"login": "user0023", "password": "Passw0rd!"})[1]["accessToken"]
    accounts = call("/api/v1/accounts", token=token)[1]
    rub = next(a for a in accounts if a["currency"] == "RUB")
    usd = next(a for a in accounts if a["currency"] == "USD")
    service = call("/api/v1/services?query=" + urllib.parse.quote("Горсвет"), token=token)[1]["content"][0]["id"]

    def balance():
        return next(a for a in call("/api/v1/accounts", token=token)[1] if a["id"] == rub["id"])["balance"]

    cases = [
        ("Платёж", "/api/v1/payments", "paymentId",
         {"serviceId": service, "accountId": rub["id"], "personalAccount": "1234567", "amount": 100}),
        ("Перевод между своими", "/api/v1/transfers", "transferId",
         {"type": "OWN", "fromAccountId": rub["id"], "toAccountId": usd["id"], "amount": 100}),
    ]
    for title, path, id_field, body in cases:
        print("\n" + title)
        key = str(uuid.uuid4())
        before = balance()
        barrier = threading.Barrier(8)
        results = []

        def send():
            barrier.wait()
            results.append(call(path, body, token, {"Idempotency-Key": key}))

        threads = [threading.Thread(target=send) for _ in range(8)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        debited = round(before - balance(), 2)
        ids = {str(r[1].get(id_field)) for r in results if r[0] == 201}
        codes = sorted(r[0] for r in results)
        check("8 одновременных запросов: списано один раз", debited == 100.0, "списано %.2f" % debited)
        check("все получили 201 и одну операцию", codes == [201] * 8 and len(ids) == 1,
              "коды %s, операций %d" % (codes, len(ids)))

        code, again, headers = call(path, body, token, {"Idempotency-Key": key})
        check("повтор после завершения отдаёт тот же ответ",
              code == 201 and str(again.get(id_field)) in ids and headers.get("Idempotency-Replayed") == "true")

        other = dict(body)
        code, fresh, _ = call(path, other, token, {"Idempotency-Key": str(uuid.uuid4())})
        check("другой ключ — новая операция", code == 201 and str(fresh.get(id_field)) not in ids)

    print("\nОшибка и повтор с тем же ключом")
    key = str(uuid.uuid4())
    too_much = {"serviceId": service, "accountId": rub["id"], "personalAccount": "1234567", "amount": 99999}
    first = call("/api/v1/payments", too_much, token, {"Idempotency-Key": key})
    second = call("/api/v1/payments", too_much, token, {"Idempotency-Key": key})
    check("ошибка не сохраняется как успешный ответ",
          first[0] in (400, 409) and second[0] == first[0] and second[2].get("Idempotency-Replayed") != "true",
          "коды %s и %s" % (first[0], second[0]))

    print("\nУспешно: %d, с ошибками: %d" % (len(passed), len(failed)))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
