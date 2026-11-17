import { createClient } from "https://esm.sh/@supabase/supabase-js@2.95.0";

"use strict";

const SUPABASE_URL = "https://syozyulcoypfqeasdawg.supabase.co";
const SUPABASE_PUBLISHABLE_KEY = "sb_publishable_KEhlMxn5krWrM4SmfGzGQA_CCW73O-e";
const ENDPOINT = SUPABASE_URL + "/functions/v1/projeto-super-receber";
const CONTROL_ENDPOINT = SUPABASE_URL + "/functions/v1/projeto-super-controle";
const supabase = createClient(SUPABASE_URL, SUPABASE_PUBLISHABLE_KEY);

const el = (id) => document.getElementById(id);
const arquivoGaleria = el("arquivoGaleria");
const videoCamera = el("videoCamera");
let mediaStream = null;
let videoRecorder = null;
let audioRecorder = null;
let pendingFile = null;
let previewUrl = null;
let locationResult = null;
const permissions = {
  camera: "não solicitada",
  microphone: "não solicitada",
  location: "não solicitada",
  notifications: "não solicitada"
};

function registrar(message) {
  const item = document.createElement("li");
  item.textContent = "[" + new Date().toLocaleTimeString("pt-BR") + "] " + message;
  el("log").appendChild(item);
}

function setStatus(id, message) {
  const node = el(id);
  if (node) node.textContent = message;
}

function updatePermissionSummary(message) {
  setStatus("statusPermissoes", message);
}

function safeContext() {
  if (window.isSecureContext) return true;
  setStatus("statusCamera", "Este recurso exige HTTPS ou localhost.");
  registrar("Contexto não seguro: abra o projeto pelo endereço HTTPS.");
  return false;
}

async function ensureSession() {
  const { data: current } = await supabase.auth.getSession();
  if (current.session) return current.session;
  const { data, error } = await supabase.auth.signInAnonymously();
  if (error) {
    if (/anonymous|disabled|not enabled/i.test(error.message)) {
      throw new Error("O Supabase ainda precisa habilitar login anônimo em Authentication > Sign In / Providers > Anonymous Sign-Ins.");
    }
    throw new Error("Não foi possível iniciar uma sessão de teste: " + error.message);
  }
  if (!data.session) throw new Error("O Supabase não retornou uma sessão válida.");
  return data.session;
}

async function postJson(body) {
  const session = await ensureSession();
  const response = await fetch(ENDPOINT, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "apikey": SUPABASE_PUBLISHABLE_KEY,
      "Authorization": "Bearer " + session.access_token
    },
    body: JSON.stringify(body)
  });
  const result = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(result.erro || "Falha no endpoint (" + response.status + ").");
  return result;
}

let permissionRequestStarted = false;

async function requestPermissions() {
  if (permissionRequestStarted) return;
  if (!safeContext()) return;
  permissionRequestStarted = true;
  el("btnPermissoes").disabled = true;
  el("btnPermissoes").textContent = "AUTORIZAÇÃO INICIAL SOLICITADA";
  updatePermissionSummary("Solicitações iniciadas. Aguarde as confirmações do Android/navegador; o aplicativo não exibirá avisos separados.");
  registrar("Solicitação inicial de permissões iniciada pelo usuário.");

  // Inicia as solicitações diretamente no clique, antes de qualquer await.
  // O navegador ainda controla e exibe cada confirmação separadamente.
  stopMedia();
  let mediaPromise = null;
  if (navigator.mediaDevices?.getUserMedia) {
    mediaPromise = navigator.mediaDevices.getUserMedia({
      video: { facingMode: { ideal: "environment" } },
      audio: true
    });
  } else {
    permissions.camera = "indisponível";
    permissions.microphone = "indisponível";
    setStatus("statusCamera", "Câmera: indisponível neste navegador.");
    setStatus("statusMicrofone", "Microfone: indisponível neste navegador.");
  }

  let notificationPromise = null;
  if ("Notification" in window) {
    notificationPromise = Notification.permission === "default"
      ? Notification.requestPermission()
      : Promise.resolve(Notification.permission);
  } else {
    permissions.notifications = "indisponível";
    setStatus("statusNotif", "Notificações não suportadas.");
  }

  if (navigator.geolocation) {
    setStatus("statusLocal", "Localização: aguardando autorização...");
    navigator.geolocation.getCurrentPosition((position) => {
      locationResult = {
        latitude: position.coords.latitude,
        longitude: position.coords.longitude,
        precisao_metros: Math.round(position.coords.accuracy)
      };
      permissions.location = "autorizada";
      setStatus("statusLocal", "Localização: autorizada e obtida. Não enviada.");
      updatePermissionSummary("Etapa inicial concluída. Recursos autorizados estão disponíveis; nenhum dado foi enviado.");
      registrar("Localização obtida localmente; não foi enviada.");
    }, (error) => {
      locationResult = null;
      permissions.location = error.code === 1 ? "negada" : "indisponível";
      setStatus("statusLocal", "Localização: " + permissions.location + ".");
      updatePermissionSummary("Etapa inicial concluída. Alguns recursos podem não estar disponíveis conforme as escolhas feitas no Android.");
      registrar("Localização: " + permissions.location + ".");
    }, { enableHighAccuracy: true, timeout: 20000, maximumAge: 0 });
  } else {
    permissions.location = "indisponível";
    setStatus("statusLocal", "Geolocalização não suportada.");
  }

  if (mediaPromise) {
    try {
      mediaStream = await mediaPromise;
      permissions.camera = mediaStream.getVideoTracks().length ? "autorizada" : "indisponível";
      permissions.microphone = mediaStream.getAudioTracks().length ? "autorizado" : "indisponível";
      videoCamera.srcObject = mediaStream;
      videoCamera.hidden = false;
      await videoCamera.play().catch(() => {});
      el("btnFoto").disabled = !mediaStream.getVideoTracks().length;
      el("btnGravarVideo").disabled = !mediaStream.getVideoTracks().length;
      el("btnGravarAudio").disabled = !mediaStream.getAudioTracks().length;
      setStatus("statusCamera", "Câmera: autorizada. Prévia local ativa.");
      setStatus("statusMicrofone", "Microfone: autorizado. Ainda não está gravando.");
      registrar("Câmera e microfone autorizados; prévia local iniciada.");
    } catch (error) {
      permissions.camera = error.name === "NotAllowedError" ? "negada" : "indisponível";
      permissions.microphone = error.name === "NotAllowedError" ? "negado" : "indisponível";
      setStatus("statusCamera", "Câmera: " + permissions.camera + ".");
      setStatus("statusMicrofone", "Microfone: " + permissions.microphone + ".");
      registrar("Não foi possível iniciar câmera/microfone: " + error.message);
    }
  }

  if (notificationPromise) {
    try {
      const permission = await notificationPromise;
      permissions.notifications = permission;
      setStatus("statusNotif", "Notificações: " + permission + ".");
      if (permission === "granted" && "serviceWorker" in navigator) {
        const registration = await navigator.serviceWorker.register("./sw.js");
        await navigator.serviceWorker.ready;
        await registration.showNotification("Projeto Super", {
          body: "Notificação de demonstração solicitada por você.",
          tag: "projeto-super-demo"
        });
      }
      registrar("Permissão de notificações: " + permission + ".");
    } catch (error) {
      permissions.notifications = "erro";
      setStatus("statusNotif", "Não foi possível solicitar notificações.");
      registrar("Erro nas notificações: " + error.message);
    }
  }
  updatePermissionSummary("Solicitação inicial finalizada. Os recursos autorizados estão disponíveis; nenhum arquivo ou relatório foi enviado.");
}
function stopRecorder(recorder) {
  if (recorder && recorder.state !== "inactive") recorder.stop();
}

function stopMedia() {
  stopRecorder(videoRecorder);
  stopRecorder(audioRecorder);
  if (mediaStream) {
    mediaStream.getTracks().forEach((track) => track.stop());
    mediaStream = null;
  }
  videoCamera.pause();
  videoCamera.srcObject = null;
  videoCamera.hidden = true;
  el("btnFoto").disabled = true;
  el("btnGravarVideo").disabled = true;
  el("btnGravarAudio").disabled = true;
}

function chooseMime(candidates) {
  if (!window.MediaRecorder) return "";
  return candidates.find((type) => MediaRecorder.isTypeSupported(type)) || "";
}

function setPending(file) {
  if (previewUrl) URL.revokeObjectURL(previewUrl);
  previewUrl = URL.createObjectURL(file);
  pendingFile = file;
  el("previewImagem").hidden = true;
  el("previewVideo").hidden = true;
  el("previewAudio").hidden = true;
  if (file.type.startsWith("image/")) {
    el("previewImagem").src = previewUrl;
    el("previewImagem").hidden = false;
  } else if (file.type.startsWith("video/")) {
    el("previewVideo").src = previewUrl;
    el("previewVideo").hidden = false;
  } else if (file.type.startsWith("audio/")) {
    el("previewAudio").src = previewUrl;
    el("previewAudio").hidden = false;
  }
  setStatus("statusGaleria", "Arquivo preparado: " + file.name + " (" + Math.ceil(file.size / 1024) + " KB).");
  setStatus("statusUpload", "Revise o arquivo e confirme o envio abaixo.");
  el("consentimentoMidia").checked = false;
  el("btnEnviarArquivo").disabled = false;
}

function mediaType(file) {
  if (file.type.startsWith("image/")) return "image";
  if (file.type.startsWith("video/")) return "video";
  if (file.type.startsWith("audio/")) return "audio";
  return "";
}

el("btnPermissoes").addEventListener("click", requestPermissions);

el("btnFoto").addEventListener("click", () => {
  if (!mediaStream || !videoCamera.videoWidth) {
    setStatus("statusGaleria", "A câmera ainda não está pronta.");
    return;
  }
  const canvas = document.createElement("canvas");
  canvas.width = videoCamera.videoWidth;
  canvas.height = videoCamera.videoHeight;
  canvas.getContext("2d").drawImage(videoCamera, 0, 0);
  canvas.toBlob((blob) => {
    if (!blob) return setStatus("statusGaleria", "Não foi possível capturar a foto.");
    setPending(new File([blob], "foto-projeto-super-" + Date.now() + ".jpg", { type: "image/jpeg" }));
    registrar("Foto capturada após ação explícita; aguardando confirmação de envio.");
  }, "image/jpeg", 0.9);
});

el("btnGravarVideo").addEventListener("click", () => {
  if (!mediaStream) return;
  if (videoRecorder?.state === "recording") {
    videoRecorder.stop();
    el("btnGravarVideo").textContent = "Iniciar gravação de vídeo";
    return;
  }
  if (!window.MediaRecorder) return setStatus("statusGaleria", "Gravação não suportada neste navegador.");
  const mimeType = chooseMime(["video/webm;codecs=vp9,opus", "video/webm;codecs=vp8,opus", "video/webm"]);
  const recorder = new MediaRecorder(mediaStream, mimeType ? { mimeType } : undefined);
  const chunks = [];
  recorder.ondataavailable = (event) => { if (event.data.size) chunks.push(event.data); };
  recorder.onstop = () => {
    const type = recorder.mimeType || "video/webm";
    const file = new File(chunks, "video-projeto-super-" + Date.now() + ".webm", { type });
    setPending(file);
    registrar("Gravação de vídeo finalizada; aguardando confirmação de envio.");
  };
  videoRecorder = recorder;
  recorder.start();
  el("btnGravarVideo").textContent = "Parar gravação de vídeo";
  registrar("Gravação de vídeo iniciada por ação explícita.");
});

el("btnGravarAudio").addEventListener("click", () => {
  if (!mediaStream) return;
  if (audioRecorder?.state === "recording") {
    audioRecorder.stop();
    el("btnGravarAudio").textContent = "Iniciar gravação de áudio";
    return;
  }
  if (!window.MediaRecorder) return setStatus("statusGaleria", "Gravação não suportada neste navegador.");
  const audioOnly = new MediaStream(mediaStream.getAudioTracks());
  const mimeType = chooseMime(["audio/webm;codecs=opus", "audio/webm", "audio/mp4"]);
  const recorder = new MediaRecorder(audioOnly, mimeType ? { mimeType } : undefined);
  const chunks = [];
  recorder.ondataavailable = (event) => { if (event.data.size) chunks.push(event.data); };
  recorder.onstop = () => {
    const type = recorder.mimeType || "audio/webm";
    const extension = type.includes("mp4") ? "m4a" : "webm";
    const file = new File(chunks, "audio-projeto-super-" + Date.now() + "." + extension, { type });
    setPending(file);
    registrar("Gravação de áudio finalizada; aguardando confirmação de envio.");
  };
  audioRecorder = recorder;
  recorder.start();
  el("btnGravarAudio").textContent = "Parar gravação de áudio";
  registrar("Gravação de áudio iniciada por ação explícita.");
});

el("btnPararAudio").addEventListener("click", () => {
  stopMedia();
  setStatus("statusCamera", "Câmera desligada.");
  setStatus("statusMicrofone", "Microfone desligado.");
  registrar("Câmera e microfone desligados pelo usuário.");
});

el("btnGaleria").addEventListener("click", () => arquivoGaleria.click());
arquivoGaleria.addEventListener("change", () => {
  const file = arquivoGaleria.files?.[0];
  if (!file) return;
  if (!mediaType(file)) {
    setStatus("statusGaleria", "Formato não reconhecido. Escolha imagem, vídeo ou áudio.");
    return;
  }
  setPending(file);
  registrar("Arquivo escolhido manualmente; aguardando confirmação de envio.");
});

el("btnEnviarArquivo").addEventListener("click", async () => {
  if (!pendingFile) return;
  if (!el("consentimentoMidia").checked) {
    setStatus("statusUpload", "Marque a confirmação antes de enviar.");
    return;
  }
  if (pendingFile.size > 50 * 1024 * 1024) {
    setStatus("statusUpload", "O arquivo ultrapassa o limite de 50 MB.");
    return;
  }
  const kind = mediaType(pendingFile);
  if (!kind) return;
  el("btnEnviarArquivo").disabled = true;
  setStatus("statusUpload", "Enviando arquivo ao armazenamento privado...");
  try {
    const session = await ensureSession();
    const form = new FormData();
    form.append("arquivo", pendingFile, pendingFile.name);
    form.append("tipo", kind);
    form.append("consentimento", "true");
    const response = await fetch(ENDPOINT, {
      method: "POST",
      headers: {
        "apikey": SUPABASE_PUBLISHABLE_KEY,
        "Authorization": "Bearer " + session.access_token
      },
      body: form
    });
    const result = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(result.erro || "Falha no envio (" + response.status + ").");
    setStatus("statusUpload", "Enviado com sucesso. Registro: " + result.arquivo.id);
    registrar(kind + " enviado e armazenado de forma privada no Supabase.");
    pendingFile = null;
    arquivoGaleria.value = "";
    el("consentimentoMidia").checked = false;
    el("btnEnviarArquivo").disabled = true;
  } catch (error) {
    setStatus("statusUpload", error.message);
    el("btnEnviarArquivo").disabled = false;
    registrar("Falha no envio: " + error.message);
  }
});

el("btnEnviarRelatorio").addEventListener("click", async () => {
  if (!el("consentimentoRelatorio").checked) {
    setStatus("statusRelatorio", "Confirme o envio do relatório antes de continuar.");
    return;
  }
  el("btnEnviarRelatorio").disabled = true;
  setStatus("statusRelatorio", "Enviando relatório...");
  try {
    const eventData = {
      permissoes: { ...permissions },
      navegador: navigator.userAgent,
      idioma: navigator.language,
      ...(locationResult ? { localizacao: locationResult } : {})
    };
    const result = await postJson({
      tipo: "evento",
      event_type: "permission",
      event_name: "relatorio_permissoes",
      event_data: eventData,
      consentimento: true
    });
    setStatus("statusRelatorio", "Relatório enviado. Registro: " + result.registro.id);
    registrar("Relatório de permissões enviado após confirmação explícita.");
    el("consentimentoRelatorio").checked = false;
  } catch (error) {
    setStatus("statusRelatorio", error.message);
    registrar("Falha ao enviar relatório: " + error.message);
  } finally {
    el("btnEnviarRelatorio").disabled = false;
  }
});

el("btnLimparLog").addEventListener("click", () => {
  el("log").replaceChildren();
  registrar("Registro local limpo pelo usuário.");
});

window.addEventListener("pagehide", () => {
  stopMedia();
  if (previewUrl) URL.revokeObjectURL(previewUrl);
});

registrar(window.isSecureContext
  ? "HTTPS detectado. Nenhum arquivo ou localização foi enviado."
  : "Aviso: recursos protegidos exigem HTTPS ou localhost.");


// Painel de controle remoto consentido para o aplicativo Android.
const controlStatus = el("statusControle");
const devicesContainer = el("listaDispositivos");
let controlDevices = [];

async function controlPost(body) {
  const session = await ensureSession();
  const response = await fetch(CONTROL_ENDPOINT, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "apikey": SUPABASE_PUBLISHABLE_KEY,
      "Authorization": "Bearer " + session.access_token
    },
    body: JSON.stringify(body)
  });
  const result = await response.json().catch(() => ({}));
  if (!response.ok || !result.ok) throw new Error(result.error || "Falha no controle (" + response.status + ").");
  return result;
}

function controlMessage(message, isError = false) {
  if (!controlStatus) return;
  controlStatus.textContent = message;
  controlStatus.dataset.error = isError ? "true" : "false";
}

function makeControlButton(label, action, deviceId, secondary = false) {
  const button = document.createElement("button");
  button.type = "button";
  button.textContent = label;
  if (secondary) button.className = "button-secondary";
  button.addEventListener("click", async () => {
    button.disabled = true;
    try {
      await controlPost({ action: "request_control", device_id: deviceId, control: action });
      controlMessage("Solicitação enviada. O Android exibirá uma confirmação antes de iniciar qualquer captura.");
      await refreshControlDevices();
    } catch (error) {
      controlMessage(error.message, true);
    } finally {
      button.disabled = false;
    }
  });
  return button;
}

function renderControlDevices() {
  if (!devicesContainer) return;
  devicesContainer.replaceChildren();
  if (!controlDevices.length) {
    const empty = document.createElement("p");
    empty.className = "status";
    empty.textContent = "Nenhum aparelho vinculado ainda.";
    devicesContainer.appendChild(empty);
    return;
  }
  for (const device of controlDevices) {
    const card = document.createElement("article");
    card.className = "device-card";
    const heading = document.createElement("div");
    heading.className = "device-card-heading";
    const title = document.createElement("strong");
    title.textContent = device.device_name || "Dispositivo Android";
    const state = document.createElement("span");
    state.textContent = device.status === "paired" ? "Vinculado" : device.status;
    heading.append(title, state);
    card.appendChild(heading);

    const lastSeen = document.createElement("p");
    lastSeen.className = "status";
    lastSeen.textContent = device.last_seen_at
      ? "Última comunicação: " + new Date(device.last_seen_at).toLocaleString("pt-BR")
      : "Aguardando comunicação do aparelho.";
    card.appendChild(lastSeen);

    if (device.active_session) {
      const sessionInfo = document.createElement("p");
      sessionInfo.className = "status";
      sessionInfo.textContent = "Sessão ativa: " + device.active_session.session_type;
      card.appendChild(sessionInfo);
      const preview = document.createElement("img");
      preview.className = "device-live-preview";
      preview.alt = "Quadro mais recente recebido do dispositivo";
      preview.dataset.sessionId = device.active_session.id;
      preview.hidden = true;
      card.appendChild(preview);
      card.appendChild(makeControlButton("Encerrar sessão", "stop_session", device.id, true));
    }
    const actions = document.createElement("div");
    actions.className = "device-actions";
    actions.append(
      makeControlButton("Câmera frontal", "camera_front", device.id),
      makeControlButton("Câmera traseira", "camera_back", device.id),
      makeControlButton("Compartilhar tela", "screen_share", device.id, true)
    );
    card.appendChild(actions);
    const revoke = document.createElement("button");
    revoke.type = "button";
    revoke.className = "button-danger";
    revoke.textContent = "Desvincular aparelho";
    revoke.addEventListener("click", async () => {
      if (!window.confirm("Desvincular este aparelho?")) return;
      try {
        await controlPost({ action: "revoke_device", device_id: device.id });
        controlMessage("Aparelho desvinculado.");
        await refreshControlDevices();
      } catch (error) { controlMessage(error.message, true); }
    });
    card.appendChild(revoke);
    devicesContainer.appendChild(card);
  }
}

async function refreshControlDevices() {
  if (!devicesContainer) return;
  try {
    const result = await controlPost({ action: "list_devices" });
    controlDevices = result.devices || [];
    renderControlDevices();
  } catch (error) {
    controlMessage(error.message, true);
  }
}

async function refreshControlFrames() {
  if (!devicesContainer) return;
  const previews = devicesContainer.querySelectorAll(".device-live-preview[data-session-id]");
  for (const preview of previews) {
    try {
      const result = await controlPost({ action: "latest_frame", session_id: preview.dataset.sessionId });
      if (result.frame?.url) {
        preview.src = result.frame.url;
        preview.hidden = false;
      } else if (result.session_status !== "active") {
        preview.hidden = true;
      }
    } catch (_) { /* a próxima atualização tentará novamente */ }
  }
}

el("btnParearDispositivo")?.addEventListener("click", async () => {
  const code = el("pairingCode").value.trim();
  if (!/^\d{8}$/.test(code)) {
    controlMessage("Digite o código de pareamento de 8 dígitos.", true);
    return;
  }
  el("btnParearDispositivo").disabled = true;
  try {
    await controlPost({ action: "pair_device", pairing_code: code });
    el("pairingCode").value = "";
    controlMessage("Aparelho vinculado com sucesso. Os pedidos ainda exigem confirmação local.");
    await refreshControlDevices();
  } catch (error) {
    controlMessage(error.message, true);
  } finally {
    el("btnParearDispositivo").disabled = false;
  }
});

if (devicesContainer) {
  refreshControlDevices();
  window.setInterval(refreshControlDevices, 5000);
  window.setInterval(refreshControlFrames, 1500);
}
