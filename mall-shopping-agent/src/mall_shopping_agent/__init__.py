"""Mall 商品导购智能体（只读）。

该服务只通过 HTTP 读取 ``mall-portal`` 的公开与会员只读接口，
不包含任何交易、库存、索引或数据库写操作。
"""

from __future__ import annotations

__all__ = ["__version__"]

__version__ = "0.1.0"
