import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const SITE_ORIGIN = "https://projeto-super-eta.vercel.app";
const BUCKET = "projeto-super-uploads";
const MAX_FRAME_BYTES = 2 * 1024 * 1024;
const cors = {
  "Access-Control-Allow-Origin": SITE_ORIGIN,
  "Access-Control-Allow-Headers": "authorization, apikey, x-client-info, content-type, x-device-secret",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Vary": "Origin",
};

function reply(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...cors, "Content-Type": "application/json", "Cache-Control": "no-store" },
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

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  const origin = req.headers.get("origin");
  if (origin && origin !== SITE_ORIGIN) return reply({ ok: false, error: "Origem não autorizada." }, 403);
  if (req.method !== "POST") return reply({ ok: false, error: "Método não permitido." }, 405);

  try {
    const projectUrl = Deno.env.get("SUPABASE_URL") || "";
    const secretMap = JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") || "{}");
    const secretKey = secretMap.default;
    if (!projectUrl || !secretKey) return reply({ ok: false, error: "Configuração segura indisponível." }, 500);
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
      const file = form.get("frame");
      if (!device || device.status !== "paired" || !device.owner_user_id) return reply({ ok: false, error: "Dispositivo não autenticado ou não pareado." }, 401);
      if (!(file instanceof File) || file.size < 1 || file.size > MAX_FRAME_BYTES) return reply({ ok: false, error: "Quadro inválido ou acima de 2 MB." }, 413);
      if (!["image/jpeg", "image/png"].includes(file.type)) return reply({ ok: false, error: "Somente JPEG ou PNG." }, 415);
      const { data: session } = await admin.from("projeto_super_device_sessions").select("id,status,owner_user_id")
        .eq("id", sessionId).eq("device_id", deviceId).maybeSingle();
      if (!session || session.status !== "active" || session.owner_user_id !== device.owner_user_id) return reply({ ok: false, error: "Sessão não está ativa." }, 409);
      const ext = file.type === "image/png" ? "png" : "jpg";
      const path = "live/" + deviceId + "/" + sessionId + "/" + Date.now() + "-" + randomHex(5) + "." + ext;
      const bytes = new Uint8Array(await file.arrayBuffer());
      const { error: uploadError } = await admin.storage.from(BUCKET).upload(path, bytes, {
        contentType: file.type, cacheControl: "0", upsert: false,
      });
      if (uploadError) return reply({ ok: false, error: "Falha ao armazenar quadro." }, 502);
      const { error: rowError } = await admin.from("projeto_super_live_frames").insert({
        device_id: deviceId, session_id: sessionId, owner_user_id: device.owner_user_id,
        storage_path: path, mime_type: file.type,
      });
      if (rowError) {
        await admin.storage.from(BUCKET).remove([path]);
        return reply({ ok: false, error: "Falha ao registrar quadro." }, 500);
      }
      // Mantém apenas os 90 quadros mais recentes por sessão.
      const { data: oldFrames } = await admin.from("projeto_super_live_frames").select("id,storage_path")
        .eq("session_id", sessionId).order("created_at", { ascending: false }).range(90, 500);
      if (oldFrames?.length) {
        await admin.storage.from(BUCKET).remove(oldFrames.map((x) => x.storage_path));
        await admin.from("projeto_super_live_frames").delete().in("id", oldFrames.map((x) => x.id));
      }
      return reply({ ok: true, received_at: new Date().toISOString() }, 201);
    }

    const body = await req.json();
    const action = String(body?.action || "");

    if (action === "register_device") {
      if (deviceSecret.length < 40) return reply({ ok: false, error: "Chave de dispositivo inválida." }, 400);
      const deviceId = String(body.device_id || "");
      const code = String(10000000 + (parseInt(randomHex(4), 16) % 90000000));
      const codeHash = await digest(code);
      let row;
      if (deviceId) {
        const { data, error } = await admin.from("projeto_super_devices").update({
          pairing_code_hash: codeHash, pairing_expires_at: new Date(Date.now() + 10 * 60_000).toISOString(),
          device_name: String(body.device_name || "Dispositivo de laboratório").slice(0, 80),
        }).eq("id", deviceId).eq("device_secret_hash", deviceHash).eq("status", "unpaired").select("id").maybeSingle();
        if (error || !data) return reply({ ok: false, error: "Não foi possível renovar o código deste dispositivo." }, 401);
        row = data;
      } else {
        const { data, error } = await admin.from("projeto_super_devices").insert({
          device_secret_hash: deviceHash, pairing_code_hash: codeHash,
          pairing_expires_at: new Date(Date.now() + 10 * 60_000).toISOString(),
          device_name: String(body.device_name || "Dispositivo de laboratório").slice(0, 80),
        }).select("id").single();
        if (error || !data) return reply({ ok: false, error: "Não foi possível registrar o dispositivo." }, 500);
        row = data;
      }
      return reply({ ok: true, device_id: row.id, pairing_code: code, expires_in_seconds: 600 }, 201);
    }

    if (action === "device_poll" || action === "device_decide" || action === "start_session" || action === "stop_session") {
      const device = await getDevice(String(body.device_id || ""));
      if (!device) return reply({ ok: false, error: "Chave do dispositivo inválida." }, 401);
      if (action === "device_poll") {
        const { data: requests } = await admin.from("projeto_super_control_requests").select("id,action,created_at,expires_at")
          .eq("device_id", device.id).eq("status", "pending").gt("expires_at", new Date().toISOString())
          .order("created_at", { ascending: true }).limit(10);
        return reply({ ok: true, paired: device.status === "paired", requests: requests || [] });
      }
      if (device.status !== "paired" || !device.owner_user_id) return reply({ ok: false, error: "Dispositivo ainda não está pareado." }, 403);
      if (action === "device_decide") {
        const requestId = String(body.request_id || "");
        const decision = body.decision === "accept" ? "accepted" : body.decision === "reject" ? "rejected" : "";
        if (!decision) return reply({ ok: false, error: "Decisão inválida." }, 400);
        const { data: request } = await admin.from("projeto_super_control_requests").update({
          status: decision, handled_at: new Date().toISOString(),
        }).eq("id", requestId).eq("device_id", device.id).eq("status", "pending")
          .gt("expires_at", new Date().toISOString()).select("id,action").maybeSingle();
        if (!request) return reply({ ok: false, error: "Solicitação expirada ou já processada." }, 409);
        if (decision === "rejected") return reply({ ok: true, decision });
        if (request.action === "stop_session") {
          await admin.from("projeto_super_device_sessions").update({
            status: "stopped", stopped_at: new Date().toISOString(),
          }).eq("device_id", device.id).eq("status", "active");
          return reply({ ok: true, decision, stopped: true });
        }
        const sessionType = request.action;
        if (!["camera_front", "camera_back", "screen_share"].includes(sessionType)) return reply({ ok: false, error: "Ação não suportada." }, 400);
        const { data: session, error } = await admin.from("projeto_super_device_sessions").insert({
          device_id: device.id, owner_user_id: device.owner_user_id, session_type: sessionType,
          local_consent_at: new Date().toISOString(), status: "active",
        }).select("id").single();
        if (error || !session) return reply({ ok: false, error: "Não foi possível abrir a sessão." }, 500);
        return reply({ ok: true, decision, session_id: session.id, session_type: sessionType });
      }
      const sessionId = String(body.session_id || "");
      const { error } = await admin.from("projeto_super_device_sessions").update({
        status: "stopped", stopped_at: new Date().toISOString(),
      }).eq("id", sessionId).eq("device_id", device.id).eq("status", "active");
      return error ? reply({ ok: false, error: "Não foi possível encerrar a sessão." }, 500) : reply({ ok: true });
    }

    const owner = await getOwner();
    if (!owner) return reply({ ok: false, error: "Sessão do painel inválida. Entre novamente." }, 401);

    if (action === "pair_device") {
      const code = String(body.pairing_code || "").replace(/\D/g, "");
      if (code.length !== 8) return reply({ ok: false, error: "Informe o código de 8 dígitos." }, 400);
      const { data: device } = await admin.from("projeto_super_devices").select("id")
        .eq("pairing_code_hash", await digest(code)).gt("pairing_expires_at", new Date().toISOString())
        .eq("status", "unpaired").maybeSingle();
      if (!device) return reply({ ok: false, error: "Código inválido ou expirado." }, 404);
      const { error } = await admin.from("projeto_super_devices").update({
        owner_user_id: owner.id, status: "paired", pairing_code_hash: null, pairing_expires_at: null,
      }).eq("id", device.id).eq("status", "unpaired");
      return error ? reply({ ok: false, error: "Falha ao parear dispositivo." }, 500) : reply({ ok: true, device_id: device.id });
    }

    if (action === "list_devices") {
      const { data, error } = await admin.from("projeto_super_devices")
        .select("id,device_name,status,last_seen_at,created_at")
        .eq("owner_user_id", owner.id).order("created_at", { ascending: false });
      if (error) return reply({ ok: false, error: "Falha ao listar dispositivos." }, 500);
      const devices = [];
      for (const device of data || []) {
        const { data: active } = await admin.from("projeto_super_device_sessions")
          .select("id,session_type,started_at").eq("device_id", device.id)
          .eq("owner_user_id", owner.id).eq("status", "active")
          .order("started_at", { ascending: false }).limit(1).maybeSingle();
        devices.push({ ...device, active_session: active || null });
      }
      return reply({ ok: true, devices });
    }

    if (action === "request_control") {
      const deviceId = String(body.device_id || "");
      const requestedAction = String(body.control || "");
      if (!["camera_front", "camera_back", "screen_share", "stop_session"].includes(requestedAction)) {
        return reply({ ok: false, error: "Controle inválido." }, 400);
      }
      const { data: device } = await admin.from("projeto_super_devices").select("id")
        .eq("id", deviceId).eq("owner_user_id", owner.id).eq("status", "paired").maybeSingle();
      if (!device) return reply({ ok: false, error: "Dispositivo não encontrado." }, 404);
      const { data: existing } = await admin.from("projeto_super_control_requests").select("id")
        .eq("device_id", deviceId).eq("status", "pending").gt("expires_at", new Date().toISOString()).limit(1);
      if (existing?.length) return reply({ ok: false, error: "Já existe uma solicitação aguardando o aparelho." }, 409);
      const { data: request, error } = await admin.from("projeto_super_control_requests").insert({
        device_id: deviceId, owner_user_id: owner.id, action: requestedAction,
      }).select("id,action,created_at,expires_at").single();
      return error ? reply({ ok: false, error: "Falha ao criar solicitação." }, 500) : reply({ ok: true, request });
    }

    if (action === "latest_frame") {
      const sessionId = String(body.session_id || "");
      const { data: session } = await admin.from("projeto_super_device_sessions").select("id,device_id,status")
        .eq("id", sessionId).eq("owner_user_id", owner.id).maybeSingle();
      if (!session) return reply({ ok: false, error: "Sessão não encontrada." }, 404);
      if (session.status !== "active") return reply({ ok: true, frame: null, session_status: "stopped" });
      const { data: frame } = await admin.from("projeto_super_live_frames").select("storage_path,created_at")
        .eq("session_id", sessionId).eq("owner_user_id", owner.id)
        .order("created_at", { ascending: false }).limit(1).maybeSingle();
      if (!frame) return reply({ ok: true, frame: null, session_status: session.status });
      const { data: signed, error } = await admin.storage.from(BUCKET).createSignedUrl(frame.storage_path, 30);
      if (error || !signed) return reply({ ok: false, error: "Não foi possível abrir o quadro." }, 500);
      return reply({ ok: true, frame: { url: signed.signedUrl, created_at: frame.created_at }, session_status: session.status });
    }

    if (action === "revoke_device") {
      const deviceId = String(body.device_id || "");
      const { error } = await admin.from("projeto_super_devices").update({ status: "revoked" })
        .eq("id", deviceId).eq("owner_user_id", owner.id);
      return error ? reply({ ok: false, error: "Falha ao revogar dispositivo." }, 500) : reply({ ok: true });
    }

    return reply({ ok: false, error: "Ação desconhecida." }, 400);
  } catch (error) {
    console.error("projeto-super-controle:", error);
    return reply({ ok: false, error: "Erro interno ao processar a solicitação." }, 500);
  }
});