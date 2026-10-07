#!/bin/sh
# Builds the shared Kotlin core (../core) as HalfNavCore.xcframework for the iOS app.
set -e
# Rebuild the shared core framework so Swift always sees the latest Kotlin code.
# Needs a JDK 17+: set JAVA_HOME in Xcode's environment or rely on /usr/libexec/java_home.
cd "$SRCROOT/.."
if [ -z "$JAVA_HOME" ]; then export JAVA_HOME=$(/usr/libexec/java_home -v 17+ 2>/dev/null); fi
if [ -z "$JAVA_HOME" ]; then
  echo "warning: No JDK 17+ found; using the last-built HalfNavCore.xcframework."
  exit 0
fi
TASK=":core:assembleHalfNavCore$(echo ${HALFNAV_CORE_CONFIG} | awk '{print toupper(substr($0,1,1)) substr($0,2)}')XCFramework"
./gradlew "$TASK" --console=plain
# Link against a fixed location (Xcode can't vary framework paths by configuration).
rm -rf core/build/XCFrameworks/current
cp -R "core/build/XCFrameworks/${HALFNAV_CORE_CONFIG}" core/build/XCFrameworks/current
