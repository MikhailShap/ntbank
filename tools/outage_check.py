# -*- coding: utf-8 -*-
"""Отказы инфраструктуры: Kafka, «Антифрод» и PostgreSQL останавливаются по-настоящему.

Запуск (нужен полный стенд, контейнеры останавливаются через docker):
    python tools/outage_check.py

Проверяет, что подтверждение перевода не зависает, пока Kafka лежит, а после её возвращения
переводы сами доходят до финального статуса; что без «Антифрода» переводы отклоняются,
а не висят; что после перезапуска PostgreSQL банк снова отвечает без собственного перезапуска.
"""
import subprocess
import sys
import time

from audit_check import api, check, section

USER, RECIPIENT = "user0801", "+79000000802"


def docker(*args):
    subprocess.run(["docker", *args], capture_output=True, text=True, timeout=180)


def token():
    return api("/api/v1/auth/login", {"login": USER, "password": "Passw0rd!"})[1]["accessToken"]


def confirmed_transfer(access, amount=10):
    """Создаёт и подтверждает перевод, возвращает (transferId, время подтверждения в секундах)."""
    rub = [a for a in api("/api/v1/accounts", token=access)[1] if a["currency"] == "RUB"][0]["id"]
    code, transfer, _ = api("/api/v1/transfers", {"fromAccountId": rub, "toPhone": RECIPIENT,
                                                  "amount": amount}, access)
    otp = api("/api/dev/sms/last?phone=%2B79000000801")[1]["code"]
    started = time.time()
    code, _, _ = api("/api/v1/transfers/%s/confirm" % transfer["transferId"], {"otp": otp}, access)
    return transfer["transferId"], code, time.time() - started


def wait_final(access, transfer_id, seconds):
    deadline = time.time() + seconds
    status = None
    while time.time() < deadline:
        status = api("/api/v1/transfers/" + transfer_id, token=access)[1]["status"]
        if status in ("DONE", "REJECTED"):
            return status
        time.sleep(1)
    return status


def wait_health(seconds=120):
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            if api("/actuator/health")[0] == 200:
                return True
        except Exception:
            pass
        time.sleep(2)
    return False


def main():
    access = token()

    section("1. Kafka остановлена")
    docker("stop", "ntbank-kafka")
    transfers = []
    for _ in range(3):
        transfer_id, code, seconds = confirmed_transfer(access)
        transfers.append(transfer_id)
    check("подтверждение не зависает, пока Kafka лежит", code == 200 and seconds < 3,
          "код %s, %.1f с" % (code, seconds))
    time.sleep(20)
    statuses = [api("/api/v1/transfers/" + t, token=access)[1]["status"] for t in transfers]
    check("без Kafka переводы ждут в обработке", all(s == "PROCESSING" for s in statuses), str(statuses))
    docker("start", "ntbank-kafka")
    statuses = [wait_final(access, t, 120) for t in transfers]
    check("после возвращения Kafka переводы сами завершаются (до 2 минут)",
          all(s in ("DONE", "REJECTED") for s in statuses), str(statuses))

    section("2. «Антифрод» остановлен")
    docker("stop", "ntbank-antifraud")
    transfer_id, code, _ = confirmed_transfer(access)
    status = wait_final(access, transfer_id, 30)
    reason = api("/api/v1/transfers/" + transfer_id, token=access)[1].get("rejectReason")
    check("без «Антифрода» перевод отклоняется, а не висит", status == "REJECTED", "%s — %s" % (status, reason))
    docker("start", "ntbank-antifraud")
    time.sleep(15)
    transfer_id, code, _ = confirmed_transfer(access)
    status = wait_final(access, transfer_id, 30)
    reason = api("/api/v1/transfers/" + transfer_id, token=access)[1].get("rejectReason") or ""
    check("после возвращения «Антифрода» переводы снова проверяются",
          status in ("DONE", "REJECTED") and "недоступен" not in reason, "%s %s" % (status, reason))

    section("3. PostgreSQL перезапущен")
    docker("restart", "ntbank-postgres")
    time.sleep(5)
    healthy = wait_health()
    code = None
    for _ in range(10):
        code = api("/api/v1/auth/login", {"login": USER, "password": "Passw0rd!"})[0]
        if code == 200:
            break
        time.sleep(3)
    check("после перезапуска базы банк отвечает сам", healthy and code == 200, "health %s, вход %s" % (healthy, code))
    transfer_id, code, _ = confirmed_transfer(token())
    check("и переводы проходят", code == 200 and wait_final(token(), transfer_id, 30) in ("DONE", "REJECTED"))

    print("\n" + "=" * 50)
    from audit_check import passed, failed
    print("Успешно: %d, с ошибками: %d" % (len(passed), len(failed)))
    for name in failed:
        print("  - " + name)
    return 1 if failed else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    finally:
        for name in ("ntbank-kafka", "ntbank-antifraud", "ntbank-postgres"):
            docker("start", name)
