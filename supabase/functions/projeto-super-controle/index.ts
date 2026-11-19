import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const SITE_ORIGIN = "https://projeto-super-eta.vercel.app";
const BUCKET = "projeto-super-uploads";
const MAX_FRAME_BYTES = 2 * 1024 * 1024;
const MAX_AUDIO_BYTES = 512 * 1024;
const cors = {
  "Access-Control-Allow-Origin": SITE_ORIGIN,
  "Access-Control-Allow-Headers": "authorization, apikey, x-client-info, content-type, x-device-secret",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Vary": "Origin",
};

function reply(data: unknown, status = 200, allowedOrigin = SITE_ORIGIN) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...cors, "Access-Control-Allow-Origin": allowedOrigin, "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}
function hex(bytes: Uint8Array) {
  return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
}
async function digest(value: string) {
  return hex(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value))));
}
function randomHex(size: number) {
  const bytes = new Uint8Array(size);
  crypto.getRandomValues(bytes);
  return hex(bytes);
}
async function clearSessionFrames(admin: ReturnType<typeof createClient>, sessionIds: string[]) {
  if (!sessionIds.length) return;
  await admin.from("projeto_super_webrtc_signals").delete().in("session_id", sessionIds);
  const { data: frames } = await admin.from("projeto_super_live_frames")
    .select("id,storage_path").in("session_id", sessionIds);
  if (frames?.length) {
    await admin.storage.from(BUCKET).remove(frames.map((frame) => frame.storage_path));
    await admin.from("projeto_super_live_frames").delete().in("id", frames.map((frame) => frame.id));
  }
}
async function clearAudioChunks(admin: ReturnType<typeof createClient>, sessionIds: string[], mode?: string) {
  if (!sessionIds.length) return;
  let query = admin.from("projeto_super_audio_chunks").select("id,storage_path").in("session_id", sessionIds);
  if (mode) query = query.eq("mode", mode);
  const { data: chunks } = await query;
  if (chunks?.length) {
    await admin.storage.from(BUCKET).remove(chunks.map((chunk) => chunk.storage_path));
    await admin.from("projeto_super_audio_chunks").delete().in("id", chunks.map((chunk) => chunk.id));
  }
}

Deno.serve(async (req: Request) => {
  const origin = req.headers.get("origin");
  let allowedOrigin = SITE_ORIGIN;
  if (origin) {
    let allowed = false;
    try {
      const host = new URL(origin).hostname;
      allowed = host === "projeto-super-eta.vercel.app" ||
        (host.startsWith("projeto-super-") && host.endsWith(".vercel.app"));
    } catch { allowed = false; }
    if (!allowed) return reply({ ok: false, error: "Origem não autorizada." }, 403);
    allowedOrigin = origin;
  }
  if (req.method === "OPTIONS") return new Response("ok", {
    headers: { ...cors, "Access-Control-Allow-Origin": allowedOrigin },
  });
  const respond = (data: unknown, status = 200) => reply(data, status, allowedOrigin);
  if (req.method !== "POST") return respond({ ok: false, error: "Método não permitido." }, 405);

  try {
    const projectUrl = Deno.env.get("SUPABASE_URL") || "";
    const secretMap = JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") || "{}");
    const secretKey = secretMap.default;
    if (!projectUrl || !secretKey) return respond({ ok: false, error: "Configuração segura indisponível." }, 500);
    const admin = createClient(projectUrl, secretKey, { auth: { persistSession: false, autoRefreshToken: false } });

    const contentType = req.headers.get("content-type") || "";
    const auth = req.headers.get("authorization") || "";
    const bearer = auth.startsWith("Bearer ") ? auth.slice(7) : "";
    const deviceSecret = req.headers.get("x-device-secret") || "";
    const deviceHash = deviceSecret ? await digest(deviceSecret) : "";
    const isMultipart = contentType.includes("multipart/form-data");

    async function getDevice(deviceId: string) {
      if (!deviceId || !deviceHash) return null;
      const { data, error } = await admin.from("projeto_super_devices")
        .select("id,owner_user_id,status").eq("id", deviceId)
        .eq("device_secret_hash", deviceHash).maybeSingle();
      if (error || !data || data.status === "revoked") return null;
      await admin.from("projeto_super_devices").update({ last_seen_at: new Date().toISOString() }).eq("id", deviceId);
      return data;
    }
    async function getOwner() {
      if (!bearer) return null;
      const { data, error } = await admin.auth.getUser(bearer);
      return error || !data.user ? null : data.user;
    }

    if (isMultipart) {
      const form = await req.formData();
      const deviceId = String(form.get("device_id") || "");
      const sessionId = String(form.get("session_id") || "");
      const device = await getDevice(deviceId);
      const audioFile = form.get("audio_chunk");
      if (audioFile instanceof File) {
        const mode = String(form.get("mode") || "");
        const chunkIndex = Number(form.get("chunk_index") || -1);
        const durationMs = Number(form.get("duration_ms") || 0);
        if (!device || device.status !== "paired" || !device.owner_user_id) return respond({ ok: false, error: "Dispositivo não autenticado ou não pareado." }, 401);
        if (!["live", "recording"].includes(mode) || !Number.isSafeInteger(chunkIndex) || chunkIndex < 0 ||
            !Number.isInteger(durationMs) || durationMs < 1 || durationMs > 10000) {
          return respond({ ok: false, error: "Metadados do áudio inválidos." }, 400);
        }
        if (audioFile.size < 44 || audioFile.size > MAX_AUDIO_BYTES) return respond({ ok: false, error: "Trecho de áudio inválido ou acima de 512 KB." }, 413);
        if (audioFile.type !== "audio/wav" && audioFile.type !== "application/octet-stream") return respond({ ok: false, error: "Formato de áudio não permitido." }, 415);
        const { data: audioSession } = await admin.from("projeto_super_device_sessions")
          .select("id,status,owner_user_id,session_type").eq("id", sessionId).eq("device_id", deviceId).maybeSingle();
        if (!audioSession || audioSession.status !== "active" || audioSession.owner_user_id !== device.owner_user_id ||
            !["microphone_live", "microphone_record"].includes(audioSession.session_type)) {
          return respond({ ok: false, error: "Sessão de áudio não está ativa." }, 409);
        }
        const path = "audio/" + deviceId + "/" + sessionId + "/" + mode + "/" + String(chunkIndex).padStart(8, "0") + ".wav";
        const bytes = new Uint8Array(await audioFile.arrayBuffer());
        const { error: uploadError } = await admin.storage.from(BUCKET).upload(path, bytes, {
          contentType: "audio/wav", cacheControl: "0", upsert: true,
        });
        if (uploadError) return respond({ ok: false, error: "Falha ao armazenar áudio." }, 502);
        const { error: rowError } = await admin.from("projeto_super_audio_chunks").upsert({
          device_id: deviceId, session_id: sessionId, owner_user_id: device.owner_user_id,
          chunk_index: chunkIndex, storage_path: path, mime_type: "audio/wav", mode, duration_ms: durationMs,
        }, { onConflict: "session_id,chunk_index" });
        if (rowError) {
          await admin.storage.from(BUCKET).remove([path]);
          return respond({ ok: false, error: "Falha ao registrar trecho de áudio." }, 500);
        }
        await admin.from("projeto_super_device_sessions").update({ last_frame_at: new Date().toISOString() }).eq("id", sessionId);
        if (mode === "live") {
          const { data: oldChunks } = await admin.from("projeto_super_audio_chunks").select("id,storage_path")
            .eq("session_id", sessionId).eq("mode", "live").order("chunk_index", { ascending: false }).range(8, 500);
          if (oldChunks?.length) {
            await admin.storage.from(BUCKET).remove(oldChunks.map((x) => x.storage_path));
            await admin.from("projeto_super_audio_chunks").delete().in("id", oldChunks.map((x) => x.id));
          }
        } else {
          const cutoff = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString();
          const { data: expired } = await admin.from("projeto_super_audio_chunks").select("id,storage_path")
            .eq("owner_user_id", device.owner_user_id).eq("mode", "recording").lt("created_at", cutoff);
          if (expired?.length) {
            await admin.storage.from(BUCKET).remove(expired.map((x) => x.storage_path));
            await admin.from("projeto_super_audio_chunks").delete().in("id", expired.map((x) => x.id));
          }
        }
        return respond({ ok: true, received_at: new Date().toISOString() }, 201);
      }
      const file = form.get("frame");
      if (!device || device.status !== "paired" || !device.owner_user_id) return respond({ ok: false, error: "Dispositivo não autenticado ou não pareado." }, 401);
      if (!(file instanceof File) || file.size < 1 || file.size > MAX_FRAME_BYTES) return respond({ ok: false, error: "Quadro inválido ou acima de 2 MB." }, 413);
      if (!["image/jpeg", "image/png"].includes(file.type)) return respond({ ok: false, error: "Somente JPEG ou PNG." }, 415);
      const { data: session } = await admin.from("projeto_super_device_sessions").select("id,status,owner_user_id")
        .eq("id", sessionId).eq("device_id", deviceId).maybeSingle();
      if (!session || session.status !== "active" || session.owner_user_id !== device.owner_user_id) return respond({ ok: false, error: "Sessão não está ativa." }, 409);
      const ext = file.type === "image/png" ? "png" : "jpg";
      const path = "live/" + deviceId + "/" + sessionId + "/" + Date.now() + "-" + randomHex(5) + "." + ext;
      const bytes = new Uint8Array(await file.arrayBuffer());
      const { error: uploadError } = await admin.storage.from(BUCKET).upload(path, bytes, {
        contentType: file.type, cacheControl: "0", upsert: false,
      });
      if (uploadError) return respond({ ok: false, error: "Falha ao armazenar quadro." }, 502);
      const { error: rowError } = await admin.from("projeto_super_live_frames").insert({
        device_id: deviceId, session_id: sessionId, owner_user_id: device.owner_user_id,
        storage_path: path, mime_type: file.type,
      });
      if (rowError) {
        await admin.storage.from(BUCKET).remove([path]);
        return respond({ ok: false, error: "Falha ao registrar quadro." }, 500);
      }
      await admin.from("projeto_super_device_sessions").update({ last_frame_at: new Date().toISOString() }).eq("id", sessionId);
      // Mantém apenas os 90 quadros mais recentes por sessão.
      const { data: oldFrames } = await admin.from("projeto_super_live_frames").select("id,storage_path")
        .eq("session_id", sessionId).order("created_at", { ascending: false }).range(90, 500);
      if (oldFrames?.length) {
        await admin.storage.from(BUCKET).remove(oldFrames.map((x) => x.storage_path));
        await admin.from("projeto_super_live_frames").delete().in("id", oldFrames.map((x) => x.id));
      }
      return respond({ ok: true, received_at: new Date().toISOString() }, 201);
    }

    const body = await req.json();
    const action = String(body?.action || "");

    if (action === "register_device") {
      if (deviceSecret.length < 40) return respond({ ok: false, error: "Chave de dispositivo inválida." }, 400);
      // Vínculo automático à conta proprietária do Projeto Super.
      // O segredo aleatório da instalação continua obrigatório para atualizar um registro existente.
      const ownerUserId = "fbde6bdd-35ba-4738-b523-74d71fcd3593";
      const deviceId = String(body.device_id || "");
      const deviceName = String(body.device_name || "Dispositivo Android").slice(0, 80);
      let row;
      if (deviceId) {
        const { data, error } = await admin.from("projeto_super_devices").update({
          device_name: deviceName,
          owner_user_id: ownerUserId,
          status: "paired",
          pairing_code_hash: null,
          pairing_expires_at: null,
        }).eq("id", deviceId).eq("device_secret_hash", deviceHash)
          .in("status", ["unpaired", "paired"])
          .or("owner_user_id.is.null,owner_user_id.eq." + ownerUserId)
          .select("id").maybeSingle();
        if (error || !data) return respond({ ok: false, error: "Dispositivo não encontrado, revogado ou não autorizado para esta conta." }, 401);
        row = data;
      } else {
        const { data, error } = await admin.from("projeto_super_devices").insert({
          device_secret_hash: deviceHash,
          device_name: deviceName,
          owner_user_id: ownerUserId,
          status: "paired",
        }).select("id").single();
        if (error || !data) return respond({ ok: false, error: "Não foi possível registrar e vincular o dispositivo." }, 500);
        row = data;
      }
      return respond({ ok: true, device_id: row.id, status: "paired", paired: true }, 200);
    }

    // Sinalização WebRTC: Supabase transporta apenas SDP/ICE; mídia não passa pelo servidor.
    if (action === "webrtc_send" || action === "webrtc_poll" || action === "webrtc_ice_config") {
      const sessionId = String(body.session_id || "");
      const signalType = String(body.signal_type || "");
      const allowedTypes = ["offer", "answer", "candidate", "renegotiate", "hangup"];
      if (!sessionId || !/^[0-9a-f-]{36}$/i.test(sessionId)) {
        return respond({ ok: false, error: "Sessão WebRTC inválida." }, 400);
      }
      let sender: "device" | "owner";
      let deviceId = "";
      let ownerId = "";
      if (deviceSecret) {
        const device = await getDevice(String(body.device_id || ""));
        if (!device || device.status !== "paired" || !device.owner_user_id) {
          return respond({ ok: false, error: "Dispositivo não autenticado ou não vinculado." }, 401);
        }
        sender = "device";
        deviceId = device.id;
        ownerId = device.owner_user_id;
      } else {
        const owner = await getOwner();
        if (!owner) return respond({ ok: false, error: "Sessão administrativa inválida." }, 401);
        if ((owner.email || "").toLowerCase() !== "elisareisrodrigues05@gmail.com") {
          return respond({ ok: false, error: "Conta sem autorização administrativa." }, 403);
        }
        sender = "owner";
        ownerId = owner.id;
      }
      const { data: liveSession, error: sessionError } = await admin.from("projeto_super_device_sessions")
        .select("id,device_id,owner_user_id,status,session_type")
        .eq("id", sessionId).eq("owner_user_id", ownerId).maybeSingle();
      if (sessionError || !liveSession || liveSession.status !== "active" ||
          !["camera_front", "camera_back", "screen_share"].includes(liveSession.session_type) ||
          (sender === "device" && liveSession.device_id !== deviceId)) {
        return respond({ ok: false, error: "Sessão de mídia não está ativa ou não pertence a este usuário." }, 409);
      }
      if (action === "webrtc_ice_config") {
        const username = Deno.env.get("METERED_TURN_USERNAME") || "";
        const credential = Deno.env.get("METERED_TURN_CREDENTIAL") || "";
        const turnConfigured = Boolean(username && credential);
        const urls = [
          "stun:stun.relay.metered.ca:80",
          "stun:stun.l.google.com:19302",
        ];
        if (turnConfigured) {
          urls.push(
            "turn:standard.relay.metered.ca:80",
            "turn:standard.relay.metered.ca:80?transport=tcp",
            "turn:standard.relay.metered.ca:443?transport=tcp",
            "turns:standard.relay.metered.ca:443?transport=tcp",
          );
        }
        const iceServers = urls.map((url) => url.startsWith("stun:")
          ? { urls: url }
          : { urls: url, username, credential });
        return respond({
          ok: true,
          iceServers,
          provider: turnConfigured ? "Metered" : "STUN fallback",
          relay: "standard.relay.metered.ca",
          turnConfigured,
          warning: turnConfigured ? null : "TURN não configurado; conexões que exigem relay podem falhar.",
        });
      }
      if (action === "webrtc_send") {
        if (!allowedTypes.includes(signalType)) return respond({ ok: false, error: "Tipo de sinal inválido." }, 400);
        const payload = body.payload;
        if (!payload || typeof payload !== "object" || Array.isArray(payload) ||
            new TextEncoder().encode(JSON.stringify(payload)).length > 256 * 1024) {
          return respond({ ok: false, error: "Conteúdo do sinal inválido ou muito grande." }, 413);
        }
        const { data, error } = await admin.from("projeto_super_webrtc_signals").insert({
          session_id: sessionId, sender, signal_type: signalType, payload,
        }).select("id").single();
        if (error || !data) return respond({ ok: false, error: "Não foi possível registrar o sinal WebRTC." }, 500);
        return respond({ ok: true, signal_id: data.id });
      }
      const afterId = Number(body.after_id || 0);
      if (!Number.isSafeInteger(afterId) || afterId < 0) return respond({ ok: false, error: "Cursor de sinal inválido." }, 400);
      const { data, error } = await admin.from("projeto_super_webrtc_signals")
        .select("id,sender,signal_type,payload,created_at")
        .eq("session_id", sessionId).neq("sender", sender).gt("id", afterId)
        .order("id", { ascending: true }).limit(100);
      if (error) return respond({ ok: false, error: "Não foi possível consultar os sinais WebRTC." }, 500);
      return respond({ ok: true, signals: data || [], next_after_id: data?.length ? data[data.length - 1].id : afterId });
    }

    if (action === "device_poll" || action === "device_sync_apps" || action === "device_decide" || action === "start_session" || action === "stop_session") {
      const device = await getDevice(String(body.device_id || ""));
      if (!device) return respond({ ok: false, error: "Chave do dispositivo inválida." }, 401);
      if (action === "device_poll") {
        const { data: requests } = await admin.from("projeto_super_control_requests").select("id,action,created_at,expires_at")
          .eq("device_id", device.id).eq("status", "pending").gt("expires_at", new Date().toISOString())
          .order("created_at", { ascending: true }).limit(10);
        return respond({ ok: true, paired: device.status === "paired", requests: requests || [] });
      }
      if (action === "device_sync_apps") {
        if (device.status !== "paired" || !device.owner_user_id) {
          return respond({ ok: false, error: "Dispositivo ainda não está vinculado." }, 403);
        }
        const apps = Array.isArray(body.apps) ? body.apps.slice(0, 300) : [];
        const rows = apps.map((app) => ({
          device_id: device.id,
          package_name: String(app?.package_name || "").trim(),
          app_label: String(app?.app_label || "").trim().slice(0, 160),
          updated_at: new Date().toISOString(),
        })).filter((app) =>
          /^[a-zA-Z0-9_]+(\.[a-zA-Z0-9_]+)+$/.test(app.package_name) &&
          app.package_name.length <= 180 && app.app_label.length > 0
        );
        const { error: deleteError } = await admin.from("projeto_super_device_apps")
          .delete().eq("device_id", device.id);
        if (deleteError) return respond({ ok: false, error: "Não foi possível atualizar a lista de aplicativos." }, 500);
        if (rows.length) {
          const { error } = await admin.from("projeto_super_device_apps").insert(rows);
          if (error) return respond({ ok: false, error: "Não foi possível salvar os aplicativos detectados." }, 500);
        }
        return respond({ ok: true, count: rows.length });
      }
      if (device.status !== "paired" || !device.owner_user_id) return respond({ ok: false, error: "Dispositivo ainda não está pareado." }, 403);
      if (action === "device_decide") {
        const requestId = String(body.request_id || "");
        const decision = body.decision === "accept" ? "accepted" : body.decision === "reject" ? "rejected" : "";
        if (!decision) return respond({ ok: false, error: "Decisão inválida." }, 400);
        const { data: request } = await admin.from("projeto_super_control_requests").update({
          status: decision, handled_at: new Date().toISOString(),
        }).eq("id", requestId).eq("device_id", device.id).eq("status", "pending")
          .gt("expires_at", new Date().toISOString()).select("id,action").maybeSingle();
        if (!request) return respond({ ok: false, error: "Solicitação expirada ou já processada." }, 409);
        if (decision === "rejected") return respond({ ok: true, decision });
        if (request.action === "stop_session") {
          const { data: activeSessions } = await admin.from("projeto_super_device_sessions")
            .select("id").eq("device_id", device.id).eq("status", "active");
          await admin.from("projeto_super_device_sessions").update({
            status: "stopped", stopped_at: new Date().toISOString(),
          }).eq("device_id", device.id).eq("status", "active");
          const stoppedIds = (activeSessions || []).map((session) => session.id);
          await clearSessionFrames(admin, stoppedIds);
          await clearAudioChunks(admin, stoppedIds, "live");
          return respond({ ok: true, decision, stopped: true });
        }
        if (request.action.startsWith("open_app:")) {
          const packageName = request.action.slice("open_app:".length);
          if (!/^[a-zA-Z0-9_]+(\.[a-zA-Z0-9_]+)+$/.test(packageName) || packageName.length > 180) {
            return respond({ ok: false, error: "Pacote de aplicativo inválido." }, 400);
          }
          return respond({ ok: true, decision, opened_app_package: packageName });
        }
        if (request.action === "remote_back" || request.action === "remote_home" ||
            /^tap:(?:[0-9]|[1-9][0-9]{1,2}|1000):(?:[0-9]|[1-9][0-9]{1,2}|1000)$/.test(request.action) ||
            (request.action.startsWith("type_text:") &&
              request.action.length > "type_text:".length &&
              request.action.length <= "type_text:".length + 2000)) {
          return respond({ ok: true, decision, remote_command: request.action });
        }
        const sessionType = request.action;
        if (!["camera_front", "camera_back", "screen_share", "microphone_live", "microphone_record"].includes(sessionType)) return respond({ ok: false, error: "Ação não suportada." }, 400);
        const { data: session, error } = await admin.from("projeto_super_device_sessions").insert({
          device_id: device.id, owner_user_id: device.owner_user_id, session_type: sessionType,
          local_consent_at: new Date().toISOString(), status: "active",
        }).select("id").single();
        if (error || !session) return respond({ ok: false, error: "Não foi possível abrir a sessão." }, 500);
        return respond({ ok: true, decision, session_id: session.id, session_type: sessionType });
      }
      const sessionId = String(body.session_id || "");
      const { error } = await admin.from("projeto_super_device_sessions").update({
        status: "stopped", stopped_at: new Date().toISOString(),
      }).eq("id", sessionId).eq("device_id", device.id).eq("status", "active");
      if (error) return respond({ ok: false, error: "Não foi possível encerrar a sessão." }, 500);
      await clearSessionFrames(admin, [sessionId]);
      await clearAudioChunks(admin, [sessionId], "live");
      return respond({ ok: true });
    }

    const owner = await getOwner();
    if (!owner) return respond({ ok: false, error: "Sessão do painel inválida. Entre novamente." }, 401);
    if ((owner.email || "").toLowerCase() !== "elisareisrodrigues05@gmail.com") {
      return respond({ ok: false, error: "Conta sem autorização administrativa." }, 403);
    }

    if (action === "list_pending_devices") {
      const { data, error } = await admin.from("projeto_super_devices")
        .select("id,device_name,status,last_seen_at,created_at")
        .is("owner_user_id", null).eq("status", "unpaired")
        .order("created_at", { ascending: false });
      if (error) return respond({ ok: false, error: "Falha ao listar novos dispositivos." }, 500);
      return respond({ ok: true, devices: data || [] });
    }

    if (action === "approve_device") {
      const deviceId = String(body.device_id || "");
      const { data, error } = await admin.from("projeto_super_devices").update({
        owner_user_id: owner.id, status: "paired", pairing_code_hash: null, pairing_expires_at: null,
      }).eq("id", deviceId).is("owner_user_id", null).eq("status", "unpaired")
        .select("id").maybeSingle();
      if (error || !data) return respond({ ok: false, error: "Dispositivo não encontrado ou já aprovado." }, 409);
      return respond({ ok: true, device_id: data.id, status: "paired" });
    }

    if (action === "list_devices") {
      const { data, error } = await admin.from("projeto_super_devices")
        .select("id,device_name,status,last_seen_at,created_at")
        .eq("owner_user_id", owner.id).order("created_at", { ascending: false });
      if (error) return respond({ ok: false, error: "Falha ao listar dispositivos." }, 500);
      const rows = data || [];
      const deviceIds = rows.map((device) => device.id);
      if (!deviceIds.length) return respond({ ok: true, devices: [] });

      // Consultas em lote: evita N+1 requests quando há vários aparelhos vinculados.
      const [appsResult, sessionsResult] = await Promise.all([
        admin.from("projeto_super_device_apps")
          .select("device_id,package_name,app_label,updated_at")
          .in("device_id", deviceIds).order("app_label", { ascending: true }),
        admin.from("projeto_super_device_sessions")
          .select("id,device_id,session_type,started_at,last_frame_at")
          .in("device_id", deviceIds).eq("owner_user_id", owner.id).eq("status", "active")
          .order("started_at", { ascending: false }),
      ]);
      if (appsResult.error || sessionsResult.error) {
        return respond({ ok: false, error: "Falha ao carregar aplicativos ou sessões dos dispositivos." }, 500);
      }
      const appsByDevice = new Map<string, unknown[]>();
      for (const app of appsResult.data || []) {
        const list = appsByDevice.get(app.device_id) || [];
        list.push({
          package_name: app.package_name,
          app_label: app.app_label,
          updated_at: app.updated_at,
        });
        appsByDevice.set(app.device_id, list);
      }
      const sessionsByDevice = new Map<string, any>();
      for (const session of sessionsResult.data || []) {
        if (!sessionsByDevice.has(session.device_id)) sessionsByDevice.set(session.device_id, session);
      }

      const devices = [];
      for (const device of rows) {
        const active = sessionsByDevice.get(device.id) || null;
        const installedApps = appsByDevice.get(device.id) || [];
        const lastActivity = device.last_seen_at || active?.started_at;
        if (active && lastActivity && Date.now() - new Date(lastActivity).getTime() > 45000) {
          await admin.from("projeto_super_device_sessions").update({
            status: "stopped", stopped_at: new Date().toISOString(),
          }).eq("id", active.id).eq("status", "active");
          await clearSessionFrames(admin, [active.id]);
          await clearAudioChunks(admin, [active.id], "live");
          devices.push({ ...device, active_session: null, apps: installedApps });
        } else {
          devices.push({ ...device, active_session: active, apps: installedApps });
        }
      }
      return respond({ ok: true, devices });
    }

    if (action === "request_control") {
      const deviceId = String(body.device_id || "");
      const requestedAction = String(body.control || "");
      const isOpenApp = requestedAction.startsWith("open_app:");
      const appPackage = isOpenApp ? requestedAction.slice("open_app:".length) : "";
      const validPackage = /^[a-zA-Z0-9_]+(\.[a-zA-Z0-9_]+)+$/.test(appPackage) && appPackage.length <= 180;
      const isRemoteNavigation = requestedAction === "remote_back" || requestedAction === "remote_home";
      const isTap = /^tap:(?:[0-9]|[1-9][0-9]{1,2}|1000):(?:[0-9]|[1-9][0-9]{1,2}|1000)$/.test(requestedAction);
      const isTypeText = requestedAction.startsWith("type_text:") &&
        requestedAction.length > "type_text:".length &&
        requestedAction.length <= "type_text:".length + 2000;
      if (!["camera_front", "camera_back", "screen_share", "microphone_live", "microphone_record", "stop_session"].includes(requestedAction) &&
          !(isOpenApp && validPackage) && !isRemoteNavigation && !isTap && !isTypeText) {
        return respond({ ok: false, error: "Controle inválido ou pacote de aplicativo inválido." }, 400);
      }
      const { data: device } = await admin.from("projeto_super_devices").select("id,last_seen_at")
        .eq("id", deviceId).eq("owner_user_id", owner.id).eq("status", "paired").maybeSingle();
      if (!device) return respond({ ok: false, error: "Dispositivo não encontrado." }, 404);
      const { data: activeSession } = await admin.from("projeto_super_device_sessions").select("id,session_type,last_frame_at,started_at")
        .eq("device_id", deviceId).eq("owner_user_id", owner.id).eq("status", "active").limit(1);
      let hasActiveSession = Boolean(activeSession?.length);
      const activeLastActivity = device.last_seen_at || activeSession?.[0]?.started_at;
      if (hasActiveSession && activeLastActivity &&
          Date.now() - new Date(activeLastActivity).getTime() > 45000) {
        await admin.from("projeto_super_device_sessions").update({
          status: "stopped", stopped_at: new Date().toISOString(),
        }).eq("id", activeSession[0].id).eq("status", "active");
        await clearSessionFrames(admin, [activeSession[0].id]);
        await clearAudioChunks(admin, [activeSession[0].id], "live");
        hasActiveSession = false;
      }
      if (requestedAction === "stop_session" && !hasActiveSession) {
        return respond({ ok: false, error: "Não há sessão ativa para encerrar." }, 409);
      }
      if (isTypeText && (!hasActiveSession || activeSession?.[0]?.session_type !== "screen_share")) {
        return respond({ ok: false, error: "Inicie e mantenha o compartilhamento de tela antes de enviar texto remoto." }, 409);
      }
      const isRemoteCommand = isRemoteNavigation || isTap || isTypeText;
      if (requestedAction !== "stop_session" && hasActiveSession &&
          !(isRemoteCommand && activeSession?.[0]?.session_type === "screen_share")) {
        return respond({ ok: false, error: "Encerre a sessão atual antes de iniciar outra." }, 409);
      }
      const { data: existing } = await admin.from("projeto_super_control_requests").select("id")
        .eq("device_id", deviceId).eq("status", "pending").gt("expires_at", new Date().toISOString()).limit(1);
      if (existing?.length) return respond({ ok: false, error: "Já existe uma solicitação aguardando o aparelho." }, 409);
      const { data: request, error } = await admin.from("projeto_super_control_requests").insert({
        device_id: deviceId, owner_user_id: owner.id, action: requestedAction,
      }).select("id,action,created_at,expires_at").single();
      return error ? respond({ ok: false, error: "Falha ao criar solicitação." }, 500) : respond({ ok: true, request });
    }

    if (action === "latest_audio_chunk") {
      const sessionId = String(body.session_id || "");
      const { data: session } = await admin.from("projeto_super_device_sessions")
        .select("id,status,session_type").eq("id", sessionId).eq("owner_user_id", owner.id).maybeSingle();
      if (!session || session.session_type !== "microphone_live") return respond({ ok: false, error: "Sessão de áudio ao vivo não encontrada." }, 404);
      if (session.status !== "active") return respond({ ok: true, chunk: null, session_status: "stopped" });
      const { data: chunk } = await admin.from("projeto_super_audio_chunks").select("storage_path,created_at,chunk_index,duration_ms")
        .eq("session_id", sessionId).eq("owner_user_id", owner.id).eq("mode", "live")
        .order("chunk_index", { ascending: false }).limit(1).maybeSingle();
      if (!chunk) return respond({ ok: true, chunk: null, session_status: "active" });
      const { data: signed, error } = await admin.storage.from(BUCKET).createSignedUrl(chunk.storage_path, 30);
      if (error || !signed) return respond({ ok: false, error: "Não foi possível abrir o trecho de áudio." }, 500);
      return respond({ ok: true, chunk: { url: signed.signedUrl, chunk_index: chunk.chunk_index, duration_ms: chunk.duration_ms, created_at: chunk.created_at }, session_status: "active" });
    }

    if (action === "list_audio_recordings") {
      const deviceId = String(body.device_id || "");
      const { data: device } = await admin.from("projeto_super_devices").select("id")
        .eq("id", deviceId).eq("owner_user_id", owner.id).maybeSingle();
      if (!device) return respond({ ok: false, error: "Dispositivo não encontrado." }, 404);
      const { data: recordings, error } = await admin.from("projeto_super_device_sessions")
        .select("id,started_at,stopped_at").eq("device_id", deviceId).eq("owner_user_id", owner.id)
        .eq("session_type", "microphone_record").eq("status", "stopped")
        .order("started_at", { ascending: false }).limit(10);
      if (error) return respond({ ok: false, error: "Falha ao listar gravações." }, 500);
      return respond({ ok: true, recordings: recordings || [] });
    }

    if (action === "list_audio_recording") {
      const sessionId = String(body.session_id || "");
      const { data: session } = await admin.from("projeto_super_device_sessions")
        .select("id,status,session_type").eq("id", sessionId).eq("owner_user_id", owner.id).maybeSingle();
      if (!session || session.session_type !== "microphone_record" || session.status !== "stopped") {
        return respond({ ok: false, error: "Gravação indisponível ou ainda em andamento." }, 404);
      }
      const { data: chunks, error } = await admin.from("projeto_super_audio_chunks")
        .select("storage_path,chunk_index,duration_ms,created_at").eq("session_id", sessionId)
        .eq("owner_user_id", owner.id).eq("mode", "recording").order("chunk_index", { ascending: true });
      if (error) return respond({ ok: false, error: "Falha ao listar a gravação." }, 500);
      const items = [];
      for (const chunk of chunks || []) {
        const { data: signed, error: signError } = await admin.storage.from(BUCKET).createSignedUrl(chunk.storage_path, 300);
        if (!signError && signed) items.push({ url: signed.signedUrl, chunk_index: chunk.chunk_index, duration_ms: chunk.duration_ms });
      }
      return respond({ ok: true, chunks: items });
    }

    if (action === "latest_frame") {
      const sessionId = String(body.session_id || "");
      const { data: session } = await admin.from("projeto_super_device_sessions").select("id,device_id,status")
        .eq("id", sessionId).eq("owner_user_id", owner.id).maybeSingle();
      if (!session) return respond({ ok: false, error: "Sessão não encontrada." }, 404);
      if (session.status !== "active") return respond({ ok: true, frame: null, session_status: "stopped" });
      const { data: frame } = await admin.from("projeto_super_live_frames").select("storage_path,created_at")
        .eq("session_id", sessionId).eq("owner_user_id", owner.id)
        .order("created_at", { ascending: false }).limit(1).maybeSingle();
      if (!frame) return respond({ ok: true, frame: null, session_status: session.status });
      const { data: signed, error } = await admin.storage.from(BUCKET).createSignedUrl(frame.storage_path, 30);
      if (error || !signed) return respond({ ok: false, error: "Não foi possível abrir o quadro." }, 500);
      return respond({ ok: true, frame: { url: signed.signedUrl, created_at: frame.created_at }, session_status: session.status });
    }

    if (action === "remove_device") {
      const deviceId = String(body.device_id || "");
      if (!deviceId) return respond({ ok: false, error: "Telefone inválido." }, 400);
      const { data: ownedDevice, error: deviceLookupError } = await admin.from("projeto_super_devices")
        .select("id").eq("id", deviceId).eq("owner_user_id", owner.id).maybeSingle();
      if (deviceLookupError) return respond({ ok: false, error: "Não foi possível validar o telefone." }, 500);
      if (!ownedDevice) return respond({ ok: false, error: "Telefone não encontrado ou não pertence à conta administrativa." }, 404);

      const { data: sessions, error: sessionError } = await admin.from("projeto_super_device_sessions")
        .select("id").eq("device_id", deviceId);
      if (sessionError) return respond({ ok: false, error: "Não foi possível localizar as sessões do telefone." }, 500);
      const sessionIds = (sessions || []).map((session) => session.id);

      // Apaga primeiro arquivos e registros dependentes para evitar órfãos no Storage.
      await clearSessionFrames(admin, sessionIds);
      await clearAudioChunks(admin, sessionIds);

      const [requestsResult, appsResult, sessionsResult] = await Promise.all([
        admin.from("projeto_super_control_requests").delete().eq("device_id", deviceId),
        admin.from("projeto_super_device_apps").delete().eq("device_id", deviceId),
        admin.from("projeto_super_device_sessions").delete().eq("device_id", deviceId),
      ]);
      if (requestsResult.error || appsResult.error || sessionsResult.error) {
        return respond({ ok: false, error: "A limpeza dos dados do telefone ficou incompleta. O registro principal foi preservado para permitir nova tentativa." }, 500);
      }

      const { error: deleteDeviceError } = await admin.from("projeto_super_devices")
        .delete().eq("id", deviceId).eq("owner_user_id", owner.id);
      if (deleteDeviceError) return respond({ ok: false, error: "Os dados associados foram limpos, mas não foi possível remover o registro do telefone." }, 500);
      return respond({ ok: true, removed: true, device_id: deviceId });
    }

    if (action === "revoke_device") {
      const deviceId = String(body.device_id || "");
      const { data: ownedDevice } = await admin.from("projeto_super_devices").select("id")
        .eq("id", deviceId).eq("owner_user_id", owner.id).maybeSingle();
      if (!ownedDevice) return respond({ ok: false, error: "Dispositivo não encontrado." }, 404);
      const { data: sessions, error: sessionError } = await admin.from("projeto_super_device_sessions")
        .select("id").eq("device_id", deviceId);
      if (sessionError) return respond({ ok: false, error: "Não foi possível localizar as sessões do aparelho." }, 500);
      const sessionIds = (sessions || []).map((session) => session.id);
      const stoppedAt = new Date().toISOString();
      const [stopResult, pendingResult, appsResult] = await Promise.all([
        admin.from("projeto_super_device_sessions").update({ status: "stopped", stopped_at: stoppedAt })
          .eq("device_id", deviceId).eq("status", "active"),
        admin.from("projeto_super_control_requests").delete().eq("device_id", deviceId).eq("status", "pending"),
        admin.from("projeto_super_device_apps").delete().eq("device_id", deviceId),
      ]);
      if (stopResult.error || pendingResult.error || appsResult.error) {
        return respond({ ok: false, error: "Não foi possível concluir o cancelamento do vínculo. Tente novamente." }, 500);
      }
      await clearSessionFrames(admin, sessionIds);
      await clearAudioChunks(admin, sessionIds);
      const { error } = await admin.from("projeto_super_devices").update({ status: "revoked" })
        .eq("id", deviceId).eq("owner_user_id", owner.id);
      return error ? respond({ ok: false, error: "Falha ao cancelar o vínculo." }, 500) : respond({ ok: true });
    }

    return respond({ ok: false, error: "Ação desconhecida." }, 400);
  } catch (error) {
    console.error("projeto-super-controle:", error);
    return respond({ ok: false, error: "Erro interno ao processar a solicitação." }, 500);
  }
});