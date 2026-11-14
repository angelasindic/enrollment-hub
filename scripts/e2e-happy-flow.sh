#!/usr/bin/env bash
# Happy flow without a browser: OIDC login through the gateway, payment check, CREDIT_CARD enrollment.
# Expects the infrastructure and all six services running locally.
set -euo pipefail

GW=http://127.0.0.1:8079
AS=http://localhost:9000
JAR=$(mktemp)
PAGE=$(mktemp)
trap 'rm -f "$JAR" "$PAGE"' EXIT
c() { curl -s -b "$JAR" -c "$JAR" "$@"; }

# 1. Start at the gateway; curl follows the redirects to the authorization server's login form.
c -L -o "$PAGE" "$GW/" || { echo "gateway not reachable at $GW" >&2; exit 1; }
csrf=$(grep -o 'name="_csrf"[^>]*value="[^"]*"' "$PAGE" | sed 's/.*value="//; s/"$//' || true)
[ -n "$csrf" ] || { echo "no login form; is the authorization server running at $AS?" >&2; exit 1; }

# 2. Log in; curl follows authorize -> gateway callback -> gateway. The session cookies of both hosts
#    stay in the jar, as in a browser.
landed=$(c -L -o "$PAGE" -w '%{url_effective}' \
  --data-urlencode username=user --data-urlencode password=password --data-urlencode "_csrf=$csrf" \
  "$AS/login")

# First login only: approve the consent screen.
if grep -q 'name="state"' "$PAGE"; then
  state=$(grep -o 'name="state" value="[^"]*"' "$PAGE" | sed 's/.*value="//; s/"$//')
  landed=$(c -L -o "$PAGE" -w '%{url_effective}' \
    --data-urlencode client_id=enrollment-login-client --data-urlencode "state=$state" \
    --data-urlencode scope=profile --data-urlencode scope=enrollment:write \
    "$AS/oauth2/authorize")
fi
case "$landed" in
  "$GW/login?error"*) echo "login rejected by the gateway ($landed)" >&2; exit 1 ;;
  "$GW"/*) echo "logged in" ;;
  *) echo "login did not return to the gateway ($landed)" >&2; exit 1 ;;
esac

# 3. The gateway set the XSRF-TOKEN cookie in its login response; read the token from the jar.
xsrf=$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$JAR")

# 4. Payment check: stores the credit_card_check token in the gateway session (expect 204).
echo "payment-check: $(c -o /dev/null -w '%{http_code}' -X POST -H "X-XSRF-TOKEN: $xsrf" "$GW/payment-check")"

# 5. Enrollment (expect 202 with enrollmentId).
c -w '\nenrollment: %{http_code}\n' -X POST "$GW/enrollment/public/v1/enrollments" \
  -H "X-XSRF-TOKEN: $xsrf" -H 'Content-Type: application/json' --data @- <<'EOF'
{
  "paymentType": "CREDIT_CARD",
  "person": { "firstName": "Ada", "lastName": "Lovelace",
              "emailAddress": "ada@example.com", "phoneNumber": "+31201234567" },
  "shippingAddress": { "streetLines": ["Dam 1"], "postalCode": "1012 JS",
                       "city": "Amsterdam", "subregion": "Noord-Holland", "countryCode": "NL" },
  "billingAddress":  { "streetLines": ["Dam 1"], "postalCode": "1012 JS",
                       "city": "Amsterdam", "subregion": "Noord-Holland", "countryCode": "NL" }
}
EOF
