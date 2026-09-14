# -*- coding: utf-8 -*-
"""
第 3 步：把本地镜像按【原对象名】上传到 MinIO 桶

本地 mirror/mall/images/20200607/x.jpg
  -> 桶 mall 里的对象 images/20200607/x.jpg
注意：去掉的是 OSS 桶名那一层（mall），剩下的路径原样作为对象名。
这样替换域名后，URL 的路径部分与原来逐字相同，能精确命中。
"""
import os

from minio import Minio
from minio.error import S3Error

from config import (MIRROR, MINIO_BUCKET, MINIO_ENDPOINT, MINIO_ACCESS_KEY,
                    MINIO_SECRET_KEY, MINIO_SECURE)

CONTENT_TYPES = {
    ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png",
    ".gif": "image/gif", ".webp": "image/webp", ".bmp": "image/bmp",
    ".svg": "image/svg+xml", ".ico": "image/x-icon",
}

PUBLIC_READ_POLICY = (
    '{"Version":"2012-10-17","Statement":[{"Effect":"Allow",'
    '"Principal":{"AWS":["*"]},"Action":["s3:GetObject"],'
    '"Resource":["arn:aws:s3:::%s/*"]}]}'
)


def ensure_bucket(cli):
    if cli.bucket_exists(MINIO_BUCKET):
        print("桶 %s 已存在" % MINIO_BUCKET)
        return
    cli.make_bucket(MINIO_BUCKET)
    cli.set_bucket_policy(MINIO_BUCKET, PUBLIC_READ_POLICY % MINIO_BUCKET)
    print("已创建桶 %s 并设为公开只读" % MINIO_BUCKET)


def main():
    src_root = os.path.join(MIRROR, "mall")        # OSS 里的 mall/ 前缀
    if not os.path.isdir(src_root):
        print("找不到本地镜像目录 %s，请先运行： python step2_download.py" % src_root)
        return

    cli = Minio(MINIO_ENDPOINT, access_key=MINIO_ACCESS_KEY,
                secret_key=MINIO_SECRET_KEY, secure=MINIO_SECURE)
    ensure_bucket(cli)

    files = []
    for dp, _, fs in os.walk(src_root):
        for name in fs:
            full = os.path.join(dp, name)
            obj = os.path.relpath(full, src_root).replace(os.sep, "/")
            files.append((full, obj))

    print("待上传 %d 个对象 ...\n" % len(files))
    ok = skip = fail = 0

    for i, (full, obj) in enumerate(files, 1):
        exists = False
        try:
            cli.stat_object(MINIO_BUCKET, obj)
            exists = True
        except S3Error as e:
            if e.code != "NoSuchKey":
                print("  [stat 异常] %s : %s" % (obj, e))
        except Exception:
            pass

        if exists:
            skip += 1
        else:
            try:
                ct = CONTENT_TYPES.get(os.path.splitext(full)[1].lower(),
                                       "application/octet-stream")
                cli.fput_object(MINIO_BUCKET, obj, full, content_type=ct)
                ok += 1
            except Exception as e:
                fail += 1
                print("  [失败] %s : %s" % (obj, e))

        if i % 100 == 0 or i == len(files):
            print("  进度 %d/%d   新增 %d / 跳过 %d / 失败 %d" % (i, len(files), ok, skip, fail))

    print("\n" + "=" * 62)
    print("新增 %d，已存在跳过 %d，失败 %d" % (ok, skip, fail))
    print("桶内浏览： http://%s/%s/images/" % (MINIO_ENDPOINT, MINIO_BUCKET))


if __name__ == "__main__":
    main()
