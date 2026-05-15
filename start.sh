#!/bin/bash
echo "Starting Spring AI server..."
gradle bootRun > app.log 2>&1 &
echo "Server starting in background. Check app.log for details."
sleep 5
for i in {1..10}; do
    if lsof -i :8082 > /dev/null; then
        echo "Server started successfully on port 8082"
        exit 0
    fi
    sleep 1
done
echo "Server startup taking longer than expected. Check app.log for status."
