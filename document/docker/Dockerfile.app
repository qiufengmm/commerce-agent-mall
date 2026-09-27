# =============================================================================
# mall-admin / mall-search / mall-portal / mall-agent 通用多阶段构建文件
# =============================================================================
# 由 docker-compose.yml 通过 build.args 传入 MODULE / JAR_FILE / APP_PORT，
# 从本地源码构建可执行 jar，不依赖任何预构建镜像。
# 商品导购智能体（Compose service key `mall-shopping-agent`）也走本文件：
#   MODULE=mall-agent、JAR_FILE=mall-agent-1.0-SNAPSHOT.jar、APP_PORT=8086，
#   并额外传入 APP_RUN_USER=mallapp 以非 root 运行；
#   其它三个服务（mall-admin / mall-search / mall-portal）不传该参数，保持默认 root。
#
# 构建上下文为仓库根目录，因此需要仓库根的 .dockerignore 排除
# node_modules / target / .git 等目录，否则上下文会非常大。
#
# 基础镜像固定原则（重要）：
# 1. 运行阶段固定为 eclipse-temurin:17-jre（Debian/Ubuntu 系列，提供 apt-get）。
#    健康管理（Docker healthcheck）依赖容器内的 curl，该镜像可通过 apt-get 安装。
#    不要替换为 Alpine 等不含 apt-get 的镜像，否则构建阶段会失败，
#    或运行阶段因缺少 curl 被误判为 unhealthy。
# 2. 因此不再提供 RUNTIME_IMAGE / BUILDER_IMAGE 覆盖能力。
#    如需使用内网镜像仓库，请在本地把镜像打成相同标签后再构建：
#      docker pull <内网仓库>/library/eclipse-temurin:17-jre
#      docker tag  <内网仓库>/library/eclipse-temurin:17-jre eclipse-temurin:17-jre
# =============================================================================

# -----------------------------------------------------------------------------
# 阶段一：Maven 构建（固定镜像，需提供 mvn 与 JDK 17）
# -----------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS builder

ARG MODULE
ARG JAR_FILE

WORKDIR /build

# 复制后端源码（mall-master 为 Maven 多模块工程根目录）
COPY mall-master/ ./

# -am 同时构建依赖模块（mall-common / mall-mbg / mall-security）
# 根 pom 默认 skipTests=false；镜像构建阶段不需要跑测试，这里显式 -DskipTests
# 只跳过测试执行，不改变模块编译与产物内容。
# -Ddocker.skip=true：mall-search/mall-admin/mall-portal 的 pom 绑定了 fabric8
# docker-maven-plugin（写死连 192.168.3.101:2375 构建镜像），容器内构建必须跳过，
# 外层 Dockerfile 已负责打镜像，该插件步骤冗余且必然失败。
RUN mvn -B -ntp -pl "${MODULE}" -am -DskipTests -Ddocker.skip=true package

# -----------------------------------------------------------------------------
# 阶段二：Java 17 运行时（固定 Debian/Ubuntu 系镜像，必须可用 apt-get）
# -----------------------------------------------------------------------------
FROM eclipse-temurin:17-jre AS runtime

ARG MODULE
ARG JAR_FILE
ARG APP_PORT=8080

WORKDIR /app

# 安装 curl，仅供容器 healthcheck 探测存活接口使用
# （mall-agent 探 /health/live，其余 Java 服务探 actuator 健康端点）
# 说明：项目规则禁止使用递归删除命令，因此这里不清理 apt 列表目录，
#       改用 apt-get clean 清理已下载的安装包缓存（不删除任何目录）。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && apt-get clean

# 运行身份由该 ARG 选择，默认 root：mall-admin / mall-search / mall-portal 不传此参数，
# 保持与迁移前一致的运行身份；只有 mall-shopping-agent 传入 mallapp（UID/GID 10001:10001）。
# ARG 只在最终镜像被打入的 USER 指令中取值，不引入任何额外特权步骤。
ARG APP_RUN_USER=root
# 进程 HOME 由该 ARG 选择，默认 /root，与 eclipse-temurin:17-jre root 基础镜像的 HOME
# 一致：mall-admin / mall-search / mall-portal 不传此参数，保持 HOME=/root 的迁移前环境；
# 只有 mall-shopping-agent 传入 /app，与 mallapp 的 passwd home（下方 useradd --home-dir）一致。
# ARG 只被下方 ENV HOME 引用，不引入任何额外特权或目录创建步骤。
ARG APP_HOME=/root

# 创建固定的运行用户/组 mallapp，UID:GID 固定 10001:10001。
# 四个 Java 服务（mall-admin / mall-search / mall-portal / mall-agent）共用本镜像，
# 但最终运行身份由上方 ARG APP_RUN_USER 选择（默认 root）：
#   - mall-admin / mall-search / mall-portal 不传该 ARG，保持迁移前一致的运行身份；
#   - 只有 Compose 的 mall-shopping-agent 传入 APP_RUN_USER=mallapp，以非 root 运行，
#     与 Compose 中 user: "10001:10001" 取值完全一致。
# 用户创建始终执行且与运行身份无关：即使以 root 运行，/app 也归属 mallapp。
# apt-get 安装与用户创建都在下方 USER 之前完成，无需 root 之外的额外特权。
# passwd home 显式设为 /app（--no-create-home 不新建目录）：/app 是下方已存在的
# WORKDIR，且随后以非递归 chown 赋权给 mallapp，因此 home 可写且无需新增/递归 chown 目录。
RUN groupadd --gid 10001 mallapp \
    && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /app --shell /usr/sbin/nologin mallapp

# app.jar 归属 mallapp 且可读；只对 /app 目录本体赋权（非递归 chown），
# 让 JVM 能在工作目录写 hs_err_pid*.log / replay_pid*.log：Agent 镜像中该目录
# 同时是 mallapp 的 home（HOME=/app）；三个 root 服务的 HOME 为基础镜像默认的 /root。
# 不递归 chown 广泛路径，也不改动 /tmp：Tomcat multipart 临时文件与 logback 的
# ${java.io.tmpdir}/logs 仍使用基础镜像默认可写的 /tmp（权限 1777）。
COPY --chown=mallapp:mallapp --from=builder /build/${MODULE}/target/${JAR_FILE} /app/app.jar
RUN chown mallapp:mallapp /app

ENV TZ=Asia/Shanghai
ENV JAVA_OPTS=""
# 进程 HOME 由上方 ARG APP_HOME 选择：默认 /root（root 基础镜像的 HOME，供
# mall-admin / mall-search / mall-portal 保持迁移前环境）；Agent 镜像传入 APP_HOME=/app，
# 与 mallapp 的 passwd home 一致（/app 已 chown 给 mallapp，可作为可写的 home）。
ENV HOME=${APP_HOME}

EXPOSE ${APP_PORT}

# 运行身份取上方 ARG APP_RUN_USER：默认 root（三个应用沿用原运行身份）；
# 仅 mall-shopping-agent 传入 mallapp（UID:GID 10001:10001），与 Compose 显式 user 一致。
# USER 晚于 apt-get 安装、用户创建与 /app chown，切换后 /app 已对 mallapp 可写。
USER ${APP_RUN_USER}

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
