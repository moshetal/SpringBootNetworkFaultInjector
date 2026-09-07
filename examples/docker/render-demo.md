# Public cloud demo (Render free)

One GitHub connect. Render builds the existing Dockerfiles and gives HTTPS URLs.

## Deploy

1. Sign up at [render.com](https://render.com) (no card required for free).
2. Open [New Blueprint](https://dashboard.render.com/blueprints) and connect this GitHub repo.
3. Select branch `feat/oracle-public-demo` until that PR is merged, then `master`.
4. Apply `render.yaml`. First deploy is a Maven Docker build (several minutes, five services).

When it finishes, Render shows five `.onrender.com` URLs. Console is `https://fault-injector-server-….onrender.com/console/`.

## After idle

Free services sleep after 15 minutes. Open the **console URL first**, wait for it to wake (~1 min), then open each of the four pod URLs once so agents reconnect. Until then the console can look empty.

Optional pod checks (replace hosts with your Render URLs):

```bash
curl -sS -o /dev/null -w "%{http_code}\n" https://billing-a-….onrender.com/demo/slow
curl -sS -o /dev/null -w "%{http_code}\n" https://catalog-a-….onrender.com/demo/catalog/browse
```

Expect `200`.

## Limits (free)

- Each web service is 512 MB and shares 750 instance hours/month.
- Free Postgres expires 30 days after creation.
- No private HTTP between free web services; agents use public `wss://`.
