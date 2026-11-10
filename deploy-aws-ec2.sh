#!/usr/bin/env bash
set -euo pipefail

if [ "$EUID" -ne 0 ]; then
  echo "Please run as root or with sudo: sudo ./deploy-aws-ec2.sh"
  exit 1
fi

ACTUAL_USER="${SUDO_USER:-$USER}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# 1. Add 4GB swap on burstable instances so Maven + Vite builds never OOM
if [ "$(swapon --show | wc -l)" -le 1 ]; then
  fallocate -l 4G /swapfile || dd if=/dev/zero of=/swapfile bs=1M count=4096
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  if ! grep -q '/swapfile' /etc/fstab; then
    echo '/swapfile none swap sw 0 0' >> /etc/fstab
  fi
fi

# 2. Install Docker CE + Compose plugin if missing
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y ca-certificates curl gnupg lsb-release git ufw

if ! command -v docker &> /dev/null; then
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  chmod a+r /etc/apt/keyrings/docker.gpg
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" > /etc/apt/sources.list.d/docker.list
  apt-get update -y
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  systemctl enable docker
  systemctl start docker
  usermod -aG docker "$ACTUAL_USER" || true
fi

# 3. Configure UFW
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable

# 4. Detect EC2 Public IP & derive sslip.io HTTPS domain
IMDS_TOKEN=$(curl -s -X PUT "http://169.254.169.254/latest/api/token" -H "X-aws-ec2-metadata-token-ttl-seconds: 60" || true)
PUBLIC_IP=""
if [ -n "$IMDS_TOKEN" ]; then
  PUBLIC_IP=$(curl -s -H "X-aws-ec2-metadata-token: $IMDS_TOKEN" "http://169.254.169.254/latest/meta-data/public-ipv4" || true)
fi
if [ -z "$PUBLIC_IP" ]; then
  PUBLIC_IP=$(curl -s https://api.ipify.org)
fi

SSLIP_DOMAIN="$(echo "$PUBLIC_IP" | tr '.' '-').sslip.io"
BASE_URL="https://${SSLIP_DOMAIN}"

if [ ! -f .env ]; then
  cp .env.example .env
fi

upsert_env() {
  local key="$1"
  local val="$2"
  if grep -q "^${key}=" .env; then
    sed -i "s|^${key}=.*|${key}=${val}|" .env
  else
    printf "\n%s=%s\n" "$key" "$val" >> .env
  fi
}

upsert_env "BOT_DOMAIN" "$SSLIP_DOMAIN"
upsert_env "PUBLIC_BASE_URL" "$BASE_URL"
upsert_env "CORS_ALLOWED_ORIGINS" "$BASE_URL"
upsert_env "STRICT_WEBHOOK_VERIFICATION" "true"
upsert_env "ALLOW_SIMULATE_PAY" "true"

# 5. Build and start production stack
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build

# 6. Verify local backend health and HTTPS Caddy endpoint
for i in $(seq 1 40); do
  if curl -fsS http://localhost:8090/api/health >/dev/null 2>&1; then
    break
  fi
  sleep 3
done

echo "PUBLIC_IP=${PUBLIC_IP}"
echo "BASE_URL=${BASE_URL}"
curl -fsS http://localhost:8090/api/health
echo ""
