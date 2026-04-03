@ECHO OFF
REM ----------------------------------------------------------------------------
REM Maven Wrapper startup script for Windows
REM This script allows running Maven without a pre-installed Maven by
REM downloading the configured Maven distribution and wrapper JAR on demand.
REM ----------------------------------------------------------------------------

SETLOCAL ENABLEDELAYEDEXPANSION

SET PRG=%~f0
SET BASEDIR=%~dp0
IF "%BASEDIR:~-1%"=="\" SET BASEDIR=%BASEDIR:~0,-1%

SET WRAPPER_DIR=%BASEDIR%\.mvn\wrapper
SET PROPS_FILE=%WRAPPER_DIR%\maven-wrapper.properties
SET WRAPPER_JAR=%WRAPPER_DIR%\maven-wrapper.jar

REM Read property value from properties file
REM Usage: call :read_prop key varName
:read_prop
  SET KEY=%~1
  SET OUTVAR=%~2
  IF NOT EXIST "%PROPS_FILE%" (
    SET %OUTVAR%=
    GOTO :EOF
  )
  FOR /F "usebackq tokens=1,2 delims==" %%A IN ("%PROPS_FILE%") DO (
    SET K=%%A
    SET V=%%B
    IF /I "!K!"=="%KEY%" (
      SET %OUTVAR%=!V!
    )
  )
GOTO :EOF

CALL :read_prop wrapperUrl WRAPPER_URL
CALL :read_prop distributionUrl DIST_URL

IF NOT EXIST "%WRAPPER_JAR%" (
  ECHO Downloading Maven Wrapper JAR from: %WRAPPER_URL% 1>&2
  IF NOT EXIST "%WRAPPER_DIR%" MKDIR "%WRAPPER_DIR%"
  WHERE curl >NUL 2>&1
  IF %ERRORLEVEL% EQU 0 (
    curl -fsSL -o "%WRAPPER_JAR%" "%WRAPPER_URL%"
  ) ELSE (
    WHERE wget >NUL 2>&1
    IF %ERRORLEVEL% EQU 0 (
      wget -q -O "%WRAPPER_JAR%" "%WRAPPER_URL%"
    ) ELSE (
      ECHO Error: curl or wget is required to download the Maven Wrapper JAR. 1>&2
      EXIT /B 1
    )
  )
)

SET JVM_CONFIG=%BASEDIR%\.mvn\jvm.config
SET JVM_OPTS=
IF EXIST "%JVM_CONFIG%" (
  FOR /F "usebackq delims=" %%A IN ("%JVM_CONFIG%") DO SET JVM_OPTS=!JVM_OPTS! %%A
)

IF DEFINED DIST_URL SET MVNW_REPOURL=%DIST_URL%

IF DEFINED JAVA_HOME (
  SET JAVA_EXE=%JAVA_HOME%\bin\java.exe
) ELSE (
  SET JAVA_EXE=java
)

"%JAVA_EXE%" %JVM_OPTS% -Dmaven.multiModuleProjectDirectory="%BASEDIR%" -cp "%WRAPPER_JAR%" org.apache.maven.wrapper.MavenWrapperMain %*
EXIT /B %ERRORLEVEL%