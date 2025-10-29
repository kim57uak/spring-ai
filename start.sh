#!/bin/bash
echo "Starting Spring AI server..."
gradle bootRun > app.log 2>&1 &
echo "Server starting in background. Check app.log for details."
sleep 3
if lsof -i :8082 > /dev/null; then
    echo "Server started successfully on port 8082"
else
    echo "Server failed to start. Check app.log for errors."
fi