#!/usr/bin/env bash
#
# First-time host bootstrap for curtli on Amazon Linux 2023 (Graviton t4g.small).
# Idempotent on re-run — safe to invoke against an already-bootstrapped box.
#
# What this does:
#   1. Updates OS packages
#   2. Installs Docker + Compose v2 plugin + Buildx plugin
#   3. Installs cron (cronie) + enables crond
#   4. Quality-of-life tools (git, jq, htop)
#   5. Adds ec2-user to the docker group (for SSH-fallback workflow)
#   6. Sets up a 2 GB swap file as an OOM circuit breaker (vm.swappiness=10
#      so the kernel doesn't reach for it eagerly — EBS swap is slow)
#   7. Configures dnf-automatic for security-only auto-updates
#
# What it deliberately does NOT do (separate manual steps):
#   - Clone /opt/curtli (you choose the branch / private repo auth)
#   - Create /opt/curtli/.env (you must paste real secrets)
#   - Install /etc/cron.d/curtli (copy from scripts/curtli.cron.example
#     and edit the BUCKET= line)
#
# Usage:
#   sudo bash scripts/host-bootstrap.sh
# (Self-elevates if you forget the sudo.)

set -euo pipefail

if [ "$EUID" -ne 0 ]; then
    exec sudo -E "$0" "$@"
fi

echo "=== curtli host bootstrap ==="

# 1. Update OS packages
dnf update -y

# 2. Quality-of-life + scheduling tools
dnf install -y git jq htop cronie
systemctl enable --now crond

# 3. Docker from AL2023 repo
dnf install -y docker
systemctl enable --now docker

# Architecture suffix used by both Compose and Buildx download URLs.
ARCH=$(uname -m)   # aarch64 on Graviton, x86_64 elsewhere

# 4. Docker Compose v2 plugin (AL2023's docker package doesn't ship it)
mkdir -p /usr/local/lib/docker/cli-plugins
curl -SL "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-${ARCH}" \
    -o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

# 5. Docker Buildx plugin (required by Compose v2 for the `build` directive)
BUILDX_VERSION=$(curl -fsS https://api.github.com/repos/docker/buildx/releases/latest | jq -r .tag_name)
curl -SL "https://github.com/docker/buildx/releases/download/${BUILDX_VERSION}/buildx-${BUILDX_VERSION}.linux-${ARCH}" \
    -o /usr/local/lib/docker/cli-plugins/docker-buildx
chmod +x /usr/local/lib/docker/cli-plugins/docker-buildx

# 6. Let ec2-user run docker without sudo (for the SSH-fallback workflow)
usermod -aG docker ec2-user

# 7. 2 GB swap — OOM circuit breaker only, NOT regular headroom (slow on EBS)
if [ ! -f /swapfile ]; then
    fallocate -l 2G /swapfile
    chmod 600 /swapfile
    mkswap /swapfile
    swapon /swapfile
    echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

# Bias the kernel against swap unless truly out of memory.
echo 'vm.swappiness=10' > /etc/sysctl.d/99-curtli.conf
sysctl -p /etc/sysctl.d/99-curtli.conf > /dev/null

# 8. Automatic security updates (security only — feature updates stay manual)
dnf install -y dnf-automatic
sed -i 's/^upgrade_type = default/upgrade_type = security/' /etc/dnf/automatic.conf
sed -i 's/^apply_updates = no/apply_updates = yes/' /etc/dnf/automatic.conf
systemctl enable --now dnf-automatic.timer

# 9. Verify
echo ""
echo "=== Bootstrap complete ==="
echo "Docker:    $(docker --version)"
echo "Compose:   $(docker compose version)"
echo "Buildx:    $(docker buildx version | head -1)"
echo "Cron:      $(systemctl is-active crond)"
echo "Auto-upd:  $(systemctl is-enabled dnf-automatic.timer)"
echo ""
echo "Memory:"
free -h
echo ""
echo "Swap:"
swapon --show
echo ""
echo "Next steps:"
echo "  1. sudo git clone <repo-url> /opt/curtli"
echo "  2. cd /opt/curtli && sudo cp .env.example .env && sudo nano .env"
echo "  3. sudo chmod 600 .env"
echo "  4. sudo docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build"
echo "  5. sudo cp scripts/curtli.cron.example /etc/cron.d/curtli   # set BUCKET="
