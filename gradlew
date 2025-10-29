#!/bin/sh

DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'
APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`

GRADLE_USER_HOME=${GRADLE_USER_HOME:-$HOME/.gradle}

exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS "\"-Dorg.gradle.appname=$APP_BASE_NAME\"" -classpath "\"$CLASSPATH\"" org.gradle.wrapper.GradleWrapperMain "$@"