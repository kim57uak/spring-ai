#!/usr/bin/env bash
set -euo pipefail

# 예약 에이전트 호출 스크립트
# 사용법:
#   ./scripts/call-reservation-agent.sh
#   ./scripts/call-reservation-agent.sh "원하는 프롬프트"
#   ./scripts/call-reservation-agent.sh "원하는 프롬프트" --stream

BASE_URL="${BASE_URL:-http://localhost:8082}"
MODEL="${MODEL:-mistral}"
PROMPT="${1:- AAZ115260411OZ1 여행예약해죠. 예약자 : 김병두,연락처 : 01038569626, 예약인원수 1명,생년월일 : 19740308.}"
STREAM_MODE="${2:-}"
REQUEST_ID="reservation-$(date +%s)"

json_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

ESCAPED_PROMPT="$(json_escape "$PROMPT")"
PAYLOAD="$(cat <<JSON
{
  "jsonrpc": "2.0",
  "id": "$REQUEST_ID",
  "method": "$( [ "$STREAM_MODE" = "--stream" ] && echo "message/stream" || echo "message/send" )",
  "params": {
    "messageText": "$ESCAPED_PROMPT",
    "model": "$MODEL"
  }
}
JSON
)"

if [ "$STREAM_MODE" = "--stream" ]; then
  curl -sS -N \
    -X POST "$BASE_URL/a2a/reservation" \
    -H "Content-Type: application/json" \
    -H "Accept: text/event-stream" \
    -d "$PAYLOAD"
else
  curl -sS \
    -X POST "$BASE_URL/a2a/reservation" \
    -H "Content-Type: application/json" \
    -d "$PAYLOAD"
fi

echo
