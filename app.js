"use strict";

// Projeto Super — laboratório de permissões do navegador.
// Nenhum arquivo, áudio, vídeo ou dado de localização é enviado pela aplicação.

const el = (id) => document.getElementById(id);

const arquivoGaleria = el("arquivoGaleria");
const previewGaleria = el("previewGaleria");
const videoCamera = el("videoCamera");

let cameraStream = null;
let microfoneStream = null;
let imagemTemporariaUrl = null;

function registrar(mensagem) {
  const item = document.createElement("li");
  const horario = new Date().toLocaleTimeString("pt-BR");
  item.textContent = `[${horario}] ${mensagem}`;
  el("log").appendChild(item);
}

function ambienteSeguro() {
  return window.isSecureContext === true;
}

function informarAmbienteSeguro(recurso, status) {
  if (ambienteSeguro()) return true;
  status.textContent = "Este recurso exige uma página HTTPS ou localhost.";
  registrar(`${recurso}: contexto não seguro; abra o projeto por HTTPS para testar.`);
  return false;
}

// GALERIA: abre o seletor do sistema. Só acessa o arquivo escolhido.
el("btnGaleria").addEventListener("click", () => arquivoGaleria.click());

arquivoGaleria.addEventListener("change", () => {
  const arquivo = arquivoGaleria.files && arquivoGaleria.files[0];

  if (!arquivo) {
    el("statusGaleria").textContent = "Nenhuma imagem selecionada.";
    registrar("Seleção da galeria cancelada.");
    return;
  }

  if (!arquivo.type.startsWith("image/")) {
    el("statusGaleria").textContent = "O arquivo escolhido não é uma imagem.";
    registrar("Arquivo selecionado não é uma imagem.");
    arquivoGaleria.value = "";
    return;
  }

  if (imagemTemporariaUrl) URL.revokeObjectURL(imagemTemporariaUrl);
  imagemTemporariaUrl = URL.createObjectURL(arquivo);
  previewGaleria.src = imagemTemporariaUrl;
  previewGaleria.hidden = false;
  el("statusGaleria").textContent = `Imagem selecionada: ${arquivo.name}`;
  registrar("Uma imagem foi escolhida pelo usuário. Nenhum envio foi realizado.");
});

// NOTIFICAÇÕES: pede autorização somente após o toque do usuário.
el("btnNotif").addEventListener("click", async () => {
  const status = el("statusNotif");

  if (!("Notification" in window)) {
    status.textContent = "Este navegador não oferece suporte a notificações web.";
    registrar("Notificações não são suportadas neste navegador.");
    return;
  }

  if (!informarAmbienteSeguro("Notificações", status)) return;

  try {
    let permissao = Notification.permission;
    if (permissao === "default") {
      permissao = await Notification.requestPermission();
    }

    if (permissao !== "granted") {
      status.textContent = permissao === "denied"
        ? "Permissão negada. Altere nas configurações do navegador se desejar."
        : "Permissão não concedida.";
      registrar(`Permissão de notificações: ${permissao}.`);
      return;
    }

    // Em navegadores móveis, a notificação deve ser exibida pelo Service Worker.
    if ("serviceWorker" in navigator) {
      const registro = await navigator.serviceWorker.register("./sw.js");
      await navigator.serviceWorker.ready;
      await registro.showNotification("Projeto Super", {
        body: "Notificação de demonstração ativada por você.",
        tag: "projeto-super-demo"
      });
      status.textContent = "Permissão concedida. Notificação de demonstração enviada.";
    } else {
      status.textContent = "Permissão concedida, mas este navegador não oferece Service Worker.";
    }

    registrar("Permissão de notificações concedida; demonstração solicitada.");
  } catch (erro) {
    status.textContent = "Não foi possível exibir a notificação neste navegador.";
    registrar(`Erro nas notificações: ${erro.message}`);
  }
});

// LOCALIZAÇÃO: consulta pontual; não acompanha a posição em segundo plano.
el("btnLocal").addEventListener("click", () => {
  const status = el("statusLocal");
  const resultado = el("resultadoLocal");

  if (!("geolocation" in navigator)) {
    status.textContent = "Geolocalização não é suportada neste navegador.";
    registrar("Geolocalização não suportada.");
    return;
  }

  if (!informarAmbienteSeguro("Localização", status)) return;

  status.textContent = "Aguardando autorização e posição...";
  resultado.hidden = true;
  registrar("Solicitação pontual de localização iniciada.");

  navigator.geolocation.getCurrentPosition(
    (posicao) => {
      const { latitude, longitude, accuracy } = posicao.coords;
      status.textContent = "Localização autorizada e recebida.";
      resultado.textContent =
        `Latitude: ${latitude.toFixed(6)} | Longitude: ${longitude.toFixed(6)} | Precisão aproximada: ${Math.round(accuracy)} m`;
      resultado.hidden = false;
      registrar("Localização recebida e exibida somente nesta página.");
    },
    (erro) => {
      const mensagens = {
        1: "Permissão de localização negada.",
        2: "A posição não está disponível no momento.",
        3: "Tempo limite ao consultar a localização."
      };
      const mensagem = mensagens[erro.code] || "Não foi possível obter a localização.";
      status.textContent = mensagem;
      registrar(mensagem);
    },
    { enableHighAccuracy: true, timeout: 20000, maximumAge: 0 }
  );
});

// CÂMERA: mostra a prévia local. Não grava nem transmite.
el("btnCamera").addEventListener("click", async () => {
  const status = el("statusCamera");

  if (!navigator.mediaDevices?.getUserMedia) {
    status.textContent = "Câmera não disponível. Verifique o navegador e o HTTPS.";
    registrar("API de câmera indisponível.");
    return;
  }
  if (!informarAmbienteSeguro("Câmera", status)) return;

  try {
    pararCamera();
    cameraStream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: { ideal: "environment" } },
      audio: false
    });
    videoCamera.srcObject = cameraStream;
    videoCamera.hidden = false;
    status.textContent = "Câmera ligada. A prévia está visível somente nesta página.";
    registrar("Câmera ativada com autorização do usuário.");
  } catch (erro) {
    status.textContent = erro.name === "NotAllowedError"
      ? "Acesso à câmera negado."
      : "Não foi possível iniciar a câmera.";
    registrar(`Erro na câmera: ${erro.message}`);
  }
});

// MICROFONE: ativa a entrada, sem gravar ou enviar áudio.
el("btnMicrofone").addEventListener("click", async () => {
  const status = el("statusMicrofone");

  if (!navigator.mediaDevices?.getUserMedia) {
    status.textContent = "Microfone não disponível. Verifique o navegador e o HTTPS.";
    registrar("API de microfone indisponível.");
    return;
  }
  if (!informarAmbienteSeguro("Microfone", status)) return;

  try {
    pararMicrofone();
    microfoneStream = await navigator.mediaDevices.getUserMedia({
      audio: true,
      video: false
    });
    status.textContent = "Microfone ligado. Nenhum áudio está sendo gravado ou transmitido.";
    registrar("Microfone ativado com autorização do usuário.");
  } catch (erro) {
    status.textContent = erro.name === "NotAllowedError"
      ? "Acesso ao microfone negado."
      : "Não foi possível iniciar o microfone.";
    registrar(`Erro no microfone: ${erro.message}`);
  }
});

function pararCamera() {
  if (cameraStream) {
    cameraStream.getTracks().forEach((track) => track.stop());
    cameraStream = null;
  }
  videoCamera.pause();
  videoCamera.srcObject = null;
  videoCamera.hidden = true;
}

function pararMicrofone() {
  if (microfoneStream) {
    microfoneStream.getTracks().forEach((track) => track.stop());
    microfoneStream = null;
  }
}

el("btnPararAudio").addEventListener("click", () => {
  pararCamera();
  pararMicrofone();
  el("statusCamera").textContent = "Câmera desligada.";
  el("statusMicrofone").textContent = "Microfone desligado.";
  registrar("Câmera e microfone desligados pelo usuário.");
});

// Libera a câmera e o microfone ao sair ou fechar a página.
window.addEventListener("pagehide", () => {
  pararCamera();
  pararMicrofone();
  if (imagemTemporariaUrl) URL.revokeObjectURL(imagemTemporariaUrl);
});

el("btnLimparLog").addEventListener("click", () => {
  el("log").replaceChildren();
  registrar("Registro limpo pelo usuário.");
});

registrar(ambienteSeguro()
  ? "Contexto seguro detectado. Recursos disponíveis conforme suporte do navegador."
  : "Aviso: recursos protegidos exigem HTTPS ou localhost.");
