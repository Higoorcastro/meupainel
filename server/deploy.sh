#!/usr/bin/env bash
# =============================================================================
#  MeuPainel — instalação, inicialização e atualização na VPS
#
#  Uso (dentro da pasta do servidor, ex.: ~/signage):
#    bash deploy.sh            instala na 1ª vez / atualiza e reinicia nas próximas
#    bash deploy.sh status     mostra se está rodando e o endereço
#    bash deploy.sh logs       acompanha os logs (Ctrl+C para sair)
#    bash deploy.sh restart    reinicia sem reconstruir
#    bash deploy.sh stop       para o servidor
#    bash deploy.sh backup     backup completo (banco + mídias) em ./backups
#
#  O mesmo comando serve para a primeira instalação e para cada atualização:
#  ele faz backup do banco, reconstrói a imagem, reinicia e confere se subiu.
# =============================================================================
set -euo pipefail

cd "$(dirname "$(readlink -f "$0")")"

APP_SERVICE="signage"
BACKUP_DIR="./backups"
KEEP_DB_BACKUPS=15

# ----------------------------------------------------------------------------- saída
if [ -t 1 ]; then
  B=$'\e[1m'; G=$'\e[32m'; Y=$'\e[33m'; R=$'\e[31m'; C=$'\e[36m'; N=$'\e[0m'
else
  B=""; G=""; Y=""; R=""; C=""; N=""
fi
step() { printf '\n%s==>%s %s%s%s\n' "$C" "$N" "$B" "$*" "$N"; }
ok()   { printf '  %s✔%s %s\n' "$G" "$N" "$*"; }
warn() { printf '  %s!%s %s\n' "$Y" "$N" "$*"; }
die()  { printf '\n%s✘ %s%s\n' "$R" "$*" "$N" >&2; exit 1; }

# ----------------------------------------------------------------------------- utilidades
env_get() { # env_get CHAVE → valor no .env (vazio se não existir)
  [ -f .env ] || return 0
  grep -E "^$1=" .env | tail -n1 | cut -d= -f2- || true
}

env_set() { # env_set CHAVE VALOR → cria ou substitui no .env
  local key="$1" value="$2" tmp
  tmp="$(mktemp)"
  if [ -f .env ] && grep -qE "^${key}=" .env; then
    awk -v k="$key" -v v="$value" 'BEGIN{FS=OFS="="} $1==k{print k "=" v; next} {print}' .env > "$tmp"
  else
    { [ -f .env ] && cat .env; printf '%s=%s\n' "$key" "$value"; } > "$tmp"
  fi
  mv "$tmp" .env
  chmod 600 .env
}

random_hex() { head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n'; }

port_in_use() { # port_in_use 80 → 0 se algo escuta na porta
  if command -v ss >/dev/null 2>&1; then
    ss -tln 2>/dev/null | awk '{print $4}' | grep -qE "[:.]$1\$"
  elif command -v netstat >/dev/null 2>&1; then
    netstat -tln 2>/dev/null | awk '{print $4}' | grep -qE "[:.]$1\$"
  else
    return 1
  fi
}

compose_services() { # serviços a subir conforme o modo
  if [ "$(env_get DEPLOY_MODE)" = "proxy" ]; then echo "$APP_SERVICE"; else echo "$APP_SERVICE caddy"; fi
}

container_id() { docker compose ps -q "$APP_SERVICE" 2>/dev/null || true; }

is_running() {
  local id; id="$(container_id)"
  [ -n "$id" ] && [ "$(docker inspect -f '{{.State.Running}}' "$id" 2>/dev/null)" = "true" ]
}

# ----------------------------------------------------------------------------- verificações
check_docker() {
  step "Verificando o Docker"
  if ! command -v docker >/dev/null 2>&1; then
    warn "Docker não encontrado."
    read -r -p "  Instalar o Docker agora? [S/n] " answer
    if [[ "${answer:-S}" =~ ^[SsYy]$ ]]; then
      curl -fsSL https://get.docker.com | sudo sh
      sudo usermod -aG docker "$USER" || true
      ok "Docker instalado."
      die "Saia do SSH (exit), entre novamente e rode 'bash deploy.sh' de novo (necessário para usar o Docker sem sudo)."
    fi
    die "O Docker é necessário. Instale com: curl -fsSL https://get.docker.com | sudo sh"
  fi
  docker info >/dev/null 2>&1 || die "Sem acesso ao Docker. Rode: sudo usermod -aG docker \$USER  e entre de novo no SSH (ou use sudo)."
  docker compose version >/dev/null 2>&1 || die "Plugin 'docker compose' não encontrado. Reinstale com: curl -fsSL https://get.docker.com | sudo sh"
  ok "$(docker --version)"
  ok "$(docker compose version)"
}

setup_env() {
  step "Configuração (.env)"
  if [ ! -f .env ]; then
    [ -f .env.example ] || die "Arquivo .env.example não encontrado. Você está na pasta do servidor?"
    warn "Primeira instalação: vamos criar o arquivo .env."
    cp .env.example .env
    chmod 600 .env

    local domain password password2
    while true; do
      read -r -p "  Domínio do painel (ex.: signage.seudominio.com): " domain
      domain="${domain#http://}"; domain="${domain#https://}"; domain="${domain%%/*}"
      [[ "$domain" =~ ^[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]] && break
      warn "Domínio inválido, tente novamente."
    done
    while true; do
      read -r -s -p "  Senha do painel (mín. 8 caracteres): " password; echo
      read -r -s -p "  Repita a senha: " password2; echo
      if [ "${#password}" -lt 8 ]; then warn "A senha precisa ter pelo menos 8 caracteres."; continue; fi
      if [ "$password" != "$password2" ]; then warn "As senhas não conferem."; continue; fi
      if [[ "$password" == *"="* || "$password" == *" "* ]]; then warn "Não use espaços ou '=' na senha."; continue; fi
      break
    done
    env_set DOMAIN "$domain"
    env_set PUBLIC_URL "https://$domain"
    env_set ADMIN_PASSWORD "$password"
    env_set SESSION_SECRET "$(random_hex)"
    ok ".env criado."
  fi

  local domain password
  domain="$(env_get DOMAIN)"
  password="$(env_get ADMIN_PASSWORD)"
  [ -n "$domain" ] && [ "$domain" != "signage.seudominio.com" ] || die "Defina DOMAIN no arquivo .env (nano .env)."
  [ -n "$password" ] && [ "$password" != "troque-esta-senha" ] || die "Defina uma ADMIN_PASSWORD forte no arquivo .env (nano .env)."
  [ -n "$(env_get PUBLIC_URL)" ] || env_set PUBLIC_URL "https://$domain"
  [ -n "$(env_get SESSION_SECRET)" ] || env_set SESSION_SECRET "$(random_hex)"
  ok "Domínio: $domain"
}

choose_mode() {
  step "Modo de publicação (HTTPS)"
  local mode; mode="$(env_get DEPLOY_MODE)"
  if [ -z "$mode" ]; then
    local caddy_id; caddy_id="$(docker compose ps -q caddy 2>/dev/null || true)"
    if [ -z "$caddy_id" ] && { port_in_use 80 || port_in_use 443; }; then
      mode="proxy"
      warn "As portas 80/443 já estão em uso (provavelmente nginx/Apache de outro site)."
      warn "O servidor ficará em 127.0.0.1:3000 e você publica pelo seu proxy (deploy/nginx-meupainel.conf)."
    else
      mode="caddy"
    fi
    env_set DEPLOY_MODE "$mode"
  fi
  if [ "$mode" = "proxy" ]; then
    ok "Modo proxy: servidor em 127.0.0.1:3000 (HTTPS pelo seu nginx/Apache)."
  else
    ok "Modo caddy: HTTPS automático (Let's Encrypt) nas portas 80/443."
  fi
}

check_dns() {
  local domain ip_dns ip_vps
  domain="$(env_get DOMAIN)"
  ip_dns="$(getent ahostsv4 "$domain" 2>/dev/null | awk 'NR==1{print $1}' || true)"
  ip_vps="$(curl -fsS --max-time 5 https://api.ipify.org 2>/dev/null || true)"
  if [ -z "$ip_dns" ]; then
    warn "O domínio $domain ainda não resolve para nenhum IP. Crie o registro DNS tipo A apontando para ${ip_vps:-o IP desta VPS}."
    warn "Sem isso o certificado HTTPS não é emitido (o servidor sobe mesmo assim e tenta de novo depois)."
  elif [ -n "$ip_vps" ] && [ "$ip_dns" != "$ip_vps" ]; then
    warn "DNS de $domain aponta para $ip_dns, mas esta VPS é $ip_vps. Confira o registro A (ignore se usa Cloudflare com proxy)."
  else
    ok "DNS: $domain → $ip_dns"
  fi
}

# ----------------------------------------------------------------------------- backup
backup_db() {
  is_running || return 0
  step "Backup do banco de dados"
  mkdir -p "$BACKUP_DIR"
  local file
  file="signage-$(date +%Y%m%d-%H%M%S).db"
  # VACUUM INTO gera uma cópia consistente mesmo com o servidor rodando.
  if docker compose exec -T "$APP_SERVICE" node --disable-warning=ExperimentalWarning -e "
      const { DatabaseSync } = require('node:sqlite');
      const db = new DatabaseSync('/data/signage.db');
      db.exec(\"VACUUM INTO '/tmp/$file'\");
    " && docker compose cp "$APP_SERVICE:/tmp/$file" "$BACKUP_DIR/$file" >/dev/null 2>&1; then
    docker compose exec -T "$APP_SERVICE" rm -f "/tmp/$file" || true
    ok "Banco salvo em $BACKUP_DIR/$file"
    # Mantém só os mais recentes.
    # Nomes gerados por este script (sem espaços): ordenar por nome = ordenar por data.
    find "$BACKUP_DIR" -maxdepth 1 -name 'signage-*.db' | sort -r | tail -n +$((KEEP_DB_BACKUPS + 1)) | xargs -r rm -f
  else
    warn "Não foi possível fazer o backup do banco (continuando)."
  fi
}

full_backup() {
  is_running || die "O servidor não está rodando."
  step "Backup completo (banco + mídias)"
  mkdir -p "$BACKUP_DIR"
  local dest
  dest="$BACKUP_DIR/completo-$(date +%Y%m%d-%H%M%S)"
  docker compose cp "$APP_SERVICE:/data" "$dest"
  ok "Backup em $dest ($(du -sh "$dest" | cut -f1))"
}

# ----------------------------------------------------------------------------- deploy
wait_healthy() {
  step "Aguardando o servidor ficar pronto"
  local id status
  id="$(container_id)"
  [ -n "$id" ] || die "O container não foi criado. Veja: docker compose logs $APP_SERVICE"
  for _ in $(seq 1 45); do
    status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id" 2>/dev/null || echo unknown)"
    case "$status" in
      healthy) ok "Servidor saudável."; return 0 ;;
      unhealthy|exited|dead)
        docker compose logs --tail 40 "$APP_SERVICE" || true
        die "O servidor não subiu (status: $status). Veja os logs acima." ;;
    esac
    sleep 2
  done
  docker compose logs --tail 40 "$APP_SERVICE" || true
  die "Tempo esgotado esperando o servidor. Veja os logs acima."
}

deploy() {
  check_docker
  setup_env
  choose_mode
  check_dns
  local first_run=true
  is_running && first_run=false
  if ! $first_run; then backup_db; fi

  step "Construindo e iniciando ($(compose_services))"
  # shellcheck disable=SC2046
  docker compose up -d --build --remove-orphans $(compose_services)
  wait_healthy

  step "Limpeza"
  docker image prune -f >/dev/null 2>&1 || true
  ok "Imagens antigas removidas."

  show_status
  if $first_run; then
    printf '\n%sPróximos passos:%s\n' "$B" "$N"
    echo "  1. Abra $(env_get PUBLIC_URL) e entre com a senha do painel."
    echo "  2. Na TV: Administração › Configurações › Conectar ao servidor › $(env_get DOMAIN)"
    echo "  3. No painel: TVs › Adicionar TV › digite o código que aparece na TV."
    if [ "$(env_get DEPLOY_MODE)" = "proxy" ]; then
      echo "  ! Modo proxy: configure seu nginx com deploy/nginx-meupainel.conf e gere o certificado (certbot)."
    fi
  fi
}

show_status() {
  step "Status"
  docker compose ps
  if is_running; then
    ok "Painel: $(env_get PUBLIC_URL)"
    local local_check
    local_check="$(curl -fsS --max-time 5 http://127.0.0.1:3000/health 2>/dev/null || true)"
    if [ -n "$local_check" ]; then ok "Resposta local: $local_check"; else warn "Sem resposta em 127.0.0.1:3000."; fi
  else
    warn "O servidor não está rodando. Inicie com: bash deploy.sh"
  fi
}

# ----------------------------------------------------------------------------- comandos
case "${1:-deploy}" in
  deploy|update|start|install) deploy ;;
  status)  show_status ;;
  logs)    docker compose logs -f --tail 100 "$APP_SERVICE" ;;
  restart) check_docker; docker compose restart; wait_healthy; show_status ;;
  stop)    docker compose stop; ok "Servidor parado. Para iniciar: bash deploy.sh" ;;
  backup)  full_backup ;;
  -h|--help|help) sed -n '3,16p' "$0" | sed 's/^# \{0,1\}//' ;;
  *) die "Comando desconhecido: $1 (use: bash deploy.sh help)" ;;
esac
