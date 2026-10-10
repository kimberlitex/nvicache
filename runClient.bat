@echo off
rem Launches Minecraft 1.21.1 (NeoForge)
rem and joins the singleplayer world below right away (empty = main menu).
set "VRGUNS_QUICKPLAY=New World"
cd /d "%~dp0"
call gradlew.bat runClient --console=plain
if errorlevel 1 (
    echo.
    echo runClient failed, see output above.
    pause
)
