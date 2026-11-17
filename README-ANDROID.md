# Projeto Super — Android nativo

Aplicativo de laboratório em Kotlin/Jetpack Compose.

## Recursos implementados
- Lista apps com atividade de inicialização visível ao Android.
- Mostra nome e identificador de pacote; abre somente após toque explícito.
- Painel de status para câmera, microfone, localização e notificações.
- Solicita permissões em resposta ao botão do usuário, sem pedir tudo ao iniciar.
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
