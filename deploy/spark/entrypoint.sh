#!/bin/bash
# Spark 容器入口（等价官方镜像 docker-entrypoint.sh 的 driver/executor 分发）。
#
# 为什么需要：executor Pod 由 driver 创建，Spark 把容器参数设成 ["executor"]，
# 真正的启动信息（driver-url / executor-id / app-id / cores）通过环境变量传入。
# 若镜像 Entrypoint 不是这个分发器，runc 会报 exec: "executor": executable file not found。
set -e

export JAVA_HOME="${JAVA_HOME:-/opt/java}"
export SPARK_HOME="${SPARK_HOME:-/opt/spark}"
export PATH="$JAVA_HOME/bin:$SPARK_HOME/bin:$PATH"

# 底座镜像的 /etc/passwd 不完整，Hadoop UGI 取 user.name 会拿到 null，
# 报 KerberosAuthException: invalid null input: name。显式指定用户规避。
export HADOOP_USER_NAME="${HADOOP_USER_NAME:-root}"

case "$1" in
  driver)
    shift
    exec "$SPARK_HOME/bin/spark-submit" "$@"
    ;;
  executor)
    shift
    MEM="${SPARK_EXECUTOR_MEMORY:-1g}"
    HOST="${SPARK_EXECUTOR_POD_IP:-$(hostname -i 2>/dev/null || echo 127.0.0.1)}"
    # JVM 参数优先用 driver 传下来的 SPARK_JAVA_OPT_<n>：Spark 在 k8s 模式下把
    # executor 需要的 JVM 参数（含 JDK17 的 --add-opens）逐个塞进环境变量，
    # 官方镜像的 entrypoint 也是这么取的。跟着 Spark 版本走，不用手抄参数表。
    JVM_OPTS=()
    i=0
    while :; do
      var="SPARK_JAVA_OPT_${i}"
      val="${!var-}"
      [ -z "$val" ] && break
      JVM_OPTS+=("$val")
      i=$((i + 1))
    done
    # 兜底：driver 没传（老版本或直接起容器调试）时，补 Spark 3.5 在 JDK17 下
    # 访问 sun.nio.ch.DirectBuffer 所需的最小集合，否则 StorageUtils 报 IllegalAccessError
    if [ ${#JVM_OPTS[@]} -eq 0 ]; then
      JVM_OPTS=(--add-opens=java.base/java.lang=ALL-UNNAMED
                --add-opens=java.base/java.lang.invoke=ALL-UNNAMED
                --add-opens=java.base/java.lang.reflect=ALL-UNNAMED
                --add-opens=java.base/java.io=ALL-UNNAMED
                --add-opens=java.base/java.net=ALL-UNNAMED
                --add-opens=java.base/java.nio=ALL-UNNAMED
                --add-opens=java.base/sun.nio.ch=ALL-UNNAMED
                --add-opens=java.base/sun.nio.cs=ALL-UNNAMED
                --add-opens=java.base/sun.security.action=ALL-UNNAMED)
    fi
    exec "$JAVA_HOME/bin/java" "${JVM_OPTS[@]}" -Xms"$MEM" -Xmx"$MEM" \
      -cp "$SPARK_HOME/conf:$SPARK_HOME/jars/*" \
      org.apache.spark.executor.CoarseGrainedExecutorBackend \
      --driver-url "$SPARK_DRIVER_URL" \
      --executor-id "$SPARK_EXECUTOR_ID" \
      --hostname "$HOST" \
      --cores "$SPARK_EXECUTOR_CORES" \
      --app-id "$SPARK_APP_ID" \
      --resourceProfileId "${SPARK_RESOURCE_PROFILE_ID:-0}"
    ;;
  "")
    echo "usage: entrypoint.sh driver|executor ..." >&2
    exit 2
    ;;
  *)
    exec "$@"
    ;;
esac
