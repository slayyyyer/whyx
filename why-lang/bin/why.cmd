@echo off
rem Runs the Why CLI. Set WHY_JAR to an existing jar; the default is the repository build.
setlocal
if "%WHY_JAR%"=="" set "WHY_JAR=%~dp0..\build\libs\why.jar"
if not exist "%WHY_JAR%" (
  echo why: no CLI jar at %WHY_JAR% 1>&2
  echo why: build one with "./gradlew shadowJar", or set WHY_JAR to a downloaded why.jar 1>&2
  exit /b 1
)
if defined JAVA_HOME (
  "%JAVA_HOME%\bin\java" -jar "%WHY_JAR%" %*
) else (
  java -jar "%WHY_JAR%" %*
)
