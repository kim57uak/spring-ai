#!/bin/bash
echo "Stopping Spring AI server..."
pkill -f "gradle bootRun"
lsof -ti :8082 | xargs kill 2>/dev/null
sleep 2
if lsof -i :8082 > /dev/null 2>&1; then
    echo "Force killing remaining processes..."
    lsof -ti :8082 | xargs kill -9 2>/dev/null
fi
echo "Server stopped."