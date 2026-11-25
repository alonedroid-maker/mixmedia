const TMDB_API_ORIGIN = "https://api.themoviedb.org/3";

const ALLOWED_TIME_WINDOWS = new Set(["day", "week"]);
const ALLOWED_SORT_BY = new Set([
  "popularity.desc",
  "popularity.asc",
  "vote_average.desc",
  "vote_average.asc",
  "primary_release_date.desc",
  "first_air_date.desc",
  "revenue.desc",
]);
const ALLOWED_APPEND_MOVIE = new Set([
  "credits",
  "external_ids",
  "release_dates",
  "watch/providers",
]);
const ALLOWED_APPEND_TV = new Set([
  "credits",
  "external_ids",
  "watch/providers",
]);

const DEFAULT_RATE_LIMIT_MAX = 60;
const DEFAULT_RATE_LIMIT_WINDOW_MS = 60_000;

/**
 * In-isolate sliding window rate limiter keyed by client IP.
 * Protects TMDB quota per Worker isolate while returning clean 429 headers.
 */
export class IpRateLimiter {
  constructor(maxRequests = DEFAULT_RATE_LIMIT_MAX, windowMs = DEFAULT_RATE_LIMIT_WINDOW_MS) {
    this.maxRequests = maxRequests;
    this.windowMs = windowMs;
    this.buckets = new Map();
  }

  check(ipKey, now = Date.now()) {
    const key = ipKey || "unknown";
    const cutoff = now - this.windowMs;
    const timestamps = (this.buckets.get(key) || []).filter((ts) => ts > cutoff);

    if (timestamps.length >= this.maxRequests) {
      const oldest = timestamps[0];
      const retryAfterSec = Math.max(1, Math.ceil((oldest + this.windowMs - now) / 1000));
      this.buckets.set(key, timestamps);
      return { allowed: false, remaining: 0, retryAfterSec };
    }

    timestamps.push(now);
    this.buckets.set(key, timestamps);

    // Opportunistic cleanup so memory stays bounded on long-lived isolates
    if (this.buckets.size > 2000) {
      for (const [entryKey, list] of this.buckets.entries()) {
        if (list.every((ts) => ts <= cutoff)) {
          this.buckets.delete(entryKey);
        }
      }
    }

    return {
      allowed: true,
      remaining: Math.max(0, this.maxRequests - timestamps.length),
      retryAfterSec: 0,
    };
  }
}

function jsonResponse(payload, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "X-Content-Type-Options": "nosniff",
      "Referrer-Policy": "no-referrer",
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type, Accept",
      ...extraHeaders,
    },
  });
}

function errorResponse(status, code, message, extraHeaders = {}) {
  return jsonResponse({ ok: false, error: code, message }, status, {
    "Cache-Control": "no-store",
    ...extraHeaders,
  });
}

function parsePositiveIntId(raw) {
  if (!raw || !/^[1-9]\d{0,9}$/.test(raw)) return null;
  const n = Number.parseInt(raw, 10);
  return Number.isSafeInteger(n) && n > 0 ? n : null;
}

function parseSeasonNumber(raw) {
  if (raw == null || !/^\d{1,4}$/.test(raw)) return null;
  const n = Number.parseInt(raw, 10);
  return Number.isSafeInteger(n) && n >= 0 && n <= 2000 ? n : null;
}

function parsePage(raw) {
  if (!raw) return 1;
  if (!/^[1-9]\d{0,2}$/.test(raw)) return null;
  const n = Number.parseInt(raw, 10);
  return n >= 1 && n <= 500 ? n : null;
}

function parseLanguage(raw) {
  if (!raw) return "en-US";
  return /^[a-z]{2}(-[A-Z]{2})?$/.test(raw) ? raw : null;
}

function parseRegion(raw) {
  if (!raw) return null;
  const upper = raw.trim().toUpperCase();
  return /^[A-Z]{2}$/.test(upper) ? upper : null;
}

function parseYear(raw) {
  if (!raw) return null;
  if (!/^\d{4}$/.test(raw)) return null;
  const n = Number.parseInt(raw, 10);
  return n >= 1888 && n <= 2100 ? n : null;
}

function parseMinRating(raw) {
  if (!raw) return null;
  if (!/^\d{1,2}(\.\d{1,2})?$/.test(raw)) return null;
  const n = Number.parseFloat(raw);
  return Number.isFinite(n) && n >= 0 && n <= 10 ? n : null;
}

function parseGenres(raw) {
  if (!raw) return null;
  const trimmed = raw.trim();
  return /^\d+(,\d+)*$/.test(trimmed) && trimmed.length <= 64 ? trimmed : null;
}

function parseAppendToResponse(raw, allowSet) {
  if (!raw) return null;
  const parts = raw
    .split(",")
    .map((p) => p.trim())
    .filter(Boolean);
  if (parts.length === 0) return null;
  for (const part of parts) {
    if (!allowSet.has(part)) return false;
  }
  return parts.join(",");
}

function buildTmdbRequestUrl(tmdbPath, queryParams) {
  const url = new URL(`${TMDB_API_ORIGIN}${tmdbPath}`);
  for (const [k, v] of Object.entries(queryParams)) {
    if (v !== undefined && v !== null && v !== "") {
      url.searchParams.set(k, String(v));
    }
  }
  return url;
}

function resolveTmdbAuth(env) {
  const bearer = (env?.TMDB_API_READ_ACCESS_TOKEN || "").trim();
  const apiKey = (env?.TMDB_API_KEY || "").trim();
  if (bearer) {
    return { mode: "bearer", bearer, apiKey: null };
  }
  if (apiKey) {
    return { mode: "v3_key", bearer: null, apiKey };
  }
  return null;
}

function getEdgeCache(customCache) {
  if (customCache) return customCache;
  if (typeof caches !== "undefined" && caches.default) {
    return caches.default;
  }
  return null;
}

export function createWorkerHandler({
  fetchImpl = fetch,
  rateLimiter = new IpRateLimiter(),
  cacheStore = null,
} = {}) {
  return async function handleRequest(request, env = {}, ctx = null) {
    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: {
          "Access-Control-Allow-Origin": "*",
          "Access-Control-Allow-Methods": "GET, OPTIONS",
          "Access-Control-Allow-Headers": "Content-Type, Accept",
          "Access-Control-Max-Age": "86400",
        },
      });
    }

    if (request.method !== "GET") {
      return errorResponse(405, "method_not_allowed", "Only GET requests are supported.");
    }

    let reqUrl;
    try {
      reqUrl = new URL(request.url);
    } catch {
      return errorResponse(400, "invalid_url", "Invalid request URL.");
    }

    const pathname = reqUrl.pathname.replace(/\/+$/, "") || "/";

    // Health check endpoint (never leaks secrets)
    if (pathname === "/health" || pathname === "/v1/health") {
      const auth = resolveTmdbAuth(env);
      return jsonResponse(
        {
          ok: true,
          service: "mixmedia-tmdb-proxy",
          tmdbConfigured: auth !== null,
          authMode: auth ? auth.mode : "unconfigured",
          attribution: "This product uses the TMDB API but is not endorsed or certified by TMDB.",
        },
        200,
        { "Cache-Control": "no-store" }
      );
    }

    // Per-IP Rate Limiting
    const clientIp =
      request.headers.get("CF-Connecting-IP") ||
      request.headers.get("X-Forwarded-For")?.split(",")[0]?.trim() ||
      "local-client";
    const rate = rateLimiter.check(clientIp);
    if (!rate.allowed) {
      return errorResponse(
        429,
        "rate_limited",
        "Too many requests. Please wait before retrying.",
        { "Retry-After": String(rate.retryAfterSec) }
      );
    }

    const auth = resolveTmdbAuth(env);
    if (!auth) {
      return errorResponse(
        503,
        "tmdb_unconfigured",
        "TMDB backend credentials are not configured in Worker secrets."
      );
    }

    const params = reqUrl.searchParams;
    const page = parsePage(params.get("page"));
    if (page === null) {
      return errorResponse(400, "invalid_page", "Parameter 'page' must be an integer between 1 and 500.");
    }
    const language = parseLanguage(params.get("language"));
    if (language === null) {
      return errorResponse(400, "invalid_language", "Parameter 'language' must match ISO 639-1 / BCP-47 (e.g. en-US).");
    }

    const rawRegion = params.get("region") || params.get("watch_region");
    const region = rawRegion ? parseRegion(rawRegion) : null;
    if (rawRegion && !region) {
      return errorResponse(400, "invalid_region", "Parameter 'region' must be a 2-letter ISO 3166-1 code.");
    }

    let tmdbPath = null;
    let tmdbQuery = {};
    let cacheTtlSeconds = 600;
    let watchProviderFilterRegion = null;

    // Route matching
    const trendingMatch = pathname.match(/^\/v1\/trending\/(movie|tv)\/([a-z]+)$/);
    const movieDetailMatch = pathname.match(/^\/v1\/movie\/([^/]+)$/);
    const tvDetailMatch = pathname.match(/^\/v1\/tv\/([^/]+)$/);
    const tvSeasonMatch = pathname.match(/^\/v1\/tv\/([^/]+)\/season\/([^/]+)$/);
    const watchProviderMatch = pathname.match(/^\/v1\/(movie|tv)\/([^/]+)\/watch\/providers$/);
    const findMatch = pathname.match(/^\/v1\/find\/(tt\d{5,12})$/);

    if (trendingMatch) {
      const mediaType = trendingMatch[1];
      const window = trendingMatch[2];
      if (!ALLOWED_TIME_WINDOWS.has(window)) {
        return errorResponse(400, "invalid_window", "Trending window must be 'day' or 'week'.");
      }
      tmdbPath = `/trending/${mediaType}/${window}`;
      tmdbQuery = { language, page };
      cacheTtlSeconds = 600;
    } else if (watchProviderMatch) {
      const mediaType = watchProviderMatch[1];
      const id = parsePositiveIntId(watchProviderMatch[2]);
      if (!id) {
        return errorResponse(400, "invalid_id", "Media ID must be a positive integer.");
      }
      tmdbPath = `/${mediaType}/${id}/watch/providers`;
      tmdbQuery = {};
      watchProviderFilterRegion = region;
      cacheTtlSeconds = 1800;
    } else if (tvSeasonMatch) {
      const id = parsePositiveIntId(tvSeasonMatch[1]);
      const seasonNumber = parseSeasonNumber(tvSeasonMatch[2]);
      if (!id || seasonNumber === null) {
        return errorResponse(400, "invalid_season", "TV ID and season number must be valid integers.");
      }
      tmdbPath = `/tv/${id}/season/${seasonNumber}`;
      tmdbQuery = { language };
      cacheTtlSeconds = 1800;
    } else if (movieDetailMatch) {
      const id = parsePositiveIntId(movieDetailMatch[1]);
      if (!id) {
        return errorResponse(400, "invalid_id", "Movie ID must be a positive integer.");
      }
      const appendParam = parseAppendToResponse(params.get("append_to_response"), ALLOWED_APPEND_MOVIE);
      if (appendParam === false) {
        return errorResponse(400, "invalid_append", "Unsupported append_to_response field requested.");
      }
      tmdbPath = `/movie/${id}`;
      tmdbQuery = {
        language,
        append_to_response: appendParam || "credits,external_ids,release_dates,watch/providers",
      };
      cacheTtlSeconds = 1800;
    } else if (tvDetailMatch) {
      const id = parsePositiveIntId(tvDetailMatch[1]);
      if (!id) {
        return errorResponse(400, "invalid_id", "TV ID must be a positive integer.");
      }
      const appendParam = parseAppendToResponse(params.get("append_to_response"), ALLOWED_APPEND_TV);
      if (appendParam === false) {
        return errorResponse(400, "invalid_append", "Unsupported append_to_response field requested.");
      }
      tmdbPath = `/tv/${id}`;
      tmdbQuery = {
        language,
        append_to_response: appendParam || "credits,external_ids,watch/providers",
      };
      cacheTtlSeconds = 1800;
    } else if (pathname === "/v1/search/movie" || pathname === "/v1/search/tv" || pathname === "/v1/search/multi") {
      const query = (params.get("query") || "").trim();
      if (query.length < 1 || query.length > 160) {
        return errorResponse(400, "invalid_query", "Parameter 'query' must be between 1 and 160 characters.");
      }
      const rawYear = params.get("year") || params.get("first_air_date_year");
      const year = rawYear ? parseYear(rawYear) : null;
      if (rawYear && !year) {
        return errorResponse(400, "invalid_year", "Parameter 'year' must be a valid 4-digit year.");
      }
      const endpoint = pathname.replace("/v1", "");
      tmdbPath = endpoint;
      tmdbQuery = {
        query,
        language,
        page,
        include_adult: "false",
      };
      if (year && pathname === "/v1/search/movie") {
        tmdbQuery.primary_release_year = year;
      } else if (year && pathname === "/v1/search/tv") {
        tmdbQuery.first_air_date_year = year;
      }
      cacheTtlSeconds = 600;
    } else if (pathname === "/v1/discover/movie" || pathname === "/v1/discover/tv") {
      const isTv = pathname.endsWith("/tv");
      const rawGenres = params.get("with_genres");
      const withGenres = rawGenres ? parseGenres(rawGenres) : null;
      if (rawGenres && !withGenres) {
        return errorResponse(400, "invalid_genres", "Parameter 'with_genres' must be comma-separated numeric genre IDs.");
      }
      const rawRating = params.get("vote_average_gte") || params.get("vote_average.gte");
      const minRating = rawRating ? parseMinRating(rawRating) : null;
      if (rawRating && minRating === null) {
        return errorResponse(400, "invalid_rating", "Parameter 'vote_average_gte' must be between 0 and 10.");
      }
      const rawYear = params.get("year") || params.get("primary_release_year") || params.get("first_air_date_year");
      const year = rawYear ? parseYear(rawYear) : null;
      if (rawYear && !year) {
        return errorResponse(400, "invalid_year", "Parameter 'year' must be a valid 4-digit year.");
      }
      const rawSort = params.get("sort_by") || "popularity.desc";
      if (!ALLOWED_SORT_BY.has(rawSort)) {
        return errorResponse(400, "invalid_sort", "Unsupported 'sort_by' parameter.");
      }
      const releaseType = params.get("with_release_type");
      if (releaseType && !/^\d(\|\d)*$/.test(releaseType)) {
        return errorResponse(400, "invalid_release_type", "Invalid 'with_release_type' parameter.");
      }

      tmdbPath = isTv ? "/discover/tv" : "/discover/movie";
      tmdbQuery = {
        language,
        page,
        sort_by: rawSort,
        include_adult: "false",
        with_genres: withGenres,
        "vote_average.gte": minRating,
        "vote_count.gte": minRating ? 25 : undefined,
        watch_region: region || undefined,
      };
      if (year) {
        if (isTv) {
          tmdbQuery.first_air_date_year = year;
        } else {
          tmdbQuery.primary_release_year = year;
        }
      }
      if (!isTv && releaseType) {
        tmdbQuery.with_release_type = releaseType;
      }
      cacheTtlSeconds = 600;
    } else if (pathname === "/v1/genres/movie") {
      tmdbPath = "/genre/movie/list";
      tmdbQuery = { language };
      cacheTtlSeconds = 21600;
    } else if (pathname === "/v1/genres/tv") {
      tmdbPath = "/genre/tv/list";
      tmdbQuery = { language };
      cacheTtlSeconds = 21600;
    } else if (findMatch) {
      tmdbPath = `/find/${findMatch[1]}`;
      tmdbQuery = { external_source: "imdb_id", language };
      cacheTtlSeconds = 1800;
    } else {
      return errorResponse(404, "not_found", "Unknown API route.");
    }

    // Check Cloudflare Edge Cache
    const cache = getEdgeCache(cacheStore);
    const cacheKeyRequest = new Request(reqUrl.toString(), { method: "GET" });
    if (cache) {
      const cached = await cache.match(cacheKeyRequest);
      if (cached) {
        return cached;
      }
    }

    // Construct strictly anchored TMDB URL with server-side authentication
    if (auth.mode === "v3_key") {
      tmdbQuery.api_key = auth.apiKey;
    }
    const upstreamUrl = buildTmdbRequestUrl(tmdbPath, tmdbQuery);

    const upstreamHeaders = {
      Accept: "application/json",
    };
    if (auth.mode === "bearer") {
      upstreamHeaders.Authorization = `Bearer ${auth.bearer}`;
    }

    let upstreamResp;
    try {
      upstreamResp = await fetchImpl(upstreamUrl.toString(), {
        method: "GET",
        headers: upstreamHeaders,
      });
    } catch {
      return errorResponse(502, "upstream_unreachable", "Unable to reach metadata upstream service.");
    }

    if (!upstreamResp.ok) {
      const mappedStatus = upstreamResp.status === 404 ? 404 : upstreamResp.status === 429 ? 429 : 502;
      return errorResponse(
        mappedStatus,
        "upstream_error",
        upstreamResp.status === 404
          ? "Requested media resource was not found."
          : "Upstream metadata service returned an error."
      );
    }

    let bodyJson;
    try {
      bodyJson = await upstreamResp.json();
    } catch {
      return errorResponse(502, "invalid_upstream_json", "Metadata service returned malformed JSON.");
    }

    // Filter Watch Providers to the requested ISO 3166-1 country if specified
    if (watchProviderFilterRegion && bodyJson && typeof bodyJson === "object" && bodyJson.results) {
      const allResults = bodyJson.results;
      const availableRegions = Object.keys(allResults);
      const selected = allResults[watchProviderFilterRegion] || null;
      bodyJson = {
        id: bodyJson.id,
        region: watchProviderFilterRegion,
        availableRegions,
        attribution: "Watch provider data provided by JustWatch via TMDB.",
        results: selected ? { [watchProviderFilterRegion]: selected } : {},
      };
    }

    const response = jsonResponse(bodyJson, 200, {
      "Cache-Control": `public, max-age=${cacheTtlSeconds}, s-maxage=${cacheTtlSeconds}`,
      "X-RateLimit-Remaining": String(rate.remaining),
    });

    if (cache) {
      const cachePut = cache.put(cacheKeyRequest, response.clone());
      if (ctx && typeof ctx.waitUntil === "function") {
        ctx.waitUntil(cachePut);
      } else {
        await cachePut;
      }
    }

    return response;
  };
}

export default {
  fetch: createWorkerHandler(),
};
