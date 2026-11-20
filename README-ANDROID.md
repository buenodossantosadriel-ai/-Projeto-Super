# Projeto Super — Android nativo

Este repositório contém o APK Android original do Projeto Super e o dashboard web correspondente.

## Arquitetura implementada

- Aplicativo Android nativo em Kotlin e Jetpack Compose.
- Identifica aplicativos que oferecem uma atividade de inicialização e sincroniza nome e pacote com o dashboard.
- Abre o aplicativo selecionado em um display virtual público, limitado ao próprio conteúdo (PUBLIC + OWN_CONTENT_ONLY), quando o aparelho oferece suporte a atividades em displays secundários.
- Captura somente os quadros do display virtual por ImageReader, converte-os para JPEG e envia os quadros à sessão remota. Não usa WebRTC nem MediaProjection e não espelha intencionalmente a tela física principal.
- Envia toque, rolagem, voltar e texto por serviço de Acessibilidade ativado manualmente nas configurações do Android. O texto só é inserido no campo editável focado do aplicativo-alvo.
- Usa serviços em primeiro plano e notificação persistente para as sessões de captura, câmera e áudio.
- O dashboard consulta os quadros JPEG e os comandos pelo endpoint HTTPS/Supabase.

## Autorizações e privacidade

- A autorização geral interna é apresentada na primeira abertura enquanto ainda não foi concedida; o usuário pode sair sem autorizar e a solicitação reaparece na próxima abertura.
- Revogar a autorização geral bloqueia novas solicitações e encerra as sessões locais de tela, câmera e microfone.
- O serviço de Acessibilidade precisa ser habilitado manualmente pelo usuário nas configurações do Android; o APK não pode ativá-lo por conta própria. O Projeto Super só inicia a sessão visual de controle depois de confirmar que esse serviço está ativo.
- A autorização geral não substitui permissões específicas do Android: câmera, microfone e notificações são solicitados quando necessários. Câmera e microfone também exigem confirmação da sessão no telefone.
- A captura de tela é exclusivamente JPEG, usando o display virtual; não usa MediaProjection, WebRTC nem captura da tela física.
- Comandos e sessões são validados pelo endpoint; a chave secreta do Supabase permanece no servidor. A chave do dispositivo é protegida localmente pelo Android Keystore.

## Limitações reais do Android

- A abertura de aplicativos em display secundário depende do suporte do aparelho (FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS) e das regras do Android e do aplicativo aberto.
- Alguns fabricantes ou aplicativos podem não aceitar a abertura em display secundário ou podem iniciar atividades adicionais na tela física. Um APK comum não consegue garantir isolamento absoluto em todos os aparelhos e em todos os aplicativos.
- O Projeto Super não deve declarar uma sessão como bem-sucedida se não conseguir confirmar a atividade no display virtual.
- Aplicativos que bloqueiam janelas, conteúdo protegido ou ações de Acessibilidade podem limitar a experiência.

## Compilação e validação

Requisitos:
- Android Gradle Plugin 8.7.3
- Gradle 8.9
- JDK 17
- Android SDK 35

O workflow .github/workflows/android.yml:
1. verifica as regras arquiteturais e a separação dos consumidores de comandos;
2. compila as fontes Kotlin da variante release;
3. gera o APK debug original para teste;
4. valida a sintaxe JavaScript do dashboard;
5. publica APK, identificação e SHA-256 como artefato temporário do GitHub Actions.

O APK debug é assinado com a chave de depuração do ambiente de CI. Ele pode não atualizar uma instalação assinada com outra chave. Não é um APK de produção.

## Testes ainda necessários

A compilação automatizada não comprova compatibilidade com todos os fabricantes. Antes de declarar o Projeto Super pronto, testar no aparelho-alvo:
- criação do display virtual e abertura de dois ou mais aplicativos;
- confirmação de que os quadros JPEG mostram somente o display virtual;
- toque, rolagem, voltar e digitação;
- mudança de orientação, perda de rede, encerramento e retomada de sessão;
- encerramento remoto e revogação do dispositivo;
- permissões e indicadores de câmera/microfone, quando usados.

Não afirmar funcionamento em um aparelho sem executar esses testes nele.