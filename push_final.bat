@echo off
chcp 65001 >nul
cd /d "c:\Users\Rodri\Downloads\FaceDetectorApp\FaceDetectorApp"

echo ========================================
echo  FaceDetector App - Push to GitHub
echo ========================================
echo.

echo [1/4] Adicionando arquivos...
git add .
if %errorlevel% neq 0 (
    echo ERRO: Falha ao adicionar arquivos
    pause
    exit /b 1
)
echo ✓ Arquivos adicionados

echo.
echo [2/4] Fazendo commit...
git commit -m "Complete face detection pipeline fix: camera rotation sync, overlay correction, video recording (CameraX), frame auto-save with JPEG rotation, resolution fallback, robust error handling"
if %errorlevel% neq 0 (
    echo ERRO: Falha ao fazer commit
    pause
    exit /b 1
)
echo ✓ Commit realizado

echo.
echo [3/4] Configurando remote...
git remote set-url origin https://github.com/rodrigoctoledo/app-face-app.git
if %errorlevel% neq 0 (
    echo AVISO: Remote já configurado ou erro
)
echo ✓ Remote configurado

echo.
echo [4/4] Fazendo push para main...
git push -u origin main --force-with-lease
if %errorlevel% neq 0 (
    echo ERRO: Falha ao fazer push
    echo.
    echo Tentando push simples...
    git push
    if %errorlevel% neq 0 (
        echo ERRO: Push falhou completamente
        pause
        exit /b 1
    )
)
echo ✓ Push realizado com sucesso!

echo.
echo ========================================
echo  ✓ Tudo enviado para GitHub!
echo ========================================
echo.
echo GitHub Actions irá compilar o APK em:
echo https://github.com/rodrigoctoledo/app-face-app/actions
echo.
echo APK disponível em: Artifacts ^> app-debug
echo.
pause
