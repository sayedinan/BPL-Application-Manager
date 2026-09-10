@echo off
echo BPL App Manager — Starting services (D:\BPL-Application-Manager)
echo 1. Start DB: docker-compose up -d postgres (if Docker installed)
echo 2. Start backend: cd backend && .\gradlew bootRun (or java -jar build\libs\*.jar)
echo 3. Start frontend: cd frontend && npm run dev
echo 4. Open browser: http://localhost:5173
echo Nothing else changed.
pause
