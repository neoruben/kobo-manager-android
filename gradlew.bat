@echo off
if not defined JAVA_HOME if exist "%ProgramFiles%\Android\Android Studio\jbr" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
set "DEFAULT_JVM_OPTS=-Xmx2048m -Dfile.encoding=UTF-8"
set "GRADLE_OPTS=-Xmx2048m -Dfile.encoding=UTF-8"
set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
set "ANDROID_SDK_ROOT=%LOCALAPPDATA%\Android\Sdk"
cd /d "%~dp0"
call "%USERPROFILE%\.gradle\wrapper\dists\gradle-9.6.0-bin\42k10rwplmzkhuboz9kdazi7s\gradle-9.6.0\bin\gradle.bat" --no-daemon %*
