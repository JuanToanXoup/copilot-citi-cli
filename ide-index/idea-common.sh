#!/usr/bin/env bash
# Common setup for headless-idea.sh and headful-idea.sh
# Sources IDE_HOME, JBR, LIB, SANDBOX, PATHS_SELECTOR, PRODUCT_PREFIX, and JNA_ARCH
# from gradle.properties. Must be sourced, not executed directly.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROPS="$SCRIPT_DIR/gradle.properties"

# Read values from gradle.properties
get_prop() {
  grep "^$1\s*=" "$PROPS" | sed 's/^[^=]*=\s*//' | tr -d '[:space:]'
}

GRADLE_VERSION="$(get_prop gradleVersion)"
PLATFORM_TYPE="$(get_prop platformType)"
PLATFORM_VERSION="$(get_prop platformVersion)"

# Derive major version (e.g., 2024.3.3 -> 2024.3)
MAJOR_VERSION="${PLATFORM_VERSION%.*}"

# Map platform type to product prefix and paths selector name
case "$PLATFORM_TYPE" in
  IC) PRODUCT_PREFIX="Idea";  SELECTOR_NAME="IdeaIC";;
  IU) PRODUCT_PREFIX="Idea";  SELECTOR_NAME="IdeaIU";;
  CL) PRODUCT_PREFIX="CLion"; SELECTOR_NAME="CLion";;
  PS) PRODUCT_PREFIX="PhpStorm"; SELECTOR_NAME="PhpStorm";;
  WS) PRODUCT_PREFIX="WebStorm"; SELECTOR_NAME="WebStorm";;
  PY) PRODUCT_PREFIX="Python"; SELECTOR_NAME="PyCharm";;
  PC) PRODUCT_PREFIX="PyCharmCore"; SELECTOR_NAME="PyCharmCE";;
  GO) PRODUCT_PREFIX="GoLand"; SELECTOR_NAME="GoLand";;
  RD) PRODUCT_PREFIX="Rider"; SELECTOR_NAME="Rider";;
  RM) PRODUCT_PREFIX="RubyMine"; SELECTOR_NAME="RubyMine";;
  AI) PRODUCT_PREFIX="AndroidStudio"; SELECTOR_NAME="AndroidStudio";;
  RR) PRODUCT_PREFIX="RustRover"; SELECTOR_NAME="RustRover";;
  DB) PRODUCT_PREFIX="DataGrip"; SELECTOR_NAME="DataGrip";;
  *)  PRODUCT_PREFIX="Idea"; SELECTOR_NAME="IdeaIC";;
esac

# Map platform type to artifact name
case "$PLATFORM_TYPE" in
  IC) ARTIFACT="ideaIC";;
  IU) ARTIFACT="ideaIU";;
  CL) ARTIFACT="clion";;
  PS) ARTIFACT="phpstorm";;
  WS) ARTIFACT="webstorm";;
  PY) ARTIFACT="pycharmPY";;
  PC) ARTIFACT="pycharmPC";;
  GO) ARTIFACT="goland";;
  RD) ARTIFACT="rider";;
  RM) ARTIFACT="rubymine";;
  *)  ARTIFACT="ideaIC";;
esac

# Detect architecture
ARCH="$(uname -m)"
case "$ARCH" in
  arm64|aarch64) ARCH_SUFFIX="aarch64"; JNA_ARCH="aarch64";;
  x86_64|amd64)  ARCH_SUFFIX="amd64";   JNA_ARCH="amd64";;
  *)             ARCH_SUFFIX="$ARCH";    JNA_ARCH="$ARCH";;
esac

# Find IDE_HOME in Gradle cache
# Different Gradle versions store transformed IDEs in different locations:
#   Gradle 9.x:    caches/<version>/transforms/<hash>/transformed/<artifact>-<version>-<arch>
#   Gradle 8.11+:  caches/<version>/transforms/<hash>/transformed/<artifact>-<version>-<arch>
#   Gradle 8.10-:  caches/transforms-3/<hash>/transformed/<artifact>-<version>-<arch>
#   Plugin 1.x:    caches/modules-2/files-2.1/com.jetbrains.intellij.idea/<artifact>/<version>/<hash>/<artifact>-<version>
GRADLE_CACHE="$HOME/.gradle/caches"
IDE_HOME=""

# Search 1: Gradle 9.x / 8.11+ (version-specific transforms)
for dir in "$GRADLE_CACHE"/*/transforms/*/transformed/"${ARTIFACT}-${PLATFORM_VERSION}"*; do
  [ -d "$dir" ] && IDE_HOME="$dir" && break
done

# Search 2: Gradle 8.10 and earlier (shared transforms-N directory)
if [ -z "$IDE_HOME" ]; then
  for dir in "$GRADLE_CACHE"/transforms-*/*/transformed/"${ARTIFACT}-${PLATFORM_VERSION}"*; do
    [ -d "$dir" ] && IDE_HOME="$dir" && break
  done
fi

# Search 3: Gradle IntelliJ Plugin 1.x modules cache (IDE without bundled JBR)
if [ -z "$IDE_HOME" ]; then
  for dir in "$GRADLE_CACHE"/modules-2/files-2.1/com.jetbrains.intellij.idea/"${ARTIFACT}"/"${PLATFORM_VERSION}"/*/"${ARTIFACT}-${PLATFORM_VERSION}"; do
    [ -d "$dir" ] && IDE_HOME="$dir" && break
  done
fi

if [ -z "$IDE_HOME" ]; then
  echo "ERROR: Could not find IDE in Gradle cache. Run './gradlew buildPlugin' first." >&2
  echo "Searched for ${ARTIFACT}-${PLATFORM_VERSION} in:" >&2
  echo "  $GRADLE_CACHE/*/transforms/*/transformed/" >&2
  echo "  $GRADLE_CACHE/transforms-*/*/transformed/" >&2
  echo "  $GRADLE_CACHE/modules-2/files-2.1/com.jetbrains.intellij.idea/${ARTIFACT}/${PLATFORM_VERSION}/" >&2
  exit 1
fi

# Find Java runtime: bundled JBR > JAVA_HOME > system java
if [ -d "$IDE_HOME/jbr/Contents/Home" ]; then
  JBR="$IDE_HOME/jbr/Contents/Home"
elif [ -d "$IDE_HOME/jbr" ]; then
  JBR="$IDE_HOME/jbr"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JBR="$JAVA_HOME"
elif command -v java &>/dev/null; then
  JBR="$(java -XshowSettings:properties -version 2>&1 | grep 'java.home' | awk '{print $3}')"
else
  echo "ERROR: No JBR found in IDE and JAVA_HOME is not set." >&2
  exit 1
fi

LIB="$IDE_HOME/lib"
SANDBOX="$SCRIPT_DIR/build/idea-sandbox"
PATHS_SELECTOR="${SELECTOR_NAME}${MAJOR_VERSION}"

# Boot classpath from product-info.json
BOOT_CP=""
for jar in \
  platform-loader.jar util-8.jar util.jar app-client.jar util_rt.jar \
  opentelemetry.jar app.jar lib-client.jar stats.jar jps-model.jar \
  external-system-rt.jar rd.jar bouncy-castle.jar protobuf.jar \
  intellij-test-discovery.jar forms_rt.jar lib.jar externalProcess-rt.jar \
  groovy.jar annotations.jar idea_rt.jar jsch-agent.jar \
  kotlinx-coroutines-slf4j-1.8.0-intellij.jar nio-fs.jar trove.jar; do
  [ -f "$LIB/$jar" ] && BOOT_CP="${BOOT_CP:+$BOOT_CP:}$LIB/$jar"
done

# Common JVM arguments
COMMON_JVM_ARGS=(
  -classpath "$BOOT_CP"
  -Xms128m -Xmx2048m
  -XX:ReservedCodeCacheSize=512m
  -XX:+HeapDumpOnOutOfMemoryError
  -XX:-OmitStackTraceInFastThrow
  -XX:+IgnoreUnrecognizedVMOptions
  -Xlog:cds=off
  -ea
  -Djava.system.class.loader=com.intellij.util.lang.PathClassLoader
  -Didea.vendor.name=JetBrains
  -Didea.paths.selector="$PATHS_SELECTOR"
  -Djna.boot.library.path="$LIB/jna/$JNA_ARCH"
  -Djna.nosys=true -Djna.noclasspath=true
  -Dpty4j.preferred.native.folder="$LIB/pty4j"
  -Dintellij.platform.runtime.repository.path="$IDE_HOME/modules/module-descriptors.jar"
  -Didea.platform.prefix="$PRODUCT_PREFIX"
  -Dsun.io.useCanonCaches=false -Dsun.java2d.metal=true
  -Djbr.catch.SIGABRT=true
  -Djdk.http.auth.tunneling.disabledSchemes=""
  -Djdk.attach.allowAttachSelf=true
  -Djdk.module.illegalAccess.silent=true
  -Dkotlinx.coroutines.debug=off
  -Didea.config.path="$SANDBOX/config"
  -Didea.system.path="$SANDBOX/system"
  -Didea.plugins.path="$SANDBOX/plugins"
  -Didea.log.path="$SANDBOX/log"
  --add-opens=java.base/java.io=ALL-UNNAMED
  --add-opens=java.base/java.lang=ALL-UNNAMED
  --add-opens=java.base/java.lang.ref=ALL-UNNAMED
  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED
  --add-opens=java.base/java.net=ALL-UNNAMED
  --add-opens=java.base/java.nio=ALL-UNNAMED
  --add-opens=java.base/java.nio.charset=ALL-UNNAMED
  --add-opens=java.base/java.text=ALL-UNNAMED
  --add-opens=java.base/java.time=ALL-UNNAMED
  --add-opens=java.base/java.util=ALL-UNNAMED
  --add-opens=java.base/java.util.concurrent=ALL-UNNAMED
  --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED
  --add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED
  --add-opens=java.base/jdk.internal.vm=ALL-UNNAMED
  --add-opens=java.base/sun.net.dns=ALL-UNNAMED
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED
  --add-opens=java.base/sun.nio.fs=ALL-UNNAMED
  --add-opens=java.base/sun.security.ssl=ALL-UNNAMED
  --add-opens=java.base/sun.security.util=ALL-UNNAMED
  --add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED
  --add-opens=java.desktop/com.apple.eawt.event=ALL-UNNAMED
  --add-opens=java.desktop/com.apple.laf=ALL-UNNAMED
  --add-opens=java.desktop/com.sun.java.swing=ALL-UNNAMED
  --add-opens=java.desktop/java.awt=ALL-UNNAMED
  --add-opens=java.desktop/java.awt.dnd.peer=ALL-UNNAMED
  --add-opens=java.desktop/java.awt.event=ALL-UNNAMED
  --add-opens=java.desktop/java.awt.font=ALL-UNNAMED
  --add-opens=java.desktop/java.awt.image=ALL-UNNAMED
  --add-opens=java.desktop/java.awt.peer=ALL-UNNAMED
  --add-opens=java.desktop/javax.swing=ALL-UNNAMED
  --add-opens=java.desktop/javax.swing.plaf.basic=ALL-UNNAMED
  --add-opens=java.desktop/javax.swing.text=ALL-UNNAMED
  --add-opens=java.desktop/javax.swing.text.html=ALL-UNNAMED
  --add-opens=java.desktop/sun.awt=ALL-UNNAMED
  --add-opens=java.desktop/sun.awt.datatransfer=ALL-UNNAMED
  --add-opens=java.desktop/sun.awt.image=ALL-UNNAMED
  --add-opens=java.desktop/sun.font=ALL-UNNAMED
  --add-opens=java.desktop/sun.java2d=ALL-UNNAMED
  --add-opens=java.desktop/sun.lwawt=ALL-UNNAMED
  --add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED
  --add-opens=java.desktop/sun.swing=ALL-UNNAMED
  --add-opens=java.management/sun.management=ALL-UNNAMED
  --add-opens=jdk.attach/sun.tools.attach=ALL-UNNAMED
  --add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED
  --add-opens=jdk.internal.jvmstat/sun.jvmstat.monitor=ALL-UNNAMED
  --add-opens=jdk.jdi/com.sun.tools.jdi=ALL-UNNAMED
)
