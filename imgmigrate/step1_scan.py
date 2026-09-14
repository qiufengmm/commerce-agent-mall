# -*- coding: utf-8 -*-
"""
第 1 步：扫描 mall 库，导出所有阿里云图片 URL 及其位置(表/列/主键)

产出两个文件（都在 output/ 目录）：
  oss_urls.txt   去重后的图片 URL 清单 —— 给第 2 步下载用
  image_map.csv  每张图出现在哪张表、哪个字段、哪一行的主键 —— 就是"位置信息"
"""
import csv

import pymysql

from config import DB, OLD_HOST, URL_RE, normalize_url, to_new_url, URLS_TXT, MAP_CSV

TEXT_TYPES = ("varchar", "char", "text", "tinytext", "mediumtext", "longtext")


def main():
    conn = pymysql.connect(**DB)
    cur = conn.cursor()

    # 1) 找出库里所有可能存放 URL 的文本字段
    cur.execute(
        "SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS "
        "WHERE TABLE_SCHEMA=%s AND DATA_TYPE IN %s "
        "ORDER BY TABLE_NAME, ORDINAL_POSITION",
        (DB["database"], TEXT_TYPES),
    )
    text_cols = cur.fetchall()

    # 2) 各表主键（复合主键只取第一个，定位用足够了）
    cur.execute(
        "SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE "
        "WHERE TABLE_SCHEMA=%s AND CONSTRAINT_NAME='PRIMARY' "
        "ORDER BY TABLE_NAME, ORDINAL_POSITION",
        (DB["database"],),
    )
    pk_map = {}
    for t, c in cur.fetchall():
        pk_map.setdefault(t, c)

    print("开始扫描 %d 个文本字段 ...\n" % len(text_cols))

    urls = set()
    rows = []
    hit_cols = 0

    for t, c in text_cols:
        pk = pk_map.get(t)
        kexpr = "`%s`" % pk if pk else "NULL"
        sql = "SELECT %s AS k, `%s` AS v FROM `%s` WHERE `%s` LIKE %%s" % (kexpr, c, t, c)
        try:
            cur.execute(sql, ("%" + OLD_HOST + "%",))
        except Exception as e:
            print("  [跳过] %s.%s: %s" % (t, c, e))
            continue

        cnt = 0
        for k, v in cur.fetchall():
            if not v:
                continue
            for raw in set(URL_RE.findall(str(v))):
                u = normalize_url(raw)
                urls.add(u)
                cnt += 1
                rows.append({
                    "url": u,
                    "new_url": to_new_url(u),
                    "table": t,
                    "column": c,
                    "pk": "" if k is None else k,
                })
        if cnt:
            hit_cols += 1
            print("  [命中] %s.%s : %d 处" % (t, c, cnt))

    with open(URLS_TXT, "w", encoding="utf-8") as f:
        f.write("\n".join(sorted(urls)))

    with open(MAP_CSV, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=["url", "new_url", "table", "column", "pk"])
        w.writeheader()
        w.writerows(rows)

    print("\n" + "=" * 62)
    print("去重后的图片数量 : %d" % len(urls))
    print("引用位置条数     : %d（分布在 %d 个字段里）" % (len(rows), hit_cols))
    print("图片清单         : %s" % URLS_TXT)
    print("位置映射         : %s" % MAP_CSV)
    conn.close()


if __name__ == "__main__":
    main()
