@echo off
chcp 65001 >nul
cd /d "c:\Users\Rodri\Downloads\FaceDetectorApp\FaceDetectorApp"

echo ========================================
echo  Building FaceDetector App...
echo ========================================
echo.

echo [1/3] Cleaning old build...
call gradlew.bat clean >nul 2>&1

echo [2/3] Compiling...
call gradlew.bat assembleDebug
if %errorlevel% neq 0 (
    echo.
    echo ✗ Build FAILED
    pause
    exit /b 1
)

echo.
echo ========================================
echo  ✓ Build successful!
echo ========================================
echo.
echo APK location:
echo c:\Users\Rodri\Downloads\FaceDetectorApp\FaceDetectorApp\app\build\outputs\apk\debug\app-debug.apk
echo.
pause
