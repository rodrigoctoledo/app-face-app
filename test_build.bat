@echo off
cd /d "c:\Users\Rodri\Downloads\FaceDetectorApp\FaceDetectorApp"
echo Compiling...
call gradlew.bat compileDebugKotlin
if %errorlevel% equ 0 (
    echo ✓ Compilation successful
    pause
) else (
    echo ✗ Compilation failed
    pause
)
