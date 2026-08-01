#!/bin/bash
# Starts the Compose/JS dev server with hot reload on http://localhost:8080.
# Works from any directory, so it can be run from a terminal or from Finder via run.command.
cd "$(dirname "$0")" || exit 1

# A Finder-launched shell does not read the login profile, so JAVA_HOME may be empty.
if [ -z "$JAVA_HOME" ]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home)"
  export JAVA_HOME
fi

./gradlew jsBrowserDevelopmentRun --continuous
