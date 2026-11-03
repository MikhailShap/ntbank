# -*- coding: utf-8 -*-
"""Генерация test-data/users.csv для CSV Data Set Config в JMeter.

Запуск:  python tools/gen_users_csv.py [количество]

Файл детерминированный и совпадает с тем, что приложение создаёт в базе при старте.
Приложение само перезаписывает этот файл при старте и при POST /api/dev/reset.
"""
import io
import os
import sys

PASSWORD = "Passw0rd!"


def main():
    count = int(sys.argv[1]) if len(sys.argv) > 1 else 1000
    target = os.path.normpath(os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "..", "test-data", "users.csv"))
    os.makedirs(os.path.dirname(target), exist_ok=True)
    # UTF-8 без BOM, перевод строки \n, разделитель — запятая
    with io.open(target, "w", encoding="utf-8", newline="\n") as f:
        f.write("login,password,phone\n")
        for i in range(1, count + 1):
            f.write("user%04d,%s,+7900%07d\n" % (i, PASSWORD, i))
    print("Записано %d пользователей -> %s" % (count, target))


if __name__ == "__main__":
    main()
