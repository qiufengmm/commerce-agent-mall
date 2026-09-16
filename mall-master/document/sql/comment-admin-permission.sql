-- ============================================================================
-- 后台「商品评价」菜单 + 接口资源权限 初始化数据
-- （专用角色方案：商品评价权限只给 username = 'admin'）
--
-- 版本      : 2026-09-16
-- 分支      : feature/comment-admin
-- 适用      : 本地 mall 库（MySQL 8.x）
--
-- 影响范围  : 只新增数据，共 13 行：
--               ums_role                    1 行  （新增角色「商品评价管理员」）
--               ums_admin_role_relation     1 行  （把 username='admin' 绑到该角色）
--               ums_menu                    1 行  （商品评价菜单 productComment）
--               ums_resource                4 行  （4 个 /comment 接口资源）
--               ums_role_menu_relation      2 行  （父菜单 pms + 新菜单 productComment）
--               ums_role_resource_relation  4 行  （4 个资源授权）
--             不改任何表结构；不修改、不删除任何既有行。
--
-- 【权限边界（本次方案的核心）】
--   商品评价权限只给 username = 'admin' 一个账号。
--   做法：新建专用角色「商品评价管理员」，只把它绑定给 admin 的账号；
--         admin 原有的「超级管理员」角色关系保持不动，
--         所以 admin 仍然是完整的超级管理员，只是额外多了商品评价权限。
--   test（ums_admin.id=1）与 macro（ums_admin.id=4）虽然也持有「超级管理员」，
--   但本脚本不给他们绑定「商品评价管理员」角色；
--   而「超级管理员」角色也不被授予新增的商品评价菜单与 /comment 资源，
--   因此 test / macro 不会获得新增的商品评价权限。
--
--   为什么"不给超级管理员授权"就能挡住 test / macro：
--   本项目的「超级管理员」是**普通角色**，不存在硬编码的超级管理员绕过逻辑。
--   一个账号能访问哪些菜单/接口，完全取决于关系表里有没有对应行：
--     - 后端接口鉴权：UmsAdminServiceImpl#getResourceList(adminId)
--                     -> UmsAdminRoleRelationDao#getResourceList(adminId)，
--                     纯按 ums_admin_role_relation + ums_role_resource_relation 查；
--                     DynamicAuthorizationManager 再拿结果与请求路径比对。
--     - 前端菜单：permission.ts 的 hasPermission() 按 ums_role_menu_relation 过滤路由。
--   「超级管理员」在种子数据里之所以看起来"什么都能做"，
--   只是因为 mall.sql 给它插了全部菜单和资源关系；少插一行，它就少一项权限。
--   本脚本正是利用这一点，把新增权限只挂在专用角色上。
--
-- 【注意】该专用角色只包含商品评价相关权限，不含 /admin/info、/admin/logout 等
--         登录必需资源，不能单独作为唯一角色使用。admin 之所以能正常使用，
--         是因为他同时保留了「超级管理员」角色。若日后要摘掉 admin 的超级管理员
--         角色，必须同时给该专用角色补上登录必需资源，否则 admin 将无法登录。
--
-- 【重要一】本文件只生成初始化数据，不含执行动作。
--           执行前必须先备份数据库，并由人工逐条确认后再手工执行。
--           备份示例：
--             mysqldump -uroot -p --databases mall > mall_backup_before_comment_admin.sql
--
-- 【重要二】本文件不执行任何数据库命令（本次生成过程未连接数据库）。
--           本文件不含 DDL / DELETE / UPDATE / REPLACE / ON DUPLICATE KEY UPDATE。
--
-- 【重要三】SQL 执行成功 != 部署完成。
--           第七节「执行后必须完成的人工部署步骤」需人工依次完成：
--             查询绑定「商品评价管理员」的 adminId（预期就是 admin）
--             -> 清理 Redis 缓存键 mall:ums:resourceList:<adminId>
--             -> 重启 mall-admin（重载内存资源 Map）
--             -> 退出并重新登录换新 token
--             -> 验证菜单与 4 个 /comment 接口
--           这些步骤不在本 SQL 内执行；本脚本不重启服务、不连接也不操作 Redis。
--
-- 【幂等性】本脚本可重复执行：
--           所有写入均为 INSERT ... SELECT ... WHERE NOT EXISTS，
--           已存在的数据不会被重复插入，重复执行只会新增缺失的行。
--           所有 NOT EXISTS 子查询都通过派生表包裹
--           （如 (SELECT id FROM ums_role WHERE name='商品评价管理员') AS existed），
--           规避 MySQL 在同表子查询上的限制。
--
-- 【编码】本文件为 UTF-8 无 BOM、LF 换行。
--         document/sql/alter_pms_comment_20260915.sql 是 UTF-8 带 BOM，
--         直接 `mysql ... < 文件` 会报 ERROR 1064；本文件无 BOM，可直接重定向执行。
--
-- 执行方式（Windows / PowerShell，二选一）：
--   1) 重定向（库名显式写在命令行）：
--      cmd.exe /c "mysql -uroot -p<密码> --default-character-set=utf8mb4 mall < document\sql\comment-admin-permission.sql"
--   2) 先连库再 source：
--      mysql -uroot -p --default-character-set=utf8mb4
--      mysql> USE mall;
--      mysql> SOURCE document/sql/comment-admin-permission.sql;
-- ============================================================================

-- 如需固化库名，可取消下一行注释（脚本本身不强制库名）
-- USE mall;


-- ============================================================================
-- 零、只读前置核对
--     本节全部是 SELECT / SHOW，只读、不改数据。
--     先跑完本节，确认下面各节依赖的“真实数据”存在，再执行第一~五节。
-- ============================================================================

-- 0.1 相关表的真实列结构
--     ums_role                  : id, name, description, admin_count, create_time, status, sort
--     ums_admin                 : id, username, password, icon, email, nick_name, note,
--                                 create_time, login_time, status
--     ums_admin_role_relation   : id, admin_id, role_id
--     ums_menu                  : id, parent_id, create_time, title, level, sort, name, icon, hidden
--     ums_resource              : id, create_time, name, url, description, category_id
--     ums_role_menu_relation    : id, role_id, menu_id
--     ums_role_resource_relation: id, role_id, resource_id
SHOW COLUMNS FROM ums_role;
SHOW COLUMNS FROM ums_admin;
SHOW COLUMNS FROM ums_admin_role_relation;
SHOW COLUMNS FROM ums_menu;
SHOW COLUMNS FROM ums_resource;
SHOW COLUMNS FROM ums_role_menu_relation;
SHOW COLUMNS FROM ums_role_resource_relation;

-- 0.2 菜单表的列说明（决定本脚本怎么写菜单）
--     ums_menu 没有 path / component 列，前端路由不是存在数据库里的。
--     后台菜单与前端的绑定方式只有一种：ums_menu.name == Vue 路由的 name
--     （见 mall-admin-web-master/src/stores/permission.ts 的 hasPermission() / getMenu()，
--      匹配失败该路由会被过滤掉，等于菜单不显示）。
--     本脚本写入 name = 'productComment'，它对应的 Vue 路由已在当前分支定义：
--       path      : 'comment'   （父路由 '/pms' 的子路由，即访问路径 /pms/comment）
--       name      : 'productComment'
--       component : @/views/pms/comment/index.vue
--       meta      : { title: '商品评价', icon: 'product-comment' }
--     来源：mall-admin-web-master/src/router/index.ts 的 asyncRouterMap -> '/pms' -> children
--     图标 product-comment 对应的图标文件已存在：
--       mall-admin-web-master/src/icons/svg/product-comment.svg
SHOW COLUMNS FROM ums_menu;

-- 0.3 商品（PMS）父菜单：预期恰好 1 行，level = 0，name = 'pms'
--     本脚本用它作为新菜单的 parent_id，并据它推导新菜单的 level。
--     注意：这一步若返回 0 行，第三节会插入 0 行（不做兜底猜测）。
SELECT id, parent_id, level, sort, name, title, icon, hidden
FROM ums_menu
WHERE name = 'pms';

-- 0.4 新角色「商品评价管理员」的行数核对
--     该角色由第一节创建，因此：
--       首次执行前  -> 预期 0 行
--       执行完成后  -> 必须恰好 1 行（见第六节 6.1）
--       返回 >= 2 行 -> 数据异常（同名角色重复），请先人工处理，不要继续执行第一节
SELECT COUNT(*) AS role_rows FROM ums_role WHERE name = '商品评价管理员';

SELECT id, name, description, admin_count, status, sort
FROM ums_role
WHERE name = '商品评价管理员';

-- 0.5 admin 账号唯一性核对：必须恰好 1 行
--     返回 0 行 -> 没有该账号，第二节会插入 0 行
--     返回 >=2 行 -> 账号重名，第二节会插入 0 行（故意不猜）
--     （第二节的 INSERT 内部也带了同样的 COUNT(*) = 1 保护条件）
SELECT COUNT(*) AS admin_rows FROM ums_admin WHERE username = 'admin';

SELECT id, username, nick_name, note, status
FROM ums_admin
WHERE username = 'admin';

-- 0.6 现存「超级管理员」持有者（只读，仅供人工确认本脚本不会动他们）
--     本脚本不插入、不修改、不删除下表中的任何关系；
--     这些账号（预期包含 test、admin、macro）仍然完整保留超级管理员权限。
--     本脚本只会给其中 username='admin' 的账号**追加**一个新角色。
SELECT a.id AS admin_id, a.username, r.id AS role_id, r.name AS role_name
FROM ums_admin a
JOIN ums_admin_role_relation ar ON ar.admin_id = a.id
JOIN ums_role r ON r.id = ar.role_id
WHERE r.name = '超级管理员'
ORDER BY a.id;

-- 0.7 资源分类：新资源归入「商品模块」（对应 ums_resource.category_id）
--     注意：这一步若返回 0 行，第四节会插入 0 行（不做兜底猜测）。
SELECT id, name
FROM ums_resource_category
WHERE name = '商品模块';

-- 0.8 重复执行前置检查：以下两条在首次执行前都应返回 0 行
SELECT id, name FROM ums_menu WHERE name = 'productComment';

SELECT id, url FROM ums_resource
WHERE url IN ('/comment/list',
              '/comment/update/showStatus/**',
              '/comment/replay/list/**',
              '/comment/replay/create');


-- ============================================================================
-- 一、新增专用角色「商品评价管理员」
--     字段按 ums_role 真实结构填写：name / description / admin_count /
--     create_time / status / sort（id 自增，由数据库分配，脚本不写死）。
--     status = 1 表示启用。
--     admin_count 在种子数据里全是 0（该列由应用侧维护、不自动更新），
--     本脚本按实际绑定数量填 1，仅作显示用途。
-- ============================================================================
INSERT INTO ums_role (name, description, admin_count, create_time, status, sort)
SELECT '商品评价管理员',
       '仅商品评价模块的菜单与接口权限；只绑定给 username=admin',
       1,
       NOW(),
       1,
       0
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM ums_role WHERE name = '商品评价管理员') AS existed);


-- ============================================================================
-- 二、把 username = 'admin' 的账号绑定到「商品评价管理员」
--     只新增 ums_admin_role_relation 一行，不删除、不修改任何既有关系。
--     admin / test / macro 现有的「超级管理员」关系保持原样。
--     内置两道保护：
--       (1) COUNT(*) = 1：username='admin' 的账号必须唯一存在，否则不写入；
--       (2) NOT EXISTS     ：该 (admin_id, role_id) 组合已存在时不重复写入。
-- ============================================================================
INSERT INTO ums_admin_role_relation (admin_id, role_id)
SELECT a.id, r.id
FROM ums_admin a
CROSS JOIN ums_role r
WHERE a.username = 'admin'
  AND r.name = '商品评价管理员'
  AND (SELECT COUNT(*) FROM (SELECT id FROM ums_admin WHERE username = 'admin') AS admin_dup) = 1
  AND NOT EXISTS (
      SELECT 1 FROM (SELECT admin_id, role_id FROM ums_admin_role_relation) AS rel
      WHERE rel.admin_id = a.id AND rel.role_id = r.id
  )
LIMIT 1;


-- ============================================================================
-- 三、新增「商品评价」后台菜单（挂在商品管理 PMS 菜单下）
--
-- 字段取值依据：
--   parent_id : 0.3 查到的 pms 菜单 id（不写死 1）
--   level     : 父菜单 level + 1（pms level = 0，故新菜单 level = 1，与同层其它子菜单一致）
--   sort      : 0（与 pms 下现有子菜单一致）
--   name      : 'productComment'（必须与 Vue 路由 name 完全一致，否则菜单不渲染）
--   icon      : 'product-comment'（与路由 meta.icon 及 src/icons/svg/product-comment.svg 一致）
--   title     : '商品评价'
--   hidden    : 0（可见）
--   create_time: NOW()
-- ============================================================================
INSERT INTO ums_menu (parent_id, create_time, title, level, sort, name, icon, hidden)
SELECT p.id,
       NOW(),
       '商品评价',
       p.level + 1,
       0,
       'productComment',
       'product-comment',
       0
FROM ums_menu p
WHERE p.name = 'pms'
  AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM ums_menu WHERE name = 'productComment') AS existed)
LIMIT 1;


-- ============================================================================
-- 四、新增最小权限资源集合（覆盖当前分支新增的 4 个后台接口）
--     资源 URL 的匹配方式（已核对实现）：
--       mall-security .../DynamicSecurityMetadataSource.getConfigAttributesWithPath()
--       用 org.springframework.util.AntPathMatcher 把 ums_resource.url 当作 Ant 模式，
--       与 HttpServletRequest.getRequestURI() 直接匹配（mall-admin 未配置 context-path，
--       故请求路径就是 /comment/... ）。
--     因此：固定路径写成精确值；路径变量 {id} / {commentId} 按项目既有写法（如 '/brand/**'）
--     写成 '/**'。
--     ums_resource 表没有 method 列，资源不区分 GET/POST，这是项目既有设计。
-- ============================================================================

-- 4.1 GET /comment/list —— 分页查询商品评价
INSERT INTO ums_resource (create_time, name, url, description, category_id)
SELECT NOW(),
       '商品评价查询',
       '/comment/list',
       '分页查询商品评价（PmsCommentController#list）',
       c.id
FROM ums_resource_category c
WHERE c.name = '商品模块'
  AND NOT EXISTS (SELECT 1 FROM (SELECT url FROM ums_resource) AS existed
                  WHERE existed.url = '/comment/list')
LIMIT 1;

-- 4.2 POST /comment/update/showStatus/{id} —— 修改评价显示状态
INSERT INTO ums_resource (create_time, name, url, description, category_id)
SELECT NOW(),
       '商品评价显示状态修改',
       '/comment/update/showStatus/**',
       '修改商品评价是否显示（PmsCommentController#updateShowStatus，{id} 用 Ant ** 覆盖）',
       c.id
FROM ums_resource_category c
WHERE c.name = '商品模块'
  AND NOT EXISTS (SELECT 1 FROM (SELECT url FROM ums_resource) AS existed
                  WHERE existed.url = '/comment/update/showStatus/**')
LIMIT 1;

-- 4.3 GET /comment/replay/list/{commentId} —— 查询评价的回复列表
INSERT INTO ums_resource (create_time, name, url, description, category_id)
SELECT NOW(),
       '商品评价回复查询',
       '/comment/replay/list/**',
       '查询某条评价的回复列表（PmsCommentController#listReplay，{commentId} 用 Ant ** 覆盖）',
       c.id
FROM ums_resource_category c
WHERE c.name = '商品模块'
  AND NOT EXISTS (SELECT 1 FROM (SELECT url FROM ums_resource) AS existed
                  WHERE existed.url = '/comment/replay/list/**')
LIMIT 1;

-- 4.4 POST /comment/replay/create —— 回复商品评价
INSERT INTO ums_resource (create_time, name, url, description, category_id)
SELECT NOW(),
       '商品评价回复',
       '/comment/replay/create',
       '回复商品评价（PmsCommentController#reply）',
       c.id
FROM ums_resource_category c
WHERE c.name = '商品模块'
  AND NOT EXISTS (SELECT 1 FROM (SELECT url FROM ums_resource) AS existed
                  WHERE existed.url = '/comment/replay/create')
LIMIT 1;


-- ============================================================================
-- 五、把商品评价菜单与资源授权给「商品评价管理员」
--     全部通过 name = '商品评价管理员' 定位角色，不写死 role id；
--     不再向「超级管理员」授予本模块的任何权限。
--     只新增关系行，不删除、不修改该角色或任何其它角色的既有授权。
-- ============================================================================

-- 5.1 父菜单 pms（必须授权，否则商品评价菜单不会显示）
--     原因：前端 permission.ts 先对父路由 '/pms'（name='pms'）做 hasPermission 判断，
--     父菜单不在该角色的菜单列表里时，整棵 /pms 子树（含商品评价）都会被过滤掉。
--     说明：pms 菜单在种子数据里本来就已授权给「超级管理员」，
--     那一行属于既有数据，本脚本不去增删；此处只是给新角色补一行。
INSERT INTO ums_role_menu_relation (role_id, menu_id)
SELECT r.id, m.id
FROM ums_role r
CROSS JOIN ums_menu m
WHERE r.name = '商品评价管理员'
  AND m.name = 'pms'
  AND NOT EXISTS (
      SELECT 1 FROM (SELECT role_id, menu_id FROM ums_role_menu_relation) AS rel
      WHERE rel.role_id = r.id AND rel.menu_id = m.id
  );

-- 5.2 新菜单 productComment
INSERT INTO ums_role_menu_relation (role_id, menu_id)
SELECT r.id, m.id
FROM ums_role r
CROSS JOIN ums_menu m
WHERE r.name = '商品评价管理员'
  AND m.name = 'productComment'
  AND NOT EXISTS (
      SELECT 1 FROM (SELECT role_id, menu_id FROM ums_role_menu_relation) AS rel
      WHERE rel.role_id = r.id AND rel.menu_id = m.id
  );

-- 5.3 4 个 /comment 资源（一次性覆盖第四节新增的资源）
INSERT INTO ums_role_resource_relation (role_id, resource_id)
SELECT r.id, res.id
FROM ums_role r
CROSS JOIN ums_resource res
WHERE r.name = '商品评价管理员'
  AND res.url IN ('/comment/list',
                  '/comment/update/showStatus/**',
                  '/comment/replay/list/**',
                  '/comment/replay/create')
  AND NOT EXISTS (
      SELECT 1 FROM (SELECT role_id, resource_id FROM ums_role_resource_relation) AS rel
      WHERE rel.role_id = r.id AND rel.resource_id = res.id
  );


-- ============================================================================
-- 六、执行后校验（全部只读）
--     预期：6.1 返回 1 行；6.2 返回 1 行且 username=admin；6.3 返回 0 行；
--           6.4 返回 2 行；6.5 返回 6 行；6.6 返回 0 行；6.7 返回 1 行。
-- ============================================================================

-- 6.1 新角色：预期恰好 1 行，status = 1
SELECT id, name, description, admin_count, status, sort
FROM ums_role
WHERE name = '商品评价管理员';

-- 6.2 角色绑定：预期恰好 1 行，username 必须是 admin
SELECT a.id AS admin_id, a.username, r.id AS role_id, r.name AS role_name
FROM ums_admin_role_relation ar
JOIN ums_admin a ON a.id = ar.admin_id
JOIN ums_role r ON r.id = ar.role_id
WHERE r.name = '商品评价管理员';

-- 6.3 反向确认：test / macro 不应出现在该角色下（预期 0 行）
SELECT a.id AS admin_id, a.username
FROM ums_admin_role_relation ar
JOIN ums_admin a ON a.id = ar.admin_id
JOIN ums_role r ON r.id = ar.role_id
WHERE r.name = '商品评价管理员'
  AND a.username <> 'admin';

-- 6.4 test / macro 的「超级管理员」关系未被改动（预期恰好 2 行）
SELECT a.id AS admin_id, a.username, r.id AS role_id, r.name AS role_name
FROM ums_admin_role_relation ar
JOIN ums_admin a ON a.id = ar.admin_id
JOIN ums_role r ON r.id = ar.role_id
WHERE r.name = '超级管理员'
  AND a.username IN ('test', 'macro')
ORDER BY a.id;

-- 6.5 「商品评价管理员」的整套授权汇总（预期 6 行 = 2 菜单 + 4 资源）
SELECT 'menu' AS kind, m.id AS rel_id, m.name AS rel_name
FROM ums_role_menu_relation rel
JOIN ums_role r ON r.id = rel.role_id
JOIN ums_menu m ON m.id = rel.menu_id
WHERE r.name = '商品评价管理员'
UNION ALL
SELECT 'resource', res.id, res.url
FROM ums_role_resource_relation rel
JOIN ums_role r ON r.id = rel.role_id
JOIN ums_resource res ON res.id = rel.resource_id
WHERE r.name = '商品评价管理员'
ORDER BY kind, rel_id;

-- 6.6 反向确认：新菜单与新资源没有落在「超级管理员」名下（预期 0 行）
--     说明：pms 父菜单属于既有数据、本来就授权给了超级管理员，故此处不检查 pms，
--     只检查本脚本新建的 productComment 菜单与 4 个 /comment 资源。
--     由于这三类数据都是本脚本新建的，预期这里不会出现任何行。
SELECT 'menu' AS kind, m.name AS rel_name
FROM ums_role_menu_relation rel
JOIN ums_role r ON r.id = rel.role_id
JOIN ums_menu m ON m.id = rel.menu_id
WHERE r.name = '超级管理员'
  AND m.name = 'productComment'
UNION ALL
SELECT 'resource', res.url
FROM ums_role_resource_relation rel
JOIN ums_role r ON r.id = rel.role_id
JOIN ums_resource res ON res.id = rel.resource_id
WHERE r.name = '超级管理员'
  AND res.url IN ('/comment/list',
                  '/comment/update/showStatus/**',
                  '/comment/replay/list/**',
                  '/comment/replay/create')
ORDER BY kind, rel_name;

-- 6.7 新菜单本身（预期 1 行，parent_id 指向 pms）
SELECT m.id, m.parent_id, m.level, m.sort, m.name, m.title, m.icon, m.hidden
FROM ums_menu m
WHERE m.name = 'productComment';


-- ============================================================================
-- 七、执行后必须完成的人工部署步骤（本 SQL 不执行以下任何操作）
--
--     为什么必须做：本脚本只改了数据库，运行的 mall-admin 进程有两层缓存
--     都不会自动感知刚写入的菜单与资源：
--       (1) 内存层：DynamicSecurityMetadataSource.configAttributeMap 是进程启动时
--           由 MallSecurityConfig 的 loadDataSource() 从 ums_resource 一次性装载的
--           static Map（仅在 @PostConstruct 时装载，运行期不自动刷新）。
--       (2) Redis 层：按管理员维度缓存了资源列表，键为
--             mall:ums:resourceList:<adminId>
--           （拼装方式见 mall-admin .../UmsAdminCacheServiceImpl#getResourceList：
--             redis.database + ':' + redis.key.resourceList + ':' + adminId，
--             即 'mall' + ':' + 'ums:resourceList' + ':' + adminId）。
--     只改数据库、不处理这两层缓存，新菜单/新接口不会生效或表现不一致。
--
--     本文件不包含也不执行任何重启、Redis、HTTP 操作；以下 5 步由人工完成。
--
--   步骤 1：查出绑定「商品评价管理员」角色的 adminId（预期就是 admin）
--     由人工执行下面这条只读查询，得到需要清缓存的 adminId：
SELECT a.id AS adminId, a.username, r.id AS role_id, r.name AS role_name
FROM ums_admin a
JOIN ums_admin_role_relation ar ON ar.admin_id = a.id
JOIN ums_role r ON r.id = ar.role_id
WHERE r.name = '商品评价管理员'
ORDER BY a.id;

--     预期结果：1 行，username = 'admin'。
--     若返回 0 行，说明第二节没有写入成功，不要继续后面的步骤；
--     若返回多行，说明该角色被绑定到了多个账号，需逐个清理各自的键。
--
--   步骤 2：清理该 adminId 对应的 Redis 资源缓存（人工操作）
--     键名 = mall:ums:resourceList:<步骤 1 查到的 adminId>，不要写死 adminId：
--       DEL mall:ums:resourceList:<步骤 1 查到的 adminId>
--     只删这一个（或步骤 1 返回的几个）键；
--     不要使用 KEYS / FLUSHDB / FLUSHALL 之类的批量命令。
--     test / macro 没有绑定该角色、也没有获得新资源，不需要清理。
--     【本脚本不连接、不操作 Redis；上面这行 DEL 只是人工步骤的说明文字，不会被执行】
--
--   步骤 3：重启 mall-admin，重载内存资源 Map
--     使 DynamicSecurityMetadataSource 在启动时重新执行 loadDataSource()，
--     把第四节新增的 4 条 ums_resource 载入内存。
--     只删缓存不重启，接口鉴权仍按进程里旧的资源 Map 判定。
--
--   步骤 4：退出登录并重新登录，取得新 token
--     资源列表在登录时随身份一起确定（AdminUserDetails 持有 resourceList），
--     旧 token 对应的仍是旧资源集合，必须退出后重新登录换新 token。
--
--   步骤 5：验证菜单与接口（都用 admin 账号）
--     5.1 前端：admin 重新登录后确认左侧「商品」下出现「商品评价」子菜单，
--         且能打开 /pms/comment 页面。
--         若菜单没出现，回查第六节 6.5：父菜单 pms 与 productComment 是否都已授权。
--     5.2 接口：用 admin 的新 token 逐个调用，确认返回 200 且不出现 403：
--           GET  /comment/list?pageNum=1&pageSize=5
--           POST /comment/update/showStatus/{id}
--           GET  /comment/replay/list/{commentId}
--           POST /comment/replay/create
--         若返回 403，优先回查步骤 2（Redis 键是否删对）与步骤 3（是否已重启）。
--     5.3 反证：用 test 或 macro 登录后确认左侧「商品」下没有「商品评价」菜单；
--         用 test / macro 的 token 调上述任一 /comment 接口，应返回 403。
-- ============================================================================


-- ============================================================================
-- 八、回滚说明（不执行，仅供参考）
--     如需回滚，由人工确认后手工处理：
--       1) 删除本脚本新增的关系行：ums_admin_role_relation 中 admin 与新角色的绑定、
--          ums_role_menu_relation 中该角色的 2 行、ums_role_resource_relation 中该角色的 4 行；
--       2) 再删除新增的 1 个菜单与 4 个资源；
--       3) 最后删除新增的 1 个角色。
--     本文件按要求不包含任何 DELETE 语句，故只给出上述说明。
--     回滚前建议先记录被删行的 id：
--       SELECT id FROM ums_role             WHERE name = '商品评价管理员';
--       SELECT id FROM ums_menu             WHERE name = 'productComment';
--       SELECT id FROM ums_resource         WHERE url LIKE '/comment/%';
--       SELECT id FROM ums_admin_role_relation WHERE role_id = <上一步查到的角色 id>;
--     注意：不要删掉 test / macro / admin 与「超级管理员」的既有关系。
-- ============================================================================
