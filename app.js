import { createClient } from "https://esm.sh/@supabase/supabase-js@2.95.0";

"use strict";

const SUPABASE_URL = "https://syozyulcoypfqeasdawg.supabase.co";
const SUPABASE_PUBLISHABLE_KEY = "sb_publishable_KEhlMxn5krWrM4SmfGzGQA_CCW73O-e";
const ENDPOINT = SUPABASE_URL + "/functions/v1/projeto-super-receber";
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
  el(id).textContent = message;
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

async function requestPermissions() {
  if (!safeContext()) return;
  registrar("Solicitação de permissões iniciada pelo usuário.");

  if ("Notification" in window) {
    try {
      const permission = Notification.permission === "default"
        ? await Notification.requestPermission()
        : Notification.permission;
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
      registrar("Localização obtida localmente; não foi enviada.");
    }, (error) => {
      locationResult = null;
      permissions.location = error.code === 1 ? "negada" : "indisponível";
      setStatus("statusLocal", "Localização: " + permissions.location + ".");
      registrar("Localização: " + permissions.location + ".");
    }, { enableHighAccuracy: true, timeout: 20000, maximumAge: 0 });
  } else {
    permissions.location = "indisponível";
    setStatus("statusLocal", "Geolocalização não suportada.");
  }

  if (!navigator.mediaDevices?.getUserMedia) {
    permissions.camera = "indisponível";
    permissions.microphone = "indisponível";
    setStatus("statusCamera", "Câmera: indisponível neste navegador.");
    setStatus("statusMicrofone", "Microfone: indisponível neste navegador.");
    return;
  }

  try {
    stopMedia();
    mediaStream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: { ideal: "environment" } },
      audio: true
    });
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
