# -*- coding: utf-8 -*-
"""Все формы страницы «Дефекты» отправляются так, как это делает браузер (или рекордер JMeter).

Запуск:  python tools/chaos_web_check.py

Скрипт входит под admin, разбирает HTML страницы /admin/chaos, для каждой карточки включает
переключатель, отправляет форму со всеми её полями и сверяет состояние через REST, затем выключает.
В конце — «Выключить всё». Так проверяется, что имена полей формы совпадают с тем, что ждёт сервер.
"""
import base64
import re
import sys
from html.parser import HTMLParser

from audit_check import Browser, api, check, section

ADMIN = {"Authorization": "Basic " + base64.b64encode(b"admin:admin").decode()}


class Forms(HTMLParser):
    """Собирает формы: action и поля так, как их отправит браузер."""

    def __init__(self):
        super().__init__()
        self.forms, self.current, self.select = [], None, None

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "form":
            self.current = {"action": a.get("action"), "fields": [], "checkboxes": []}
            self.forms.append(self.current)
        elif self.current is None:
            return
        elif tag == "input" and a.get("name"):
            kind = a.get("type", "text")
            if kind == "checkbox":
                self.current["checkboxes"].append((a["name"], a.get("value", "on"), "checked" in a))
            elif kind == "radio":
                if "checked" in a:
                    self.current["fields"].append((a["name"], a.get("value", "")))
            elif kind not in ("submit", "button"):
                self.current["fields"].append((a["name"], a.get("value", "")))
        elif tag == "select" and a.get("name"):
            self.select = {"name": a["name"], "first": None, "chosen": None}
        elif tag == "option" and self.select is not None:
            value = a.get("value", "")
            if self.select["first"] is None:
                self.select["first"] = value
            if "selected" in a:
                self.select["chosen"] = value

    def handle_endtag(self, tag):
        if tag == "select" and self.select is not None and self.current is not None:
            value = self.select["chosen"] if self.select["chosen"] is not None else self.select["first"]
            self.current["fields"].append((self.select["name"], value or ""))
            self.select = None
        elif tag == "form":
            self.current = None


def forms_of(html):
    parser = Forms()
    parser.feed(html)
    return {f["action"]: f for f in parser.forms}


def state():
    return api("/api/admin/chaos", headers=ADMIN)[1]


# карточка -> (поле состояния в REST, имя переключателя в форме, правки полей перед отправкой)
CARDS = {
    "/admin/chaos/latency": ("latencyEnabled", "enabled", {"minMs": "150", "maxMs": "300"}),
    "/admin/chaos/errors": ("errorsEnabled", "enabled", {"percent": "7"}),
    "/admin/chaos/slow-sql": ("slowSqlEnabled", "enabled", {}),
    "/admin/chaos/leak": ("leakEnabled", "enabled", {"kbPerRequest": "20"}),
    "/admin/chaos/cpu": ("cpuEnabled", "enabled", {"millis": "30"}),
    "/admin/chaos/antifraud": ("antifraudEnabled", "enabled", {"delayMinMs": "100", "delayMaxMs": "200"}),
    "/admin/chaos/kafka": ("kafkaConsumerPaused", "paused", {}),
    "/admin/chaos/rate-limit": ("rateLimitEnabled", "enabled", {"rps": "50"}),
}


def submit(browser, form, toggle, on, edits):
    fields = [(name, edits.get(name, value)) for name, value in form["fields"]]
    for name, value, _ in form["checkboxes"]:
        if name == toggle:
            if on:
                fields.append((name, value))
        elif name in edits:
            fields.append((name, value))
    return browser.post(form["action"], dict(fields) if len({n for n, _ in fields}) == len(fields) else fields)


def main():
    browser = Browser()
    browser.login("admin", "admin")
    code, html, _ = browser.get("/admin/chaos")
    check("страница «Дефекты» открывается под admin", code == 200 and "Выключить всё" in html, "код %s" % code)
    forms = forms_of(html)
    check("на странице есть все карточки", all(a in forms for a in CARDS) and "/admin/chaos/pools" in forms,
          "найдено форм: %d" % len(forms))

    for action, (flag, toggle, edits) in CARDS.items():
        section(action.rsplit("/", 1)[1])
        form = forms_of(browser.get("/admin/chaos")[1])[action]
        code, page, _ = submit(browser, form, toggle, True, edits)
        current = state()
        alert = re.search(r'alert--(ok|danger)">.*?class="alert__body">([^<]+)<', page, re.S)
        check("включение через форму", code == 200 and current.get(flag) is True,
              "%s, %s: %s" % (code, flag, current.get(flag)))
        check("после отправки — зелёная плашка", alert is not None and alert.group(1) == "ok",
              alert.group(2).strip()[:90] if alert else "плашки нет")
        if action.endswith("latency"):
            check("значения полей применились", current["latencyMinMs"] == 150 and current["latencyMaxMs"] == 300,
                  "%s–%s мс" % (current["latencyMinMs"], current["latencyMaxMs"]))
        if action.endswith("leak"):
            check("размер утечки применился", current["leakKbPerRequest"] == 20, str(current["leakKbPerRequest"]))
        form = forms_of(browser.get("/admin/chaos")[1])[action]
        code, page, _ = submit(browser, form, toggle, False, {})
        check("выключение через форму", code == 200 and state().get(flag) is False, "%s" % state().get(flag))

    section("pools")
    form = forms_of(browser.get("/admin/chaos")[1])["/admin/chaos/pools"]
    code, page, _ = submit(browser, form, None, False, {"hikariMaxPoolSize": "5", "tomcatMaxThreads": "40"})
    current = state()
    check("размеры пулов через форму", current["hikariMaxPoolSize"] == 5 and current["tomcatMaxThreads"] == 40,
          "Hikari %s, Tomcat %s" % (current["hikariMaxPoolSize"], current["tomcatMaxThreads"]))
    check("в сообщении реальные значения", "HikariCP 5" in page and "Tomcat 40" in page)

    section("Выключить всё")
    form = forms_of(browser.get("/admin/chaos")[1])["/admin/chaos/reset"]
    browser.post(form["action"], dict(form["fields"]))
    current = state()
    check("всё выключено, пулы штатные", not current["anyEnabled"]
          and current["hikariMaxPoolSize"] == current["hikariDefaultPoolSize"]
          and current["tomcatMaxThreads"] == current["tomcatDefaultThreads"],
          "anyEnabled %s, Hikari %s/%s" % (current["anyEnabled"], current["hikariMaxPoolSize"],
                                           current["hikariDefaultPoolSize"]))

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
        api("/api/admin/chaos/reset", {}, method="POST", headers=ADMIN)
