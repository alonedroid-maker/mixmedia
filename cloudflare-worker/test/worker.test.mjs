import assert from "node:assert/strict";
import test from "node:test";
import { createWorkerHandler, IpRateLimiter } from "../src/index.js";

test("health endpoint reports configuration status without exposing any secret values", async () => {
  const handler = createWorkerHandler({
    fetchImpl: async () => {
      throw new Error("should not call upstream");
    },
  });

  const env = {
    TMDB_API_READ_ACCESS_TOKEN: "super_secret_bearer_token_value",
    TMDB_API_KEY: "super_secret_v3_key",
  };

  const resp = await handler(new Request("https://proxy.example/health"), env);
  assert.equal(resp.status, 200);
  const text = await resp.text();
  assert.ok(!text.includes("super_secret_bearer_token_value"));
  assert.ok(!text.includes("super_secret_v3_key"));

  const data = JSON.parse(text);
  assert.equal(data.ok, true);
  assert.equal(data.tmdbConfigured, true);
  assert.equal(data.authMode, "bearer");
  assert.match(data.attribution, /not endorsed or certified by TMDB/);
});

test("trending and discover routes inject server-side Bearer header and validate params", async () => {
  const calls = [];
  const handler = createWorkerHandler({
    fetchImpl: async (url, init) => {
      calls.push({ url, headers: init?.headers });
      return new Response(JSON.stringify({ page: 2, total_pages: 10, results: [{ id: 550 }] }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    },
  });

  const env = { TMDB_API_READ_ACCESS_TOKEN: "jwt_read_token_123" };

  const resp = await handler(
    new Request("https://proxy.example/v1/trending/movie/day?page=2&language=en-US", {
      headers: { "CF-Connecting-IP": "1.2.3.4" },
    }),
    env
  );
  assert.equal(resp.status, 200);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].headers.Authorization, "Bearer jwt_read_token_123");
  assert.ok(calls[0].url.startsWith("https://api.themoviedb.org/3/trending/movie/day"));
  assert.ok(!calls[0].url.includes("api_key="));

  const badWindow = await handler(
    new Request("https://proxy.example/v1/trending/movie/year"),
    env
  );
  assert.equal(badWindow.status, 400);

  const discoverResp = await handler(
    new Request(
      "https://proxy.example/v1/discover/movie?with_genres=28,12&vote_average_gte=7.5&year=2024&region=GB&page=1",
      { headers: { "CF-Connecting-IP": "1.2.3.4" } }
    ),
    env
  );
  assert.equal(discoverResp.status, 200);
  assert.ok(calls[1].url.includes("/3/discover/movie"));
  assert.ok(calls[1].url.includes("with_genres=28%2C12"));
  assert.ok(calls[1].url.includes("vote_average.gte=7.5"));
  assert.ok(calls[1].url.includes("primary_release_year=2024"));
  assert.ok(calls[1].url.includes("watch_region=GB"));
});

test("watch providers endpoint filters results by ISO 3166-1 country code", async () => {
  const handler = createWorkerHandler({
    fetchImpl: async () =>
      new Response(
        JSON.stringify({
          id: 102,
          results: {
            US: { link: "https://www.themoviedb.org/movie/102/watch?locale=US", flatrate: [{ provider_name: "Netflix" }] },
            GB: { link: "https://www.themoviedb.org/movie/102/watch?locale=GB", flatrate: [{ provider_name: "Sky Go" }] },
          },
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      ),
  });

  const env = { TMDB_API_READ_ACCESS_TOKEN: "jwt_read_token_123" };
  const resp = await handler(
    new Request("https://proxy.example/v1/movie/102/watch/providers?region=gb"),
    env
  );
  assert.equal(resp.status, 200);
  const data = await resp.json();
  assert.equal(data.region, "GB");
  assert.deepEqual(Object.keys(data.results), ["GB"]);
  assert.equal(data.results.GB.flatrate[0].provider_name, "Sky Go");
  assert.match(data.attribution, /JustWatch/);
});

test("rate limiter returns 429 with Retry-After and cache prevents duplicate upstream fetches", async () => {
  let upstreamCount = 0;
  const cacheMap = new Map();
  const mockCache = {
    async match(req) {
      const hit = cacheMap.get(req.url);
      return hit ? hit.clone() : null;
    },
    async put(req, res) {
      cacheMap.set(req.url, res);
    },
  };

  const handler = createWorkerHandler({
    fetchImpl: async () => {
      upstreamCount += 1;
      return new Response(JSON.stringify({ genres: [{ id: 28, name: "Action" }] }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    },
    rateLimiter: new IpRateLimiter(2, 60_000),
    cacheStore: mockCache,
  });

  const env = { TMDB_API_READ_ACCESS_TOKEN: "jwt_read_token_123" };
  const req1 = await handler(
    new Request("https://proxy.example/v1/genres/movie", { headers: { "CF-Connecting-IP": "9.9.9.9" } }),
    env
  );
  const req2 = await handler(
    new Request("https://proxy.example/v1/genres/movie", { headers: { "CF-Connecting-IP": "9.9.9.9" } }),
    env
  );
  const req3 = await handler(
    new Request("https://proxy.example/v1/genres/movie", { headers: { "CF-Connecting-IP": "9.9.9.9" } }),
    env
  );

  assert.equal(req1.status, 200);
  assert.equal(req2.status, 200);
  assert.equal(upstreamCount, 1); // Served from edge cache on 2nd request
  assert.equal(req3.status, 429);
  assert.ok(Number(req3.headers.get("Retry-After")) >= 1);
});

test("rejects SSRF / arbitrary paths and never echoes upstream credentials on upstream failure", async () => {
  const handler = createWorkerHandler({
    fetchImpl: async () => new Response("Unauthorized", { status: 401 }),
  });
  const env = { TMDB_API_KEY: "secret_v3_xyz" };

  const badRoute = await handler(new Request("https://proxy.example/3/account"), env);
  assert.equal(badRoute.status, 404);

  const badId = await handler(new Request("https://proxy.example/v1/movie/123..%2Faccount"), env);
  assert.equal(badId.status, 400);

  const upstreamErr = await handler(new Request("https://proxy.example/v1/movie/550"), env);
  assert.equal(upstreamErr.status, 502);
  const errText = await upstreamErr.text();
  assert.ok(!errText.includes("secret_v3_xyz"));
  assert.ok(!errText.includes("api.themoviedb.org"));
});
