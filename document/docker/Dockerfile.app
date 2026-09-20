# =============================================================================
# mall-admin / mall-search / mall-portal 通用多阶段构建文件
# =============================================================================
# 由 docker-compose.yml 通过 build.args 传入 MODULE / JAR_FILE / APP_PORT，
# 从本地源码构建可执行 jar，不依赖任何预构建镜像。
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
# pom.xml 中已设置 skipTests=true，这里再次显式声明
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

# 安装 curl，仅供容器 healthcheck 调用 /actuator/health 使用
# 说明：项目规则禁止使用递归删除命令，因此这里不清理 apt 列表目录，
#       改用 apt-get clean 清理已下载的安装包缓存（不删除任何目录）。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && apt-get clean

COPY --from=builder /build/${MODULE}/target/${JAR_FILE} /app/app.jar

ENV TZ=Asia/Shanghai
ENV JAVA_OPTS=""

EXPOSE ${APP_PORT}

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
