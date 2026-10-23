@echo off
rem Autogame Windows 客户端启动器：优先用包内自带的 JRE，找不到才退回系统 javaw。
rem 想在命令行里看报错，可在 cmd 里执行：runtime\bin\java.exe -jar client.jar --selftest
setlocal
cd /d "%~dp0"

set "JAVA=%~dp0runtime\bin\javaw.exe"
if not exist "%JAVA%" set "JAVA=javaw.exe"

start "" "%JAVA%" -Dfile.encoding=UTF-8 -jar "%~dp0client.jar" %*
endlocal
