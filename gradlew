#!/bin/sh
cd "$(dirname "$0")" || exit 1
if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
  if command -v gradle >/dev/null 2>&1; then
    echo "首次运行：用本机 gradle 生成 wrapper …"
    gradle wrapper --gradle-version 8.9 || exit 1
    exec ./gradlew "$@"
  else
    echo "缺少 gradle/wrapper/gradle-wrapper.jar。"
    echo "办法一：用 Android Studio 打开本目录，它会自动同步并下载 Gradle。"
    echo "办法二：装好 gradle（8.x）后再运行一次 ./gradlew。"
    exit 1
  fi
fi
