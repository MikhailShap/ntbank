# -*- coding: utf-8 -*-
"""Проверка критерия приёмки: каждый дефект заметно проявляется на дашборде под нагрузкой.

Запуск (нужен полный стенд с мониторингом):
    python tools/dashboard_defects_check.py              # все дефекты, около 13 минут
    python tools/dashboard_defects_check.py пул kafka    # только дефекты, в названии которых есть слово

Скрипт держит фоновую нагрузку через REST (счета, выписка, переводы, каталог услуг),
по очереди включает каждый дефект и сравнивает метрику панели дашборда «НТ-Банк»
до и во время дефекта — тем же запросом PromQL, что и в Grafana. В конце стенд
возвращается в штатный режим. Длится около 12 минут.
"""
import base64
import json
import random
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

BANK = "http://localhost:8080"
PROMETHEUS = "http://localhost:9090"
ADMIN = "Basic " + base64.b64encode(b"admin:admin").decode()
THREADS = 12
BASELINE_SECONDS = 25
DEFECT_SECONDS = 40

HTTP = ('application="bank-app", uri!~"/actuator.*|/\\\\*\\\\*|/css/.*|/js/.*|/favicon.*|NOT_FOUND|UNKNOWN"')

# Каждый дефект: что включаем, какую панель смотрим, её запрос и правило «заметно»
DEFECTS = [
    ("Задержка ответа", {"latencyEnabled": True, "latencyTarget": "ALL", "latencyMinMs": 300, "latencyMaxMs": 600},
     "Время ответа p95", 'histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{%s}[30s])))' % HTTP,
     "с", lambda before, during: during > before + 0.25),
    ("Ошибки 500", {"errorsEnabled": True, "errorsTarget": "API", "errorPercent": 20},
     "Ошибки 5xx",
     '(sum(rate(http_server_requests_seconds_count{%s, status=~"5.."}[30s])) or vector(0)) '
     '/ sum(rate(http_server_requests_seconds_count{%s}[30s]))' % (HTTP, HTTP),
     "доля", lambda before, during: during > 0.1 and during > before + 0.1),
    ("Медленный SQL: нет индекса", {"slowSqlEnabled": True, "slowSqlMode": "NO_INDEX"},
     "Строк прочитано полным перебором в секунду",
     'sum(rate(pg_stat_user_tables_seq_tup_read{relname="operation"}[30s]))',
     "строк/с", lambda before, during: during > max(before * 10, 100000)),
    ("Медленный SQL: N+1", {"slowSqlEnabled": True, "slowSqlMode": "N_PLUS_ONE"},
     "Обращения к таблице операций (чтений по индексу)",
     'sum(rate(pg_stat_user_tables_idx_scan{relname="operation"}[30s]))',
     "в секунду", lambda before, during: during > before * 3),
    ("Медленный SQL: тяжёлое вычисление", {"slowSqlEnabled": True, "slowSqlMode": "HEAVY_COMPUTE"},
     "Время ответа p95 по эндпоинтам (выписка)",
     'histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket'
     '{application="bank-app", uri="/api/v1/accounts/{id}/statement"}[30s])))',
     "с", lambda before, during: during > before * 5),
    ("Утечка памяти", {"leakEnabled": True, "leakTarget": "ACCOUNTS", "leakKbPerRequest": 20},
     "Heap и утечка памяти (накоплено утечкой)", 'max(ntbank_chaos_leaked_bytes{application="bank-app"})',
     "байт", lambda before, during: during > before + 10 * 1024 * 1024),
    ("Нагрузка на CPU", {"cpuEnabled": True, "cpuTarget": "API", "cpuMillis": 150},
     "CPU процесса и системы", 'max(process_cpu_usage{application="bank-app"})',
     "доля", lambda before, during: during > before * 2 and during > 0.3),
    # Пулы проверяются по отдельности: если урезать оба сразу, Tomcat пропускает внутрь
    # всего несколько запросов, и очередь выстраивается перед приложением, а не за соединением.
    ("Маленький пул соединений к БД", {"hikariMaxPoolSize": 2},
     "Время ожидания соединения из пула (среднее)",
     'sum(rate(hikaricp_connections_acquire_seconds_sum{application="bank-app"}[30s])) / '
     'sum(rate(hikaricp_connections_acquire_seconds_count{application="bank-app"}[30s]))',
     "с", lambda before, during: during > max(before * 5, 0.002), 24),
    # Очередь к потокам Tomcat стоит ДО приложения, поэтому серверный p95 её не видит —
    # видны насыщение пула (занято = максимум) и рост времени ответа у клиента (у JMeter).
    ("Маленький пул потоков Tomcat", {"tomcatMaxThreads": 2},
     "Потоки Tomcat: занято от максимума",
     'max_over_time((sum(tomcat_threads_busy_threads{application="bank-app"}) / '
     'sum(tomcat_threads_config_max_threads{application="bank-app"}))[30s:5s])',
     "доля", lambda before, during: during >= 0.99 and before < 0.5, 24),
    ("Тормозит «Антифрод»", {"antifraudEnabled": True, "antifraudDelayMinMs": 3000, "antifraudDelayMaxMs": 5000,
                              "antifraudErrorPercent": 30},
     "Время обработки перевода p95",
     'histogram_quantile(0.95, sum by (le) (rate(ntbank_transfer_processing_seconds_bucket{application="bank-app"}[40s])))',
     "с", lambda before, during: during > before + 2),
    ("Kafka: пауза консьюмера", {"kafkaConsumerPaused": True},
     "Лаг консьюмера Kafka", 'sum(kafka_consumergroup_lag{consumergroup="nt-bank-processor"})',
     "сообщений", lambda before, during: during >= 5 and during > before),
    ("Ограничение частоты запросов", {"rateLimitEnabled": True, "rateLimitRps": 3},
     "Ответы по кодам (429)",
     'sum(rate(http_server_requests_seconds_count{%s, status="429"}[30s])) or vector(0)' % HTTP,
     "в секунду", lambda before, during: during > 1),
]

stop = threading.Event()
client_samples = []          # (момент, время ответа в секундах) — как их видит клиент, т.е. JMeter
client_lock = threading.Lock()


def client_p95(window=20):
    """p95 времени ответа на стороне клиента за последние window секунд."""
    since = time.time() - window
    with client_lock:
        values = sorted(latency for moment, latency in client_samples if moment >= since)
    return values[int(len(values) * 0.95)] if values else 0.0


def call(path, payload=None, headers=None, method=None, base=BANK, timeout=60):
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
    except Exception:
        return 0, b""


def prom(query):
    url = PROMETHEUS + "/api/v1/query?" + urllib.parse.urlencode({"query": query})
    with urllib.request.urlopen(url, timeout=20) as response:
        result = json.loads(response.read())["data"]["result"]
    values = [float(r["value"][1]) for r in result if r["value"][1] not in ("NaN", "+Inf", "-Inf")]
    return max(values) if values else 0.0


def chaos(payload):
    return call("/api/admin/chaos", payload, {"Authorization": ADMIN})


def chaos_reset():
    call("/api/admin/chaos/reset", {}, {"Authorization": ADMIN}, method="POST")


def burst_worker(index, step_stop):
    """Клиент без пауз: пока пул справляется, он почти не влияет на время ответа,
    а когда пул становится узким местом — запросы начинают стоять в очереди."""
    login = "user%04d" % (300 + index)
    code, body = call("/api/v1/auth/login", {"login": login, "password": "Passw0rd!"})
    if code != 200:
        return
    auth = {"Authorization": "Bearer " + json.loads(body)["accessToken"]}
    code, body = call("/api/v1/accounts", headers=auth)
    account = [a for a in json.loads(body) if a["currency"] == "RUB"][0]["id"]
    while not step_stop.is_set() and not stop.is_set():
        for path in ("/api/v1/accounts", "/api/v1/accounts/%d/statement?page=0&size=20" % account):
            started = time.time()
            call(path, headers=auth)
            with client_lock:
                client_samples.append((time.time(), time.time() - started))


def worker(index):
    login = "user%04d" % (100 + index)
    phone = "+7900%07d" % (100 + index)

    def sign_in():
        code, body = call("/api/v1/auth/login", {"login": login, "password": "Passw0rd!"})
        return {"Authorization": "Bearer " + json.loads(body)["accessToken"]}, time.time()

    auth, signed_at = sign_in()
    code, body = call("/api/v1/accounts", headers=auth)
    account = [a for a in json.loads(body) if a["currency"] == "RUB"][0]["id"]
    iteration = 0
    while not stop.is_set():
        iteration += 1
        # access-токен живёт 15 минут, а полный прогон — дольше: входим заново раз в 10 минут,
        # иначе к последним шагам все запросы фона получали бы 401 и дефект было бы не видно
        if time.time() - signed_at > 600:
            auth, signed_at = sign_in()
        call("/api/v1/accounts", headers=auth)
        call("/api/v1/accounts/%d/statement?page=0&size=20" % account, headers=auth)
        call("/api/v1/services?query=" + urllib.parse.quote(random.choice(["Горсвет", "связь", "ЖКХ"])), headers=auth)
        if iteration % 3 == 0:
            code, body = call("/api/v1/transfers", {"type": "PHONE", "fromAccountId": account,
                                                     "toPhone": "+79000000077", "amount": 10}, auth)
            if code == 201:
                transfer = json.loads(body)["transferId"]
                code, body = call("/api/dev/sms/last?phone=" + urllib.parse.quote(phone))
                if code == 200:
                    call("/api/v1/transfers/%s/confirm" % transfer, {"otp": json.loads(body)["code"]}, auth)
        time.sleep(0.2)


def main():
    only = [a.lower() for a in sys.argv[1:]]
    selected = [d for d in DEFECTS if not only or any(o in d[0].lower() for o in only)]
    print("Стенд: %s, Prometheus: %s, фоновая нагрузка: %d клиентов, дефектов: %d"
          % (BANK, PROMETHEUS, THREADS, len(selected)))
    chaos_reset()
    threads = [threading.Thread(target=worker, args=(i,), daemon=True) for i in range(THREADS)]
    for t in threads:
        t.start()
    print("Разгон нагрузки 40 с...")
    time.sleep(40)

    rows = []
    try:
        for defect in selected:
            title, payload, panel, query, unit, noticeable = defect[:6]
            burst = defect[6] if len(defect) > 6 else 0
            step_stop = threading.Event()
            for i in range(burst):
                threading.Thread(target=burst_worker, args=(i, step_stop), daemon=True).start()
            if burst:
                print("  + %d клиентов без пауз на время шага «%s»" % (burst, title), flush=True)
            time.sleep(BASELINE_SECONDS)
            before = prom(query)
            client_before = client_p95()
            chaos(payload)
            time.sleep(DEFECT_SECONDS)
            during = prom(query)
            client_during = client_p95()
            flag_on = prom('max(ntbank_chaos_active{application="bank-app"})') >= 1
            chaos_reset()
            step_stop.set()
            if burst:
                print("       время ответа у клиента p95: до %.3f с, во время %.3f с"
                      % (client_before, client_during), flush=True)
            ok = noticeable(before, during) and flag_on
            rows.append((title, panel, before, during, unit, ok, flag_on))
            print("[%s] %-34s %-50s до: %-12s во время: %-12s %s" % (
                "ОК  " if ok else "СБОЙ", title, panel, fmt(before, unit), fmt(during, unit),
                "" if flag_on else "(флаг дефекта на шкале не загорелся!)"), flush=True)
            time.sleep(10)
    finally:
        stop.set()
        chaos_reset()

    print("\n| Дефект | Панель дашборда | До | Во время |")
    print("|---|---|---|---|")
    for title, panel, before, during, unit, ok, flag_on in rows:
        print("| %s | %s | %s | %s |" % (title, panel, fmt(before, unit), fmt(during, unit)))
    failed = [r for r in rows if not r[5]]
    print("\nЗаметно на дашборде: %d из %d" % (len(rows) - len(failed), len(rows)))
    return 1 if failed else 0


def fmt(value, unit):
    if unit == "байт":
        return "%.0f МБ" % (value / 1048576)
    if unit == "доля":
        return "%.0f%%" % (value * 100)
    if unit == "с":
        return "%.2f с" % value
    if value >= 1000:
        return "{:,.0f}".format(value).replace(",", " ") + " " + unit
    return "%.1f %s" % (value, unit)


if __name__ == "__main__":
    sys.exit(main())
