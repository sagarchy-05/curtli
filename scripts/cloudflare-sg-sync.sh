#!/usr/bin/env bash
#
# Sync the security group's inbound port-8080 rules with Cloudflare's published
# IP list. Designed to run via cron — quiet on no-op, loud on changes.
#
# Requires the EC2 instance to have an IAM role granting:
#   - ec2:DescribeSecurityGroups
#   - ec2:AuthorizeSecurityGroupIngress
#   - ec2:RevokeSecurityGroupIngress
#
# Install via /etc/cron.d/curtli (see scripts/curtli.cron.example).

set -euo pipefail

# ─── Config ──────────────────────────────────────────────────────────────────
SG_NAME="${SG_NAME:-curtli-ec2-sg}"
REGION="${REGION:-ap-south-2}"
PORT="${PORT:-8080}"
PROTOCOL="tcp"
LOG_TAG="curtli-sg-sync"

# ─── Helpers ─────────────────────────────────────────────────────────────────
log()  { echo "$(date -Iseconds)  $*"; logger -t "$LOG_TAG" -- "$*" 2>/dev/null || true; }
die()  { log "ERROR: $*"; exit 1; }

# ─── Resolve the SG ID by name ───────────────────────────────────────────────
SG_ID=$(aws ec2 describe-security-groups \
    --region "$REGION" \
    --filters "Name=group-name,Values=$SG_NAME" \
    --query "SecurityGroups[0].GroupId" \
    --output text 2>/dev/null || true)

[ -z "$SG_ID" ] || [ "$SG_ID" = "None" ] && die "SG '$SG_NAME' not found in $REGION"

# ─── Fetch desired state from Cloudflare ─────────────────────────────────────
DESIRED_V4=$(curl -fsS https://www.cloudflare.com/ips-v4 | sort -u)
DESIRED_V6=$(curl -fsS https://www.cloudflare.com/ips-v6 | sort -u)

[ -n "$DESIRED_V4" ] || die "empty Cloudflare IPv4 list — refusing to apply"

# ─── Fetch existing rules for this port ──────────────────────────────────────
EXISTING_V4=$(aws ec2 describe-security-groups \
    --region "$REGION" --group-ids "$SG_ID" \
    --query "SecurityGroups[0].IpPermissions[?ToPort==\`$PORT\` && IpProtocol=='$PROTOCOL'].IpRanges[].CidrIp" \
    --output text | tr '\t' '\n' | grep -v '^$' | sort -u || true)

EXISTING_V6=$(aws ec2 describe-security-groups \
    --region "$REGION" --group-ids "$SG_ID" \
    --query "SecurityGroups[0].IpPermissions[?ToPort==\`$PORT\` && IpProtocol=='$PROTOCOL'].Ipv6Ranges[].CidrIpv6" \
    --output text | tr '\t' '\n' | grep -v '^$' | sort -u || true)

# ─── Compute diffs ───────────────────────────────────────────────────────────
TO_ADD_V4=$(comm -23 <(echo "$DESIRED_V4") <(echo "$EXISTING_V4") | grep -v '^$' || true)
TO_REMOVE_V4=$(comm -13 <(echo "$DESIRED_V4") <(echo "$EXISTING_V4") | grep -v '^$' || true)
TO_ADD_V6=$(comm -23 <(echo "$DESIRED_V6") <(echo "$EXISTING_V6") | grep -v '^$' || true)
TO_REMOVE_V6=$(comm -13 <(echo "$DESIRED_V6") <(echo "$EXISTING_V6") | grep -v '^$' || true)

CHANGES=0

# ─── Apply changes ───────────────────────────────────────────────────────────
apply_rule() {
    local action=$1 family=$2 cidr=$3 perm_field

    if [ "$family" = "v4" ]; then
        if [ "$action" = "authorize" ]; then
            perm_field="IpRanges=[{CidrIp=$cidr,Description=Cloudflare}]"
        else
            perm_field="IpRanges=[{CidrIp=$cidr}]"
        fi
    else
        if [ "$action" = "authorize" ]; then
            perm_field="Ipv6Ranges=[{CidrIpv6=$cidr,Description=Cloudflare}]"
        else
            perm_field="Ipv6Ranges=[{CidrIpv6=$cidr}]"
        fi
    fi

    aws ec2 "${action}-security-group-ingress" \
        --region "$REGION" \
        --group-id "$SG_ID" \
        --ip-permissions "IpProtocol=$PROTOCOL,FromPort=$PORT,ToPort=$PORT,$perm_field" \
        > /dev/null
    CHANGES=$((CHANGES + 1))
}

while IFS= read -r cidr; do [ -n "$cidr" ] && { log "+ v4 $cidr"; apply_rule authorize v4 "$cidr"; }; done <<< "$TO_ADD_V4"
while IFS= read -r cidr; do [ -n "$cidr" ] && { log "- v4 $cidr"; apply_rule revoke    v4 "$cidr"; }; done <<< "$TO_REMOVE_V4"
while IFS= read -r cidr; do [ -n "$cidr" ] && { log "+ v6 $cidr"; apply_rule authorize v6 "$cidr"; }; done <<< "$TO_ADD_V6"
while IFS= read -r cidr; do [ -n "$cidr" ] && { log "- v6 $cidr"; apply_rule revoke    v6 "$cidr"; }; done <<< "$TO_REMOVE_V6"

# ─── Summary ────────────────────────────────────────────────────────────────
DESIRED_V4_COUNT=$(echo "$DESIRED_V4" | wc -l)
DESIRED_V6_COUNT=$(echo "$DESIRED_V6" | wc -l)

if [ "$CHANGES" -eq 0 ]; then
    log "in sync (${DESIRED_V4_COUNT} IPv4 + ${DESIRED_V6_COUNT} IPv6 rules, no changes)"
else
    log "applied $CHANGES change(s) — target: ${DESIRED_V4_COUNT} IPv4 + ${DESIRED_V6_COUNT} IPv6"
fi
