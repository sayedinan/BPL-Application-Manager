@echo off
echo BPL App Manager — Starting services (D:\BPL-Application-Manager)
echo 1. Database: nothing to start (SQLite file backend\data\bpl.db is created on first run)
echo 2. Start backend: cd backend && .\gradlew bootRun (or java -jar build\libs\*.jar)
echo 3. Start frontend: cd frontend && npm run dev
echo 4. Open browser: http://localhost:5173
echo Nothing else changed.
pause
