-- ==============================================================
-- mall 图片域名替换： macro-oss.oss-cn-shenzhen.aliyuncs.com  ->  localhost:9000
-- 由 step4_gen_sql.py 自动生成，按 表.列 做子串替换
-- 涉及字段数：15
-- ★ 执行前务必先备份数据库 ★
-- ==============================================================
SET SQL_SAFE_UPDATES = 0;

-- ---------- oms_cart_item.product_pic ----------
UPDATE `oms_cart_item` SET `product_pic` = REPLACE(`product_pic`, 'http://macro-oss.oss-cn-shenzhen.aliyuncs.com', 'http://localhost:9000') WHERE `product_pic` LIKE '%macro-oss.oss-cn-shenzhen.aliyuncs.com%';
UPDATE `oms_cart_item` SET `product_pic` = REPLACE(`product_pic`, 'https://macro-oss.oss-cn-shenzhen.aliyuncs.com', 'http://localhost:9000') WHERE `product_pic` LIKE '%macro-oss.oss-cn-shenzhen.aliyuncs.com%';

-- ---------- oms_order_item.product_pic ----------
UPDATE `oms_order_item` SET `product_pic` = REPLACE(`product_pic`, 'http://macro-oss.oss-cn-shenzhen.aliyuncs.com', 'http://localhost:9000') WHERE `product_pic` LIKE '%macro-oss.oss-cn-shenzhen.aliyuncs.com%';
UPDATE `oms_order_item` SET `product_pic` = REPLACE(`product_pic`, 'https://macro-oss.oss-cn-shenzhen.aliyuncs.com', 'http://localhost:9000') WHERE `product_pic` LIKE '%macro-oss.oss-cn-shenzhen.aliyuncs.com%';

-- ---------- oms_order_return_apply.product_pic ----------
UPDATE `oms_order_return_apply` SET `product_pic` = REPLACE(`product_pic`, 'http://macro-oss.oss-cn-shenzhen.aliyuncs.com', 'http://localhost:9000') WHERE `product_pic` LIKE '%macro-oss.oss-cn-shenzhen.aliyuncs.com%';
UPDATE `oms_order_return_apply` SET `product_pic` = REPLACE(`product_pic`, 'https://macro-oss.oss-cn-shenzhen.aliyuncs.com', 'http://localhost:9000') WHERE `product_pic` LIKE '%macro-oss.oss-cn-shenzhen.aliyuncs.com%';

-- ---------- oms_order_return_apply.proof_pics ----------
UPDATE `oms_order_return_apply` SET `proof_pics` = REPLACE(`proof_pics`, 'http://macro-oss.oss-cn-shenzhen.aliyuncs.com', 'http://localhost:9000') WHERE `proof_pics` LIKE '%macro-oss.oss-cn-shenzhen.aliyuncs.com%';
UPDATE `oms_order_return_apply` SET `proof_pics` = REPLACE(`proof_pics`, 'https://macro-oss.oss-cn-shenzhen.aliyuncs.com', 'http://localhost:9000') WHERE `proof_pics` LIKE '%macro-oss.oss-cn-shenzhen.aliyuncs.com%';

-- ---------- pms_brand.big_pic ----------
UPDATE `pms_brand` SET `big_pic` = REPLACE(`big_pic`, 'http://macro-oss.oss-cn-shenzhen.aliyuncs.com', 'http://localhost:9000') WHERE `big_pic` LIKE '%macro-oss.oss-cn-shenzhen.aliyuncs.com%';
UPDATE `pms_brand` SET `big_pic` = REPLACE(`big_pic`, 'https://macro-oss.oss-cn-shenzhen.aliyuncs.com', 'http://localhost:9000') WHERE `big_pic` LIKE '%macro-oss.oss-cn-shenzhen.aliyuncs.com%';

-- ---------- pms_brand.logo ----------
UPDATE `pms_brand` SET `logo` = REPLACE(`logo`, 'http://macro-oss.oss-cn-shenzhen.aliyuncs.com', 'http://localhost:9000') WHERE `logo` LIKE '%macro-oss.oss-cn-shenzhen.aliyuncs.com%';
UPDATE `pms_brand` SET `logo` = REPLACE(`logo`, 'https://macro-oss.oss-cn-shenzhen.aliyuncs.com', 'http://localhost:9000') WHERE `logo` LIKE '%macro-oss.oss-cn-shenzhen.aliyuncs.com%';

-- ---------- pms_product.album_pics ----------
UPDATE `pms_product` SET `album_pics` = REPLACE(`album_pics`, 'http://macro-oss.oss-cn-shenzhen.aliyuncs.com', 'http://localhost:9000') WHERE `album_pics` LIKE '%macro-oss.oss-cn-shenzhen.aliyuncs.com%';
UPDATE `pms_product` SET `album_pics` = REPLACE(`album_pics`, 'https://macro-oss.oss-cn-shenzhen.aliyuncs.com', 'http://localhost:9000') WHERE `album_pics` LIKE '%macro-oss.oss-cn-shenzhen.aliyuncs.com%';

-- ---------- pms_product.detail_html ----------
UPDATE `pms_product` SET `detail_html` = REPLACE(`detail_html`, 'http://macro-oss.oss-cn-shenzhen.aliyuncs.com', 'http://localhost:9000') WHERE `detail_html` LIKE '%macro-oss.oss-cn-shenzhen.aliyuncs.com%';
UPDATE `pms_product` SET `detail_html` = REPLACE(`detail_html`, 'https://macro-oss.oss-cn-shenzhen.aliyuncs.com', 'http://localhost:9000') WHERE `detail_html` LIKE '%macro-oss.oss-cn-shenzhen.aliyuncs.com%';

-- ---------- pms_product.detail_mobile_html ----------
UPDATE `pms_product` SET `detail_mobile_html` = REPLACE(`detail_mobile_html`, 'http://macro-oss.oss-cn-shenzhen.aliyuncs.com', 'http://localhost:9000') WHERE `detail_mobile_html` LIKE '%macro-oss.oss-cn-shenzhen.aliyuncs.com%';
UPDATE `pms_product` SET `detail_mobile_html` = REPLACE(`detail_mobile_html`, 'https://macro-oss.oss-cn-shenzhen.aliyuncs.com', 'http://localhost:9000') WHERE `detail_mobile_html` LIKE '%macro-oss.oss-cn-shenzhen.aliyuncs.com%';

-- ---------- pms_product.pic ----------
UPDATE `pms_product` SET `pic` = REPLACE(`pic`, 'http://macro-oss.oss-cn-shenzhen.aliyuncs.com', 'http://localhost:9000') WHERE `pic` LIKE '%macro-oss.oss-cn-shenzhen.aliyuncs.com%';
UPDATE `pms_product` SET `pic` = REPLACE(`pic`, 'https://macro-oss.oss-cn-shenzhen.aliyuncs.com', 'http://localhost:9000') WHERE `pic` LIKE '%macro-oss.oss-cn-shenzhen.aliyuncs.com%';

-- ---------- pms_product_category.icon ----------
UPDATE `pms_product_category` SET `icon` = REPLACE(`icon`, 'http://macro-oss.oss-cn-shenzhen.aliyuncs.com', 'http://localhost:9000') WHERE `icon` LIKE '%macro-oss.oss-cn-shenzhen.aliyuncs.com%';
UPDATE `pms_product_category` SET `icon` = REPLACE(`icon`, 'https://macro-oss.oss-cn-shenzhen.aliyuncs.com', 'http://localhost:9000') WHERE `icon` LIKE '%macro-oss.oss-cn-shenzhen.aliyuncs.com%';

-- ---------- pms_sku_stock.pic ----------
UPDATE `pms_sku_stock` SET `pic` = REPLACE(`pic`, 'http://macro-oss.oss-cn-shenzhen.aliyuncs.com', 'http://localhost:9000') WHERE `pic` LIKE '%macro-oss.oss-cn-shenzhen.aliyuncs.com%';
UPDATE `pms_sku_stock` SET `pic` = REPLACE(`pic`, 'https://macro-oss.oss-cn-shenzhen.aliyuncs.com', 'http://localhost:9000') WHERE `pic` LIKE '%macro-oss.oss-cn-shenzhen.aliyuncs.com%';

-- ---------- sms_home_advertise.pic ----------
UPDATE `sms_home_advertise` SET `pic` = REPLACE(`pic`, 'http://macro-oss.oss-cn-shenzhen.aliyuncs.com', 'http://localhost:9000') WHERE `pic` LIKE '%macro-oss.oss-cn-shenzhen.aliyuncs.com%';
UPDATE `sms_home_advertise` SET `pic` = REPLACE(`pic`, 'https://macro-oss.oss-cn-shenzhen.aliyuncs.com', 'http://localhost:9000') WHERE `pic` LIKE '%macro-oss.oss-cn-shenzhen.aliyuncs.com%';

-- ---------- ums_admin.icon ----------
UPDATE `ums_admin` SET `icon` = REPLACE(`icon`, 'http://macro-oss.oss-cn-shenzhen.aliyuncs.com', 'http://localhost:9000') WHERE `icon` LIKE '%macro-oss.oss-cn-shenzhen.aliyuncs.com%';
UPDATE `ums_admin` SET `icon` = REPLACE(`icon`, 'https://macro-oss.oss-cn-shenzhen.aliyuncs.com', 'http://localhost:9000') WHERE `icon` LIKE '%macro-oss.oss-cn-shenzhen.aliyuncs.com%';

-- ---------- ums_member.icon ----------
UPDATE `ums_member` SET `icon` = REPLACE(`icon`, 'http://macro-oss.oss-cn-shenzhen.aliyuncs.com', 'http://localhost:9000') WHERE `icon` LIKE '%macro-oss.oss-cn-shenzhen.aliyuncs.com%';
UPDATE `ums_member` SET `icon` = REPLACE(`icon`, 'https://macro-oss.oss-cn-shenzhen.aliyuncs.com', 'http://localhost:9000') WHERE `icon` LIKE '%macro-oss.oss-cn-shenzhen.aliyuncs.com%';

SET SQL_SAFE_UPDATES = 1;