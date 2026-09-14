# -*- coding: utf-8 -*-
"""
第 2 步：按清单下载全部图片，并保持与 OSS 完全一致的目录结构

下载结果放在 mirror/ 下，例如：
  http://...aliyuncs.com/mall/images/20180615/timg (51).jpg
  -> mirror/mall/images/20180615/timg (51).jpg

支持断点续传：已存在且非空的文件会直接跳过，中断后重跑即可。
"""
import os
import threading
import time
import urllib.parse as up
from concurrent.futures import ThreadPoolExecutor

import requests

from config import MIRROR, URLS_TXT, FAILED_TXT

WORKERS = 8      # 并发数，别调太大，对人家 OSS 友好一点
TIMEOUT = 30
RETRY   = 3

_session = requests.Session()
_session.headers.update({
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
})

_lock = threading.Lock()
_done = [0]


def local_path(url: str) -> str:
    """把 URL 映射成本地路径：去掉协议+域名，解码后保持原目录层级。"""
    path = up.unquote(up.urlsplit(url).path)          # /mall/images/20180615/timg (51).jpg
    return os.path.join(MIRROR, path.lstrip("/").replace("/", os.sep))


def fetch(url: str):
    """下载单个文件。返回 None 表示成功，否则返回失败原因字符串。"""
    target = local_path(url)
    if os.path.exists(target) and os.path.getsize(target) > 0:
        return None                                   # 断点续传

    os.makedirs(os.path.dirname(target), exist_ok=True)

    cur = url
    last = ""
    for attempt in range(RETRY):
        try:
            r = _session.get(cur, timeout=TIMEOUT)
            if r.status_code == 200 and r.content:
                ct = (r.headers.get("Content-Type") or "").lower()
                if "xml" in ct or "html" in ct:
                    last = "返回了非图片内容(%s)" % ct
                else:
                    with open(target, "wb") as f:
                        f.write(r.content)
                    if cur != url:
                        print("  [裁剪后成功] %s -> %s" % (url, cur))
                    return None
            else:
                last = "HTTP %s" % r.status_code
        except Exception as e:
            last = "%s: %s" % (type(e).__name__, e)
        time.sleep(1 + attempt)

    return "%s\t%s" % (last, url)


def _worker(u):
    err = fetch(u)
    with _lock:
        _done[0] += 1
        if _done[0] % 50 == 0 or _done[0] == _total:
            print("  进度 %d/%d" % (_done[0], _total))
    return err


_total = 0


def main():
    global _total
    with open(URLS_TXT, encoding="utf-8") as f:
        urls = [l.strip() for l in f if l.strip()]
    if not urls:
        print("清单为空，请先运行： python step1_scan.py")
        return

    _total = len(urls)
    print("待下载 %d 张，并发 %d ...\n" % (_total, WORKERS))

    with ThreadPoolExecutor(WORKERS) as ex:
        results = list(ex.map(_worker, urls))

    failed = [x for x in results if x]
    with open(FAILED_TXT, "w", encoding="utf-8") as f:
        f.write("\n".join(failed))

    print("\n" + "=" * 62)
    print("成功 %d / %d" % (_total - len(failed), _total))
    print("失败 %d 条（清单：%s）" % (len(failed), FAILED_TXT))
    print("本地目录：%s" % MIRROR)


if __name__ == "__main__":
    main()
