# @vendocas/tracking-sdk

```html
<script src="https://app.vendocas.com/sdk/vendocas.js"></script>
<script>
  Vendocas.init({ pk: "pk_YOUR_PUBLIC_KEY", endpoint: "https://api.vendocas.com", consent: false });
  // When your consent banner grants analytics/marketing tracking:
  Vendocas.setConsent(true);
  // After login/checkout identification, with a token signed by YOUR backend:
  Vendocas.identify({ kind: "email", value: "ana@example.com", ts: 1763560000, sig: "<hmac>" });
  Vendocas.track("checkout_started");
  Vendocas.purchase({ orderId: "1001", amountMinor: 4990, currency: "BRL", sig: "<hmac>" });
</script>
```

Server-side signing (Node):

```js
const crypto = require("crypto");
const ts = Math.floor(Date.now() / 1000);
const sig = crypto.createHmac("sha256", process.env.VENDOCAS_TRACKING_SECRET).update(`email:${email}|${ts}`).digest("hex");
const orderSig = crypto.createHmac("sha256", process.env.VENDOCAS_TRACKING_SECRET).update(`${orderId}|${amountMinor}|${currency}`).digest("hex");
```

The tracking secret is obtained (and rotated) in Settings → Integrations. Never expose it in the browser.
