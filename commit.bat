@echo off
cd /d "c:\Users\Rodri\Downloads\FaceDetectorApp\FaceDetectorApp"
echo Adicionando arquivos...
git add .
echo Fazendo commit...
git commit -m "Fix: add missing imports in MainActivity"
echo Fazendo push...
git push
echo.
echo Pronto! Verifique em: https://github.com/rodrigoctoledo/app-face-app
pause
