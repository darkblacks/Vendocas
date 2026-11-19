/* Vendocas tracking SDK v0.1.0 */
(function(){
const ANON_KEY = "vd_anon";
const CHECKOUT_KEY = "vd_checkout_at";
const ABANDON_AFTER_MS = 30 * 60 * 1000;
let cfg = null;
function storage(kind) {
    try {
        return kind === "local" ? window.localStorage : window.sessionStorage;
    }
    catch {
        return null;
    }
}
function uid() {
    const a = new Uint8Array(16);
    crypto.getRandomValues(a);
    return Array.from(a, (b) => b.toString(16).padStart(2, "0")).join("");
}
function anonymousId() {
    var _a;
    const s = storage("local");
    let id = (_a = s === null || s === void 0 ? void 0 : s.getItem(ANON_KEY)) !== null && _a !== void 0 ? _a : null;
    if (!id) {
        id = uid();
        s === null || s === void 0 ? void 0 : s.setItem(ANON_KEY, id);
    }
    return id;
}
function sessionId() {
    var _a;
    const s = storage("session");
    let id = (_a = s === null || s === void 0 ? void 0 : s.getItem("vd_session")) !== null && _a !== void 0 ? _a : null;
    if (!id) {
        id = uid();
        s === null || s === void 0 ? void 0 : s.setItem("vd_session", id);
    }
    return id;
}
function send(event, extra = {}) {
    if (!cfg || !cfg.consent)
        return Promise.resolve();
    const body = JSON.stringify({ pk: cfg.pk, anonymousId: anonymousId(), sessionId: sessionId(), event, consent: true,
        url: location.href.slice(0, 1000), ...extra });
    return fetch(cfg.endpoint + "/api/v1/track", { method: "POST", headers: { "Content-Type": "application/json" }, body, keepalive: true, credentials: "omit" })
        .then(() => undefined).catch(() => undefined);
}
function init(opts) {
    var _a;
    cfg = { pk: opts.pk, endpoint: ((_a = opts.endpoint) !== null && _a !== void 0 ? _a : "https://api.vendocas.com").replace(/\/$/, ""), consent: !!opts.consent };
    if (cfg.consent)
        afterConsent();
}
function setConsent(granted) {
    var _a, _b;
    if (!cfg)
        return;
    cfg.consent = granted;
    if (granted)
        afterConsent();
    else {
        (_a = storage("local")) === null || _a === void 0 ? void 0 : _a.removeItem(ANON_KEY);
        (_b = storage("session")) === null || _b === void 0 ? void 0 : _b.removeItem(CHECKOUT_KEY);
    }
}
function afterConsent() {
    var _a, _b, _c;
    const started = Number((_b = (_a = storage("session")) === null || _a === void 0 ? void 0 : _a.getItem(CHECKOUT_KEY)) !== null && _b !== void 0 ? _b : 0);
    if (started && Date.now() - started > ABANDON_AFTER_MS) {
        (_c = storage("session")) === null || _c === void 0 ? void 0 : _c.removeItem(CHECKOUT_KEY);
        void send("checkout_abandoned");
    }
}
function identify(identity) { return send("identify", { identity }); }
function track(event) {
    var _a;
    if (event === "checkout_started")
        (_a = storage("session")) === null || _a === void 0 ? void 0 : _a.setItem(CHECKOUT_KEY, String(Date.now()));
    return send(event);
}
/** Pass a server-signed order to have it counted as confirmed revenue; unsigned purchases only stop abandonment flows. */
function purchase(order) {
    var _a;
    (_a = storage("session")) === null || _a === void 0 ? void 0 : _a.removeItem(CHECKOUT_KEY);
    return send("purchase_completed", order ? { order } : {});
}
function pageView() { return send("page_view"); }

window.Vendocas={init,setConsent,identify,track,purchase,pageView};
})();
