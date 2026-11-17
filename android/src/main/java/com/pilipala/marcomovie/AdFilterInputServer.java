package com.pilipala.marcomovie;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/**
 * Short-lived, token-scoped LAN HTTP server for receiving ad-filter rules text from a phone
 * browser (QR scan). The TV remote cannot comfortably paste long JSON, so the phone acts as the
 * text entry surface while the TV only renders a QR code and a short-lived local URL.
 */
final class AdFilterInputServer implements Closeable {
    static final int MAX_BODY_BYTES = 512 * 1024;

    interface Listener {
        void onRulesText(String rules) throws Exception;
    }

    final ServerSocket socket;
    final String path;
    final long expires;
    final Listener listener;
    volatile boolean closed;
    final ThreadPoolExecutor requests =
            new ThreadPoolExecutor(
                    0,
                    3,
                    10,
                    TimeUnit.SECONDS,
                    new SynchronousQueue<>(),
                    r -> {
                        Thread t = new Thread(r, "movie-adfilter-qr");
                        t.setDaemon(true);
                        return t;
                    });

    AdFilterInputServer(Listener handler) throws IOException {
        this(handler, TimeUnit.MINUTES.toMillis(5), new InetSocketAddress("0.0.0.0", 0));
    }

    AdFilterInputServer(Listener handler, long ttlMs, InetSocketAddress bind) throws IOException {
        if (handler == null) throw new IllegalArgumentException("listener required");
        listener = handler;
        expires = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ttlMs);
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder token = new StringBuilder(32);
        for (byte b : bytes) token.append(String.format(Locale.ROOT, "%02x", b & 255));
        path = "/adfilter/" + token;
        socket = new ServerSocket();
        socket.bind(bind);
        Thread accept =
                new Thread(
                        () -> {
                            while (!closed) {
                                try {
                                    Socket client = socket.accept();
                                    try {
                                        requests.execute(() -> serve(client));
                                    } catch (RejectedExecutionException full) {
                                        client.close();
                                    }
                                } catch (IOException error) {
                                    if (!closed) close();
                                }
                            }
                        },
                        "movie-adfilter-qr-listener");
        accept.setDaemon(true);
        accept.start();
    }

    String address(String host) {
        return "http://" + host + ":" + socket.getLocalPort() + path;
    }

    int port() {
        return socket.getLocalPort();
    }

    boolean expired() {
        return closed || System.nanoTime() >= expires;
    }

    void serve(Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(20000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            InetAddress peer = s.getInetAddress();
            if (!ConfigTransferServer.isAllowedLanPeer(peer)) {
                reply(out, 403, "text/plain", "仅允许同一局域网设备访问");
                return;
            }
            String requestLine = ConfigTransferServer.line(in, 4096);
            String[] first = requestLine.split(" ");
            if (first.length != 3) return;
            Map<String, String> headers = new HashMap<>();
            int total = 0;
            for (; ; ) {
                String row = ConfigTransferServer.line(in, 4096);
                total += row.length();
                if (total > 16384) throw new IOException("Header limit");
                if (row.isEmpty()) break;
                int colon = row.indexOf(':');
                if (colon > 0) {
                    headers.put(
                            row.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            row.substring(colon + 1).trim());
                }
            }
            String reqPath = first[1];
            int qIdx = reqPath.indexOf('?');
            if (qIdx >= 0) reqPath = reqPath.substring(0, qIdx);
            boolean isMain = reqPath.equals(path);
            boolean isPush = reqPath.equals(path + "/push");
            if (!isMain && !isPush) {
                reply(out, 404, "text/plain", "页面不存在");
                return;
            }
            if (expired()) {
                reply(out, 410, "text/plain", "二维码已过期，请在电视上重新打开去广告规则输入");
                return;
            }
            String host = headers.containsKey("host") ? headers.get("host") : "";
            String expected = s.getLocalAddress().getHostAddress() + ":" + socket.getLocalPort();
            if (!host.equals(expected)) {
                reply(out, 403, "text/plain", "请使用电视屏幕上显示的扫码地址");
                return;
            }
            String method = first[0];
            if ("GET".equals(method)) {
                if (isMain) {
                    reply(out, 200, "text/html", renderPage(""));
                    return;
                }
                reply(out, 405, "text/plain", "请求方式不支持");
                return;
            }
            if (!"POST".equals(method)) {
                reply(out, 405, "text/plain", "请求方式不支持");
                return;
            }
            String secFetchSite = headers.get("sec-fetch-site");
            if (secFetchSite != null
                    && !secFetchSite.equalsIgnoreCase("same-origin")
                    && !secFetchSite.equalsIgnoreCase("none")) {
                reply(out, 403, "text/plain", "跨站请求已拦截");
                return;
            }
            String origin = headers.get("origin");
            if (origin != null && !origin.equals("http://" + host)) {
                reply(out, 403, "text/plain", "来源校验未通过");
                return;
            }
            if (headers.containsKey("transfer-encoding")) {
                reply(out, 415, "text/plain", "不支持分块编码");
                return;
            }
            String contentType =
                    headers.containsKey("content-type")
                            ? headers.get("content-type").toLowerCase(Locale.ROOT)
                            : "";
            boolean isJson = contentType.startsWith("application/json");
            boolean isForm = contentType.startsWith("application/x-www-form-urlencoded");
            if (!isJson && !isForm) {
                reply(out, 415, "text/plain", "请通过扫码页面提交规则");
                return;
            }
            int length;
            try {
                length =
                        Integer.parseInt(
                                headers.containsKey("content-length")
                                        ? headers.get("content-length")
                                        : "-1");
            } catch (NumberFormatException e) {
                length = -1;
            }
            if (length < 0 || length > MAX_BODY_BYTES) {
                reply(out, 413, "text/plain", "规则内容过长");
                return;
            }
            byte[] body = new byte[length];
            for (int n = 0; n < length; ) {
                int got = in.read(body, n, length - n);
                if (got < 0) throw new EOFException();
                n += got;
            }
            String rawText = new String(body, "UTF-8");
            String rules = "";
            if (isJson) {
                rules = new JSONObject(rawText).optString("rules", "");
            } else {
                for (String pair : rawText.split("&")) {
                    if (pair.isEmpty()) continue;
                    String[] kv = pair.split("=", 2);
                    String k = URLDecoder.decode(kv[0], "UTF-8");
                    if ("rules".equals(k)) {
                        rules = kv.length == 2 ? URLDecoder.decode(kv[1], "UTF-8") : "";
                    }
                }
            }
            if (rules.trim().isEmpty()) {
                if (isJson || isPush) {
                    JSONObject err = new JSONObject();
                    err.put("ok", false);
                    err.put("message", "请粘贴去广告规则内容");
                    reply(out, 400, "application/json", err.toString());
                } else {
                    reply(out, 400, "text/html", renderPage("请粘贴去广告规则内容"));
                }
                return;
            }
            try {
                listener.onRulesText(rules);
            } catch (Exception error) {
                String message = error.getMessage() == null ? "规则格式无法识别" : error.getMessage();
                if (isJson || isPush) {
                    JSONObject err = new JSONObject();
                    err.put("ok", false);
                    err.put("message", message);
                    reply(out, 400, "application/json", err.toString());
                } else {
                    reply(out, 400, "text/html", renderPage(message));
                }
                return;
            }
            if (isJson || isPush) {
                JSONObject res = new JSONObject();
                res.put("ok", true);
                res.put("message", "已在电视导入去广告规则，可以关闭此页面");
                reply(out, 200, "application/json", res.toString());
            } else {
                reply(out, 200, "text/html", renderPage("已在电视导入去广告规则，可以关闭此页面"));
            }
        } catch (Exception ignored) {
        }
    }

    private String renderPage(String bannerMessage) {
        String escPath = escapeHtml(path);
        String escMsg = escapeHtml(bannerMessage == null ? "" : bannerMessage);
        return "<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
                + "<title>Marco Movie 去广告规则输入</title>"
                + "<style>"
                + "*{box-sizing:border-box}"
                + "body{margin:0;background:#0e0f14;color:#f5f5f7;font:16px/1.5 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;padding:20px}"
                + "main{max-width:560px;margin:2vh auto;background:#191b23;border:1px solid rgba(255,255,255,.09);border-radius:20px;padding:22px;box-shadow:0 18px 48px rgba(0,0,0,.45)}"
                + ".eyebrow{font-size:12px;letter-spacing:.12em;color:#7cb8ff;font-weight:600}"
                + "h1{margin:6px 0 8px;font-size:22px}"
                + "p.sub{margin:0 0 16px;color:#9ea2b0;font-size:14px}"
                + "textarea{width:100%;min-height:240px;padding:14px 16px;font-size:14px;line-height:1.55;color:#fff;background:#252834;border:1px solid rgba(255,255,255,.14);border-radius:14px;outline:none;resize:vertical;font-family:ui-monospace,'SF Mono',Menlo,Consolas,monospace}"
                + "textarea:focus{border-color:#5ac8fa;background:#2a2e3d}"
                + "button{width:100%;margin-top:14px;padding:13px 14px;font-size:16px;font-weight:600;border-radius:13px;border:0;cursor:pointer;background:#f5f5f7;color:#101013}"
                + ".msg{margin-top:14px;padding:11px 14px;border-radius:12px;background:rgba(90,200,250,.14);color:#b7e6ff;font-size:14px;display:"
                + (escMsg.isEmpty() ? "none" : "block")
                + "}"
                + ".msg.err{background:rgba(255,99,99,.16);color:#ffb8b8}"
                + "</style></head><body><main>"
                + "<div class='eyebrow'>MARCO MOVIE · AD FILTER</div>"
                + "<h1>手机输入 · 去广告规则</h1>"
                + "<p class='sub'>粘贴 TVBox XC.json、anti-AD 域名列表、m3u8-proxy 规则，或本应用导出的规则 JSON，点击发送即可导入到电视。</p>"
                + "<form id='f' method='post' action='"
                + escPath
                + "'>"
                + "<textarea id='rules' name='rules' autocomplete='off' spellcheck='false' placeholder='在此粘贴规则内容…' autofocus></textarea>"
                + "<button type='submit'>发送到电视</button>"
                + "</form>"
                + "<div id='msg' class='msg'>"
                + escMsg
                + "</div>"
                + "</main><script>"
                + "(function(){"
                + "var f=document.getElementById('f'),ta=document.getElementById('rules'),msg=document.getElementById('msg');"
                + "function show(t,err){msg.style.display='block';msg.className='msg'+(err?' err':'');msg.textContent=t;}"
                + "function send(){"
                + "var val=ta.value;"
                + "if(!val||!val.trim()){show('请粘贴去广告规则内容',true);return;}"
                + "fetch('"
                + escPath
                + "/push',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({rules:val})})"
                + ".then(function(r){return r.json().then(function(j){return{status:r.status,body:j};});})"
                + ".then(function(x){show(x.body.message||(x.body.ok?'已发送':'发送失败'),!x.body.ok);})"
                + ".catch(function(){f.submit();});"
                + "}"
                + "f.addEventListener('submit',function(e){if(window.fetch){e.preventDefault();send();}});"
                + "})();"
                + "</script></body></html>";
    }

    static String escapeHtml(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':
                    out.append("&amp;");
                    break;
                case '<':
                    out.append("&lt;");
                    break;
                case '>':
                    out.append("&gt;");
                    break;
                case '"':
                    out.append("&quot;");
                    break;
                case '\'':
                    out.append("&#39;");
                    break;
                default:
                    out.append(c);
                    break;
            }
        }
        return out.toString();
    }

    private static void reply(OutputStream out, int code, String type, String body)
            throws IOException {
        byte[] bytes = body.getBytes("UTF-8");
        ByteArrayOutputStream head = new ByteArrayOutputStream(256);
        String status =
                code == 200
                        ? "OK"
                        : code == 400
                                ? "Bad Request"
                                : code == 403
                                        ? "Forbidden"
                                        : code == 404
                                                ? "Not Found"
                                                : code == 405
                                                        ? "Method Not Allowed"
                                                        : code == 410
                                                                ? "Gone"
                                                                : code == 413
                                                                        ? "Payload Too Large"
                                                                        : "Unsupported Media Type";
        head.write(
                ("HTTP/1.1 "
                                + code
                                + " "
                                + status
                                + "\r\nContent-Type: "
                                + type
                                + "; charset=utf-8\r\nContent-Length: "
                                + bytes.length
                                + "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n")
                        .getBytes("US-ASCII"));
        out.write(head.toByteArray());
        out.write(bytes);
        out.flush();
    }

    @Override
    public void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        requests.shutdownNow();
    }
}
