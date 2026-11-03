# -*- coding: utf-8 -*-
"""Проверка гонки при подтверждении перевода.

Запуск:  python tools/race_check.py [попыток]

Создаёт перевод и шлёт несколько одновременных подтверждений с верным кодом —
так бывает при двойном клике или повторе запроса в JMeter. Деньги должны
списаться ровно один раз, а остальные подтверждения — получить 409.
"""
import json
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://localhost:8080"
LOGIN, PHONE = "user0021", "+79000000021"
PARALLEL = 8


def call(path, payload=None, token=None):
    data = json.dumps(payload).encode() if payload is not None else None
    request = urllib.request.Request(BASE + path, data=data)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.getcode(), json.loads(response.read() or b"null")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"null")


def balance(token, account_id):
    code, accounts = call("/api/v1/accounts", token=token)
    return next(a["balance"] for a in accounts if a["id"] == account_id)


def attempt(token, account_id):
    code, transfer = call("/api/v1/transfers", {"type": "PHONE", "fromAccountId": account_id,
                                                "toPhone": "+79000000088", "amount": 100}, token)
    transfer_id = transfer["transferId"]
    code, sms = call("/api/dev/sms/last?phone=" + urllib.parse.quote(PHONE))
    otp = sms["code"]

    before = balance(token, account_id)
    barrier = threading.Barrier(PARALLEL)
    codes = []

    def confirm():
        barrier.wait()
        status, _ = call("/api/v1/transfers/%s/confirm" % transfer_id, {"otp": otp}, token)
        codes.append(status)

    threads = [threading.Thread(target=confirm) for _ in range(PARALLEL)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    debited = round(before - balance(token, account_id), 2)
    return debited, sorted(codes)


def main():
    tries = int(sys.argv[1]) if len(sys.argv) > 1 else 5
    code, tokens = call("/api/v1/auth/login", {"login": LOGIN, "password": "Passw0rd!"})
    token = tokens["accessToken"]
    code, accounts = call("/api/v1/accounts", token=token)
    account_id = next(a["id"] for a in accounts if a["currency"] == "RUB")

    double = 0
    for i in range(tries):
        debited, codes = attempt(token, account_id)
        ok = debited == 100.0 and codes.count(200) == 1
        double += 0 if ok else 1
        print("Попытка %d: списано %.2f ₽, ответы %s %s"
              % (i + 1, debited, codes, "" if ok else "<- СПИСАНО НЕ ОДИН РАЗ"))
    print("Попыток с повторным списанием: %d из %d" % (double, tries))
    return 1 if double else 0


if __name__ == "__main__":
    sys.exit(main())
