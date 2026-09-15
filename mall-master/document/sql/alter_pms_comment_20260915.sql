-- ============================================================================
-- 增量迁移脚本：pms_comment 增加会员 / 订单关联字段（商品真实评价功能）
--
-- 版本    : 2026-09-15
-- 适用    : 已存在的 mall 库（本地 MySQL 8.x）
-- 影响范围: 仅 pms_comment 表结构，不动任何数据、不碰其他表
--
-- 【重要】本脚本与 document/sql/mall.sql 的区别
--   mall.sql 是作者提供的完整初始化脚本，含 DROP TABLE + CREATE TABLE + 全量 INSERT，
--   且 INSERT 中的图片字段为作者阿里云 OSS 地址。
--   在当前库上执行 mall.sql 会清空重建数据并把图片地址打回阿里云，
--   使已完成 MinIO 迁移的数据失效。因此本脚本与之完全独立，不得混用。
--
-- 本脚本只包含 ALTER TABLE，不含 DROP / CREATE / INSERT，不会重置任何数据。
-- 执行前建议先备份 pms_comment（当前该表无数据，亦可跳过）：
--   CREATE TABLE pms_comment_bak_20260915 AS SELECT * FROM pms_comment;
--
-- 执行方式（Windows / PowerShell）：
--   本文件为 UTF-8 带 BOM 编码，直接 `mysql ... < xxx.sql` 会因 BOM 报 ERROR 1064，
--   需先去掉 BOM 再执行：
--     $p = "document\sql\alter_pms_comment_20260915.sql"
--     $tmp = "$env:TEMP\alter_tmp.sql"
--     $sql = [IO.File]::ReadAllText($p, [Text.Encoding]::UTF8)
--     [IO.File]::WriteAllText($tmp, $sql, (New-Object Text.UTF8Encoding($false)))
--     cmd.exe /c "mysql -uroot -p<密码> --default-character-set=utf8mb4 mall < ""$tmp"""
--
-- 执行状态：已于 2026-09-15 在本地 mall 库执行成功（member_id / order_id /
--           order_item_id 三列 + uk_order_item_id + idx_product_id 均已创建）。
-- ============================================================================

USE mall;

-- 1. 新增关联字段
ALTER TABLE pms_comment
  ADD COLUMN member_id     bigint(20) NULL COMMENT '评价会员id'   AFTER product_id,
  ADD COLUMN order_id      bigint(20) NULL COMMENT '所属订单id'   AFTER member_id,
  ADD COLUMN order_item_id bigint(20) NULL COMMENT '订单明细id'   AFTER order_id;

-- 2. 订单明细唯一约束：保证一条订单明细只能评价一次
--    说明：MySQL 唯一索引允许多个 NULL，历史数据（无订单关联的评价）不受影响
ALTER TABLE pms_comment
  ADD UNIQUE KEY uk_order_item_id (order_item_id);

-- 3. 商品维度查询索引：商品详情页按 product_id 分页查评价
--    若已存在同名索引可忽略报错，或先执行 SHOW INDEX FROM pms_comment; 确认
ALTER TABLE pms_comment
  ADD KEY idx_product_id (product_id);

-- ============================================================================
-- 执行后校验（应看到 member_id / order_id / order_item_id 三列，
-- 以及 uk_order_item_id、idx_product_id 两个索引）
-- ============================================================================
SHOW COLUMNS FROM pms_comment LIKE '%id';
SHOW INDEX FROM pms_comment;

-- ============================================================================
-- 回滚脚本（仅在需要撤销时手动执行，不要自动运行）
-- ============================================================================
-- ALTER TABLE pms_comment
--   DROP INDEX idx_product_id,
--   DROP INDEX uk_order_item_id,
--   DROP COLUMN order_item_id,
--   DROP COLUMN order_id,
--   DROP COLUMN member_id;
