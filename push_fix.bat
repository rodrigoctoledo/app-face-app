@echo off
cd /d "c:\Users\Rodri\Downloads\FaceDetectorApp\FaceDetectorApp"
echo Adicionando arquivos...
git add .
echo Fazendo commit...
git commit -m "Remove video recorder - simplify to core functionality"
echo Fazendo push...
git push
echo.
echo Pronto! APK será gerado em: https://github.com/rodrigoctoledo/app-face-app/actions
pause
