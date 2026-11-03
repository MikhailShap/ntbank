# -*- coding: utf-8 -*-
"""Проверка дашбордов Grafana: каждый запрос к Prometheus из JSON-файлов
выполняется на живом стенде и должен вернуть данные.

Запуск — во время нагрузки на стенд (без трафика перцентили за минуту пустые):
    python tools/check_dashboards.py

Скрипт подставляет переменные дашборда ($uri, $__rate_interval) и печатает запросы,
которые не вернули ни одной серии. Пустой результат не всегда ошибка:
например, ошибки 5xx или таймауты пула появляются только при включённых дефектах.
"""
import io
import json
import os
import sys
import urllib.parse
import urllib.request

PROMETHEUS = "http://localhost:9090"
ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                                     "monitoring", "grafana", "dashboards"))


def targets(panels):
    for panel in panels:
        for target in panel.get("targets", []):
            if "expr" in target:
                yield panel["title"], target.get("legendFormat", ""), target["expr"]


def evaluate(expr):
    expr = (expr.replace("$__rate_interval", "1m")
                .replace('uri=~"$uri"', 'uri=~".*"'))
    url = PROMETHEUS + "/api/v1/query?" + urllib.parse.urlencode({"query": expr})
    with urllib.request.urlopen(url, timeout=20) as response:
        body = json.loads(response.read())
    if body.get("status") != "success":
        return None, body.get("error")
    # серия со значением NaN — не данные: так ведёт себя, например, Gauge с потерянной ссылкой
    real = [r for r in body["data"]["result"] if r["value"][1] not in ("NaN", "+Inf", "-Inf")]
    return len(real), None


def main():
    failed, empty, ok = [], [], 0
    for name in ("ntbank.json", "jmeter.json"):
        with io.open(os.path.join(ROOT, name), encoding="utf-8") as f:
            dashboard = json.load(f)
        for title, legend, expr in targets(dashboard["panels"]):
            count, error = evaluate(expr)
            label = "%s / %s" % (title, legend or "-")
            if error:
                failed.append((name, label, error))
            elif count == 0:
                empty.append((name, label))
            else:
                ok += 1
    print("Запросов с данными: %d" % ok)
    if empty:
        print("\nБез данных (нужна нагрузка или включённый дефект):")
        for name, label in empty:
            print("  - %s: %s" % (name, label))
    if failed:
        print("\nОШИБКИ в запросах:")
        for name, label, error in failed:
            print("  - %s: %s — %s" % (name, label, error))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
