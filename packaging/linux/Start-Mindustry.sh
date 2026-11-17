#!/bin/sh
set -eu
cd "$(dirname "$0")"
exec ./jre/bin/java -jar Mindustry.jar "$@"
