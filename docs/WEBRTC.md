# Projeto Super — WebRTC

## Arquitetura
- Android captura somente após o usuário aceitar a solicitação e, para compartilhamento de tela, aceitar também a tela nativa do Android.
- O Android mantém uma notificação persistente com ação para interromper a captura.
- O dashboard negocia WebRTC (oferta/resposta SDP e candidatos ICE) por meio da Edge Function autenticada.
- O vídeo trafega pelo canal WebRTC; Supabase armazena somente a sinalização e o estado da sessão.

## Qualidade adaptativa
A captura inicia em resolução moderada (câmera 640x360 / tela 960x540), com limite inicial de 1,2 Mbps e 20 fps. A implementação usa WebRTC para congestion control; limites podem ser ajustados após testes em redes reais.

## ICE / TURN
A configuração atual inclui servidores STUN públicos para descoberta de rota. STUN sozinho não garante conexão em redes com CGNAT ou NAT restritivo. Para cobertura confiável em 4G/5G e redes corporativas, configurar um serviço TURN com credenciais temporárias e TLS/UDP/TCP conforme suporte do provedor. Nunca colocar credenciais TURN permanentes no JavaScript público ou no APK.

## Testes antes de distribuição
1. Autorizar câmera frontal e traseira no próprio Android.
2. Autorizar compartilhamento de tela no diálogo nativo do Android.
3. Confirmar vídeo no dashboard e estado ICE conectado.
4. Testar alternância Wi-Fi/4G e queda/retorno de sinal.
5. Confirmar botão Parar no Android e comando Encerrar sessão no dashboard.
6. Validar TURN em rede móvel com CGNAT antes de declarar compatibilidade ampla.
