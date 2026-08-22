@REM ----------------------------------------------------------------------------
@REM Licensed to the Apache Software Foundation (ASF) under one
@REM or more contributor license agreements.  See the NOTICE file
@REM distributed with this work for additional information
@REM regarding copyright ownership.  The ASF licenses this file
@REM to you under the Apache License, Version 2.0 (the
@REM "License"); you may not use this file except in compliance
@REM with the License.  You may obtain a copy of the License at
@REM
@REM    https://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing,
@REM software distributed under the License is distributed on an
@REM "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
@REM KIND, either express or implied.  See the License for the
@REM specific language governing permissions and limitations
@REM under the License.
@REM ----------------------------------------------------------------------------

@REM ----------------------------------------------------------------------------
@REM Maven Start Up Batch script
@REM ----------------------------------------------------------------------------

@if "%MAVEN_BATCH_ECHO%" == "on"  echo %MAVEN_BATCH_ECHO%
@if "%MAVEN_BATCH_PAUSE%" == "on" set MAVEN_BATCH_PAUSE=on

@setlocal

set ERROR_CODE=0

@REM To isolate internal variables from possible post scripts, we use another setlocal
@setlocal

@REM ==== START VALIDATION ====
if not "%JAVA_HOME%" == "" goto OkJHome

echo.
echo ERROR: JAVA_HOME not found in your environment. >&2
echo Please set the JAVA_HOME variable in your environment to match the >&2
echo location of your Java installation. >&2
echo.
goto error

:OkJHome
if exist "%JAVA_HOME%\bin\java.exe" goto init

echo.
echo ERROR: JAVA_HOME is set to an invalid directory. >&2
echo JAVA_HOME = "%JAVA_HOME%" >&2
echo Please set the JAVA_HOME variable in your environment to match the >&2
echo location of your Java installation. >&2
echo.
goto error

@REM ==== END VALIDATION ====

:init

@REM Find the project base dir, i.e. the dir that contains the folder ".mvn".
@REM Fallback to current dir if not found.

set MAVEN_PROJECTBASEDIR=%MAVEN_BASEDIR%
if "%MAVEN_PROJECTBASEDIR%"=="." set MAVEN_PROJECTBASEDIR=
if not "%MAVEN_PROJECTBASEDIR%"=="" goto valBase

set MAVEN_PROJECTBASEDIR=%CD%

:findBaseDir
if exist "%MAVEN_PROJECTBASEDIR%\.mvn" goto valBase
set "MAVEN_PROJECTBASEDIR=%MAVEN_PROJECTBASEDIR%\.."
if not "%MAVEN_PROJECTBASEDIR%"=="..\" goto findBaseDir
set MAVEN_PROJECTBASEDIR=%CD%

:valBase
set "EXEC_DIR=%CD%"
set "WDIR=%MAVEN_PROJECTBASEDIR%"

@REM Set WRAPPER_JAR
set "WRAPPER_JAR=%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.jar"
set "WRAPPER_LAUNCHER=org.apache.maven.wrapper.MavenWrapperMain"

@REM Download Wrapper Jar if not present
if exist "%WRAPPER_JAR%" goto runEscaped

set MAVEN_WRAPPER_PROPERTIES="%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.properties"
powershell -Command "& { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; (New-Object Net.WebClient).DownloadFile((Get-Content '%MAVEN_WRAPPER_PROPERTIES%' | Select-String -Pattern 'wrapperUrl=(.*)' | ForEach-Object { $_.Matches.Groups[1].Value }), '%WRAPPER_JAR%') }"

:runEscaped
"%JAVA_HOME%\bin\java.exe" %MAVEN_OPTS% -classpath "%WRAPPER_JAR%" "-Dmaven.home=%MAVEN_HOME%" "-Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR%" %WRAPPER_LAUNCHER% %*
if ERRORLEVEL 1 goto error
goto end

:error
set ERROR_CODE=1

:end
@endlocal & set ERROR_CODE=%ERROR_CODE%

if not "%MAVEN_SKIP_RC%" == "" goto skipRcPost
if exist "%HOME%\mavenrc_post.bat" call "%HOME%\mavenrc_post.bat"
if exist "%HOMEDRIVE%%HOMEPATH%\mavenrc_post.bat" call "%HOMEDRIVE%%HOMEPATH%\mavenrc_post.bat"
:skipRcPost

if "%MAVEN_BATCH_PAUSE%" == "on" pause

if "%NDS_PAUSE%" == "on" pause

cmd /C exit /B %ERROR_CODE%
