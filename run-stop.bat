@echo off
echo Stopping BPL services...
docker-compose down 2>nul
TASKKILL /F /IM java.exe 2>nul
echo Frontend / backend stopped. Nothing else changed.
pause
