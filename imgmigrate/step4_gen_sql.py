# -*- coding: utf-8 -*-
"""
第 4 步：根据 image_map.csv 里的(表,列)生成域名替换 SQL

生成的是"按 表.列 做子串替换"的语句，一次覆盖该列里所有出现位置
（包括富文本 detail_html 里的 <img src>、CSS url() 等），不用逐条改。

★ 执行生成的 SQL 之前，务必先备份数据库 ★
"""
import csv

from config import MAP_CSV, OLD_HOST, NEW_HOST, SQL_OUT


def main():
    pairs = set()
    with open(MAP_CSV, encoding="utf-8-sig") as f:
        for r in csv.DictReader(f):
            if r.get("table") and r.get("column"):
                pairs.add((r["table"], r["column"]))

    if not pairs:
        print("image_map.csv 是空的，请先运行： python step1_scan.py")
        return

    new_base = "http://" + NEW_HOST
    lines = [
        "-- ==============================================================",
        "-- mall 图片域名替换： %s  ->  %s" % (OLD_HOST, NEW_HOST),
        "-- 由 step4_gen_sql.py 自动生成，按 表.列 做子串替换",
        "-- 涉及字段数：%d" % len(pairs),
        "-- ★ 执行前务必先备份数据库 ★",
        "-- ==============================================================",
        "SET SQL_SAFE_UPDATES = 0;",
        "",
    ]

    for t, c in sorted(pairs):
        lines.append("-- ---------- %s.%s ----------" % (t, c))
        for old in ("http://" + OLD_HOST, "https://" + OLD_HOST):
            lines.append(
                "UPDATE `%s` SET `%s` = REPLACE(`%s`, '%s', '%s') WHERE `%s` LIKE '%%%s%%';"
                % (t, c, c, old, new_base, c, OLD_HOST)
            )
        lines.append("")

    lines.append("SET SQL_SAFE_UPDATES = 1;")

    with open(SQL_OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))

    print("共 %d 个字段，已生成：%s" % (len(pairs), SQL_OUT))


if __name__ == "__main__":
    main()
