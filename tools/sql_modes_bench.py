# -*- coding: utf-8 -*-
"""Замер режимов дефекта «Медленный SQL»: одиночный запрос и параллельная нагрузка.

Запуск:
    python tools/sql_modes_bench.py                       # на текущем объёме истории
    python tools/sql_modes_bench.py --grow 2000000        # сначала нарастить историю до 2 млн
    python tools/sql_modes_bench.py --threads 16 --duration 15

Для каждого режима выписки скрипт меряет:
  * медиану одиночного запроса (страница 20 строк, для N+1 ещё и 100 строк);
  * пропускную способность и перцентили, когда выписку параллельно запрашивают
    --threads клиентов в течение --duration секунд;
  * сколько обращений к таблице operation приходится на один HTTP-запрос:
    индексных сканов (признак N+1) и полных переборов (признак отсутствия индекса).
Перед замерами идёт прогрев, в конце стенд возвращается в штатный режим.
"""
import argparse
import base64
import json
import statistics
import subprocess
import threading
import time
import urllib.error
import urllib.request

ADMIN = "Basic " + base64.b64encode(b"admin:admin").decode()
MODES = [
    (None, "Штатный режим"),
    ("HEAVY_COMPUTE", "Тяжёлое вычисление в БД"),
    ("NO_INDEX", "Нет индекса"),
    ("N_PLUS_ONE", "N+1 запросов"),
]


def call(base, path, payload=None, headers=None, method=None, timeout=120):
    data = json.dumps(payload).encode() if payload is not None else None
    request = urllib.request.Request(base + path, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.getcode(), response.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()
    except Exception as e:
        return 0, str(e).encode()


def clients(base, count):
    """Логинимся разными клиентами, чтобы нагрузка шла по разным счетам."""
    result = []
    for i in range(10, 10 + count):
        code, body = call(base, "/api/v1/auth/login",
                          {"login": "user%04d" % i, "password": "Passw0rd!"})
        token = json.loads(body)["accessToken"]
        code, body = call(base, "/api/v1/accounts", headers={"Authorization": "Bearer " + token})
        account = [a for a in json.loads(body) if a["currency"] == "RUB"][0]["id"]
        result.append((token, account))
    return result


def statement_path(account, size):
    return "/api/v1/accounts/%d/statement?page=0&size=%d" % (account, size)


def single(base, token, account, size, repeats=7):
    samples = []
    for _ in range(repeats):
        started = time.perf_counter()
        call(base, statement_path(account, size), headers={"Authorization": "Bearer " + token})
        samples.append((time.perf_counter() - started) * 1000)
    return statistics.median(samples)


def load(base, users, duration, size=20):
    latencies, errors = [], [0]
    lock = threading.Lock()
    deadline = time.perf_counter() + duration

    def worker(token, account):
        local, local_errors = [], 0
        while time.perf_counter() < deadline:
            started = time.perf_counter()
            code, _ = call(base, statement_path(account, size),
                           headers={"Authorization": "Bearer " + token})
            local.append((time.perf_counter() - started) * 1000)
            if code != 200:
                local_errors += 1
        with lock:
            latencies.extend(local)
            errors[0] += local_errors

    threads = [threading.Thread(target=worker, args=user) for user in users]
    started = time.perf_counter()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    elapsed = time.perf_counter() - started

    latencies.sort()

    def pct(p):
        return latencies[min(len(latencies) - 1, int(len(latencies) * p))] if latencies else 0

    return {"rps": len(latencies) / elapsed, "p50": pct(0.50), "p95": pct(0.95),
            "count": len(latencies), "errors": errors[0]}


def table_stats():
    """Счётчики обращений к таблице operation — те же, что отдаёт postgres-exporter.

    idx_scan     — сколько раз читали через индекс;
    seq_scan     — сколько раз перебирали таблицу целиком;
    seq_tup_read — сколько строк прочитано полными переборами.
    """
    time.sleep(1.5)  # PostgreSQL сбрасывает статистику бэкендов не мгновенно
    try:
        out = subprocess.run(
            ["docker", "exec", "ntbank-postgres", "psql", "-U", "ntbank", "-d", "ntbank", "-tAc",
             "select coalesce(idx_scan, 0), seq_scan, seq_tup_read "
             "from pg_stat_user_tables where relname = 'operation'"],
            capture_output=True, text=True, timeout=20)
        idx, seq, tup = (int(x) for x in out.stdout.strip().split("|"))
        return {"idx": idx, "seq": seq, "tup": tup}
    except Exception:
        return None


def set_mode(base, mode):
    if mode is None:
        call(base, "/api/admin/chaos/reset", {}, {"Authorization": ADMIN}, method="POST")
    else:
        call(base, "/api/admin/chaos", {"slowSqlEnabled": True, "slowSqlMode": mode},
             {"Authorization": ADMIN})


def operations(base):
    code, body = call(base, "/api/admin/chaos", headers={"Authorization": ADMIN})
    return json.loads(body).get("operationsEstimate", 0)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:8080")
    parser.add_argument("--threads", type=int, default=16)
    parser.add_argument("--duration", type=int, default=15)
    parser.add_argument("--grow", type=int, default=0, help="нарастить историю до N операций")
    args = parser.parse_args()

    if args.grow:
        print("Наращиваю историю до %d операций..." % args.grow)
        started = time.time()
        code, body = call(args.base, "/api/admin/history?total=%d" % args.grow, {},
                          {"Authorization": ADMIN}, method="POST", timeout=900)
        print("  %s за %.0f с: %s" % (code, time.time() - started, body.decode()[:200]))

    total = operations(args.base)
    users = clients(args.base, args.threads)
    token, account = users[0]
    print("Операций в базе: ~%d, клиентов под нагрузкой: %d, длительность: %d с"
          % (total, len(users), args.duration))

    # Прогрев: без него первый прогон меряет ещё не скомпилированный JIT-ом код
    set_mode(args.base, None)
    load(args.base, users, 8)
    print("Прогрев завершён\n")

    rows = []
    try:
        for mode, title in MODES:
            set_mode(args.base, mode)
            single(args.base, token, account, 20, repeats=2)
            one = single(args.base, token, account, 20)
            hundred = single(args.base, token, account, 100) if mode == "N_PLUS_ONE" else None

            before = table_stats()
            result = load(args.base, users, args.duration)
            after = table_stats()
            n = result["count"] or 1
            if before and after:
                result["idx"] = (after["idx"] - before["idx"]) / n
                result["seq"] = (after["seq"] - before["seq"]) / n
                result["tup"] = (after["tup"] - before["tup"]) / n
            else:
                result["idx"] = result["seq"] = result["tup"] = 0

            rows.append((title, one, hundred, result))
            print("%-24s одиночный %6.0f мс%s | %6.1f RPS, p50 %5.0f мс, p95 %5.0f мс | "
                  "на запрос: индексных сканов %.1f, полных переборов %.1f, строк перебором %.0f | ошибок %d"
                  % (title, one, (" (100 строк: %.0f мс)" % hundred) if hundred else "",
                     result["rps"], result["p50"], result["p95"],
                     result["idx"], result["seq"], result["tup"], result["errors"]))
    finally:
        set_mode(args.base, None)

    print()
    print("| Режим | Одиночный запрос | RPS | p50 | p95 | Индексных сканов на запрос "
          "| Строк полным перебором на запрос |")
    print("|---|---|---|---|---|---|---|")
    for title, one, hundred, r in rows:
        print("| %s | %.0f мс%s | %.0f | %.0f мс | %.0f мс | %.1f | %s |"
              % (title, one, (" (100 строк — %.0f мс)" % hundred) if hundred else "",
                 r["rps"], r["p50"], r["p95"], r["idx"],
                 "{:,}".format(int(r["tup"])).replace(",", " ")))


if __name__ == "__main__":
    main()
