# -*- coding: utf-8 -*-
"""Генератор дашбордов Grafana для стенда НТ-Банк.

Запуск:  python tools/gen_dashboards.py

Пишет два файла в monitoring/grafana/dashboards:
  * ntbank.json — «НТ-Банк»: приложение, JVM, пулы, Kafka, переводы, PostgreSQL, дефекты;
  * jmeter.json — «JMeter + НТ-Банк»: метрики JMeter из InfluxDB и ключевые метрики
    приложения из Prometheus на одном экране.

Дашборды собираются кодом, а не руками, чтобы их было легко менять и не ломать JSON.
Правьте этот скрипт, а не JSON-файлы: Grafana подхватит изменения в течение 30 секунд.
"""
import io
import json
import os

PROM = {"type": "prometheus", "uid": "prometheus"}
INFLUX = {"type": "influxdb", "uid": "influxdb-jmeter"}

APP = 'application="bank-app"'
# Служебные адреса и статика в графиках только мешают. Админка и сброс данных — тоже: сброс идёт
# несколько секунд, наращивание истории — минуту, и без фильтра один такой вызов в простое
# показывал бы p95 в десятки секунд, хотя клиентская нагрузка тут ни при чём.
HTTP = APP + ', uri!~"/actuator.*|/\\\\*\\\\*|/css/.*|/js/.*|/favicon.*|NOT_FOUND|UNKNOWN|/admin.*|/api/admin/.*|/api/dev/reset"'
HTTP_URI = HTTP + ', uri=~"$uri"'


# --------------------------------------------------------------------------- компоновка

class Dashboard:
    def __init__(self, uid, title, description):
        self.uid = uid
        self.title = title
        self.description = description
        self.panels = []
        self.next_id = 1
        self.x = 0
        self.y = 0
        self.row_h = 0
        self.templating = []
        self.annotations = [{
            "builtIn": 1, "datasource": {"type": "grafana", "uid": "-- Grafana --"},
            "enable": True, "hide": True, "iconColor": "rgba(0, 211, 255, 1)",
            "name": "Annotations & Alerts", "type": "dashboard",
        }]

    def row(self, title):
        self._newline()
        self.panels.append({"type": "row", "title": title, "id": self._id(), "collapsed": False,
                            "gridPos": {"x": 0, "y": self.y, "w": 24, "h": 1}, "panels": []})
        self.y += 1

    def add(self, panel, w, h):
        if self.x + w > 24:
            self._newline()
        panel["id"] = self._id()
        panel["gridPos"] = {"x": self.x, "y": self.y, "w": w, "h": h}
        self.panels.append(panel)
        self.x += w
        self.row_h = max(self.row_h, h)

    def _newline(self):
        if self.x > 0:
            self.y += self.row_h
        self.x = 0
        self.row_h = 0

    def _id(self):
        value = self.next_id
        self.next_id += 1
        return value

    def to_json(self):
        return {
            "uid": self.uid,
            "title": self.title,
            "description": self.description,
            "tags": ["ntbank", "нагрузочное тестирование"],
            "timezone": "browser",
            "editable": True,
            "graphTooltip": 1,  # общий курсор на всех графиках
            "refresh": "5s",
            "time": {"from": "now-15m", "to": "now"},
            "timepicker": {"refresh_intervals": ["5s", "10s", "30s", "1m", "5m"]},
            "schemaVersion": 39,
            "version": 1,
            "templating": {"list": self.templating},
            "annotations": {"list": self.annotations},
            "panels": self.panels,
        }


# --------------------------------------------------------------------------- панели

def prom(expr, legend="", ref="A", instant=False):
    target = {"datasource": PROM, "expr": expr, "legendFormat": legend, "refId": ref}
    if instant:
        target["instant"] = True
        target["range"] = False
    else:
        target["range"] = True
    return target


def influx(query, alias="", ref="A"):
    return {"datasource": INFLUX, "query": query, "rawQuery": True, "resultFormat": "time_series",
            "alias": alias, "refId": ref}


def refs(targets):
    for index, target in enumerate(targets):
        target["refId"] = chr(ord("A") + index)
    return targets


def timeseries(title, targets, unit="short", description="", stack=False, fill=10,
               min_value=None, overrides=None, ds=PROM, legend_calcs=("mean", "max", "lastNotNull")):
    defaults = {
        "unit": unit,
        "custom": {
            "drawStyle": "line", "lineWidth": 1, "fillOpacity": fill, "showPoints": "never",
            "spanNulls": True, "stacking": {"mode": "normal" if stack else "none", "group": "A"},
        },
    }
    if min_value is not None:
        defaults["min"] = min_value
    return {
        "type": "timeseries", "title": title, "description": description, "datasource": ds,
        "targets": refs(targets),
        "fieldConfig": {"defaults": defaults, "overrides": overrides or []},
        "options": {
            "legend": {"displayMode": "table", "placement": "bottom", "showLegend": True,
                       "calcs": list(legend_calcs)},
            "tooltip": {"mode": "multi", "sort": "desc"},
        },
    }


def stat(title, target, unit="short", description="", decimals=None, steps=None, ds=PROM):
    defaults = {
        "unit": unit,
        "color": {"mode": "thresholds"},
        "thresholds": {"mode": "absolute",
                       "steps": steps or [{"color": "blue", "value": None}]},
    }
    if decimals is not None:
        defaults["decimals"] = decimals
    return {
        "type": "stat", "title": title, "description": description, "datasource": ds,
        "targets": refs([target]),
        "fieldConfig": {"defaults": defaults, "overrides": []},
        "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                    "colorMode": "value", "graphMode": "area", "textMode": "value",
                    "justifyMode": "center", "orientation": "auto"},
    }


def defects_timeline(height_rows=0.85):
    return {
        "type": "state-timeline",
        "title": "Включённые дефекты",
        "description": "Красная полоса — дефект включён на странице /admin/chaos. "
                       "Сопоставляйте её с остальными графиками по времени.",
        "datasource": PROM,
        "targets": refs([prom('max by (defect) (ntbank_chaos_active{' + APP + '})', "{{defect}}")]),
        "fieldConfig": {
            "defaults": {
                "color": {"mode": "thresholds"},
                "thresholds": {"mode": "absolute", "steps": [
                    {"color": "#e8f5ee", "value": None}, {"color": "red", "value": 1}]},
                "mappings": [{"type": "value", "options": {
                    "0": {"text": "выкл", "color": "#e8f5ee", "index": 0},
                    "1": {"text": "ВКЛЮЧЁН", "color": "red", "index": 1}}}],
                "custom": {"fillOpacity": 85, "lineWidth": 0},
            },
            "overrides": [],
        },
        "options": {"showValue": "never", "mergeValues": True, "rowHeight": height_rows,
                    "alignValue": "left", "legend": {"showLegend": False},
                    "tooltip": {"mode": "single"}},
    }


def status_overrides():
    def color(regexp, value):
        return {"matcher": {"id": "byRegexp", "options": regexp},
                "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": value}}]}
    return [color("^2..$", "green"), color("^3..$", "blue"),
            color("^4..$", "orange"), color("^5..$", "red")]


def series_color(name, value):
    return {"matcher": {"id": "byName", "options": name},
            "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": value}}]}


def quantiles(metric, labels, by="", title_suffix=""):
    group = "le" + ("," + by if by else "")
    return [
        prom('histogram_quantile(0.50, sum by (%s) (rate(%s_bucket{%s}[$__rate_interval])))'
             % (group, metric, labels), "p50" + title_suffix),
        prom('histogram_quantile(0.95, sum by (%s) (rate(%s_bucket{%s}[$__rate_interval])))'
             % (group, metric, labels), "p95" + title_suffix),
        prom('histogram_quantile(0.99, sum by (%s) (rate(%s_bucket{%s}[$__rate_interval])))'
             % (group, metric, labels), "p99" + title_suffix),
    ]


# --------------------------------------------------------------------------- общие блоки

def app_http_panels(d, uri_filter=True):
    labels = HTTP_URI if uri_filter else HTTP
    d.add(timeseries(
        "Запросов в секунду по эндпоинтам",
        [prom('sum by (method, uri) (rate(http_server_requests_seconds_count{%s}[$__rate_interval]))'
              % labels, "{{method}} {{uri}}")],
        unit="reqps", min_value=0,
        description="Пропускная способность каждого эндпоинта банка."), 12, 9)
    d.add(timeseries(
        "Время ответа p95 по эндпоинтам",
        [prom('histogram_quantile(0.95, sum by (le, method, uri) '
              '(rate(http_server_requests_seconds_bucket{%s}[$__rate_interval])))' % labels,
              "{{method}} {{uri}}")],
        unit="s", min_value=0,
        description="95% запросов к эндпоинту укладываются в это время."), 12, 9)


def jvm_heap_panel():
    return timeseries(
        "Heap и утечка памяти",
        [prom('sum(jvm_memory_used_bytes{%s, area="heap"})' % APP, "занято"),
         prom('sum(jvm_memory_committed_bytes{%s, area="heap"})' % APP, "выделено JVM"),
         prom('max(jvm_memory_max_bytes{%s, area="heap", id=~".*Old Gen"})' % APP, "максимум (-Xmx)"),
         prom('ntbank_chaos_leaked_bytes{%s}' % APP, "накоплено утечкой")],
        unit="bytes", min_value=0,
        overrides=[series_color("максимум (-Xmx)", "red"), series_color("накоплено утечкой", "purple")],
        description="При утечке «занято» растёт пилой вверх, нижняя граница пилы поднимается. "
                    "Когда она подходит к максимуму — скоро OutOfMemoryError.")


def hikari_panel():
    return timeseries(
        "Пул соединений к БД (HikariCP)",
        [prom('sum(hikaricp_connections_active{%s})' % APP, "активные"),
         prom('sum(hikaricp_connections_idle{%s})' % APP, "свободные"),
         prom('sum(hikaricp_connections_pending{%s})' % APP, "ждут соединения"),
         prom('sum(hikaricp_connections_max{%s})' % APP, "максимум пула")],
        unit="short", min_value=0, fill=0,
        overrides=[series_color("ждут соединения", "red"), series_color("максимум пула", "orange")],
        description="Если «ждут соединения» больше нуля — потоки стоят в очереди за соединением. "
                    "Типично при маленьком пуле, медленном SQL и N+1.")


def tomcat_panel():
    return timeseries(
        "Потоки Tomcat",
        [prom('sum(tomcat_threads_busy_threads{%s})' % APP, "заняты"),
         prom('sum(tomcat_threads_current_threads{%s})' % APP, "созданы"),
         prom('sum(tomcat_threads_config_max_threads{%s})' % APP, "максимум")],
        unit="short", min_value=0, fill=0, overrides=[series_color("максимум", "orange")],
        description="Когда «заняты» упираются в «максимум», новые запросы ждут в очереди перед "
                    "приложением. Серверное время ответа (http_server_requests) эту очередь НЕ видит — "
                    "оно считается с момента, когда поток уже взял запрос. Рост времени ответа в этом "
                    "случае видно только у клиента: на дашборде JMeter.")


def kafka_lag_panel():
    return timeseries(
        "Лаг консьюмера Kafka",
        [prom('sum(kafka_consumergroup_lag{consumergroup="nt-bank-processor"})', "сообщений в очереди")],
        unit="short", min_value=0,
        description="Сколько событий о переводах ещё не разобрано. Растёт при паузе "
                    "или замедлении консьюмера.")


def transfer_processing_panel():
    return timeseries(
        "Время обработки перевода (от подтверждения до финального статуса)",
        quantiles("ntbank_transfer_processing_seconds", APP),
        unit="s", min_value=0,
        description="Штатно 1–5 секунд. Растёт, когда тормозит «Антифрод» или стоит консьюмер Kafka.")


# --------------------------------------------------------------------------- «НТ-Банк»

def ntbank_dashboard():
    d = Dashboard("ntbank-main", "НТ-Банк",
                  "Приложение, JVM, пулы, Kafka, переводы и PostgreSQL. "
                  "Сверху — какие дефекты включены.")
    d.templating.append({
        "type": "query", "name": "uri", "label": "Эндпоинт", "datasource": PROM,
        "query": {"query": 'label_values(http_server_requests_seconds_count{%s}, uri)' % HTTP,
                  "refId": "uri"},
        "definition": 'label_values(http_server_requests_seconds_count{%s}, uri)' % HTTP,
        "refresh": 2, "includeAll": True, "multi": True, "allValue": ".*",
        "current": {"selected": True, "text": ["All"], "value": ["$__all"]},
        "sort": 1,
    })

    # ---- обзор
    d.row("Обзор")
    d.add(stat("Запросов в секунду",
               prom('sum(rate(http_server_requests_seconds_count{%s}[1m]))' % HTTP),
               unit="reqps", decimals=1), 4, 4)
    d.add(stat("Ошибки 5xx",
               prom('(sum(rate(http_server_requests_seconds_count{%s, status=~"5.."}[1m])) or vector(0)) '
                    '/ sum(rate(http_server_requests_seconds_count{%s}[1m]))' % (HTTP, HTTP)),
               unit="percentunit", decimals=1,
               steps=[{"color": "green", "value": None}, {"color": "orange", "value": 0.01},
                      {"color": "red", "value": 0.05}]), 4, 4)
    d.add(stat("Время ответа p95",
               prom('histogram_quantile(0.95, sum by (le) '
                    '(rate(http_server_requests_seconds_bucket{%s}[1m])))' % HTTP),
               unit="s", decimals=2,
               steps=[{"color": "green", "value": None}, {"color": "orange", "value": 0.5},
                      {"color": "red", "value": 2}]), 4, 4)
    d.add(stat("Heap занят",
               prom('sum(jvm_memory_used_bytes{%s, area="heap"}) / '
                    'max(jvm_memory_max_bytes{%s, area="heap", id=~".*Old Gen"})' % (APP, APP)),
               unit="percentunit", decimals=0,
               steps=[{"color": "green", "value": None}, {"color": "orange", "value": 0.7},
                      {"color": "red", "value": 0.9}]), 4, 4)
    d.add(stat("Переводов в обработке",
               prom('sum(ntbank_transfers{%s, status="PROCESSING"})' % APP),
               steps=[{"color": "green", "value": None}, {"color": "orange", "value": 50},
                      {"color": "red", "value": 500}]), 4, 4)
    d.add(stat("Лаг Kafka",
               prom('sum(kafka_consumergroup_lag{consumergroup="nt-bank-processor"})'),
               steps=[{"color": "green", "value": None}, {"color": "orange", "value": 20},
                      {"color": "red", "value": 200}]), 4, 4)
    d.add(defects_timeline(), 24, 8)

    # ---- HTTP
    d.row("HTTP: запросы и время ответа")
    app_http_panels(d)
    d.add(timeseries(
        "Время ответа: p50, p95, p99 (выбранные эндпоинты)",
        quantiles("http_server_requests_seconds", HTTP_URI),
        unit="s", min_value=0), 12, 8)
    d.add(timeseries(
        "Ответы по кодам",
        [prom('sum by (status) (rate(http_server_requests_seconds_count{%s}[$__rate_interval]))'
              % HTTP_URI, "{{status}}")],
        unit="reqps", stack=True, fill=40, min_value=0, overrides=status_overrides(),
        description="429 — сработало ограничение частоты, 500 — ошибки, в том числе намеренные."), 12, 8)
    d.add(timeseries(
        "Доля ошибок",
        [prom('(sum(rate(http_server_requests_seconds_count{%s, status=~"5.."}[$__rate_interval])) '
              'or vector(0)) / sum(rate(http_server_requests_seconds_count{%s}[$__rate_interval]))'
              % (HTTP_URI, HTTP_URI), "5xx"),
         prom('(sum(rate(http_server_requests_seconds_count{%s, status=~"4.."}[$__rate_interval])) '
              'or vector(0)) / sum(rate(http_server_requests_seconds_count{%s}[$__rate_interval]))'
              % (HTTP_URI, HTTP_URI), "4xx")],
        unit="percentunit", min_value=0,
        overrides=[series_color("5xx", "red"), series_color("4xx", "orange")]), 24, 7)

    # ---- JVM
    d.row("JVM банка")
    d.add(jvm_heap_panel(), 12, 8)
    d.add(timeseries(
        "Сборки мусора в секунду",
        [prom('sum by (gc) (rate(jvm_gc_pause_seconds_count{%s}[$__rate_interval]))' % APP, "{{gc}}")],
        unit="short", min_value=0,
        description="При утечке памяти сборки учащаются, а освобождают всё меньше."), 6, 8)
    d.add(timeseries(
        "Доля времени в сборке мусора",
        [prom('sum(rate(jvm_gc_pause_seconds_sum{%s}[$__rate_interval]))' % APP, "время в GC")],
        unit="percentunit", min_value=0), 6, 8)
    d.add(timeseries(
        "CPU процесса и системы",
        [prom('process_cpu_usage{%s}' % APP, "процесс банка"),
         prom('system_cpu_usage{%s}' % APP, "вся система")],
        unit="percentunit", min_value=0,
        description="Дефект «Нагрузка на CPU» загоняет процесс в полку."), 12, 7)
    d.add(timeseries(
        "Потоки JVM",
        [prom('jvm_threads_live_threads{%s}' % APP, "живые"),
         prom('jvm_threads_daemon_threads{%s}' % APP, "фоновые"),
         prom('jvm_threads_peak_threads{%s}' % APP, "пик")],
        unit="short", min_value=0, fill=0), 12, 7)

    # ---- пулы
    d.row("Пулы: соединения к БД и потоки Tomcat")
    d.add(hikari_panel(), 12, 8)
    d.add(timeseries(
        "Время ожидания соединения из пула",
        [prom('sum(rate(hikaricp_connections_acquire_seconds_sum{%s}[$__rate_interval])) / '
              'sum(rate(hikaricp_connections_acquire_seconds_count{%s}[$__rate_interval]))' % (APP, APP),
              "среднее ожидание"),
         prom('sum(rate(hikaricp_connections_timeout_total{%s}[$__rate_interval]))' % APP,
              "таймаутов в секунду")],
        unit="s", min_value=0), 6, 8)
    d.add(tomcat_panel(), 6, 8)

    # ---- Kafka и переводы
    d.row("Kafka, переводы и «Антифрод»")
    d.add(timeseries(
        "Сообщения Kafka в секунду",
        [prom('sum(rate(kafka_topic_partition_current_offset{topic="nt-bank.transfers"}[$__rate_interval]))',
              "записано в топик"),
         prom('sum(rate(kafka_consumergroup_current_offset{consumergroup="nt-bank-processor", '
              'topic="nt-bank.transfers"}[$__rate_interval]))', "разобрано консьюмером")],
        unit="short", min_value=0, fill=0,
        description="Если «записано» больше «разобрано» — очередь копится, лаг растёт."), 8, 8)
    d.add(kafka_lag_panel(), 8, 8)
    d.add(transfer_processing_panel(), 8, 8)
    d.add({
        "type": "bargauge", "title": "Переводы по статусам (всего в базе)", "datasource": PROM,
        "targets": refs([prom('sum by (status) (ntbank_transfers{%s})' % APP, "{{status}}", instant=True)]),
        "fieldConfig": {"defaults": {"unit": "short", "color": {"mode": "palette-classic"},
                                     "thresholds": {"mode": "absolute",
                                                    "steps": [{"color": "blue", "value": None}]}},
                        "overrides": [series_color("DONE", "green"), series_color("REJECTED", "red"),
                                      series_color("PROCESSING", "yellow"), series_color("DRAFT", "blue"),
                                      series_color("EXPIRED", "orange")]},
        "options": {"orientation": "horizontal", "displayMode": "gradient", "showUnfilled": True,
                    "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False}},
    }, 8, 8)
    d.add(timeseries(
        "Завершённые переводы в секунду",
        [prom('sum by (status) (rate(ntbank_transfers_finished_total{%s}[$__rate_interval]))' % APP,
              "{{status}}")],
        unit="short", stack=True, fill=40, min_value=0,
        overrides=[series_color("DONE", "green"), series_color("REJECTED", "red"),
                   series_color("EXPIRED", "orange")]), 8, 8)
    d.add(timeseries(
        "«Антифрод»: проверки и время ответа",
        [prom('sum by (result) (rate(antifraud_checks_total[$__rate_interval]))', "{{result}}"),
         prom('histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket'
              '{application="antifraud-stub", uri="/api/antifraud/check"}[$__rate_interval])))',
              "p95 ответа, с")],
        unit="short", min_value=0, fill=0,
        overrides=[series_color("approved", "green"), series_color("rejected", "orange"),
                   series_color("failed", "red")],
        description="failed — внешняя система ответила ошибкой (дефект «Тормозит Антифрод»)."), 8, 8)

    # ---- PostgreSQL
    d.row("PostgreSQL")
    d.add(timeseries(
        "Обращения к таблице операций",
        [prom('sum(rate(pg_stat_user_tables_idx_scan{relname="operation"}[$__rate_interval]))',
              "чтений по индексу в секунду"),
         prom('sum(rate(pg_stat_user_tables_seq_scan{relname="operation"}[$__rate_interval]))',
              "полных переборов в секунду")],
        unit="short", min_value=0, fill=0,
        overrides=[series_color("полных переборов в секунду", "red")],
        description="N+1: чтений по индексу в разы больше, чем запросов выписки. "
                    "«Нет индекса»: чтения по индексу пропадают, появляются полные переборы."), 8, 8)
    d.add(timeseries(
        "Строк прочитано полным перебором в секунду",
        [prom('sum(rate(pg_stat_user_tables_seq_tup_read{relname="operation"}[$__rate_interval]))',
              "строк в секунду")],
        unit="short", min_value=0,
        description="Главный признак отсутствующего индекса: база перебирает миллионы строк."), 8, 8)
    d.add(timeseries(
        "Транзакции и активные запросы",
        [prom('sum(rate(pg_stat_database_xact_commit{datname="ntbank"}[$__rate_interval]))',
              "транзакций в секунду"),
         prom('sum(pg_stat_activity_count{datname="ntbank", state="active"})', "активных запросов")],
        unit="short", min_value=0, fill=0), 8, 8)
    d.add(stat("Операций в истории", prom('max(ntbank_operations_estimate{%s})' % APP),
               description="Нарастить можно на странице /admin."), 4, 4)
    d.add(stat("Размер базы", prom('max(pg_database_size_bytes{datname="ntbank"})'), unit="bytes"), 4, 4)
    return d


# --------------------------------------------------------------------------- «JMeter + НТ-Банк»

def jmeter_dashboard():
    d = Dashboard("ntbank-jmeter", "JMeter + НТ-Банк",
                  "Метрики теста из InfluxDB (Backend Listener) и метрики приложения "
                  "из Prometheus на одном экране.")
    where = '"application" =~ /^$application$/'
    d.templating.append({
        "type": "query", "name": "application", "label": "Тест (application)", "datasource": INFLUX,
        "query": 'SHOW TAG VALUES FROM "jmeter" WITH KEY = "application"',
        "definition": 'SHOW TAG VALUES FROM "jmeter" WITH KEY = "application"',
        "refresh": 2, "includeAll": False, "multi": False, "sort": 1,
    })
    d.templating.append({
        "type": "query", "name": "transaction", "label": "Транзакции", "datasource": INFLUX,
        "query": 'SHOW TAG VALUES FROM "jmeter" WITH KEY = "transaction" WHERE %s' % where,
        "definition": 'SHOW TAG VALUES FROM "jmeter" WITH KEY = "transaction" WHERE %s' % where,
        "regex": "/^(?!all$|internal$).*/",
        "refresh": 2, "includeAll": True, "multi": True, "allValue": ".*",
        "current": {"selected": True, "text": ["All"], "value": ["$__all"]}, "sort": 1,
    })
    d.annotations.append({
        "name": "Старт и окончание теста", "datasource": INFLUX, "enable": True,
        "iconColor": "#5794F2",
        "query": 'SELECT "text" FROM "events" WHERE %s AND $timeFilter' % where,
        "textColumn": "text", "tagsColumn": "title",
    })

    # ---- JMeter
    # Схема Backend Listener (JMeter 5.6): сводная транзакция «all» пишется только со statut=all
    # и только в ней есть countError; ok/ko пишутся по каждому сэмплеру; ошибки по кодам ответа —
    # отдельные серии с тегом responseCode; активные потоки — транзакция «internal».
    d.row("JMeter (InfluxDB)")
    total = '"transaction" = \'all\' AND "statut" = \'all\''
    samplers = '"transaction" !~ /^(all|internal)$/'
    d.add(stat("Активных потоков",
               influx('SELECT last("meanAT") FROM "jmeter" WHERE %s AND "transaction" = \'internal\' '
                      'AND $timeFilter' % where), ds=INFLUX), 4, 4)
    d.add(stat("Запросов в секунду",
               influx('SELECT sum("count") / 5 FROM "jmeter" WHERE %s AND %s AND $timeFilter '
                      'GROUP BY time(5s) fill(none)' % (where, total)),
               unit="reqps", decimals=1, ds=INFLUX), 4, 4)
    d.add(stat("Ошибок",
               influx('SELECT sum("countError") / sum("count") FROM "jmeter" WHERE %s AND %s '
                      'AND $timeFilter' % (where, total)),
               unit="percentunit", decimals=2, ds=INFLUX,
               steps=[{"color": "green", "value": None}, {"color": "orange", "value": 0.01},
                      {"color": "red", "value": 0.05}]), 4, 4)
    d.add(stat("Время ответа p95",
               influx('SELECT last("pct95.0") FROM "jmeter" WHERE %s AND %s AND $timeFilter'
                      % (where, total)),
               unit="ms", decimals=0, ds=INFLUX), 4, 4)
    d.add(stat("Всего запросов",
               influx('SELECT sum("count") FROM "jmeter" WHERE %s AND %s AND $timeFilter'
                      % (where, total)), ds=INFLUX), 4, 4)
    d.add(stat("Всего ошибок",
               influx('SELECT sum("countError") FROM "jmeter" WHERE %s AND %s AND $timeFilter'
                      % (where, total)), ds=INFLUX,
               steps=[{"color": "green", "value": None}, {"color": "red", "value": 1}]), 4, 4)

    d.add(timeseries(
        "Запросов в секунду: успешные и ошибки",
        [influx('SELECT sum("count") / 5 FROM "jmeter" WHERE %s AND %s AND "statut" = \'ok\' '
                'AND $timeFilter GROUP BY time(5s) fill(0)' % (where, samplers), "успешные"),
         influx('SELECT sum("count") / 5 FROM "jmeter" WHERE %s AND %s AND "statut" = \'ko\' '
                'AND $timeFilter GROUP BY time(5s) fill(0)' % (where, samplers), "ошибки")],
        unit="reqps", stack=True, fill=40, min_value=0, ds=INFLUX,
        overrides=[series_color("успешные", "green"), series_color("ошибки", "red")]), 12, 8)
    d.add(timeseries(
        "Активные потоки JMeter",
        [influx('SELECT mean("meanAT") FROM "jmeter" WHERE %s AND "transaction" = \'internal\' '
                'AND $timeFilter GROUP BY time(5s) fill(none)' % where, "активных потоков")],
        unit="short", min_value=0, ds=INFLUX,
        description="Ступени нагрузки хорошо видны здесь."), 12, 8)
    d.add(timeseries(
        "Время ответа p95 по транзакциям",
        [influx('SELECT mean("pct95.0") FROM "jmeter" WHERE %s AND "transaction" =~ /^$transaction$/ '
                'AND "transaction" !~ /^(all|internal)$/ AND "statut" = \'all\' AND $timeFilter '
                'GROUP BY time(5s), "transaction" fill(none)' % where, "$tag_transaction")],
        unit="ms", min_value=0, ds=INFLUX,
        description="Нужен Backend Listener с summaryOnly = false — иначе JMeter отправляет только сводку."), 12, 9)
    d.add(timeseries(
        "Ошибки по транзакциям",
        [influx('SELECT sum("count") / 5 FROM "jmeter" WHERE %s AND "transaction" =~ /^$transaction$/ '
                'AND %s AND "statut" = \'ko\' AND $timeFilter '
                'GROUP BY time(5s), "transaction" fill(0)' % (where, samplers), "$tag_transaction")],
        unit="reqps", min_value=0, ds=INFLUX), 12, 9)
    d.add(timeseries(
        "Ошибки по кодам ответа",
        [influx('SELECT sum("count") / 5 FROM "jmeter" WHERE %s AND "responseCode" =~ /.+/ '
                'AND $timeFilter GROUP BY time(5s), "responseCode", "transaction" fill(0)' % where,
                "$tag_responseCode — $tag_transaction")],
        unit="reqps", min_value=0, ds=INFLUX, stack=True, fill=40,
        description="401 — нет или истёк токен, 429 — ограничение частоты, 500 — ошибка сервера, "
                    "в том числе намеренная из режима «Дефекты»."), 24, 8)
    d.add(timeseries(
        "Время ответа всех запросов: среднее, p90, p95, p99",
        [influx('SELECT mean("avg") FROM "jmeter" WHERE %s AND %s AND $timeFilter '
                'GROUP BY time(5s) fill(none)' % (where, total), "среднее"),
         influx('SELECT mean("pct90.0") FROM "jmeter" WHERE %s AND %s AND $timeFilter '
                'GROUP BY time(5s) fill(none)' % (where, total), "p90"),
         influx('SELECT mean("pct95.0") FROM "jmeter" WHERE %s AND %s AND $timeFilter '
                'GROUP BY time(5s) fill(none)' % (where, total), "p95"),
         influx('SELECT mean("pct99.0") FROM "jmeter" WHERE %s AND %s AND $timeFilter '
                'GROUP BY time(5s) fill(none)' % (where, total), "p99")],
        unit="ms", min_value=0, fill=0, ds=INFLUX), 24, 8)

    # ---- приложение на том же экране
    d.row("Приложение НТ-Банк (Prometheus) за то же время")
    d.add(defects_timeline(), 24, 7)
    app_http_panels(d, uri_filter=False)
    d.add(jvm_heap_panel(), 6, 8)
    d.add(hikari_panel(), 6, 8)
    d.add(tomcat_panel(), 6, 8)
    d.add(timeseries(
        "CPU процесса банка",
        [prom('process_cpu_usage{%s}' % APP, "процесс банка")],
        unit="percentunit", min_value=0), 6, 8)
    d.add(kafka_lag_panel(), 12, 8)
    d.add(transfer_processing_panel(), 12, 8)
    return d


def main():
    target_dir = os.path.normpath(os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "..", "monitoring", "grafana", "dashboards"))
    os.makedirs(target_dir, exist_ok=True)
    for name, dashboard in (("ntbank.json", ntbank_dashboard()), ("jmeter.json", jmeter_dashboard())):
        path = os.path.join(target_dir, name)
        with io.open(path, "w", encoding="utf-8", newline="\n") as f:
            json.dump(dashboard.to_json(), f, ensure_ascii=False, indent=2)
        panels = [p for p in dashboard.panels if p["type"] != "row"]
        print("%s: %d панелей -> %s" % (dashboard.title, len(panels), path))


if __name__ == "__main__":
    main()
