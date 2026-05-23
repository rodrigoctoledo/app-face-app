@echo off
cd /d c:\Users\Rodri\Downloads\FaceDetectorApp\FaceDetectorApp
call gradlew.bat assembleDebug
exit /b %ERRORLEVEL%
