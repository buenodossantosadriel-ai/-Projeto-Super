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

// PERMISSÕES EM UM ÚNICO BOTÃO.
// O navegador mantém prompts separados por tipo de permissão.
el("btnPermissoes").addEventListener("click", () => {
  const statusNotif = el("statusNotif");
  const statusLocal = el("statusLocal");
  const statusCamera = el("statusCamera");
  const statusMicrofone = el("statusMicrofone");

  if (!informarAmbienteSeguro("Permissões", statusCamera)) return;

  registrar("Solicitação geral de permissões iniciada pelo usuário.");

  // Inicia as solicitações diretamente no clique para respeitar a ativação do usuário.
  let notificacaoPromise = null;
  if ("Notification" in window) {
    notificacaoPromise = Notification.permission === "default"
      ? Notification.requestPermission()
      : Promise.resolve(Notification.permission);
  } else {
    statusNotif.textContent = "Notificações não são suportadas neste navegador.";
  }

  let midiaPromise = null;
  if (navigator.mediaDevices?.getUserMedia) {
    pararCamera();
    pararMicrofone();
    midiaPromise = navigator.mediaDevices.getUserMedia({
      video: { facingMode: { ideal: "environment" } },
      audio: true
    });
  } else {
    statusCamera.textContent = "Câmera indisponível neste navegador.";
    statusMicrofone.textContent = "Microfone indisponível neste navegador.";
  }

  if ("geolocation" in navigator) {
    statusLocal.textContent = "Aguardando autorização e posição...";
    el("resultadoLocal").hidden = true;
    navigator.geolocation.getCurrentPosition(
      (posicao) => {
        const { latitude, longitude, accuracy } = posicao.coords;
        statusLocal.textContent = "Localização autorizada e recebida.";
        el("resultadoLocal").textContent =
          `Latitude: ${latitude.toFixed(6)} | Longitude: ${longitude.toFixed(6)} | Precisão aproximada: ${Math.round(accuracy)} m`;
        el("resultadoLocal").hidden = false;
        registrar("Localização recebida e exibida somente nesta página.");
      },
      (erro) => {
        const mensagens = {
          1: "Permissão de localização negada.",
          2: "A posição não está disponível no momento.",
          3: "Tempo limite ao consultar a localização."
        };
        const mensagem = mensagens[erro.code] || "Não foi possível obter a localização.";
        statusLocal.textContent = mensagem;
        registrar(mensagem);
      },
      { enableHighAccuracy: true, timeout: 20000, maximumAge: 0 }
    );
  } else {
    statusLocal.textContent = "Geolocalização não é suportada neste navegador.";
  }

  if (midiaPromise) {
    midiaPromise.then((stream) => {
      cameraStream = stream;
      microfoneStream = stream;
      videoCamera.srcObject = stream;
      videoCamera.hidden = false;
      statusCamera.textContent = "Câmera ligada. Prévia local ativa.";
      statusMicrofone.textContent = "Microfone ligado. Nenhum áudio é gravado ou transmitido.";
      registrar("Câmera e microfone autorizados e ativados.");
    }).catch((erro) => {
      const mensagem = erro.name === "NotAllowedError"
        ? "Acesso à câmera e/ou microfone negado."
        : "Não foi possível iniciar câmera e microfone.";
      statusCamera.textContent = mensagem;
      statusMicrofone.textContent = mensagem;
      registrar(`Erro em câmera/microfone: ${erro.message}`);
    });
  }

  if (notificacaoPromise) {
    notificacaoPromise.then(async (permissao) => {
      if (permissao !== "granted") {
        statusNotif.textContent = permissao === "denied"
          ? "Permissão de notificações negada."
          : "Permissão de notificações não concedida.";
        registrar(`Permissão de notificações: ${permissao}.`);
        return;
      }
      try {
        if (!("serviceWorker" in navigator)) {
          statusNotif.textContent = "Permissão concedida, mas Service Worker não é suportado.";
          return;
        }
        const registro = await navigator.serviceWorker.register("./sw.js");
        await navigator.serviceWorker.ready;
        await registro.showNotification("Projeto Super", {
          body: "Notificação de demonstração ativada por você.",
          tag: "projeto-super-demo"
        });
        statusNotif.textContent = "Notificações autorizadas; demonstração enviada.";
        registrar("Permissão de notificações concedida.");
      } catch (erro) {
        statusNotif.textContent = "Permissão concedida, mas não foi possível exibir a notificação.";
        registrar(`Erro nas notificações: ${erro.message}`);
      }
    }).catch((erro) => {
      statusNotif.textContent = "Não foi possível solicitar notificações.";
      registrar(`Erro nas notificações: ${erro.message}`);
    });
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
