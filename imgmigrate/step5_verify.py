# -*- coding: utf-8 -*-
"""
第 5 步：校验迁移结果

1) 再扫一遍库，确认还有没有残留的阿里云地址（正常应为 0 行）
2) 从 image_map.csv 里随机抽样新地址，实际发请求看是否 200
"""
import csv
import random

import pymysql
import requests

from config import DB, OLD_HOST, MAP_CSV, NEW_HOST

TEXT_TYPES = ("varchar", "char", "text", "tinytext", "mediumtext", "longtext")
SAMPLE = 20


def check_db():
    conn = pymysql.connect(**DB)
    cur = conn.cursor()
    cur.execute(
        "SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS "
        "WHERE TABLE_SCHEMA=%s AND DATA_TYPE IN %s",
        (DB["database"], TEXT_TYPES),
    )
    left = 0
    for t, c in cur.fetchall():
        try:
            cur.execute("SELECT COUNT(*) FROM `%s` WHERE `%s` LIKE %%s" % (t, c),
                        ("%" + OLD_HOST + "%",))
            n = cur.fetchone()[0]
        except Exception:
            continue
        if n:
            left += n
            print("  [残留] %s.%s : %d 行" % (t, c, n))
    conn.close()
    print("\n库里残留阿里云地址的行数：%d  （正常应为 0）" % left)
    return left


def check_sample():
    try:
        with open(MAP_CSV, encoding="utf-8-sig") as f:
            rows = list(csv.DictReader(f))
    except FileNotFoundError:
        print("找不到 %s，跳过抽样" % MAP_CSV)
        return 0

    urls = sorted({r["new_url"] for r in rows if r.get("new_url")})
    if not urls:
        print("image_map.csv 无数据，跳过抽样")
        return 0

    sample = random.sample(urls, min(SAMPLE, len(urls)))
    print("\n抽样访问 %d 个新地址：" % len(sample))
    bad = 0
    for u in sample:
        try:
            r = requests.get(u, timeout=15)
            if r.status_code == 200:
                print("  [OK ] %s" % u)
            else:
                bad += 1
                print("  [BAD] HTTP %s  %s" % (r.status_code, u))
        except Exception as e:
            bad += 1
            print("  [BAD] %s  %s" % (type(e).__name__, u))
    print("\n抽样失败：%d" % bad)
    return bad


def main():
    print("=" * 62)
    print("1) 检查数据库残留")
    print("=" * 62)
    left = check_db()

    print("\n" + "=" * 62)
    print("2) 抽查 MinIO 上的新地址（%s）" % NEW_HOST)
    print("=" * 62)
    bad = check_sample()

    print("\n" + "=" * 62)
    print("结论：")
    print("  数据库残留 %d 行 %s" % (left, "✔" if left == 0 else "✘ 需要重新执行替换 SQL"))
    print("  抽样失败 %d 个 %s" % (bad, "✔" if bad == 0 else "✘ 检查对应文件是否已上传"))
    print("=" * 62)


if __name__ == "__main__":
    main()
