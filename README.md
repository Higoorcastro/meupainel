# Digital Signage para Android TV (TCL)

Aplicativo de painel de anúncios para Android TV / Google TV. Reproduz em loop, em tela cheia, uma playlist de
imagens (JPG, JPEG, PNG, WebP) e vídeos (MP4 H.264, H.265/HEVC se o hardware suportar), com painel administrativo
protegido por PIN e totalmente navegável pelo controle remoto.

---

## 1. Stack e versões

| Item | Versão | Motivo |
|---|---|---|
| Android Gradle Plugin | 9.4.1 | Estável atual; Kotlin embutido no AGP 9 |
| Gradle | 9.8.0 (wrapper incluso) | Compatível com AGP 9.4 |
| Kotlin | 2.4.20 | Compilador K2 + plugin do Compose |
| KSP | 2.3.12 | Geração de código do Room |
| Compose BOM | 2026.09.00 | UI declarativa |
| **Compose for TV** (`androidx.tv:tv-material`) | 1.1.0 | Componentes com foco/zoom para D-pad |
| **Media3 / ExoPlayer** | 1.11.1 | Vídeo, cache de streaming, fallback de decoder |
| Room | 2.8.5 | Banco da playlist |
| DataStore Preferences | 1.2.1 | Configurações |
| WorkManager | 2.12.0 | Downloads offline e sincronização remota |
| Coil 3 | 3.6.3 | Imagens eficientes (decodifica no tamanho da tela) |
| compileSdk / targetSdk / minSdk | 37 / 36 / 24 | minSdk 24 cobre todas as TVs TCL com Android TV |

Nenhum serviço pago é usado.

---

## 2. Arquitetura

```
app/src/main/java/com/tvloja/signage/
├── SignageApp.kt              Application: logger, crash handler, ImageLoader, container
├── MainActivity.kt            Activity única: tela cheia, tela sempre ligada, gesto secreto, VOLTAR bloqueado
├── di/AppContainer.kt         Injeção de dependências manual (troque implementações aqui)
│
├── domain/                    Regras de negócio — sem Android UI, sem Room
│   ├── model/                 MediaItem, MediaType, SignageSettings, RemoteMediaSpec...
│   ├── repository/            PlaylistRepository, SettingsRepository, SignageServerApi (interfaces)
│   └── usecase/               Importar mídia, adicionar URL, excluir, playlist reproduzível
│
├── data/
│   ├── local/db/              Room: MediaItemEntity, MediaItemDao, SignageDatabase
│   ├── local/settings/        DataStoreSettingsRepository
│   ├── local/files/           MediaFileStore (cópia atômica, espaço, limpeza), MediaInspector, LocalMediaScanner
│   ├── remote/                MediaDownloader (OkHttp), SignageServerClient (API do painel web)
│   └── repository/            LocalPlaylistRepository (implementa PlaylistRepository)
│
├── player/                    Reprodução — independente da UI
│   ├── PlaylistPlayer.kt      Máquina de estados: atual/próximo/anterior, timers, pausa, erros, recuperação
│   ├── PlaybackState.kt       Estado imutável publicado via StateFlow
│   ├── VideoPlayer.kt         Wrapper do ExoPlayer (instância única reutilizada + watchdog)
│   ├── VideoSourceFactory.kt  Local direto / remoto com cache LRU em disco
│   ├── MediaAvailabilityChecker.kt  Verifica se o arquivo existe antes de exibir
│   └── PlaybackReporter.kt    Eventos de exibição (base para relatórios futuros)
│
├── ui/
│   ├── player/PlayerScreen.kt Camada de vídeo (SurfaceView) + camada de imagens com fade
│   ├── pin/PinScreen.kt       Teclado numérico para D-pad + teclas numéricas do controle
│   ├── admin/                 Painel: playlist, mídia, configurações, status, logs
│   ├── picker/                Seletor próprio de arquivos (USB, armazenamento, pasta de importação)
│   ├── components/            Botões, diálogos, linhas de opção para TV
│   └── theme/
│
├── security/AdminSecurity.kt  PIN padrão (ALTERE AQUI), gesto secreto, bloqueio por tentativas
├── boot/                      BootReceiver + AutoStart (permissão de sobreposição, modo launcher)
├── sync/ServerSyncManager.kt  Sincronização com o servidor central (pareamento, playlist, status, comandos)
├── work/                      DownloadMediaWorker, PlaylistSyncWorker, WorkScheduler
└── util/                      AppLogger, CrashHandler, DeviceInfo, Formatters
```

### Fluxo de reprodução

```
Room (media_items) ──Flow──▶ ObservePlayablePlaylistUseCase (ativos + ordem; futuro: agendamento)
                                   │
                                   ▼
                            PlaylistPlayer (escopo da aplicação)
                             │  state: StateFlow<PlaybackState>
                             ▼
                        PlayerScreen
                         ├── ImageRenderer (Coil, fade)     ── erro ──┐
                         └── VideoPlayer (ExoPlayer)        ── fim/erro ┤
                                   ▲                                    │
                                   └──── onMediaCompleted / onMediaError┘
```

### Principais decisões

1. **`PlaylistPlayer` no escopo da aplicação.** A playlist e a posição sobrevivem à recriação da Activity, e o
   painel mostra o status em tempo real. A Activity e o ExoPlayer são apenas "visualizações".
2. **Um único ExoPlayer, reutilizado.** Ele é criado quando o player entra em primeiro plano e liberado quando sai.
   Criar um player por vídeo fragmenta memória e esgota decodificadores de hardware em execuções de dias.
3. **Token por mídia.** Cada início de mídia recebe um token novo. Callbacks atrasados de uma mídia antiga (por
   exemplo, um erro do ExoPlayer que chega depois do avanço) são descartados, evitando pulos duplos.
4. **Vídeo por baixo, imagens por cima.** O vídeo é desenhado pelo SurfaceView do decodificador, em até 4K mesmo
   com a interface em 1080p. As imagens ficam numa camada Compose com fade. Na passagem de vídeo para imagem, a
   imagem surge sobre o último quadro do vídeo; na passagem de imagem para vídeo, a imagem esmaece revelando o vídeo.
5. **Imagens decodificadas no tamanho da tela.** O Coil lê o arquivo em streaming e reduz a imagem só até a
   resolução da janela. Uma foto de 8000px não estoura a memória e uma imagem 4K não perde qualidade à toa.
6. **Seletor de arquivos próprio.** Muitas TVs não têm o seletor de documentos do sistema
   (`ACTION_OPEN_DOCUMENT`). O app lista o pendrive, o armazenamento e uma pasta de importação.
7. **Cópia para a memória interna (padrão).** O conteúdo continua funcionando sem o pendrive. A cópia é feita em
   streaming para um arquivo `.tmp-*` e só depois renomeada, então nunca há arquivo pela metade.
8. **Alterações salvas imediatamente.** Não existe estado "não salvo" que se perca se a TV for desligada.
   "Salvar e reproduzir" apenas volta ao player, que já recebeu a playlist atualizada via Flow.
9. **DI manual** em vez de Hilt: menos geração de código e menos dependências. Para trocar o repositório local por
   um remoto, basta alterar uma linha no `AppContainer`.
10. **Vídeos sempre em FIT (sem cortes).** O ajuste CENTER_CROP/FIT_CENTER configurável vale para imagens, como
    pedido. Vídeos são exibidos inteiros, com faixas pretas se a proporção não for 16:9.
11. **Foco de áudio desativado no ExoPlayer.** Se outro app tomasse o foco de áudio, o vídeo pausaria para sempre.

### Confiabilidade (o que acontece quando algo dá errado)

| Situação | Comportamento |
|---|---|
| Arquivo apagado / pendrive removido | Detectado antes de exibir → registrado → próxima mídia |
| Imagem corrompida | Rejeitada no cadastro; se corromper depois, o Coil falha → próxima mídia |
| Vídeo com codec não suportado | Rejeitado no cadastro, se detectável; senão, erro do ExoPlayer → próxima mídia |
| Vídeo travado / buffering infinito | Watchdog (20s parado / 45s de buffering) → próxima mídia |
| Todas as mídias falharam | Estado "Recuperando": nova tentativa a cada 30s (sem loop rápido) |
| Playlist vazia | Mensagem de orientação na tela |
| Sem internet | Itens baixados tocam offline; streaming falha e é pulado; downloads tentam de novo com backoff |
| Armazenamento cheio | Cópia/download recusados com mensagem; reserva de 200 MB sempre preservada |
| Crash inesperado | Registrado em `files/logs/signage.log`; app reaberto em 3s (com proteção contra loop) |
| Activity recriada | Estado preservado (player no escopo da aplicação + `configChanges`) |
| Painel esquecido aberto | Volta sozinho para a reprodução após 10 min sem uso do controle |
| Arquivos temporários órfãos | Limpos na inicialização |

---

## 3. Instalar o Android Studio e abrir o projeto

1. Baixe o Android Studio em <https://developer.android.com/studio> e instale com as opções padrão.
2. Na primeira execução, deixe o assistente instalar o **Android SDK**, o **Platform-Tools** e o **Build-Tools**.
3. **File › Open** e selecione a pasta `tv-loja`. Aguarde a sincronização do Gradle (na primeira vez ele baixa as
   dependências, o que leva alguns minutos).
4. Se o Android Studio pedir o **SDK Platform 37**, aceite. Ou abra **Tools › SDK Manager › SDK Platforms**,
   marque *Android API 37* e clique em *Apply*.
5. JDK: use o JDK embutido do Android Studio (**Settings › Build, Execution, Deployment › Build Tools › Gradle ›
   Gradle JDK = jbr**) ou qualquer JDK 17+.

> O arquivo `local.properties` (caminho do SDK) é criado automaticamente pelo Android Studio. Para compilar pela
> linha de comando em outra máquina, crie-o com: `sdk.dir=C\:/Users/SEU_USUARIO/AppData/Local/Android/Sdk`

## 4. Compilar e gerar o APK

**Pelo Android Studio:** *Build › Generate App Bundles or APKs › Generate APKs*.

**Pela linha de comando** (na pasta do projeto):

```bash
# Windows
gradlew.bat assembleRelease
# macOS / Linux
./gradlew assembleRelease
```

APKs gerados:

- Release (otimizado com R8, recomendado para a TV): `app/build/outputs/apk/release/app-release.apk`
- Debug: `app/build/outputs/apk/debug/app-debug.apk` (`gradlew assembleDebug`)

> O release está assinado com a chave de debug para facilitar a instalação manual. Para produção, gere uma
> keystore própria (*Build › Generate Signed App Bundle or APK › Create new...*) e configure
> `signingConfigs` em `app/build.gradle.kts`. Atualizações precisam ser assinadas sempre com a mesma chave.

## 5. Instalar na TV TCL

### Opção A — ADB pela rede (recomendado)

1. Na TV: **Configurações › Sistema › Sobre › Build do Android TV OS** e pressione **OK 7 vezes**, até aparecer
   "Você agora é um desenvolvedor". O caminho varia conforme o modelo; nas Google TV é *Configurações › Sistema ›
   Sobre › Versão do Android TV OS*.
2. **Configurações › Sistema › Opções do desenvolvedor**: ative **Depuração USB** e, se existir, **Depuração por rede/ADB**.
3. Descubra o IP da TV em **Configurações › Rede e Internet** (exemplo: `192.168.0.50`).
4. No computador, na mesma rede, rode:

```bash
# O adb fica em %LOCALAPPDATA%\Android\Sdk\platform-tools
adb connect 192.168.0.50:5555        # aceite "Permitir depuração" na TV
adb install -r app/build/outputs/apk/release/app-release.apk
```

> Se a instalação falhar sem mostrar o motivo (`failed to install ...:`), repita com `--no-streaming`:
> `adb install -r --no-streaming app/build/outputs/apk/release/app-release.apk` — necessário em algumas TVs com Android 9.

### Opção B — Pendrive

Copie o APK para um pendrive e instale-o na TV com um gerenciador de arquivos (por exemplo, "File Commander" ou
"X-plore"). Será preciso permitir **Fontes desconhecidas** para esse gerenciador.

### Após instalar

O app aparece na tela inicial da TV como **Digital Signage** (banner azul). Na primeira abertura a playlist está
vazia e a TV mostra as instruções.

## 6. Configurações recomendadas da TV (importante para funcionar 24h)

- **Desligamento automático / Sleep timer / Auto power off:** desative. Fica em *Configurações › Sistema ›
  Energia* nas TCL. O app impede o protetor de tela, mas **não** consegue impedir o desligamento automático por
  inatividade do controle, que é uma regra de energia do fabricante.
- **Protetor de tela / Modo ambiente:** defina como *Nunca*, por segurança.
- **Energia ao ligar / Power on behavior:** se existir, escolha "Última entrada/app".

## 7. Inicialização automática ao ligar a TV

O Android 10+ **não permite** que um app em segundo plano abra a própria tela. Por isso existem três camadas:

1. **Modo launcher (o mais confiável).** Em *Administração › Configurações › Modo launcher*, escolha **Ativado**.
   Depois pressione HOME e, se a TV perguntar, escolha **Digital Signage** e "Sempre". A TV abre o app ao ligar e
   ao pressionar HOME. Para voltar ao launcher original, desative a opção no painel.
   > Algumas Google TV não oferecem a troca de launcher. Nesse caso use a opção 2.
2. **Abertura no boot com a permissão de sobreposição.** O `BootReceiver` abre o player após o boot e ao voltar
   do standby, desde que o app tenha a permissão "Sobrepor a outros apps". Muitas TVs não exibem essa tela;
   conceda pelo computador:
   ```bash
   adb shell appops set com.tvloja.signage SYSTEM_ALERT_WINDOW allow
   ```
   O status da permissão aparece em *Administração › Configurações*. Com ela concedida, o app também reabre sozinho
   após um crash.
3. **Android 9 ou anterior:** a abertura no boot funciona sem nenhuma configuração.

Para desligar o início automático: *Configurações › Abrir ao ligar a TV › Não*.

## 8. Usar o painel administrativo

### Abrir

Durante a reprodução, faça um dos gestos:

- pressione **OK 5 vezes** em até 3 segundos; **ou**
- pressione **↑ ↑ ↓ ↓ ← →** em até 5 segundos.

Depois digite o PIN. O **PIN padrão de desenvolvimento é `1234`**.

> ⚠ **Troque o PIN** em *Configurações › Alterar PIN*, ou altere `AdminConfig.DEFAULT_PIN` em
> `app/src/main/java/com/tvloja/signage/security/AdminSecurity.kt` antes de compilar. Depois de 5 PINs errados, o
> acesso fica bloqueado por 60 segundos.

Navegação: **setas** movem o foco, **OK** seleciona e **VOLTAR** fecha diálogos ou volta para a reprodução.

### Adicionar imagens e vídeos

**Pelo pendrive (mais simples):**

1. Copie os arquivos (`.jpg`, `.jpeg`, `.png`, `.webp`, `.mp4`) para um pendrive e conecte-o à TV.
2. No painel, escolha **＋ Arquivo**. Na primeira vez, permita o acesso a fotos e vídeos.
3. Deixe **Copiar para a TV = Sim** (recomendado) e pressione **OK** em cada arquivo. Ele vai para o final da
   playlist.
4. Pressione VOLTAR para retornar ao painel.

**Pelo computador (ADB):**

```bash
adb push banner.jpg /sdcard/Android/data/com.tvloja.signage/files/import/
adb push video.mp4  /sdcard/Android/data/com.tvloja.signage/files/import/
```

Os arquivos aparecem em **＋ Arquivo**, no grupo "Pasta de importação". Abra o app ao menos uma vez antes,
para a pasta ser criada.

**Por URL:** escolha **＋ URL** e digite o endereço direto do arquivo. Com "Baixar para uso offline = Sim",
o arquivo é baixado em segundo plano; enquanto isso, ele é reproduzido via streaming.

### Configurar a duração de uma imagem

Selecione a imagem na lista à esquerda; a aba **Mídia** abre. Em "Duração da imagem", use −10/−1/+1/+10.
A duração padrão das novas imagens fica em *Configurações › Duração padrão*, e o botão "Aplicar a todas as imagens"
replica esse valor. Vídeos sempre duram o tempo do próprio vídeo.

### Alterar a ordem da playlist

Selecione a mídia e use **▲ Subir** / **▼ Descer**. O número à esquerda de cada item é a
posição na playlist.

### Outras ações

- **Ativar/Desativar:** o item fica na lista, mas não é exibido.
- **Renomear** e **Excluir** (a exclusão pede confirmação e apaga o arquivo copiado).
- **▶ Salvar e reproduzir**, **❚❚ Pausar** e **⟲ Testar do início**.
- **Configurações:** ajuste da imagem (CENTER_CROP/FIT_CENTER), transição (Fade/Nenhuma e duração), som dos
  vídeos, servidor central (painel web), início automático, modo launcher e PIN.
- **Status:** reprodução, mídia atual, posição, armazenamento, versão, resolução e ID da TV.
- **Logs:** últimos 300 eventos. O histórico completo fica em `files/logs/signage.log` (veja abaixo).

## 9. Testar

### No emulador (sem TV)

1. No Android Studio: **Device Manager › Create Virtual Device › TV › "Television (1080p)"**, com imagem
   **Android TV API 31+ (x86)**.
2. Clique em **Run ▶**. No emulador, as setas do teclado funcionam como D-pad, **Enter** é OK e **Esc** é VOLTAR.
3. Para enviar mídias de teste ao emulador, use os comandos `adb push` da seção 8.

### Roteiro de testes recomendado

1. Primeira abertura: aparece a mensagem "Nenhuma mídia cadastrada".
2. Abra o painel (OK ×5), digite PIN errado (aparece o aviso) e depois o PIN correto.
3. Adicione 2 imagens e 1 vídeo; configure 5s e 10s nas imagens; reordene a lista.
4. Toque em **Salvar e reproduzir** e confira a sequência, o fade e o retorno ao primeiro item.
5. Desative um item; ele deixa de aparecer na reprodução seguinte.
6. Apague um arquivo copiado via adb (`adb shell run-as com.tvloja.signage rm files/media/<arquivo>`, só funciona
   no build de debug); o item é pulado e o erro aparece em Logs.
7. Adicione um arquivo que não é imagem renomeado para `.jpg`; ele é rejeitado como corrompido.
8. Desligue e ligue a TV/emulador (`adb reboot`); o app abre sozinho se o modo launcher ou a permissão de
   sobreposição estiver ativa.
9. Deixe rodando por várias horas e confira em *Status* que não há erros acumulados.

### Ver os logs pelo computador

```bash
adb logcat -s Signage            # logs ao vivo
adb shell run-as com.tvloja.signage cat files/logs/signage.log   # arquivo persistido (build debug)
```

Exemplo:

```
I Signage: Playlist loaded: 3 mídia(s) ativa(s)
I Signage: Starting media: Banner Promoção [IMAGE]
I Signage: Starting media: Vídeo Institucional [VIDEO]
I Signage: Video completed: Vídeo Institucional
E Signage: Media failed: Banner Produto — Arquivo não encontrado: 1712_banner.jpg
```

Em builds de release, os logs de nível DEBUG ("Image duration", "Moving to next media") ficam desativados.

---

## 10. Painel web (servidor central na sua VPS)

A pasta [`server/`](server/) contém o servidor: painel web + API das TVs + armazenamento das mídias.
Com ele você gerencia todas as TVs de qualquer lugar pelo navegador (PC ou celular).

```
Navegador ──HTTPS──▶ VPS: Caddy (ou nginx) ──▶ signage-server (Node 24)
                                                ├─ SQLite: TVs, playlists, mídias
TV ── POST /api/device/sync (a cada 60 s) ─────▶└─ /media/<uuid>.mp4
```

**O que o painel faz:**

- **Mídias:** envio de imagens e vídeos arrastando os arquivos, com barra de progresso; renomear; excluir.
- **Playlists:** criar várias; adicionar mídias; reordenar (arrastar ou ▲▼); duração por imagem; ativar/desativar.
- **TVs:** parear com código; escolher a playlist de cada TV; ajuste da imagem, transição e som por TV; ver se
  está online, o que está passando, armazenamento, versão e erros; botões "Reiniciar playlist" e
  "Sincronizar"; bloquear/remover.

**Como a TV se comporta:** sincroniza a cada minuto (a cada 10 s enquanto aguarda pareamento), baixa as mídias
para a memória da TV e **continua exibindo o último conteúdo se a internet ou o servidor caírem**. Mídias
adicionadas por pendrive na própria TV continuam funcionando junto com as do servidor.

### 10.1 Instalar e atualizar na VPS (scripts prontos)

Pré-requisitos: VPS Linux com acesso SSH e um registro DNS **A** do subdomínio (ex.: `signage.seudominio.com`)
apontando para o IP da VPS. O Docker é instalado pelo próprio script, se faltar.

**Do seu PC (PowerShell), um único comando instala e também atualiza:**

```powershell
cd C:\Users\Higor\Documents\tv-loja\server
.\publicar.ps1 -Servidor usuario@IP_DA_VPS          # 1ª vez (o endereço fica salvo)
.\publicar.ps1                                      # próximas atualizações
.\publicar.ps1 -Chave C:\caminho\minha-vps.pem      # se a VPS usa arquivo de chave
```

O `publicar.ps1` empacota a pasta `server/`, envia por SSH e executa o `deploy.sh` na VPS. Na primeira vez o
`deploy.sh` pergunta o domínio e a senha do painel, cria o `.env` e detecta sozinho se a VPS já tem nginx/Apache
nas portas 80/443:

- **portas livres** → sobe o Caddy, que emite e renova o HTTPS automaticamente;
- **portas ocupadas** → sobe só o servidor em `127.0.0.1:3000`; configure seu nginx com
  `deploy/nginx-meupainel.conf` e gere o certificado com `certbot --nginx`.

A cada atualização ele faz **backup do banco** (mantém os 15 mais recentes em `~/signage/backups`), reconstrói,
reinicia e só termina quando o servidor responde saudável. O `.env` e os dados nunca são sobrescritos.

> Se o PowerShell bloquear o script ("execução de scripts desabilitada"), rode uma vez:
> `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`

**Comandos na VPS** (dentro de `~/signage`):

| Para… | Comando |
|---|---|
| Instalar / atualizar / reiniciar com rebuild | `bash deploy.sh` |
| Ver se está rodando | `bash deploy.sh status` |
| Acompanhar os logs | `bash deploy.sh logs` |
| Reiniciar sem rebuild | `bash deploy.sh restart` |
| Parar | `bash deploy.sh stop` |
| Backup completo (banco + mídias) | `bash deploy.sh backup` |

O servidor volta sozinho se a VPS reiniciar (`restart: unless-stopped`).

### 10.2 Instalar sem Docker (opcional)

Instale o Node.js 24, copie a pasta `server/` e rode `npm ci --omit=dev`. Inicie com
`ADMIN_PASSWORD=... PUBLIC_URL=https://... npm start` (porta 3000), mantendo o processo vivo com systemd ou pm2
e um proxy HTTPS na frente (veja `deploy/nginx-meupainel.conf`).

### 10.3 Conectar uma TV ao painel

1. Atualize o app da TV para a versão **1.1.0** (`adb install -r app-release.apk`; playlist e configurações são mantidas).
2. Na TV: Administração › **Configurações** › **Servidor central** › **Conectar ao servidor** › digite
   `signage.seudominio.com` (o `https://` é adicionado automaticamente).
3. A TV mostra um **código de 6 dígitos**.
4. No painel web: **TVs › Adicionar TV** › digite o código, dê um nome e escolha a playlist › **Conectar TV**.
5. Em até 10 segundos a TV baixa a playlist e começa a exibir.

Segurança: o painel exige senha (bloqueio após 5 tentativas erradas por 15 min) e só aceita alterações com
cabeçalho próprio (proteção CSRF). Cada TV gera um token secreto; só o hash fica no servidor, e uma TV aprovada
não pode ser "clonada" por outro aparelho. Os arquivos de mídia têm nomes aleatórios (UUID) e são públicos para
quem tiver o link — adequado para anúncios.

> Android 7.0 (muito raro em TVs TCL) não reconhece certificados Let's Encrypt. Android 7.1.1 ou superior funciona.

### 10.4 Atualizar o app das TVs pelo painel (sem cabo/ADB)

A partir da versão **1.2.0**, novas versões do app são instaladas pelo painel:

1. Aumente `versionCode` (e `versionName`) em `app/build.gradle.kts` e gere o APK: `gradlew assembleRelease`.
2. No painel, aba **App**, arraste o `app/build/outputs/apk/release/app-release.apk`.
   A versão é lida do próprio arquivo; APKs de outro app, repetidos ou mais antigos são recusados.
3. As TVs baixam o APK em segundo plano (autenticadas, com verificação SHA-256) — o status aparece no painel.
4. Clique em **Instalar nesta TV** (ou **Atualizar todas as TVs**).
5. Na TV aparece *"Deseja atualizar este app?"*. O foco começa em **Cancelar**: aperte **← e depois OK**.
   O app é atualizado e reaberto sozinho, mantendo playlist e configurações.

Na **primeira** atualização a TV abre a tela *"Instalar apps desconhecidos"* com o Digital Signage selecionado:
aperte **OK** para permitir e repita o passo 4. Pelo computador também dá:
`adb shell appops set com.tvloja.signage REQUEST_INSTALL_PACKAGES allow`.

> ⚠ **Chave de assinatura:** o Android só aceita a atualização se o APK novo for assinado com a **mesma chave**
> do instalado. Os APKs deste projeto são assinados com a chave de debug deste computador:
> `%USERPROFILE%\.android\debug.keystore`. **Faça backup desse arquivo.** Se gerar o APK em outro computador
> (ou perder a chave), as TVs recusam a atualização e será preciso reinstalar o app via ADB, perdendo os dados locais.

---

## 11. Preparação para a evolução

| Funcionalidade futura | Onde encaixar |
|---|---|
| Agendamento, horários, dias da semana | Tabela `schedules` no servidor + filtro em `buildPlaylist` (servidor) ou em `ObservePlayablePlaylistUseCase` (TV) |
| Grupos de TVs | Coluna `group_id` em `devices` e playlist por grupo |
| Relatório de exibição (prova de exibição) | Implementação de `PlaybackReporter` que envia eventos em lote ao servidor |
| Atualização remota do app | Download do APK + `PackageInstaller` (ou MDM); `BootReceiver` já reabre após `MY_PACKAGE_REPLACED` |
| Screenshot da TV | `PixelCopy` da janela; vídeo protegido pode sair preto (limitação do SurfaceView) |
| Comandos instantâneos | Trocar o polling de 60 s por WebSocket em `SignageServerClient` (contrato `SignageServerApi` não muda) |
| Vários usuários no painel | Tabela `users` com perfis no servidor |

## 12. Melhorias futuras sugeridas

- Miniaturas de vídeo geradas no servidor (ffmpeg) e compressão automática de vídeos enviados.
- Pré-visualização em miniatura das mídias no painel.
- Suporte a HLS/DASH (adicionar `media3-exoplayer-hls`/`-dash`) para transmissões ao vivo.
- Volume configurável por vídeo e janela de "horário silencioso".
- Testes automatizados: unitários para `PlaylistPlayer` (com `TestScope`) e de UI com Compose Test.
- Keystore de produção e pipeline de CI que gera o APK.
- Detecção de queima de tela (burn-in) em painéis OLED, com deslocamento sutil de pixels.
- Export/import da playlist (backup) via pendrive.
