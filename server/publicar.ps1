<#
.SYNOPSIS
  Envia o servidor do Digital Signage para a VPS e instala/atualiza com um único comando.

.DESCRIPTION
  1. Empacota a pasta server/ (sem .env, backups e arquivos locais);
  2. Envia para a VPS por SSH (scp);
  3. Extrai na pasta de destino preservando o .env e os dados existentes;
  4. Executa "bash deploy.sh" na VPS (backup do banco, rebuild, reinício e verificação).

  Na primeira execução, pergunta o endereço SSH da VPS e lembra para as próximas vezes.

.EXAMPLE
  .\publicar.ps1                          # usa o servidor salvo (pergunta na 1ª vez)
  .\publicar.ps1 -Servidor root@203.0.113.10
  .\publicar.ps1 -Servidor usuario@minhavps.com -Porta 2222 -Pasta "~/signage"
  .\publicar.ps1 -Chave C:\Users\voce\.ssh\minha-vps.pem   # login por arquivo de chave
#>
param(
  [string]$Servidor,
  [int]$Porta = 0,
  [string]$Pasta,
  [string]$Chave
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$configFile = Join-Path $here '.publicar.json'

function Fail($msg) { Write-Host "`n✘ $msg" -ForegroundColor Red; exit 1 }
function Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }

foreach ($tool in 'ssh', 'scp', 'tar') {
  if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
    Fail "Comando '$tool' não encontrado. No Windows 10/11 ative o 'Cliente OpenSSH' em Configurações › Aplicativos › Recursos opcionais."
  }
}

# Configuração salva (servidor, porta, pasta)
$config = @{ Servidor = ''; Porta = 22; Pasta = '~/signage'; Chave = '' }
if (Test-Path $configFile) {
  $saved = Get-Content $configFile -Raw | ConvertFrom-Json
  foreach ($k in 'Servidor', 'Porta', 'Pasta', 'Chave') { if ($saved.$k) { $config[$k] = $saved.$k } }
}
if ($Servidor) { $config.Servidor = $Servidor }
if ($Porta -gt 0) { $config.Porta = $Porta }
if ($Pasta) { $config.Pasta = $Pasta }
if ($Chave) { $config.Chave = (Resolve-Path $Chave).Path }
if (-not $config.Servidor) {
  $config.Servidor = Read-Host 'Acesso SSH da VPS (ex.: root@203.0.113.10 ou usuario@minhavps.com)'
  if (-not $config.Servidor) { Fail 'Informe o acesso SSH da VPS.' }
}
$config | ConvertTo-Json | Set-Content -Encoding utf8 $configFile

$target = $config.Servidor
$port = [string]$config.Porta
$remoteDir = $config.Pasta
# accept-new: aceita a chave da VPS na 1ª conexão e recusa se ela mudar depois (proteção contra servidor falso).
$sshOpts = @('-o', 'StrictHostKeyChecking=accept-new')
if ($config.Chave) { $sshOpts += @('-i', $config.Chave) }
Write-Host "VPS: $target (porta $port) • pasta: $remoteDir" -ForegroundColor Gray

# Daqui em diante só rodam comandos externos (tar/scp/ssh), cujo resultado é conferido por $LASTEXITCODE.
# Com 'Stop', o PowerShell 5.1 trataria avisos do ssh no stderr (ex.: "Permanently added...") como erro fatal.
$ErrorActionPreference = 'Continue'

# 1. Empacotar
Step 'Empacotando o servidor'
$package = Join-Path $env:TEMP 'signage-deploy.tgz'
Remove-Item $package -ErrorAction SilentlyContinue
Push-Location $here
try {
  & tar -czf $package --exclude=.env --exclude=backups --exclude=data --exclude=node_modules --exclude=.publicar.json .
  if ($LASTEXITCODE -ne 0) { Fail 'Falha ao empacotar.' }
} finally { Pop-Location }
Write-Host ("  pacote: {0:N0} KB" -f ((Get-Item $package).Length / 1KB))

# 2. Enviar
Step 'Enviando para a VPS'
& scp @sshOpts -P $port $package "${target}:/tmp/signage-deploy.tgz"
if ($LASTEXITCODE -ne 0) { Fail "Falha no envio (scp). Confira o endereço, a porta e se você consegue entrar com: ssh -p $port $target" }

# 3 e 4. Extrair e executar o deploy (-t: permite responder as perguntas da 1ª instalação)
Step 'Instalando/atualizando na VPS'
$remote = "set -e; mkdir -p $remoteDir && tar -xzf /tmp/signage-deploy.tgz -C $remoteDir --no-same-owner && rm -f /tmp/signage-deploy.tgz && cd $remoteDir && chmod -R go-w . && sed -i 's/\r$//' deploy.sh && bash deploy.sh"
& ssh @sshOpts -t -p $port $target $remote
if ($LASTEXITCODE -ne 0) { Fail 'O deploy na VPS terminou com erro (veja as mensagens acima).' }

Remove-Item $package -ErrorAction SilentlyContinue
Write-Host "`n✔ Publicado com sucesso." -ForegroundColor Green
