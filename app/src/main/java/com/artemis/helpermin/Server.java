package com.artemis.helpermin;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tiny HTTP/1.1 JSON server on 127.0.0.1. Every endpoint except /ping and /health needs header
 * X-Artemis-Token (or ?token=). Query parameters and the JSON body are merged into one
 * params object, so both "POST /focus/to {"text":"OK"}" and "/focus/to?text=OK" work.
 *
 * Threading: the accept thread only accepts and hands sockets to a small bounded pool. Accessibility
 * (binder) work runs serially on a separate thread under a time budget, so a busy TalkBack / app
 * can only produce {"success":false,"error":"timeout"}, never a hung HTTP request.
 */
class Server extends Thread {

    private static final String TAG = "HelperMin";
    static final String VERSION = "0.1.18";
    private static final int IO_TIMEOUT_MS = 5000;      // socket read/write timeout
    private static final long BINDER_BUDGET_MS = 8000;  // max time for accessibility work per request
    private static final int MAX_STUCK = 3;             // abandoned (still blocked) binder threads allowed

    private final int port;
    private volatile boolean running = true;
    private volatile ServerSocket serverSocket;
    private final long startMs = System.currentTimeMillis();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong timeouts = new AtomicLong();
    private final AtomicInteger stuck = new AtomicInteger();
    private final ExecutorService pool = new ThreadPoolExecutor(6, 6, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(32));
    private volatile ExecutorService binder = newBinder();

    Server(int port) {
        super("HelperMinServer");
        this.port = port;
    }

    private static ExecutorService newBinder() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(4));
    }

    @Override
    public void run() {
        while (running) {
            ServerSocket ss = null;
            try {
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 20);
                serverSocket = ss;
                while (running) {
                    final Socket s = ss.accept();
                    try {
                        pool.execute(new Runnable() {
                            @Override
                            public void run() {
                                handle(s);
                            }
                        });
                    } catch (Throwable t) { // pool queue full: drop this client, keep serving
                        try { s.close(); } catch (Exception ignored) {}
                    }
                }
            } catch (Throwable t) {
                if (running) Log.e(TAG, "Accept loop error, restarting", t);
            } finally {
                try { if (ss != null) ss.close(); } catch (Exception ignored) {}
            }
            if (running) {
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
            }
        }
    }

    void shutdown() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        pool.shutdownNow();
        binder.shutdownNow();
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0 && c != '\n') {
            if (c != '\r') b.write(c);
            if (b.size() > 16384) throw new IllegalStateException("line too long");
        }
        if (c < 0 && b.size() == 0) return null;
        return new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private void handle(Socket s) {
        try {
            requests.incrementAndGet();
            s.setSoTimeout(IO_TIMEOUT_MS);
            InputStream in = new BufferedInputStream(s.getInputStream(), 4096);
            OutputStream out = s.getOutputStream();
            String first = readLine(in);
            if (first == null) return;
            String[] parts = first.split(" ");
            if (parts.length < 2) {
                send(out, 400, error("bad request"));
                return;
            }
            String target = parts[1];
            final String httpMethod = parts[0].toUpperCase(Locale.ROOT);

            int len = 0;
            String token = null;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String val = line.substring(colon + 1).trim();
                if (name.equals("content-length")) {
                    try { len = Integer.parseInt(val); } catch (Exception ignored) {}
                } else if (name.equals("x-artemis-token")) {
                    token = val;
                }
            }
            if (len < 0 || len > 1024 * 1024) {
                send(out, 413, error("body too large"));
                return;
            }
            byte[] body = new byte[len];
            int got = 0;
            while (got < len) {
                int r = in.read(body, got, len - got);
                if (r < 0) break;
                got += r;
            }

            JSONObject params = new JSONObject();
            String path = target;
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                for (String pair : target.substring(q + 1).split("&")) {
                    if (pair.isEmpty()) continue;
                    int eq = pair.indexOf('=');
                    String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), "UTF-8");
                    String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                    params.put(k, v);
                }
            }
            if (got > 0) {
                String text = new String(body, 0, got, StandardCharsets.UTF_8).trim();
                if (!text.isEmpty()) {
                    JSONObject j = new JSONObject(text);
                    java.util.Iterator<String> it = j.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        params.put(k, j.get(k));
                    }
                }
            }
            if (token == null) token = params.optString("token", null);

            if (path.equals("/health")) { // no auth, no binder work: pure liveness probe
                JSONObject r = new JSONObject();
                r.put("success", true);
                r.put("version", VERSION);
                r.put("uptime_ms", System.currentTimeMillis() - startMs);
                r.put("requests", requests.get());
                r.put("binder_timeouts", timeouts.get());
                r.put("binder_stuck_threads", stuck.get());
                r.put("danger_lock", Guard.dangerLock);
                send(out, 200, r);
                return;
            }
            if (path.equals("/ping") || path.equals("/")) {
                JSONObject r = new JSONObject();
                r.put("success", true);
                r.put("service", "ArtemisHelperMin");
                r.put("port", port);
                r.put("connected", HelperService.get() != null);
                r.put("token_set", TokenReceiver.isSet());
                r.put("authenticated", TokenReceiver.matches(token));
                send(out, 200, r);
                return;
            }
            if (!TokenReceiver.matches(token)) {
                JSONObject e = error(TokenReceiver.isSet()
                        ? "unauthorized: wrong or missing X-Artemis-Token"
                        : "unauthorized: no token set (see README)");
                send(out, 401, e);
                return;
            }
            HelperService svc = HelperService.get();
            if (svc == null) {
                send(out, 503, error("accessibility service not connected"));
                return;
            }
            if (!path.matches("/focus/(current|next|prev|swipe|to|first|last)|/activate|/activate/read|/act/at-text"
                    + "|/actions|/actions/run|/sweep|/sweep/last|/sweep/stop|/inspect|/scroll"
                    + "|/global|/type|/popup|/guard/status|/guard/reset|/fix/ghost-pointers|/fix/ghost-pointers/info")) {
                send(out, 404, error("unknown endpoint: " + path));
                return;
            }
            if (path.equals("/guard/status")) {
                send(out, 200, Guard.status());
                return;
            }
            if (path.equals("/guard/reset")) {
                if (!"user-approved".equals(params.optString("confirm", ""))) {
                    send(out, 200, error("need {confirm:'user-approved'} (only after the user agreed)"));
                    return;
                }
                Guard.dangerLock = false;
                Guard.lockPhrase = null;
                JSONObject r = new JSONObject();
                r.put("success", true);
                r.put("danger_lock", false);
                send(out, 200, r);
                return;
            }
            if (path.equals("/fix/ghost-pointers/info")) { // static usage/limits text, no accessibility work
                send(out, 200, GhostFix.info());
                return;
            }
            if (path.equals("/fix/ghost-pointers") && !httpMethod.equals("POST")) {
                send(out, 405, error("use POST /fix/ghost-pointers (GET /fix/ghost-pointers/info for usage)"));
                return;
            }
            if (Guard.dangerLock && !path.matches("/popup|/inspect|/sweep/stop")) {
                send(out, 200, Guard.lockedError());
                return;
            }
            if (path.equals("/sweep/last")) {
                JSONObject last = Nav.lastSweep;
                send(out, 200, last != null ? last : error("no sweep yet"));
                return;
            }
            if (path.equals("/sweep/stop")) {
                Nav.sweepStop = true;
                JSONObject r = new JSONObject();
                r.put("success", true);
                r.put("sweeping", Nav.sweeping);
                send(out, 200, r);
                return;
            }
            if (Nav.sweeping) { // binder thread is busy with a long sweep: fail fast instead of queueing
                send(out, 200, error("busy: sweep in progress (GET /sweep/last later, POST /sweep/stop to abort)"));
                return;
            }
            String modeStr = params.optString("mode", "plain").toLowerCase(Locale.ROOT);
            if (!modeStr.isEmpty() && !modeStr.equals("plain") && !modeStr.equals("tb")
                    && !modeStr.equals("tbx") && !modeStr.equals("swipe")) {
                send(out, 200, error("bad mode: " + modeStr + " (plain|tb|tbx|swipe)"));
                return;
            }
            final Nav nav = new Nav(svc, modeStr.equals("tb") || modeStr.equals("tbx") || modeStr.equals("swipe"),
                    modeStr.equals("tbx") || modeStr.equals("swipe"), modeStr.equals("swipe"));
            final String fpath = path;
            final HelperService fsvc = svc;
            final JSONObject fparams = params;
            long budget = BINDER_BUDGET_MS;
            if (path.equals("/sweep")) budget = Nav.sweepBudgetMs(params);
            else if (path.equals("/scroll")) budget = 30000;
            else if (path.equals("/fix/ghost-pointers")) budget = 6000;
            else if (path.equals("/activate/read")) budget = BINDER_BUDGET_MS + 5000;
            final long fbudget = budget;
            JSONObject res = runBinder(budget, new Callable<JSONObject>() {
                @Override
                public JSONObject call() throws Exception {
                    String kind = fparams.optString("kind", "any").toLowerCase(Locale.ROOT);
                    boolean anyKind = kind.isEmpty() || kind.equals("any");
                    if (!anyKind && !kind.matches("heading|button|link|control")) {
                        return error("bad kind: " + kind + " (heading|button|link|control|any)");
                    }
                    if (fpath.matches("/focus/(next|prev|first|last)")) { // hack-detect before moving
                        JSONObject d = nav.checkDanger();
                        if (d != null) return d;
                    }
                    JSONObject rr;
                    switch (fpath) {
                        case "/sweep": return nav.sweep(fparams, fbudget - 3000);
                        case "/inspect": return nav.inspect(fparams);
                        case "/scroll": return nav.scroll(fparams);
                        case "/global": return nav.global(fparams);
                        case "/type": return nav.type(fparams);
                        case "/popup": return nav.popup();
                        case "/fix/ghost-pointers": return GhostFix.run(fsvc, fparams);
                        case "/focus/first": rr = nav.edge(true); break;
                        case "/focus/last": rr = nav.edge(false); break;
                        case "/activate/read": rr = nav.activateRead(fparams); break;
                        case "/focus/current": return nav.current();
                        case "/focus/next": rr = anyKind ? nav.step(true) : nav.stepKind(true, kind); break;
                        case "/focus/prev": rr = anyKind ? nav.step(false) : nav.stepKind(false, kind); break;
                        case "/focus/swipe": rr = nav.swipe(fparams); break;
                        case "/focus/to": return nav.focusTo(fparams);
                        case "/activate": rr = nav.activate(fparams); break;
                        case "/act/at-text": rr = nav.actAtText(fparams); break;
                        case "/actions": return nav.listActions(fparams);
                        default: return nav.runAction(fparams);
                    }
                    if (fpath.matches("/activate|/activate/read|/focus/swipe|/act/at-text") && rr.optBoolean("success")) {
                        if (fpath.equals("/activate")) Thread.sleep(400); // let the clicked screen/popup appear
                        JSONObject d = nav.checkDanger(); // after: a popup may have appeared
                        if (d != null) {
                            d.put("after", fpath);
                            return d;
                        }
                    }
                    if (fpath.matches("/focus/(next|prev|first|last)") && rr.optBoolean("success")) {
                        // the move itself may have focused popup text: re-check cheaply via the focused node
                        JSONObject fn = rr.optJSONObject("node");
                        String ph = fn == null ? null : Guard.matchDanger(fn.optString("text", null));
                        if (ph == null && fn != null) ph = Guard.matchDanger(fn.optString("contentDescription", null));
                        if (ph != null) return Guard.trip(ph);
                    }
                    return rr;
                }
            });
            send(out, 200, res);
        } catch (Throwable e) {
            Log.w(TAG, "request failed", e);
            try { send(s.getOutputStream(), 500, error("internal: " + e)); } catch (Throwable ignored) {}
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    /** Run accessibility work with a time budget; the caller waits at most BINDER_BUDGET_MS. */
    private JSONObject runBinder(long budgetMs, final Callable<JSONObject> job) {
        if (stuck.get() >= MAX_STUCK) return error("busy: accessibility calls are blocked");
        final AtomicInteger state = new AtomicInteger(); // 0 queued, 1 running, 2 done, 3 abandoned
        Future<JSONObject> f;
        try {
            f = binder.submit(new Callable<JSONObject>() {
                @Override
                public JSONObject call() throws Exception {
                    state.compareAndSet(0, 1);
                    try {
                        return job.call();
                    } finally {
                        if (state.getAndSet(2) == 3) stuck.decrementAndGet(); // abandoned thread came back
                    }
                }
            });
        } catch (Throwable t) {
            return error("busy: accessibility queue full");
        }
        try {
            return f.get(budgetMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            timeouts.incrementAndGet();
            f.cancel(true); // sets the interrupt flag; Nav checks it between binder calls
            if (state.compareAndSet(1, 3)) { // still blocked in a binder call: replace the worker thread
                stuck.incrementAndGet();
                ExecutorService old = binder;
                binder = newBinder();
                old.shutdownNow();
            }
            return error("timeout");
        } catch (ExecutionException ee) {
            if (ee.getCause() instanceof StackOverflowError) { // keep a short trace for diagnosis
                StackTraceElement[] st = ee.getCause().getStackTrace();
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(6, st.length); i++) sb.append(st[i]).append(" < ");
                Log.w(TAG, "stack overflow at " + sb);
            }
            return error("failed: " + (ee.getCause() == null ? ee : ee.getCause()));
        } catch (InterruptedException ie) {
            f.cancel(true);
            return error("interrupted");
        }
    }

    static JSONObject error(String msg) {
        JSONObject r = new JSONObject();
        try {
            r.put("success", false);
            r.put("error", msg);
        } catch (Exception ignored) {}
        return r;
    }

    private static void send(OutputStream out, int status, JSONObject json) {
        try {
            byte[] b = json.toString().getBytes(StandardCharsets.UTF_8);
            String h = "HTTP/1.1 " + status + " X\r\nContent-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + b.length + "\r\nConnection: close\r\n\r\n";
            out.write(h.getBytes(StandardCharsets.UTF_8));
            out.write(b);
            out.flush();
        } catch (Exception ignored) {}
    }
}
