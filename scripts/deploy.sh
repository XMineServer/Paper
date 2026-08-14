#!/usr/bin/env bash
#
# XMine - build this fork and publish it to https://maven.xmine.world/
#
# Published artifacts, and the repository each one lands in:
#
#   snapshots/  com.destroystokyo.paper:paper-parent:dev-SNAPSHOT   (parent pom, needed to resolve paper-api)
#   snapshots/  com.destroystokyo.paper:paper-api:<mcver>-R0.1-SNAPSHOT      (+ sources jar)
#   snapshots/  com.destroystokyo.paper:paper-mojangapi:<mcver>-R0.1-SNAPSHOT (+ sources jar)
#   releases/   ru.xmine.paper:paperclip:<mcver>-xmine.<n>          (runnable server jar)
#
# The split is not cosmetic. The API artifacts carry the version baked into the
# generated poms, and that version ends in -SNAPSHOT: a release repository would
# reject them. Paperclip is different - it is what the game nodes download at
# startup, so it needs an address that never changes meaning. It therefore gets
# an immutable release version and goes to releases/.
#
# Version scheme: <mcver>-xmine.<commit count>, e.g. 1.16.5-xmine.5766.
# Monotonic, unique per commit, and traceable back to the exact source. Override
# with --version or XMINE_PAPER_VERSION when you need a hand-picked number.
#
# Credentials live in ~/.m2/settings.xml under the server ids
# `xmine-releases` / `xmine-snapshots`.
#
# Usage:
#   scripts/deploy.sh              patch sources, build everything, publish everything
#   scripts/deploy.sh --api        only the maven API artifacts
#   scripts/deploy.sh --paperclip  only the paperclip jar
#   scripts/deploy.sh --skip-build publish what is already in Paper-*/target and ./paperclip.jar
#   scripts/deploy.sh --version V  publish paperclip as version V

set -e

basedir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$basedir"

do_api=1
do_paperclip=1
do_build=1
forced_version="${XMINE_PAPER_VERSION:-}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        "--api")        do_paperclip=0 ;;
        "--paperclip")  do_api=0 ;;
        "--skip-build") do_build=0 ;;
        "--version")
            shift
            [[ $# -gt 0 ]] || { echo "--version needs an argument" >&2; exit 1; }
            forced_version="$1"
            ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
    shift
done

# --- toolchain -------------------------------------------------------------
# 1.16.5 needs JDK 8, which is deliberately kept out of the system PATH.
if [[ -z "$JAVA_HOME" || ! -x "$JAVA_HOME/bin/javac" ]] \
   || ! "$JAVA_HOME/bin/javac" -version 2>&1 | grep -q "javac 1\.8"; then
    jdk8="$(ls -d "$HOME"/.jdks/jdk8* 2>/dev/null | sort | tail -n1)"
    if [[ -z "$jdk8" ]]; then
        echo "JDK 8 not found in ~/.jdks - install it or export JAVA_HOME yourself." >&2
        exit 1
    fi
    export JAVA_HOME="$jdk8"
fi
export PATH="$JAVA_HOME/bin:$PATH"
echo "Using JAVA_HOME=$JAVA_HOME"

# --- credential check ------------------------------------------------------
# Checked before the build, because the build takes minutes and the upload is
# the very last step: a bad credential otherwise surfaces as a 401 after all
# the waiting.
settings="${HOME}/.m2/settings.xml"
if [[ ! -f "$settings" ]] || ! grep -q "xmine-snapshots" "$settings"; then
    echo "No 'xmine-snapshots' server entry in $settings - deploy would fail with 401." >&2
    exit 1
fi
# The presence of the server id is not enough. The shipped settings.xml is a
# template with REPLACE_WITH_TOKEN_NAME / REPLACE_WITH_TOKEN_SECRET in it, and
# an id check happily passes on it - which is exactly how a full build once
# ended in a 401 at the upload step.
if grep -q "REPLACE_WITH_" "$settings"; then
    echo "$settings still contains template placeholders (REPLACE_WITH_...)." >&2
    echo "Put a real Reposilite token into the xmine-releases / xmine-snapshots" >&2
    echo "server entries - otherwise the upload fails with 401." >&2
    exit 1
fi

mcver="$(grep minecraftVersion "$basedir/work/BuildData/info.json" | cut -d '"' -f 4)"

api_repo_id="xmine-snapshots"
api_repo_url="https://maven.xmine.world/snapshots"

paperclip_version="${forced_version:-${mcver}-xmine.$(git rev-list --count HEAD)}"
paperclip_repo_id="xmine-releases"
paperclip_repo_url="https://maven.xmine.world/releases"

if [[ "$paperclip_version" == *SNAPSHOT* ]]; then
    echo "Paperclip version '$paperclip_version' is a SNAPSHOT - the release repository will reject it." >&2
    exit 1
fi

paperclip_path="ru/xmine/paper/paperclip/${paperclip_version}/paperclip-${paperclip_version}.jar"
paperclip_url="${paperclip_repo_url}/${paperclip_path}"

echo "Minecraft version : $mcver"
[[ "$do_api" == 1 ]]       && echo "API artifacts     : $api_repo_url"
[[ "$do_paperclip" == 1 ]] && echo "Paperclip         : $paperclip_url"

# The release repository is append-only: publishing the same version twice fails
# with 405 halfway through, which reads like a broken build rather than "you
# already shipped this". Check up front and say so plainly.
if [[ "$do_paperclip" == 1 ]]; then
    existing="$(curl -sS -o /dev/null -w '%{http_code}' -m 30 "$paperclip_url" || echo 000)"
    case "$existing" in
        200)
            echo "" >&2
            echo "Version $paperclip_version is already published at $paperclip_url" >&2
            echo "Release versions are immutable. Commit your changes (the version follows the" >&2
            echo "commit count) or pass --version with a new number." >&2
            exit 1
            ;;
        404) ;;   # free, go ahead
        *)
            # A timeout or a TLS drop here is NOT proof that the version is free,
            # and treating it as such is how you find out mid-upload. This has
            # already happened once: the repository was briefly unreachable and
            # the check silently passed.
            echo "" >&2
            echo "Could not determine whether $paperclip_version is already published" >&2
            echo "(HTTP status '$existing' from $paperclip_url)." >&2
            echo "Refusing to guess - check the repository and retry." >&2
            exit 1
            ;;
    esac
fi

# --- build -----------------------------------------------------------------
if [[ "$do_build" == 1 ]]; then
    scripts/build.sh "$basedir"
    mvn clean install
fi

# --- API artifacts ---------------------------------------------------------
# `.` deploys paper-parent, which consumers need to resolve paper-api's pom.
# source:jar is not configured in the generated poms, so attach it here.
if [[ "$do_api" == 1 ]]; then
    mvn -pl .,Paper-API,Paper-MojangAPI source:jar deploy -DskipTests \
        -DaltDeploymentRepository="${api_repo_id}::default::${api_repo_url}"
fi

# --- paperclip -------------------------------------------------------------
if [[ "$do_paperclip" == 1 ]]; then
    if [[ "$do_build" == 1 || ! -f "$basedir/paperclip.jar" ]]; then
        scripts/paperclip.sh "$basedir"
    fi
    # Явный pom обязателен. Без -DpomFile плагин deploy-file берёт pom, вшитый
    # в сам jar (META-INF/maven/io.papermc/paperclip-java8/pom.xml), и публикует
    # его как есть: артефакт лежит по адресу ru.xmine.paper:paperclip:<версия>,
    # а внутри pom объявляет io.papermc:paperclip-java8:1.4.1-SNAPSHOT — да ещё
    # и со SNAPSHOT-зависимостью внутри релизного репозитория. Для скачивания по
    # прямому URL это безразлично, но любой, кто попробует подтянуть paperclip
    # как maven-зависимость, упрётся в несуществующие координаты.
    pom_file="$(mktemp -t paperclip-pom-XXXXXX.xml)"
    trap 'rm -f "$pom_file"' EXIT
    cat > "$pom_file" <<POM
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>ru.xmine.paper</groupId>
  <artifactId>paperclip</artifactId>
  <version>${paperclip_version}</version>
  <packaging>jar</packaging>
  <name>XMine Paper (paperclip)</name>
  <description>Runnable Paper server for Minecraft ${mcver}, built from the XMine fork.</description>
</project>
POM

    mvn deploy:deploy-file \
        -Durl="$paperclip_repo_url" \
        -DrepositoryId="$paperclip_repo_id" \
        -DpomFile="$pom_file" \
        -DgroupId=ru.xmine.paper \
        -DartifactId=paperclip \
        -Dversion="$paperclip_version" \
        -Dpackaging=jar \
        -Dfile="$basedir/paperclip.jar"

    # XMineNode downloads this jar by URL and verifies it against a pinned
    # sha256 (scripts/core.py:check_hash), so the pair below is what has to end
    # up in the node configuration. Printed together to keep them in sync -
    # a URL bumped without its hash fails the check and the node refuses to start.
    sha256="$(sha256sum "$basedir/paperclip.jar" | cut -d' ' -f1)"
    echo ""
    echo "Pin this in XMineNode (env of the minecraft stack):"
    echo "  SERVER_JAR_URL=$paperclip_url"
    echo "  SERVER_JAR_SHA256=$sha256"
fi

echo ""
echo "Done."
