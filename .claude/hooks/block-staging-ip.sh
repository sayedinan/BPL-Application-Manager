#!/bin/bash
# block-staging-ip.sh
# Blocks any Bash command or file write that references the live
# staging server IP (180.210.129.233), shared with the JMeter suite.
# This protects the BUILD process only — see the note above about the
# finished product's intended use of this address.
#
# Exit code 2 = block. Exit code 0 = allow.

set -euo pipefail

STAGING_IP="180.210.129.233"

INPUT=$(cat)

# Two tool shapes: Bash has tool_input.command, Write/Edit have
# tool_input.content (Write also has tool_input.file_path).
COMMAND=$(echo "$INPUT" | jq -r '.tool_input.command // empty')
CONTENT=$(echo "$INPUT" | jq -r '.tool_input.content // empty')
PAYLOAD="${COMMAND}${CONTENT}"

if [ -z "$PAYLOAD" ]; then
  exit 0
fi

if echo "$PAYLOAD" | grep -qF "$STAGING_IP"; then
  echo "Blocked: this references the live staging server ($STAGING_IP)," >&2
  echo "shared with the JMeter integration suite." >&2
  echo "" >&2
  echo "During development, test/seed data should use a placeholder or" >&2
  echo "local address instead. If you are intentionally configuring the" >&2
  echo "real production engine, do this manually through the app's UI" >&2
  echo "once the product is finished — not through an automated edit." >&2
  exit 2
fi

exit 0