# Projeto Super — Android nativo

Aplicativo de laboratório em Kotlin/Jetpack Compose.

## Recursos implementados
- Lista apps com atividade de inicialização visível ao Android.
- Mostra nome e identificador de pacote; abre somente após toque explícito.
- Painel de status para câmera, microfone, localização e notificações.
- Solicita câmera, microfone, localização e notificações automaticamente apenas na primeira abertura; registra que a solicitação inicial já ocorreu.
- Não repete automaticamente os pedidos nos acessos seguintes; se uma permissão for negada ou revogada, o usuário pode abrir as configurações do Android.
- Usa o Photo Picker oficial para o usuário selecionar uma foto ou vídeo.
- Não inicia gravação, captura de tela ou envio de arquivos automaticamente.
- Não monitora nem bloqueia outros aplicativos.

## Próximas etapas
- Implementar prévia de câmera e áudio em sessão visível, iniciada localmente.
- Implementar localização sob demanda com indicador e finalidade informada.
- Integrar autenticação do dispositivo e endpoint Supabase com tokens de curta duração.
- Implementar envio de mídia somente após seleção/ação explícita e confirmação.
- Adicionar histórico local de operações e estado de conexão.
- Avaliar controles de uso do Android apenas por APIs oficiais e autorização especial.

## Requisitos
- Android Gradle Plugin 8.7.3.
- JDK 17.
- Android SDK 35.


## Integração Android + endpoint (em implementação)

- O app registra um identificador de dispositivo e uma chave aleatória privada no armazenamento local do app.
- O app mostra um código temporário de pareamento; o painel usa esse código para vincular o aparelho à sessão autenticada do navegador.
- O painel pode solicitar câmera frontal, câmera traseira, compartilhamento de tela ou encerramento.
- Pedidos de captura aguardam confirmação no próprio Android. O compartilhamento de tela também exige a tela oficial de consentimento do Android a cada sessão.
- A câmera usa CameraX sem prévia na interface principal e publica quadros JPEG para armazenamento privado; o endpoint recebe links temporários assinados para exibir a prévia.
- Sessões de câmera e tela usam serviços em primeiro plano com notificação persistente e ação para parar.
- O app consulta pedidos enquanto está aberto; durante uma sessão ativa, o serviço em primeiro plano também consulta pedidos de encerramento.
- O endpoint de controle está em `supabase/functions/projeto-super-controle/index.ts`. Ele valida JWT do painel ou chave aleatória do dispositivo; nenhuma chave secreta Supabase é incluída no APK.
- As tabelas de dispositivos, pedidos, sessões e quadros têm RLS habilitado e acesso direto revogado para clientes públicos; a Edge Function usa a chave secreta somente no servidor.

**Importante:** a integração precisa passar pela compilação e testes em dois aparelhos antes de considerar a versão pronta para instalação. A captura não inicia silenciosamente por comando remoto.
