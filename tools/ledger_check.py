# -*- coding: utf-8 -*-
"""Сходятся ли деньги после параллельной нагрузки.

Запуск:  python tools/ledger_check.py [секунд] [потоков]     (по умолчанию 60 с, 24 потока)

Скрипт запоминает балансы всех счетов, гоняет вперемешку переводы по телефону (в том числе
заведомо отклоняемые антифродом и с параллельными подтверждениями), переводы между своими
счетами в обе стороны, оплаты и повторы по шаблону, а потом сверяет:
  * изменение баланса каждого счёта = сумма его новых операций в выписке;
  * ни одного отрицательного баланса и ни одного перевода, застрявшего в обработке;
  * суммы переводов и платежей совпадают с проводками (списание, зачисление, возврат).
"""
import random
import subprocess
import sys
import threading
import time
import uuid
from decimal import Decimal

from audit_check import api, check, section

DURATION = int(sys.argv[1]) if len(sys.argv) > 1 else 60
THREADS = int(sys.argv[2]) if len(sys.argv) > 2 else 24
FIRST_USER = 850
stop_at = time.time() + DURATION
counts = {"phone": 0, "own": 0, "pay": 0, "template": 0, "errors500": 0}
lock = threading.Lock()


def sql(query):
    out = subprocess.run(["docker", "exec", "ntbank-postgres", "psql", "-U", "ntbank", "-d", "ntbank",
                          "-tA", "-F", "|", "-c", query], capture_output=True, text=True, timeout=120)
    if out.returncode != 0:
        raise RuntimeError(out.stderr)
    return [line.split("|") for line in out.stdout.strip().splitlines() if line]


def bump(key, code=200):
    with lock:
        counts[key] += 1
        if code >= 500:
            counts["errors500"] += 1


def worker(index):
    login = "user%04d" % (FIRST_USER + index)
    phone = "+7900%07d" % (FIRST_USER + index)
    token = api("/api/v1/auth/login", {"login": login, "password": "Passw0rd!"})[1]["accessToken"]
    accounts = {a["currency"]: a["id"] for a in api("/api/v1/accounts", token=token)[1]}
    rnd = random.Random(index)
    template_id = None
    while time.time() < stop_at:
        action = rnd.random()
        if action < 0.4:
            # перевод соседу по телефону; каждый пятый — на 150 000 ₽+, его отклонит антифрод
            amount = rnd.choice([rnd.randint(1, 300) + 0.37, 150000 + rnd.randint(0, 1000)]) \
                if rnd.random() < 0.2 else rnd.randint(1, 300) + 0.37
            target = "+7900%07d" % (FIRST_USER + (index + rnd.randint(1, THREADS - 1)) % THREADS)
            source = accounts["USD"] if rnd.random() < 0.15 else accounts["RUB"]
            code, transfer, _ = api("/api/v1/transfers", {"fromAccountId": source, "toPhone": target,
                                                          "amount": amount if source == accounts["RUB"] else 1.25},
                                    token, headers={"Idempotency-Key": str(uuid.uuid4())})
            bump("phone", code)
            if code != 201:
                continue
            otp = api("/api/dev/sms/last?phone=" + phone.replace("+", "%2B"))[1]["code"]
            confirm = lambda: bump("phone", api("/api/v1/transfers/%s/confirm" % transfer["transferId"],
                                                {"otp": otp}, token)[0])
            if rnd.random() < 0.3:  # двойное подтверждение одновременно
                pair = [threading.Thread(target=confirm) for _ in range(2)]
                [t.start() for t in pair]
                [t.join() for t in pair]
            else:
                confirm()
        elif action < 0.65:
            pair = [accounts["RUB"], accounts["USD"]]
            rnd.shuffle(pair)
            amount = rnd.randint(93, 900) + 0.5 if pair[0] == accounts["RUB"] else rnd.randint(1, 3) + 0.01
            code = api("/api/v1/transfers", {"type": "OWN", "fromAccountId": pair[0], "toAccountId": pair[1],
                                             "amount": amount}, token)[0]
            bump("own", code)
        elif action < 0.9:
            key = str(uuid.uuid4())
            body = {"serviceId": rnd.randint(1, 100), "accountId": accounts["RUB"],
                    "personalAccount": str(rnd.randint(10000, 99999)), "amount": rnd.randint(100, 900) + 0.99,
                    "saveTemplate": template_id is None}
            # один и тот же ключ дважды параллельно — платёж должен пройти один раз
            results = []
            pair = [threading.Thread(target=lambda: results.append(
                api("/api/v1/payments", body, token, headers={"Idempotency-Key": key}))) for _ in range(2)]
            [t.start() for t in pair]
            [t.join() for t in pair]
            for code, _, _ in results:
                bump("pay", code)
            if template_id is None:
                templates = api("/api/v1/templates", token=token)[1]
                template_id = templates[0]["id"] if templates else None
        elif template_id:
            code = api("/api/v1/templates/%s/pay" % template_id, {}, token,
                       headers={"Idempotency-Key": str(uuid.uuid4())})[0]
            bump("template", code)


def main():
    section("1. Снимок до нагрузки")
    # снимок честный, только если очередь пуста: иначе доезжающие переводы попадут между запросами
    while int(sql("select count(*) from transfer where status = 'PROCESSING'")[0][0]):
        print("  до старта: очередь переводов ещё не разобрана, ждём...")
        time.sleep(10)
    rows = sql("select a.id, a.balance, (select coalesce(max(id), 0) from operation), now() from account a")
    before = {int(r[0]): Decimal(r[1]) for r in rows}
    start_op, start_transfer_time = int(rows[0][2]), rows[0][3]
    print("  счетов: %d, последняя операция: %d" % (len(before), start_op))

    section("2. Нагрузка %d с, %d потоков" % (DURATION, THREADS))
    threads = [threading.Thread(target=worker, args=(i,), daemon=True) for i in range(THREADS)]
    [t.start() for t in threads]
    [t.join() for t in threads]
    print("  запросов: %s" % counts)

    # консьюмер Kafka разбирает очередь медленнее, чем её наполняют 24 потока, — ждём, пока разберёт
    deadline = time.time() + 600
    while time.time() < deadline:
        left = int(sql("select count(*) from transfer where status = 'PROCESSING'")[0][0])
        if not left:
            break
        print("  в обработке ещё %d, ждём..." % left)
        time.sleep(10)

    section("3. Сверка")
    check("ни одного ответа 5xx под нагрузкой", counts["errors500"] == 0, "5xx: %d" % counts["errors500"])
    stuck = int(sql("select count(*) from transfer where status = 'PROCESSING'")[0][0])
    check("ни одного перевода, застрявшего в обработке", stuck == 0, "в обработке: %d" % stuck)
    negative = int(sql("select count(*) from account where balance < 0")[0][0])
    check("ни одного отрицательного баланса", negative == 0, "отрицательных: %d" % negative)

    # балансы и операции — одним запросом, то есть из одного снимка базы
    rows = sql("select a.id, a.balance, coalesce((select sum(case when o.direction = 'IN' then o.amount "
               "else -o.amount end) from operation o where o.account_id = a.id and o.id > %d), 0) "
               "from account a" % start_op)
    after = {int(r[0]): Decimal(r[1]) for r in rows}
    moves = {int(r[0]): Decimal(r[2]) for r in rows}
    mismatched = [(acc, after[acc] - before.get(acc, 0), moves.get(acc, 0)) for acc in after
                  if after[acc] - before.get(acc, Decimal(0)) != moves.get(acc, Decimal(0))]
    check("изменение баланса каждого счёта = сумма его новых операций",
          not mismatched, "счетов с расхождением: %d %s" % (len(mismatched), mismatched[:3]))

    since = "confirmed_at >= '%s'" % start_transfer_time
    phone = sql("select coalesce(sum(amount), 0), count(*) from transfer where type = 'PHONE' "
                "and status in ('DONE', 'REJECTED') and confirmed_at is not null and " + since)[0]
    debits = sql("select coalesce(sum(amount), 0), count(*) from operation where id > %d and direction = 'OUT' "
                 "and description like 'Перевод по номеру телефона%%'" % start_op)[0]
    check("списано по переводам по телефону = сумма подтверждённых переводов",
          phone == debits, "переводы %s, списания %s" % (phone, debits))
    done = sql("select coalesce(sum(credited_amount), 0), count(*) from transfer where type = 'PHONE' "
               "and status = 'DONE' and " + since)[0]
    credits = sql("select coalesce(sum(amount), 0), count(*) from operation where id > %d and direction = 'IN' "
                  "and kind = 'TRANSFER' and description like 'Перевод от %%'" % start_op)[0]
    check("зачислено получателям = сумма выполненных переводов", done == credits,
          "переводы %s, зачисления %s" % (done, credits))
    rejected = sql("select coalesce(sum(amount), 0), count(*) from transfer where type = 'PHONE' "
                   "and status = 'REJECTED' and confirmed_at is not null and " + since)[0]
    refunds = sql("select coalesce(sum(amount), 0), count(*) from operation where id > %d and kind = 'REFUND'"
                  % start_op)[0]
    check("возвращено отправителям = сумма отклонённых после подтверждения", rejected == refunds,
          "отклонено %s, возвраты %s" % (rejected, refunds))
    payments = sql("select coalesce(sum(p.amount), 0), count(*) from payment p where p.created_at >= '%s'"
                   % start_transfer_time)[0]
    payment_ops = sql("select coalesce(sum(amount), 0), count(*) from operation where id > %d "
                      "and kind like 'PAYMENT%%'" % start_op)[0]
    check("списано за услуги = сумма платежей", payments == payment_ops,
          "платежи %s, списания %s" % (payments, payment_ops))
    keys = int(sql("select count(*) from (select user_id, idempotency_key from payment where created_at >= '%s' "
                   "and idempotency_key is not null group by 1, 2 having count(*) > 1) t" % start_transfer_time)[0][0])
    check("ни одного платежа, проведённого дважды по одному ключу", keys == 0, "дублей: %d" % keys)

    print("\n" + "=" * 50)
    from audit_check import passed, failed
    print("Успешно: %d, с ошибками: %d" % (len(passed), len(failed)))
    for name in failed:
        print("  - " + name)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
