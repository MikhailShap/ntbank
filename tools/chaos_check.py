# -*- coding: utf-8 -*-
"""Проверка режима «Дефекты»: включает каждый дефект, измеряет эффект и выключает.

Запуск:  python tools/chaos_check.py [http://localhost:8080]

Скрипт сам приводит стенд в штатный режим в конце, даже если проверка не прошла.
"""
import base64
import json
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = sys.argv[1].rstrip("/") if len(sys.argv) > 1 else "http://localhost:8080"
ADMIN = base64.b64encode(b"admin:admin").decode()
LOGIN, PASSWORD, PHONE = "user0003", "Passw0rd!", "+79000000003"

passed, failed = [], []


def check(name, condition, detail=""):
    (passed if condition else failed).append(name)
    print("  [%s] %s%s" % ("ОК  " if condition else "СБОЙ", name, (" — " + detail) if detail else ""))
    return condition


def call(url, payload=None, headers=None, method=None, timeout=60):
    data = json.dumps(payload, ensure_ascii=False).encode() if payload is not None else None
    request = urllib.request.Request(BASE + url if url.startswith("/") else url,
                                     data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.getcode(), response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        return 0, str(e)


def chaos(payload):
    code, body = call("/api/admin/chaos", payload, {"Authorization": "Basic " + ADMIN})
    return json.loads(body) if code == 200 else {}


def chaos_state():
    code, body = call("/api/admin/chaos", headers={"Authorization": "Basic " + ADMIN})
    return json.loads(body) if code == 200 else {}


def chaos_reset():
    call("/api/admin/chaos/reset", {}, {"Authorization": "Basic " + ADMIN}, method="POST")


def table_stats(wait=1.5):
    """Счётчики обращений к таблице operation из pg_stat_user_tables (через docker exec).

    PostgreSQL сбрасывает статистику простаивающего соединения с задержкой до ~10 секунд,
    поэтому для точного счёта по нескольким запросам нужна пауза побольше.
    """
    time.sleep(wait)
    try:
        out = subprocess.run(
            ["docker", "exec", "ntbank-postgres", "psql", "-U", "ntbank", "-d", "ntbank", "-tAc",
             "select coalesce(idx_scan, 0), seq_scan from pg_stat_user_tables where relname = 'operation'"],
            capture_output=True, text=True, timeout=20)
        idx, seq = (int(x) for x in out.stdout.strip().split("|"))
        return {"idx": idx, "seq": seq}
    except Exception:
        return None


def login():
    code, body = call("/api/v1/auth/login", {"login": LOGIN, "password": PASSWORD})
    return json.loads(body)["accessToken"]


def timed(url, token, repeats=3):
    """Медианное время ответа в миллисекундах."""
    samples = []
    for _ in range(repeats):
        started = time.time()
        code, _body = call(url, headers={"Authorization": "Bearer " + token})
        samples.append((time.time() - started) * 1000)
    samples.sort()
    return samples[len(samples) // 2], code


def section(title):
    print("\n" + title)
    print("-" * len(title))


def main():
    print("Стенд: %s" % BASE)
    chaos_reset()
    token = login()
    accounts = json.loads(call("/api/v1/accounts", headers={"Authorization": "Bearer " + token})[1])
    account = [a for a in accounts if a["currency"] == "RUB"][0]["id"]
    statement = "/api/v1/accounts/%d/statement?page=0&size=20" % account

    baseline, _ = timed(statement, token, 5)
    print("Эталонное время выписки: %.0f мс" % baseline)

    # ------------------------------------------------------------------ задержка
    section("1. Задержка ответа")
    chaos({"latencyEnabled": True, "latencyTarget": "STATEMENT",
           "latencyMinMs": 700, "latencyMaxMs": 900})
    slowed, _ = timed(statement, token)
    check("Время выписки выросло", slowed > baseline + 600, "%.0f мс против %.0f мс" % (slowed, baseline))
    other, _ = timed("/api/v1/accounts", token)
    check("Другие эндпоинты не затронуты", other < baseline + 600, "%.0f мс" % other)
    chaos({"latencyEnabled": False})

    # ------------------------------------------------------------------ ошибки
    section("2. Ошибки 500")
    chaos({"errorsEnabled": True, "errorsTarget": "API", "errorPercent": 100})
    code, _ = call("/api/v1/accounts", headers={"Authorization": "Bearer " + token})
    check("REST отвечает 500", code == 500, "код %s" % code)
    admin_code, _ = call("/api/admin/chaos", headers={"Authorization": "Basic " + ADMIN})
    check("Админка не затронута дефектом", admin_code == 200, "код %s" % admin_code)
    chaos({"errorsEnabled": False})

    # ------------------------------------------------------------------ медленный SQL
    section("3. Медленный SQL в выписке (операций в базе ≈ %d)" % chaos_state().get("operationsEstimate", 0))

    chaos({"slowSqlEnabled": True, "slowSqlMode": "HEAVY_COMPUTE"})
    heavy, code = timed(statement, token)
    check("Тяжёлое вычисление: выписка в разы медленнее", heavy > baseline * 3 and code == 200,
          "%.0f мс против %.0f мс" % (heavy, baseline))

    state = chaos({"slowSqlEnabled": True, "slowSqlMode": "NO_INDEX"})
    check("Нет индекса: индекс удалён из базы", state.get("statementIndexPresent") is False)
    scans_before = table_stats(wait=11)
    no_index, code = timed(statement, token)
    scans_after = table_stats(wait=11)
    full_scans = (scans_after["seq"] - scans_before["seq"]) if scans_before and scans_after else None
    check("Нет индекса: выписка идёт полным перебором таблицы",
          code == 200 and (full_scans is None or full_scans > 0),
          "%.0f мс против %.0f мс, полных переборов: %s" % (no_index, baseline, full_scans))

    chaos({"slowSqlEnabled": True, "slowSqlMode": "N_PLUS_ONE"})
    check("Индекс создан обратно при смене режима", chaos_state().get("statementIndexPresent") is True)
    scans_before = table_stats(wait=11)
    n_plus_one, code = timed(statement, token, 10)
    scans_after = table_stats(wait=11)
    per_request = ((scans_after["idx"] - scans_before["idx"]) / 10) if scans_before and scans_after else None
    check("N+1: на одну выписку — по запросу на каждую строку",
          code == 200 and (per_request is None or per_request >= 15),
          "%.0f мс, индексных сканов на запрос: %s"
          % (n_plus_one, "%.0f" % per_request if per_request is not None else "нет данных"))

    chaos({"slowSqlEnabled": False})
    check("После выключения индекс на месте", chaos_state().get("statementIndexPresent") is True)

    # ------------------------------------------------------------------ утечка
    section("4. Утечка памяти (по умолчанию 50 КБ на запрос)")
    state = chaos({"leakEnabled": True, "leakTarget": "ACCOUNTS"})
    kb = state.get("leakKbPerRequest", 0)
    check("Размер по умолчанию — 50 КБ", kb == 50, "%s КБ" % kb)
    for _ in range(40):
        call("/api/v1/accounts", headers={"Authorization": "Bearer " + token})
    state = chaos_state()
    leaked = state.get("leakedBytes", 0)
    check("Память накапливается", leaked >= 40 * kb * 1024,
          "накоплено %.1f МБ, скорость %.1f МБ/с"
          % (leaked / 1048576, state.get("leakBytesPerSecond", 0) / 1048576))
    chaos({"leakEnabled": False, "clearLeaked": True})
    check("Накопленное очищается", chaos_state().get("leakedBytes", -1) == 0)

    # ------------------------------------------------------------------ CPU
    section("5. Нагрузка на CPU")
    chaos({"cpuEnabled": True, "cpuTarget": "ACCOUNTS", "cpuMillis": 400})
    cpu_time, _ = timed("/api/v1/accounts", token)
    check("Время ответа выросло из-за вычислений", cpu_time > 350, "%.0f мс" % cpu_time)
    chaos({"cpuEnabled": False})

    # ------------------------------------------------------------------ пулы
    section("6. Пулы соединений и потоков")
    state = chaos({"hikariMaxPoolSize": 2, "tomcatMaxThreads": 5})
    check("Пул HikariCP уменьшен", state.get("hikariMaxPoolSize") == 2,
          "стало %s при штатных %s" % (state.get("hikariMaxPoolSize"), state.get("hikariDefaultPoolSize")))
    check("Пул потоков Tomcat уменьшен", state.get("tomcatMaxThreads") == 5,
          "стало %s при штатных %s" % (state.get("tomcatMaxThreads"), state.get("tomcatDefaultThreads")))
    code, _ = call("/api/v1/accounts", headers={"Authorization": "Bearer " + token})
    check("Стенд продолжает отвечать с маленькими пулами", code == 200, "код %s" % code)
    chaos({"hikariMaxPoolSize": state.get("hikariDefaultPoolSize", 30),
           "tomcatMaxThreads": state.get("tomcatDefaultThreads", 200)})

    # ------------------------------------------------------------------ rate limit
    section("7. Ограничение частоты запросов")
    chaos({"rateLimitEnabled": True, "rateLimitRps": 2})
    codes = [call("/api/v1/accounts", headers={"Authorization": "Bearer " + token})[0]
             for _ in range(12)]
    check("Появились ответы 429", 429 in codes, "коды: %s" % sorted(set(codes)))
    chaos({"rateLimitEnabled": False})
    time.sleep(1)
    code, _ = call("/api/v1/accounts", headers={"Authorization": "Bearer " + token})
    check("После выключения лимита запросы проходят", code == 200, "код %s" % code)

    # ------------------------------------------------------------------ антифрод
    section("8. Тормозит «Антифрод»")
    state = chaos_state()
    if state.get("antifraudMode") != "http":
        print("  [ -- ] Внешний «Антифрод» не подключён, проверка пропущена")
    else:
        chaos({"antifraudEnabled": True, "antifraudErrorPercent": 100})
        status, reason = run_transfer(token, account)
        check("Перевод отклонён из-за внешней системы",
              status == "REJECTED" and reason and "антифрод" in reason.lower(),
              "%s — %s" % (status, reason))
        chaos({"antifraudEnabled": False, "antifraudErrorPercent": 0})

    # ------------------------------------------------------------------ Kafka
    section("9. Kafka: пауза консьюмера")
    state = chaos_state()
    if not state.get("kafkaAvailable"):
        print("  [ -- ] Kafka в этом запуске не используется, проверка пропущена")
    else:
        chaos({"kafkaConsumerPaused": True})
        transfer_id = start_transfer(token, account)
        time.sleep(9)
        status = transfer_status(token, transfer_id)
        check("На паузе перевод висит в обработке", status == "PROCESSING", "статус: %s" % status)
        chaos({"kafkaConsumerPaused": False})
        final = wait_final(token, transfer_id, 30)
        check("После снятия паузы перевод завершился", final in ("DONE", "REJECTED"),
              "статус: %s" % final)

    chaos_reset()
    state = chaos_state()
    check("«Выключить всё» вернуло штатный режим", not state.get("anyEnabled", True))
    return summary()


# --------------------------------------------------------------------- переводы

def start_transfer(token, account_id):
    auth = {"Authorization": "Bearer " + token}
    code, body = call("/api/v1/transfers",
                      {"type": "PHONE", "fromAccountId": account_id,
                       "toPhone": "+79000000077", "amount": 150}, auth)
    transfer_id = json.loads(body)["transferId"]
    otp = json.loads(call("/api/dev/sms/last?phone=" + urllib.parse.quote(PHONE))[1])["code"]
    call("/api/v1/transfers/%s/confirm" % transfer_id, {"otp": otp}, auth)
    return transfer_id


def transfer_status(token, transfer_id):
    code, body = call("/api/v1/transfers/" + transfer_id,
                      headers={"Authorization": "Bearer " + token})
    return json.loads(body).get("status")


def wait_final(token, transfer_id, seconds):
    started = time.time()
    status = None
    while time.time() - started < seconds:
        code, body = call("/api/v1/transfers/" + transfer_id,
                          headers={"Authorization": "Bearer " + token})
        data = json.loads(body)
        status = data.get("status")
        if data.get("finalStatus"):
            return status
        time.sleep(0.5)
    return status


def run_transfer(token, account_id):
    transfer_id = start_transfer(token, account_id)
    started = time.time()
    while time.time() - started < 30:
        code, body = call("/api/v1/transfers/" + transfer_id,
                          headers={"Authorization": "Bearer " + token})
        data = json.loads(body)
        if data.get("finalStatus"):
            return data.get("status"), data.get("rejectReason")
        time.sleep(0.5)
    return "TIMEOUT", None


def summary():
    print("\n" + "=" * 52)
    print("Успешно: %d, с ошибками: %d" % (len(passed), len(failed)))
    for name in failed:
        print("  - " + name)
    print("=" * 52)
    return 1 if failed else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    finally:
        chaos_reset()
