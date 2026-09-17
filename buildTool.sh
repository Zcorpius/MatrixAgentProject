#!/usr/bin/env bash
#
# buildTool.sh —— MatrixAgent APK 编译 + 归档工具
#
# 用法:
#   ./buildTool.sh <service|launcher|test|all> <debug|release>
#
# 示例:
#   ./buildTool.sh all release        # 三个 APK 模块一起编 release
#   ./buildTool.sh service debug      # 只编 Service APK
#   ./buildTool.sh launcher release   # 只编 Launcher APK
#   ./buildTool.sh test debug         # 编 Test APK（trusted/untrusted 两个 flavor 各一个）
#
# 行为:
#   1. 先在临时目录归档；全部构建成功后才替换 outputs/，失败时保留上一批产物
#   2. 编译前先执行根级 gradle clean（已验证不会删除 ondevice/.cxx，MNN native 不重编）
#   3. 归档文件名带版本与连续分钟时间戳，如 matrix-agent-service-debug-v0.6.0-6000-202609161730.apk
#   4. Service/Launcher 的 debug、release 均走 platform 签名；Test 的 untrusted flavor 保留 debug 签名

set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT_DIR="$ROOT/outputs"
VERSION_PROPERTIES="$ROOT/gradle.properties"

read_version_property() {
    local key="$1"
    local value
    value="$(awk -F= -v key="$key" '$1 == key { print substr($0, index($0, "=") + 1); exit }' "$VERSION_PROPERTIES")"
    if [[ -z "$value" ]]; then
        echo "错误: $VERSION_PROPERTIES 缺少 $key" >&2
        exit 1
    fi
    printf '%s' "$value"
}

usage() {
    cat <<'EOF'
用法: ./buildTool.sh <service|launcher|test|all> <debug|release>

目标:
  service    :matrix-agent-service   Service APK  (com.matrix.agent)
  launcher   :matrix-agent-launcher  Launcher APK (com.matrix.agent.launcher)
  test       :matrix-agent-test      Test APK     (trusted/untrusted 各一个)
  all        上述三个模块一起编译

变体:
  debug | release
EOF
}

if [[ $# -ne 2 ]]; then
    usage
    exit 1
fi

target="$1"
variant="$2"

case "$target" in
    service | launcher | test | all) ;;
    *)
        echo "错误: 未知目标 '$target'" >&2
        usage
        exit 1
        ;;
esac

case "$variant" in
    debug | release) ;;
    *)
        echo "错误: 未知变体 '$variant'（仅支持 debug|release）" >&2
        usage
        exit 1
        ;;
esac

# 兼容 macOS 自带 bash 3.2：不用 ${var^}，显式映射任务名大小写
case "$variant" in
    debug) variant_cap="Debug" ;;
    release) variant_cap="Release" ;;
esac

case "$target" in
    service) modules="matrix-agent-service" ;;
    launcher) modules="matrix-agent-launcher" ;;
    test) modules="matrix-agent-test" ;;
    all) modules="matrix-agent-service matrix-agent-launcher matrix-agent-test" ;;
esac

# 同一次运行共享版本与连续分钟时间戳，标识同一批产物。
VERSION_NAME="$(read_version_property MATRIX_VERSION_NAME)"
VERSION_CODE="$(read_version_property MATRIX_VERSION_CODE)"
if ! [[ "$VERSION_CODE" =~ ^[1-9][0-9]*$ ]]; then
    echo "错误: MATRIX_VERSION_CODE 必须是正整数，当前为 '$VERSION_CODE'" >&2
    exit 1
fi
VERSION_TAG="v${VERSION_NAME}-${VERSION_CODE}"
STAMP="$(date +%Y%m%d%H%M)"
STAGE_DIR="$(mktemp -d "$ROOT/.outputs-stage.XXXXXX")"
cleanup_stage() {
    rm -rf "$STAGE_DIR"
}
trap cleanup_stage EXIT

echo "==> 目标: $target  变体: $variant  版本: $VERSION_TAG  时间戳: $STAMP"

# 1. 创建最终归档目录；旧内容在本次构建和暂存全部成功后才会清理。
mkdir -p "$OUT_DIR"

# 2. 一次 Gradle 调用：clean + 所有目标模块的 assemble（共享配置阶段；
#    clean 在前，命令行任务按给定顺序执行）
cd "$ROOT"
tasks="clean"
for m in $modules; do
    tasks="$tasks :$m:assemble$variant_cap"
done
echo
echo "==> ./gradlew$tasks"
./gradlew $tasks

# 3. 从各模块 build/outputs/apk 收集所选变体的 APK，先归档到临时目录。
collect_module() {
    local module="$1"
    local apk_root="$ROOT/$module/build/outputs/apk"
    if [[ ! -d "$apk_root" ]]; then
        echo "错误: $module 无 APK 产出目录 $apk_root" >&2
        exit 1
    fi
    local count=0
    while IFS= read -r -d '' apk; do
        local base
        base="$(basename "$apk" .apk)"
        cp "$apk" "$STAGE_DIR/${base}-${VERSION_TAG}-${STAMP}.apk"
        echo "    + ${base}-${VERSION_TAG}-${STAMP}.apk"
        count=$((count + 1))
    done < <(find "$apk_root" -type f -name '*.apk' -path "*/$variant/*" ! -path '*/androidTest/*' -print0)
    if [[ "$count" -eq 0 ]]; then
        echo "错误: $module 未产出 $variant 变体 APK" >&2
        exit 1
    fi
}

echo
echo "==> 暂存归档产物"
for m in $modules; do
    collect_module "$m"
done

# 4. 仅在所有模块均编译、归档成功后，替换上一批 outputs/ 内容。
find "$OUT_DIR" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
find "$STAGE_DIR" -mindepth 1 -maxdepth 1 -type f -name '*.apk' -exec mv {} "$OUT_DIR"/ \;

echo
echo "==> 完成。outputs/ 内容:"
ls -lh "$OUT_DIR"
