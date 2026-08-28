#!/usr/bin/env sh
# ============================================================
# greensam-cli 启动脚本（类 Unix）
# 运行 Maven 打包出的 fat jar，并固化 UTF-8 编码参数。
# 用法: ./bin/greensam-cli.sh
# ============================================================
set -e

VERSION=0.0.1-SNAPSHOT
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
JAR="$SCRIPT_DIR/../target/greensam-cli-$VERSION.jar"

if [ ! -f "$JAR" ]; then
    echo "[错误] 未找到 $JAR"
    echo "请先在项目根目录执行: mvn clean package -DskipTests" >&2
    exit 1
fi

# file.encoding: JVM 默认字符集；stdout/stderr.encoding: JDK18+ 标准流编码
exec java \
  -Dfile.encoding=UTF-8 \
  -Dstdout.encoding=UTF-8 \
  -Dstderr.encoding=UTF-8 \
  -jar "$JAR" "$@"
