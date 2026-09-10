#!/bin/bash
echo "BPL App Manager — Starting all services (D:\BPL-Application-Manager)"
echo "1. PostgreSQL (if Docker): docker-compose up -d postgres"
echo "2. Backend: ./gradlew bootRun (or java -jar backend/build/libs/*.jar)"
echo "3. Frontend: cd frontend && npm run dev -> http://localhost:5173"
echo "Then open http://localhost:5173 in browser. Nothing else changed."
