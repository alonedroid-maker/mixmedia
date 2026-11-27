(() => {
  'use strict';

  const STORAGE_KEY = 'mediax_web_state_v2';

  const STRINGS = {
    en: {
      hubTitle: 'MediaX Hub',
      discoverTitle: 'Movies & TV',
      resolverTitle: 'Link Resolver',
      downloadsTitle: 'Saved Links & Downloads',
      settingsTitle: 'Settings',
      unifiedPlaceholder: 'Search movies & series or paste any video / X / Instagram / YouTube link...',
      searchPlaceholder: 'Search movies or TV series...',
      resolvePlaceholder: 'Paste direct link, shortlink, X/Twitter, Instagram, or YouTube URL...',
      go: 'Go',
      search: 'Search',
      resolve: 'Resolve',
      trendingTitle: 'Trending Now',
      windowDay: 'Today',
      windowWeek: 'This Week',
      allTypes: 'All',
      moviesOnly: 'Movies',
      tvOnly: 'TV Shows',
      allGenres: 'All Genres',
      minRating: 'Min Rating',
      anyYear: 'Any Year',
      loadMore: 'Load More',
      favorites: 'Favorites',
      recentLinks: 'Recent Resolved Links',
      emptySearch: 'Search a movie, series, or paste a video link above.',
      loading: 'Loading...',
      persianSummary: 'Persian Summary',
      englishSummary: 'English Summary',
      castLabel: 'Cast',
      watchProviders: 'Where to Watch',
      seasonsLabel: 'Seasons & Episodes',
      findSources: 'Find Sources',
      addFavorite: 'Save Favorite',
      removeFavorite: 'Saved',
      close: 'Close',
      verifyLink: 'Verify',
      sendToResolver: 'Resolve',
      openExternal: 'Open',
      downloadFile: 'Download File',
      playPreview: 'Play Preview',
      copyUrl: 'Copy Link',
      openVlc: 'Open in VLC',
      copied: 'Link copied!',
      directUrlReady: 'Direct Media Ready',
      downloadOptionsTitle: 'Available Download Options',
      downloadOptionsPrompt: 'Multiple downloadable files or qualities were found. Choose which option to download:',
      selectedOptionBadge: 'Selected',
      selectOptionBtn: 'Select',
      xVariants: 'X / Twitter Video Qualities',
      sourcesTitle: 'Source Discovery',
      searchEngines: 'Search Engines',
      siteResults: 'Configured Source Sites',
      webResults: 'Web Search Results',
      noSourcesYet: 'No direct results matched from configured sites; try the web search links below.',
      customQuery: 'Custom query override (optional)',
      searchAgain: 'Refresh Sources',
      noDownloads: 'Resolved links and downloads you start will appear here for quick access.',
      clearHistory: 'Clear History',
      languageLabel: 'Interface Language',
      themeLabel: 'Appearance',
      themeSystem: 'System',
      themeDark: 'Dark',
      themeLight: 'Light',
      tabsModeLabel: 'Navigation Layout',
      tabsUnified: 'Unified Hub (Minimal)',
      tabsSeparate: 'Separate Tabs',
      searchLangLabel: 'Metadata & Search Language',
      watchRegionLabel: 'Watch Region',
      backendUrlLabel: 'Backend Server URL (Optional for static hosting)',
      backendUrlHint: 'Leave empty when opened directly from the MediaX Web Server.',
      sourceSitesLabel: 'Configured Source Sites',
      addSiteTitle: 'Add Custom Source Site',
      siteNamePlaceholder: 'Site Name',
      siteBasePlaceholder: 'https://example.com',
      siteTemplatePlaceholder: 'https://example.com/?s={query}',
      addSiteBtn: 'Add Site',
      deleteBtn: 'Remove',
      saveBtn: 'Save',
      iosBanner: 'Tip for iPhone/iPad: Tap Share in Safari and choose "Add to Home Screen" for a full-screen app experience.',
      tmdbActive: 'TMDB Metadata Active',
      tmdbFallback: 'Zero-Key Mode (Wikipedia / IMDb / TVmaze)',
      checkUpdates: 'Open GitHub Releases',
    },
    fa: {
      hubTitle: 'مرکز هوشمند MediaX',
      discoverTitle: 'فیلم و سریال',
      resolverTitle: 'ریزالور لینک',
      downloadsTitle: 'دانلودها و لینک‌ها',
      settingsTitle: 'تنظیمات',
      unifiedPlaceholder: 'نام فیلم/سریال را جستجو کنید یا لینک مستقیم / X / اینستاگرام / یوتیوب را وارد کنید...',
      searchPlaceholder: 'جستجوی نام فیلم یا سریال...',
      resolvePlaceholder: 'لینک مستقیم، لینک کوتاه، X/توییتر، اینستاگرام یا یوتیوب را وارد کنید...',
      go: 'اجرا',
      search: 'جستجو',
      resolve: 'بررسی لینک',
      trendingTitle: 'ترندهای روز',
      windowDay: 'امروز',
      windowWeek: 'این هفته',
      allTypes: 'همه',
      moviesOnly: 'فیلم',
      tvOnly: 'سریال',
      allGenres: 'همه ژانرها',
      minRating: 'حداقل امتیاز',
      anyYear: 'همه سال‌ها',
      loadMore: 'نتایج بیشتر',
      favorites: 'علاقه‌مندی‌ها',
      recentLinks: 'لینک‌های اخیر',
      emptySearch: 'نام فیلم یا سریال را جستجو کنید یا یک لینک ویدیو وارد نمایید.',
      loading: 'در حال بارگذاری...',
      persianSummary: 'خلاصه فارسی',
      englishSummary: 'خلاصه انگلیسی',
      castLabel: 'بازیگران',
      watchProviders: 'پلتفرم‌های تماشا',
      seasonsLabel: 'فصل‌ها و قسمت‌ها',
      findSources: 'جستجوی منابع دانلود',
      addFavorite: 'افزودن به علاقه‌مندی',
      removeFavorite: 'ذخیره‌شده',
      close: 'بستن',
      verifyLink: 'بررسی وضعیت',
      sendToResolver: 'ریزالور',
      openExternal: 'باز کردن',
      downloadFile: 'دانلود فایل',
      playPreview: 'پخش پیش‌نمایش',
      copyUrl: 'کپی لینک',
      openVlc: 'باز کردن در VLC',
      copied: 'لینک کپی شد!',
      directUrlReady: 'لینک مستقیم آماده دانلود',
      downloadOptionsTitle: 'گزینه‌های قابل دانلود',
      downloadOptionsPrompt: 'چند فایل یا کیفیت در این صفحه پیدا شد؛ گزینه مورد نظر خود را برای دانلود انتخاب کنید:',
      selectedOptionBadge: 'انتخاب‌شده',
      selectOptionBtn: 'انتخاب',
      xVariants: 'کیفیت‌های ویدیو X / توییتر',
      sourcesTitle: 'یافتن منابع دانلود',
      searchEngines: 'موتورهای جستجو',
      siteResults: 'نتایج سایت‌های منبع',
      webResults: 'نتایج جستجوی وب',
      noSourcesYet: 'نتیجه‌ای از سایت‌های فعال پیدا نشد؛ از لینک‌های جستجوی وب استفاده کنید.',
      customQuery: 'عبارت جستجوی دلخواه (اختیاری)',
      searchAgain: 'بروزرسانی منابع',
      noDownloads: 'لینک‌های استخراج‌شده و دانلودهای شما برای دسترسی سریع اینجا نمایش داده می‌شوند.',
      clearHistory: 'پاک کردن تاریخچه',
      languageLabel: 'زبان برنامه',
      themeLabel: 'ظاهر برنامه',
      themeSystem: 'سیستم',
      themeDark: 'تیره',
      themeLight: 'روشن',
      tabsModeLabel: 'چیدمان تب‌ها',
      tabsUnified: 'حالت یکپارچه (مینیمال)',
      tabsSeparate: 'تب‌های جداگانه',
      searchLangLabel: 'زبان جستجو و اطلاعات فیلم',
      watchRegionLabel: 'منطقه پخش',
      backendUrlLabel: 'آدرس سرور بک‌اند (اختیاری برای هاست استاتیک)',
      backendUrlHint: 'اگر مستقیم از سرور وب MediaX باز کرده‌اید، این فیلد را خالی بگذارید.',
      sourceSitesLabel: 'سایت‌های منبع جستجو',
      addSiteTitle: 'افزودن سایت منبع دلخواه',
      siteNamePlaceholder: 'نام سایت',
      siteBasePlaceholder: 'https://example.com',
      siteTemplatePlaceholder: 'https://example.com/?s={query}',
      addSiteBtn: 'افزودن سایت',
      deleteBtn: 'حذف',
      saveBtn: 'ذخیره',
      iosBanner: 'راهنمای آیفون/آیپد: در سافاری دکمه Share را بزنید و گزینه Add to Home Screen را انتخاب کنید.',
      tmdbActive: 'اتصال مستقیم TMDB فعال است',
      tmdbFallback: 'حالت بدون کلید (ویکی‌پدیا / IMDb / TVmaze)',
      checkUpdates: 'مشاهده نسخه‌ها در گیت‌هاب',
    }
  };

  function defaultState() {
    return {
      lang: 'fa',
      theme: 'system',
      useSeparateTabs: false,
      searchLang: 'both',
      watchRegion: 'US',
      trendingWindow: 'day',
      backendUrl: '',
      activeTab: 'hub',
      iosBannerDismissed: false,
      favorites: [],
      downloads: [],
      customSites: [],
      disabledSiteIds: [],
    };
  }

  function loadState() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) return defaultState();
      const parsed = JSON.parse(raw);
      return { ...defaultState(), ...parsed };
    } catch (_) {
      return defaultState();
    }
  }

  const state = loadState();

  function saveState() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
    } catch (_) { /* ignore quota errors */ }
  }

  const runtime = {
    version: '2.0',
    tmdbConfigured: false,
    builtInSites: [],
    watchRegions: [],
    genres: [],
    trendingMovies: [],
    trendingTv: [],
    trendingAttribution: '',
    loadingTrending: false,
    searchQuery: '',
    smartInput: '',
    mediaType: 'ALL',
    genreId: '',
    minRating: '',
    year: '',
    searchPage: 1,
    totalPages: 1,
    hasSearched: false,
    searching: false,
    searchResults: [],
    searchError: null,
    resolverInput: '',
    resolving: false,
    resolveResult: null,
    resolveError: null,
    selectedOptionUrl: null,
  };

  function t(key) {
    return (STRINGS[state.lang] && STRINGS[state.lang][key]) || STRINGS.en[key] || key;
  }

  function safeUrl(raw, allowVlc = false) {
    if (!raw || typeof raw !== 'string') return null;
    try {
      const u = new URL(raw.trim(), window.location.origin);
      const p = u.protocol.toLowerCase();
      if (p === 'http:' || p === 'https:') return u.href;
      if (allowVlc && p === 'vlc:') return raw.trim();
      return null;
    } catch (_) {
      return null;
    }
  }

  function apiBase() {
    const custom = (state.backendUrl || '').trim().replace(/\/+$/, '');
    if (custom && safeUrl(custom)) return custom;
    return '';
  }

  async function apiGet(path) {
    const res = await fetch(`${apiBase()}${path}`, { headers: { Accept: 'application/json' } });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
    return data;
  }

  async function apiPost(path, payload) {
    const res = await fetch(`${apiBase()}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify(payload),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      throw new Error(data.error || `HTTP ${res.status}`);
    }
    return data;
  }

  function el(tag, attrs = {}, children = []) {
    const node = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) {
      if (v === null || v === undefined || v === false) continue;
      if (k === 'class') node.className = String(v);
      else if (k === 'text') node.textContent = String(v);
      else if (k.startsWith('on') && typeof v === 'function') node.addEventListener(k.slice(2).toLowerCase(), v);
      else if (k === 'value') node.value = String(v);
      else if (k === 'checked') node.checked = Boolean(v);
      else if (k === 'disabled') node.disabled = Boolean(v);
      else node.setAttribute(k, String(v));
    }
    const list = Array.isArray(children) ? children : [children];
    for (const child of list) {
      if (child === null || child === undefined || child === false) continue;
      node.appendChild(typeof child === 'string' || typeof child === 'number' ? document.createTextNode(String(child)) : child);
    }
    return node;
  }

  let toastTimer = null;
  function showToast(msg) {
    const toast = document.getElementById('toast');
    toast.textContent = msg;
    toast.classList.remove('hidden');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toast.classList.add('hidden'), 2400);
  }

  function applyThemeAndDirection() {
    const html = document.documentElement;
    html.lang = state.lang === 'fa' ? 'fa' : 'en';
    html.dir = state.lang === 'fa' ? 'rtl' : 'ltr';

    let effectiveTheme = state.theme;
    if (effectiveTheme === 'system') {
      effectiveTheme = window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
    }
    html.setAttribute('data-theme', effectiveTheme);

    const langLabel = state.lang === 'fa' ? 'EN' : 'فا';
    const themeLabel = state.theme === 'dark' ? '🌙' : state.theme === 'light' ? '☀️' : '🌓';
    document.getElementById('quick-lang-btn').textContent = `${t('languageLabel')}: ${state.lang.toUpperCase()}`;
    document.getElementById('quick-theme-btn').textContent = `${themeLabel} ${t('theme' + capitalize(state.theme))}`;
    document.getElementById('mobile-lang-btn').textContent = langLabel;
    document.getElementById('mobile-theme-btn').textContent = themeLabel;
  }

  function capitalize(s) {
    return s.charAt(0).toUpperCase() + s.slice(1);
  }

  function isLikelyUrl(raw) {
    const s = (raw || '').trim();
    if (!s || /\s/.test(s)) return false;
    return /^(https?:\/\/|www\.)/i.test(s) ||
      /^(x\.com|twitter\.com|instagram\.com|instagr\.am|youtu\.be|youtube\.com|m\.youtube\.com)\//i.test(s);
  }

  function normalizeInputUrl(raw) {
    const s = (raw || '').trim();
    if (/^https?:\/\//i.test(s)) return s;
    return `https://${s}`;
  }

  function effectiveSites() {
    const disabled = new Set(state.disabledSiteIds || []);
    const builtIn = (runtime.builtInSites || []).map((s) => ({ ...s, enabled: !disabled.has(s.id) }));
    const custom = (state.customSites || []).map((s) => ({ ...s, enabled: !disabled.has(s.id), isCustom: true }));
    return [...builtIn, ...custom];
  }

  function navTabs() {
    if (state.useSeparateTabs) {
      return [
        { id: 'hub', icon: '🔍', label: t('discoverTitle') },
        { id: 'resolver', icon: '🔗', label: t('resolverTitle') },
        { id: 'downloads', icon: '⬇️', label: t('downloadsTitle') },
        { id: 'settings', icon: '⚙️', label: t('settingsTitle') },
      ];
    }
    return [
      { id: 'hub', icon: '🎬', label: t('hubTitle') },
      { id: 'downloads', icon: '⬇️', label: t('downloadsTitle') },
      { id: 'settings', icon: '⚙️', label: t('settingsTitle') },
    ];
  }

  function renderNav() {
    const tabs = navTabs();
    if (!tabs.some((x) => x.id === state.activeTab)) {
      state.activeTab = 'hub';
    }
    const rail = document.getElementById('rail-nav');
    const bottom = document.getElementById('bottom-nav');
    rail.replaceChildren();
    bottom.replaceChildren();

    tabs.forEach((tab) => {
      const isActive = state.activeTab === tab.id;
      rail.appendChild(
        el('button', {
          type: 'button',
          class: `nav-item${isActive ? ' active' : ''}`,
          onClick: () => switchTab(tab.id),
        }, [el('span', { text: tab.icon }), el('span', { text: tab.label })])
      );
      bottom.appendChild(
        el('button', {
          type: 'button',
          class: `bottom-nav-btn${isActive ? ' active' : ''}`,
          onClick: () => switchTab(tab.id),
        }, [el('span', { text: tab.icon }), el('span', { text: tab.label })])
      );
    });

    const current = tabs.find((x) => x.id === state.activeTab) || tabs[0];
    document.getElementById('top-title').textContent = current.label;
    document.getElementById('version-badge').textContent = `v${runtime.version} • Web`;
  }

  function switchTab(id) {
    state.activeTab = id;
    saveState();
    render();
  }

  function render() {
    applyThemeAndDirection();
    renderNav();
    const main = document.getElementById('main-content');
    main.replaceChildren();

    if (state.activeTab === 'hub') {
      main.appendChild(renderHubView());
    } else if (state.activeTab === 'resolver') {
      main.appendChild(renderResolverCard(true));
    } else if (state.activeTab === 'downloads') {
      main.appendChild(renderDownloadsView());
    } else {
      main.appendChild(renderSettingsView());
    }
  }

  function renderHubView() {
    const frag = document.createDocumentFragment();
    const isUnified = !state.useSeparateTabs;

    const searchCard = el('section', { class: 'card hero-card' }, [
      el('div', { class: 'section-header' }, [
        el('h2', { class: 'section-title', text: isUnified ? t('hubTitle') : t('discoverTitle') }),
        el('span', { class: `badge ${runtime.tmdbConfigured ? 'ok' : 'warn'}`, text: runtime.tmdbConfigured ? t('tmdbActive') : t('tmdbFallback') }),
      ]),
      el('form', {
        class: 'search-row',
        onSubmit: (e) => {
          e.preventDefault();
          const val = (isUnified ? runtime.smartInput : runtime.searchQuery).trim();
          if (!val) return;
          if (isUnified && isLikelyUrl(val)) {
            const url = normalizeInputUrl(val);
            runtime.resolverInput = url;
            runResolve(url);
          } else {
            runtime.searchQuery = val;
            runSearch(1, false);
          }
        },
      }, [
        el('input', {
          type: 'text',
          class: 'text-input',
          placeholder: isUnified ? t('unifiedPlaceholder') : t('searchPlaceholder'),
          value: isUnified ? runtime.smartInput : runtime.searchQuery,
          onInput: (e) => {
            if (isUnified) runtime.smartInput = e.target.value;
            else runtime.searchQuery = e.target.value;
          },
        }),
        el('button', { type: 'submit', class: 'btn btn-accent', text: isUnified ? t('go') : t('search') }),
      ]),
      el('div', { class: 'filters-grid' }, [
        el('div', { class: 'chips-row' }, [
          ['ALL', t('allTypes')],
          ['MOVIE', t('moviesOnly')],
          ['TV', t('tvOnly')],
        ].map(([typeVal, label]) =>
          el('button', {
            type: 'button',
            class: `chip-btn${runtime.mediaType === typeVal ? ' active' : ''}`,
            text: label,
            onClick: () => {
              runtime.mediaType = typeVal;
              if (runtime.hasSearched || runtime.genreId || runtime.minRating || runtime.year) runSearch(1, false);
              else render();
            },
          })
        )),
        el('select', {
          class: 'select-input',
          onChange: (e) => {
            runtime.genreId = e.target.value;
            runSearch(1, false);
          },
        }, [
          el('option', { value: '', text: t('allGenres') }),
          ...(runtime.genres || []).map((g) =>
            el('option', { value: String(g.id), text: g.name, ...(String(g.id) === String(runtime.genreId) ? { selected: 'selected' } : {}) })
          ),
        ]),
        el('select', {
          class: 'select-input',
          onChange: (e) => {
            runtime.minRating = e.target.value;
            runSearch(1, false);
          },
        }, [
          ['', t('minRating')],
          ['6', '6.0+'],
          ['7', '7.0+'],
          ['7.5', '7.5+'],
          ['8', '8.0+'],
        ].map(([val, lbl]) =>
          el('option', { value: val, text: lbl, ...(val === String(runtime.minRating) ? { selected: 'selected' } : {}) })
        )),
        el('input', {
          type: 'number',
          class: 'text-input',
          min: '1900',
          max: '2030',
          placeholder: t('anyYear'),
          value: runtime.year,
          onChange: (e) => {
            runtime.year = e.target.value;
            if (runtime.year || runtime.searchQuery) runSearch(1, false);
          },
        }),
      ]),
    ]);
    frag.appendChild(searchCard);

    if (isUnified && (runtime.resolving || runtime.resolveResult || runtime.resolveError)) {
      frag.appendChild(renderResolverCard(false));
    }

    if (runtime.searching || runtime.hasSearched || runtime.searchError) {
      const resultsCard = el('section', { class: 'card' }, [
        el('div', { class: 'section-header' }, [
          el('h3', { class: 'section-title', text: `${t('search')} (${runtime.searchResults.length})` }),
        ]),
      ]);
      if (runtime.searching) {
        resultsCard.appendChild(el('p', { class: 'muted', text: t('loading') }));
      } else if (runtime.searchError) {
        resultsCard.appendChild(el('p', { class: 'muted', text: runtime.searchError }));
      } else if (runtime.searchResults.length === 0) {
        resultsCard.appendChild(el('p', { class: 'muted', text: t('emptySearch') }));
      } else {
        resultsCard.appendChild(renderMediaGrid(runtime.searchResults));
        if (runtime.searchPage < runtime.totalPages) {
          resultsCard.appendChild(
            el('div', { style: 'margin-top:14px; text-align:center;' }, [
              el('button', {
                type: 'button',
                class: 'btn btn-secondary',
                text: t('loadMore'),
                onClick: () => runSearch(runtime.searchPage + 1, true),
              }),
            ])
          );
        }
      }
      frag.appendChild(resultsCard);
    }

    // Trending section
    const trendingCard = el('section', { class: 'card' }, [
      el('div', { class: 'section-header' }, [
        el('h3', { class: 'section-title', text: t('trendingTitle') }),
        el('div', { class: 'chips-row' }, [
          el('button', {
            type: 'button',
            class: `chip-btn${state.trendingWindow === 'day' ? ' active' : ''}`,
            text: t('windowDay'),
            onClick: () => {
              state.trendingWindow = 'day';
              saveState();
              loadTrending(true);
            },
          }),
          el('button', {
            type: 'button',
            class: `chip-btn${state.trendingWindow === 'week' ? ' active' : ''}`,
            text: t('windowWeek'),
            onClick: () => {
              state.trendingWindow = 'week';
              saveState();
              loadTrending(true);
            },
          }),
        ]),
      ]),
    ]);
    if (runtime.loadingTrending) {
      trendingCard.appendChild(el('p', { class: 'muted', text: t('loading') }));
    } else {
      const combinedTrending = interleave(runtime.trendingMovies, runtime.trendingTv);
      if (combinedTrending.length > 0) {
        trendingCard.appendChild(renderMediaGrid(combinedTrending));
      } else {
        trendingCard.appendChild(el('p', { class: 'muted', text: t('emptySearch') }));
      }
      if (runtime.trendingAttribution) {
        trendingCard.appendChild(el('p', { class: 'muted', style: 'margin-top:10px;font-size:0.78rem;', text: runtime.trendingAttribution }));
      }
    }
    frag.appendChild(trendingCard);

    if (state.favorites.length > 0) {
      frag.appendChild(
        el('section', { class: 'card' }, [
          el('div', { class: 'section-header' }, [el('h3', { class: 'section-title', text: `${t('favorites')} (${state.favorites.length})` })]),
          renderMediaGrid(state.favorites),
        ])
      );
    }

    return frag;
  }

  function interleave(a, b) {
    const out = [];
    const maxLen = Math.max(a.length, b.length);
    for (let i = 0; i < maxLen; i++) {
      if (a[i]) out.push(a[i]);
      if (b[i]) out.push(b[i]);
    }
    return out;
  }

  function renderMediaGrid(items) {
    return el(
      'div',
      { class: 'media-grid' },
      items.map((item) => {
        const poster = safeUrl(item.posterUrl);
        const primaryTitle = state.lang === 'fa' && item.persianTitle ? item.persianTitle : item.title;
        const subParts = [];
        if (item.releaseYear) subParts.push(String(item.releaseYear));
        if (item.persianTitle && primaryTitle !== item.persianTitle) subParts.push(item.persianTitle);
        else if (item.title !== primaryTitle) subParts.push(item.title);
        return el('article', {
          class: 'media-card',
          tabindex: '0',
          onClick: () => openDetailsModal(item),
          onKeydown: (e) => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault();
              openDetailsModal(item);
            }
          },
        }, [
          el('div', { class: 'poster-wrap' }, [
            poster
              ? el('img', { class: 'poster-img', src: poster, alt: primaryTitle, loading: 'lazy' })
              : el('div', { class: 'poster-placeholder', text: primaryTitle }),
            el('span', { class: 'type-pill', text: item.mediaType === 'TV' ? t('tvOnly') : t('moviesOnly') }),
            item.imdbRating ? el('span', { class: 'rating-pill', text: `★ ${Number(item.imdbRating).toFixed(1)}` }) : null,
          ]),
          el('div', { class: 'media-card-body' }, [
            el('div', { class: 'media-card-title', text: primaryTitle }),
            el('div', { class: 'media-card-sub', text: subParts.join(' • ') }),
          ]),
        ]);
      })
    );
  }

  function renderResolverCard(standalone) {
    const card = el('section', { class: 'card' }, [
      el('div', { class: 'section-header' }, [
        el('h2', { class: 'section-title', text: t('resolverTitle') }),
        !standalone && (runtime.resolveResult || runtime.resolveError)
          ? el('button', {
              type: 'button',
              class: 'chip-btn',
              text: t('close'),
              onClick: () => {
                runtime.resolveResult = null;
                runtime.resolveError = null;
                runtime.selectedOptionUrl = null;
                render();
              },
            })
          : null,
      ]),
    ]);

    if (standalone) {
      card.appendChild(
        el('form', {
          class: 'search-row',
          style: 'margin-bottom: 14px;',
          onSubmit: (e) => {
            e.preventDefault();
            const val = runtime.resolverInput.trim();
            if (val) runResolve(normalizeInputUrl(val));
          },
        }, [
          el('input', {
            type: 'url',
            class: 'text-input',
            placeholder: t('resolvePlaceholder'),
            value: runtime.resolverInput,
            onInput: (e) => (runtime.resolverInput = e.target.value),
          }),
          el('button', { type: 'submit', class: 'btn btn-accent', text: t('resolve') }),
        ])
      );
    }

    if (runtime.resolving) {
      card.appendChild(el('p', { class: 'muted', text: t('loading') }));
      return card;
    }

    if (runtime.resolveError) {
      card.appendChild(el('div', { class: 'badge err', text: runtime.resolveError }));
      return card;
    }

    const r = runtime.resolveResult;
    if (!r) {
      card.appendChild(el('p', { class: 'muted', text: t('resolvePlaceholder') }));
      return card;
    }

    if (r.mediaTitle || r.mediaAuthor) {
      card.appendChild(
        el('div', { class: 'item-row', style: 'margin-bottom:10px;' }, [
          el('div', { class: 'section-title', text: r.mediaTitle || r.sourceUrl }),
          r.mediaAuthor ? el('div', { class: 'muted', text: r.mediaAuthor }) : null,
        ])
      );
    }

    const options = buildNormalizedDownloadOptions(r);
    if (options.length > 1) {
      const selectedUrl = runtime.selectedOptionUrl || options[0].url;
      card.appendChild(
        el('div', { class: 'options-banner' }, [
          el('div', { class: 'section-header', style: 'margin-bottom:4px;' }, [
            el('strong', { text: `${t('downloadOptionsTitle')} (${options.length})` }),
            el('span', { class: 'badge ok', text: t('directUrlReady') }),
          ]),
          el('div', { class: 'muted', text: t('downloadOptionsPrompt') }),
        ])
      );

      const box = el('div', { class: 'list-stack', style: 'margin-top:10px;' });
      options.forEach((opt) => {
        const optSafe = safeUrl(opt.url);
        if (!optSafe) return;
        const isSelected = optSafe === selectedUrl;
        const fname = opt.fileName || 'mediax-file';
        const proxyUrl = `${apiBase()}/api/download-proxy?url=${encodeURIComponent(optSafe)}&filename=${encodeURIComponent(fname)}`;
        const isVideo = isVideoExtension(opt.extension, fname);

        box.appendChild(
          el('div', { class: `option-card${isSelected ? ' selected' : ''}` }, [
            el('div', { class: 'item-row-head' }, [
              el('div', { class: 'chips-row', style: 'align-items:center;' }, [
                opt.qualityLabel ? el('span', { class: 'badge pill-quality', text: opt.qualityLabel }) : null,
                opt.extension ? el('span', { class: 'badge', text: opt.extension }) : null,
                opt.fileSizeFormatted ? el('span', { class: 'badge', text: opt.fileSizeFormatted }) : null,
                isSelected ? el('span', { class: 'badge ok', text: t('selectedOptionBadge') }) : null,
              ]),
            ]),
            opt.label ? el('div', { style: 'font-weight:700;', text: opt.label }) : null,
            el('div', { style: 'word-break:break-all; font-weight:600; font-size:0.9rem;', text: fname }),
            el('div', { class: 'chips-row' }, [
              el('a', {
                class: 'btn btn-accent',
                href: proxyUrl,
                download: fname,
                onClick: () => {
                  runtime.selectedOptionUrl = optSafe;
                  recordDownloadHistory(fname, optSafe, opt.fileSizeFormatted);
                },
                text: t('downloadFile'),
              }),
              isVideo
                ? el('button', {
                    type: 'button',
                    class: 'btn btn-secondary',
                    text: t('playPreview'),
                    onClick: () => {
                      runtime.selectedOptionUrl = optSafe;
                      recordDownloadHistory(fname, optSafe, opt.fileSizeFormatted);
                      openVideoPreviewModal(fname, proxyUrl, optSafe);
                    },
                  })
                : null,
              el('button', {
                type: 'button',
                class: 'btn btn-secondary',
                text: t('copyUrl'),
                onClick: () => copyText(optSafe),
              }),
              isVideo
                ? el('a', {
                    class: 'btn btn-secondary',
                    href: `vlc://${optSafe}`,
                    text: t('openVlc'),
                  })
                : null,
              !isSelected
                ? el('button', {
                    type: 'button',
                    class: 'chip-btn',
                    text: t('selectOptionBtn'),
                    onClick: () => {
                      runtime.selectedOptionUrl = optSafe;
                      render();
                    },
                  })
                : null,
            ]),
          ])
        );
      });
      card.appendChild(box);
      return card;
    }

    const single = options[0];
    const directSafe = safeUrl(single ? single.url : r.directVideoUrl);
    if (directSafe) {
      const fname = (single && single.fileName) || r.filename || 'mediax-video.mp4';
      const ext = (single && single.extension) || r.extension;
      const sizeFmt = (single && single.fileSizeFormatted) || r.fileSizeFormatted;
      const isVideo = isVideoExtension(ext, fname);
      const proxyDownloadUrl = `${apiBase()}/api/download-proxy?url=${encodeURIComponent(directSafe)}&filename=${encodeURIComponent(fname)}`;
      const vlcSafe = `vlc://${directSafe}`;
      card.appendChild(
        el('div', { class: 'item-row', style: 'margin-bottom:12px;' }, [
          el('div', { class: 'item-row-head' }, [
            el('span', { class: 'badge ok', text: t('directUrlReady') }),
            el('span', { class: 'badge', text: [single && single.qualityLabel, ext, sizeFmt].filter(Boolean).join(' • ') || 'MEDIA' }),
          ]),
          single && single.label ? el('div', { style: 'font-weight:700;', text: single.label }) : null,
          el('div', { style: 'word-break:break-all; font-weight:700;', text: fname }),
          el('div', { class: 'muted', style: 'word-break:break-all; font-size:0.8rem;', text: directSafe }),
          el('div', { class: 'chips-row' }, [
            el('a', {
              class: 'btn btn-accent',
              href: proxyDownloadUrl,
              download: fname,
              onClick: () => recordDownloadHistory(fname, directSafe, sizeFmt),
              text: t('downloadFile'),
            }),
            isVideo
              ? el('button', {
                  type: 'button',
                  class: 'btn btn-secondary',
                  text: t('playPreview'),
                  onClick: () => openVideoPreviewModal(fname, proxyDownloadUrl, directSafe),
                })
              : null,
            el('button', {
              type: 'button',
              class: 'btn btn-secondary',
              text: t('copyUrl'),
              onClick: () => copyText(directSafe),
            }),
            isVideo
              ? el('a', {
                  class: 'btn btn-secondary',
                  href: vlcSafe,
                  text: t('openVlc'),
                })
              : null,
          ]),
        ])
      );
    }

    return card;
  }

  function isVideoExtension(ext, fileName) {
    const e = (ext || (fileName && fileName.includes('.') ? fileName.split('.').pop() : '') || '').toLowerCase();
    return ['mkv', 'mp4', 'mov', 'webm', 'm4v', 'avi', 'm3u8', 'ts', 'flv', '3gp'].includes(e);
  }

  function buildNormalizedDownloadOptions(r) {
    const out = [];
    const seen = new Set();
    if (Array.isArray(r.xVideoVariants) && r.xVideoVariants.length > 0) {
      r.xVideoVariants.forEach((v) => {
        const u = safeUrl(v.url);
        if (!u || seen.has(u)) return;
        seen.add(u);
        out.push({
          url: u,
          fileName: v.suggestedFileName || 'x-video.mp4',
          extension: (v.container || 'MP4').toUpperCase(),
          label: null,
          qualityLabel: v.qualityLabel || null,
          fileSizeFormatted: v.fileSizeFormatted || null,
        });
      });
    }
    if (Array.isArray(r.downloadOptions) && r.downloadOptions.length > 0) {
      r.downloadOptions.forEach((o) => {
        const u = safeUrl(o.url);
        if (!u || seen.has(u)) return;
        seen.add(u);
        out.push({
          url: u,
          fileName: o.fileName || 'mediax-file',
          extension: o.extension || null,
          label: o.label || null,
          qualityLabel: o.qualityLabel || null,
          fileSizeFormatted: o.fileSizeFormatted || null,
        });
      });
    }
    if (out.length === 0 && r.directVideoUrl) {
      const u = safeUrl(r.directVideoUrl);
      if (u) {
        out.push({
          url: u,
          fileName: r.filename || 'mediax-file',
          extension: r.extension || null,
          label: null,
          qualityLabel: null,
          fileSizeFormatted: r.fileSizeFormatted || null,
        });
      }
    }
    return out;
  }

  function renderDownloadsView() {
    const card = el('section', { class: 'card' }, [
      el('div', { class: 'section-header' }, [
        el('h2', { class: 'section-title', text: `${t('downloadsTitle')} (${state.downloads.length})` }),
        state.downloads.length > 0
          ? el('button', {
              type: 'button',
              class: 'chip-btn',
              text: t('clearHistory'),
              onClick: () => {
                state.downloads = [];
                saveState();
                render();
              },
            })
          : null,
      ]),
    ]);

    if (state.downloads.length === 0) {
      card.appendChild(el('p', { class: 'muted', text: t('noDownloads') }));
      return card;
    }

    const list = el('div', { class: 'list-stack' });
    state.downloads.forEach((d, idx) => {
      const safe = safeUrl(d.url);
      if (!safe) return;
      const fname = d.fileName || 'mediax-file.mp4';
      const proxyUrl = `${apiBase()}/api/download-proxy?url=${encodeURIComponent(safe)}&filename=${encodeURIComponent(fname)}`;
      list.appendChild(
        el('div', { class: 'item-row' }, [
          el('div', { class: 'item-row-head' }, [
            el('strong', { style: 'word-break:break-all;', text: fname }),
            d.size ? el('span', { class: 'badge', text: d.size }) : null,
          ]),
          el('div', { class: 'muted', style: 'word-break:break-all;font-size:0.8rem;', text: safe }),
          el('div', { class: 'chips-row' }, [
            el('a', { class: 'btn btn-accent', href: proxyUrl, download: fname, text: t('downloadFile') }),
            el('button', { type: 'button', class: 'btn btn-secondary', text: t('playPreview'), onClick: () => openVideoPreviewModal(fname, proxyUrl, safe) }),
            el('button', { type: 'button', class: 'btn btn-secondary', text: t('copyUrl'), onClick: () => copyText(safe) }),
            el('a', { class: 'btn btn-secondary', href: `vlc://${safe}`, text: t('openVlc') }),
            el('button', {
              type: 'button',
              class: 'btn btn-secondary',
              text: t('deleteBtn'),
              onClick: () => {
                state.downloads.splice(idx, 1);
                saveState();
                render();
              },
            }),
          ]),
        ])
      );
    });
    card.appendChild(list);
    return card;
  }

  function renderSettingsView() {
    const wrap = el('div', { class: 'list-stack' });

    const general = el('section', { class: 'card list-stack' }, [
      el('h2', { class: 'section-title', text: t('settingsTitle') }),
      el('div', {}, [
        el('div', { class: 'muted', style: 'margin-bottom:6px;', text: t('languageLabel') }),
        el('div', { class: 'chips-row' }, [
          el('button', {
            type: 'button',
            class: `chip-btn${state.lang === 'fa' ? ' active' : ''}`,
            text: 'فارسی (RTL)',
            onClick: () => { state.lang = 'fa'; saveState(); render(); },
          }),
          el('button', {
            type: 'button',
            class: `chip-btn${state.lang === 'en' ? ' active' : ''}`,
            text: 'English (LTR)',
            onClick: () => { state.lang = 'en'; saveState(); render(); },
          }),
        ]),
      ]),
      el('div', {}, [
        el('div', { class: 'muted', style: 'margin-bottom:6px;', text: t('themeLabel') }),
        el('div', { class: 'chips-row' }, [
          ['system', t('themeSystem')],
          ['dark', t('themeDark')],
          ['light', t('themeLight')],
        ].map(([code, lbl]) =>
          el('button', {
            type: 'button',
            class: `chip-btn${state.theme === code ? ' active' : ''}`,
            text: lbl,
            onClick: () => { state.theme = code; saveState(); render(); },
          })
        )),
      ]),
      el('div', {}, [
        el('div', { class: 'muted', style: 'margin-bottom:6px;', text: t('tabsModeLabel') }),
        el('div', { class: 'chips-row' }, [
          el('button', {
            type: 'button',
            class: `chip-btn${!state.useSeparateTabs ? ' active' : ''}`,
            text: t('tabsUnified'),
            onClick: () => { state.useSeparateTabs = false; saveState(); render(); },
          }),
          el('button', {
            type: 'button',
            class: `chip-btn${state.useSeparateTabs ? ' active' : ''}`,
            text: t('tabsSeparate'),
            onClick: () => { state.useSeparateTabs = true; saveState(); render(); },
          }),
        ]),
      ]),
      el('div', {}, [
        el('div', { class: 'muted', style: 'margin-bottom:6px;', text: t('searchLangLabel') }),
        el('div', { class: 'chips-row' }, [
          ['both', 'Both (EN + FA)'],
          ['fa', 'Persian (FA)'],
          ['en', 'English (EN)'],
        ].map(([code, lbl]) =>
          el('button', {
            type: 'button',
            class: `chip-btn${state.searchLang === code ? ' active' : ''}`,
            text: lbl,
            onClick: () => {
              state.searchLang = code;
              saveState();
              loadGenres();
              loadTrending(true);
              render();
            },
          })
        )),
      ]),
      el('div', {}, [
        el('div', { class: 'muted', style: 'margin-bottom:6px;', text: t('watchRegionLabel') }),
        el('select', {
          class: 'select-input',
          onChange: (e) => {
            state.watchRegion = e.target.value;
            saveState();
            loadTrending(true);
          },
        }, (runtime.watchRegions || []).map((r) =>
          el('option', {
            value: r.code,
            text: state.lang === 'fa' ? r.labelFa : r.labelEn,
            ...(r.code === state.watchRegion ? { selected: 'selected' } : {}),
          })
        )),
      ]),
      el('div', {}, [
        el('div', { class: 'muted', style: 'margin-bottom:6px;', text: t('backendUrlLabel') }),
        el('div', { class: 'search-row' }, [
          el('input', {
            id: 'backend-url-input',
            type: 'url',
            class: 'text-input',
            placeholder: 'https://your-mediax-server.example.com',
            value: state.backendUrl || '',
          }),
          el('button', {
            type: 'button',
            class: 'btn btn-secondary',
            text: t('saveBtn'),
            onClick: () => {
              const v = document.getElementById('backend-url-input').value.trim();
              if (v && !safeUrl(v)) {
                showToast('Invalid URL');
                return;
              }
              state.backendUrl = v;
              saveState();
              bootstrap();
              showToast(t('saveBtn'));
            },
          }),
        ]),
        el('div', { class: 'muted', style: 'margin-top:4px;font-size:0.8rem;', text: t('backendUrlHint') }),
      ]),
      el('div', {}, [
        el('a', {
          class: 'btn btn-secondary',
          href: 'https://github.com/alonedroid-maker/mixmedia/releases',
          target: '_blank',
          rel: 'noopener noreferrer',
          text: `${t('checkUpdates')} (v${runtime.version})`,
        }),
      ]),
    ]);
    wrap.appendChild(general);

    // Source Sites Management
    const sitesCard = el('section', { class: 'card list-stack' }, [
      el('h3', { class: 'section-title', text: t('sourceSitesLabel') }),
    ]);
    effectiveSites().forEach((s) => {
      sitesCard.appendChild(
        el('div', { class: 'item-row' }, [
          el('div', { class: 'item-row-head' }, [
            el('div', {}, [
              el('strong', { text: s.name }),
              el('div', { class: 'muted', style: 'font-size:0.8rem;', text: s.baseUrl }),
            ]),
            el('div', { class: 'chips-row' }, [
              el('button', {
                type: 'button',
                class: `chip-btn${s.enabled ? ' active' : ''}`,
                text: s.enabled ? 'ON' : 'OFF',
                onClick: () => {
                  const set = new Set(state.disabledSiteIds || []);
                  if (s.enabled) set.add(s.id);
                  else set.delete(s.id);
                  state.disabledSiteIds = Array.from(set);
                  saveState();
                  render();
                },
              }),
              s.isCustom
                ? el('button', {
                    type: 'button',
                    class: 'chip-btn',
                    text: t('deleteBtn'),
                    onClick: () => {
                      state.customSites = (state.customSites || []).filter((x) => x.id !== s.id);
                      saveState();
                      render();
                    },
                  })
                : null,
            ]),
          ]),
        ])
      );
    });

    sitesCard.appendChild(
      el('form', {
        class: 'list-stack',
        style: 'margin-top:8px;',
        onSubmit: (e) => {
          e.preventDefault();
          const name = document.getElementById('new-site-name').value.trim();
          const base = document.getElementById('new-site-base').value.trim();
          const tpl = document.getElementById('new-site-tpl').value.trim();
          if (!name || !safeUrl(base) || !safeUrl(tpl.replace('{query}', 'test'))) {
            showToast('Invalid site URL');
            return;
          }
          state.customSites = [
            ...(state.customSites || []),
            { id: `custom_${Date.now()}`, name, baseUrl: base, searchUrlTemplate: tpl, isCustom: true, isArchive: false },
          ];
          saveState();
          render();
        },
      }, [
        el('strong', { text: t('addSiteTitle') }),
        el('input', { id: 'new-site-name', type: 'text', class: 'text-input', placeholder: t('siteNamePlaceholder') }),
        el('input', { id: 'new-site-base', type: 'url', class: 'text-input', placeholder: t('siteBasePlaceholder') }),
        el('input', { id: 'new-site-tpl', type: 'url', class: 'text-input', placeholder: t('siteTemplatePlaceholder') }),
        el('button', { type: 'submit', class: 'btn btn-secondary', text: t('addSiteBtn') }),
      ])
    );

    wrap.appendChild(sitesCard);
    return wrap;
  }

  async function loadTrending(forceRefresh = false) {
    runtime.loadingTrending = true;
    render();
    try {
      const qs = new URLSearchParams({
        window: state.trendingWindow,
        lang: state.searchLang,
        region: state.watchRegion,
        refresh: forceRefresh ? 'true' : 'false',
      });
      const data = await apiGet(`/api/trending?${qs.toString()}`);
      runtime.trendingMovies = data.movies || [];
      runtime.trendingTv = data.tvShows || [];
      runtime.trendingAttribution = data.attribution || '';
    } catch (_) {
      runtime.trendingMovies = [];
      runtime.trendingTv = [];
    } finally {
      runtime.loadingTrending = false;
      render();
    }
  }

  async function loadGenres() {
    try {
      const data = await apiGet(`/api/genres?lang=${encodeURIComponent(state.searchLang)}`);
      runtime.genres = data.genres || [];
    } catch (_) { /* ignore */ }
  }

  async function runSearch(page = 1, append = false) {
    runtime.searching = !append;
    runtime.hasSearched = true;
    runtime.searchError = null;
    if (!append) runtime.searchResults = [];
    render();

    try {
      const qs = new URLSearchParams({
        q: runtime.searchQuery,
        type: runtime.mediaType,
        page: String(page),
        lang: state.searchLang,
        region: state.watchRegion,
      });
      if (runtime.genreId) qs.set('genreId', runtime.genreId);
      if (runtime.minRating) qs.set('minRating', runtime.minRating);
      if (runtime.year) qs.set('year', runtime.year);

      const data = await apiGet(`/api/search?${qs.toString()}`);
      const items = data.items || [];
      runtime.searchResults = append ? [...runtime.searchResults, ...items] : items;
      runtime.searchPage = data.page || page;
      runtime.totalPages = data.totalPages || 1;
    } catch (e) {
      runtime.searchError = e.message || 'Search failed';
    } finally {
      runtime.searching = false;
      render();
    }
  }

  async function runResolve(url) {
    runtime.resolving = true;
    runtime.resolveResult = null;
    runtime.resolveError = null;
    runtime.selectedOptionUrl = null;
    render();

    try {
      const data = await apiPost('/api/resolve', { url });
      runtime.resolveResult = data;
      runtime.selectedOptionUrl = data.directVideoUrl || null;
      const optionCount = (data.downloadOptions || []).length + (data.xVideoVariants || []).length;
      if (data.directVideoUrl && optionCount <= 1) {
        recordDownloadHistory(data.filename || 'mediax-video.mp4', data.directVideoUrl, data.fileSizeFormatted);
      }
    } catch (e) {
      runtime.resolveError = e.message || 'Resolution failed';
    } finally {
      runtime.resolving = false;
      render();
    }
  }

  function recordDownloadHistory(fileName, url, size) {
    const safe = safeUrl(url);
    if (!safe) return;
    const filtered = (state.downloads || []).filter((x) => x.url !== safe);
    state.downloads = [{ fileName, url: safe, size: size || null, savedAt: Date.now() }, ...filtered].slice(0, 40);
    saveState();
  }

  function copyText(text) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(() => showToast(t('copied'))).catch(() => showToast(text));
    } else {
      showToast(text);
    }
  }

  function showModal(contentNode) {
    const backdrop = document.getElementById('modal-backdrop');
    const sheet = document.getElementById('modal-sheet');
    sheet.replaceChildren(contentNode);
    backdrop.classList.remove('hidden');
  }

  function closeModal() {
    const backdrop = document.getElementById('modal-backdrop');
    const sheet = document.getElementById('modal-sheet');
    backdrop.classList.add('hidden');
    sheet.replaceChildren();
  }

  async function openDetailsModal(summary) {
    const loadingBox = el('div', { class: 'list-stack' }, [
      el('div', { class: 'section-header' }, [
        el('h2', { class: 'section-title', text: summary.persianTitle || summary.title }),
        el('button', { type: 'button', class: 'chip-btn', text: t('close'), onClick: closeModal }),
      ]),
      el('p', { class: 'muted', text: t('loading') }),
    ]);
    showModal(loadingBox);

    try {
      const qs = new URLSearchParams({
        id: summary.id || '',
        type: summary.mediaType || 'MOVIE',
        title: summary.title || '',
        lang: state.searchLang,
        region: state.watchRegion,
      });
      if (summary.tmdbId) qs.set('tmdbId', String(summary.tmdbId));
      if (summary.imdbId) qs.set('imdbId', summary.imdbId);
      if (summary.tvMazeId) qs.set('tvMazeId', String(summary.tvMazeId));
      if (summary.releaseYear) qs.set('year', String(summary.releaseYear));

      const d = await apiGet(`/api/details?${qs.toString()}`);
      renderDetailsSheet(d, summary);
    } catch (e) {
      showModal(el('div', { class: 'list-stack' }, [
        el('div', { class: 'section-header' }, [
          el('h2', { class: 'section-title', text: summary.title }),
          el('button', { type: 'button', class: 'chip-btn', text: t('close'), onClick: closeModal }),
        ]),
        el('p', { class: 'muted', text: e.message }),
      ]));
    }
  }

  function renderDetailsSheet(d, summary) {
    const isFav = (state.favorites || []).some((x) => x.id === (d.id || summary.id));
    const poster = safeUrl(d.posterUrl || summary.posterUrl);
    const titleMain = state.lang === 'fa' && d.persianTitle ? d.persianTitle : d.title;
    const metaLine = [
      d.releaseYear || d.startYear,
      d.runtimeMinutes ? `${d.runtimeMinutes}m` : (d.episodeRuntimeMinutes ? `${d.episodeRuntimeMinutes}m/ep` : null),
      d.imdbRating ? `IMDb ${Number(d.imdbRating).toFixed(1)}` : null,
      (d.genres || []).join(', '),
    ].filter(Boolean).join(' • ');

    const body = el('div', { class: 'list-stack' }, [
      el('div', { class: 'section-header' }, [
        el('h2', { class: 'section-title', text: titleMain }),
        el('button', { type: 'button', class: 'chip-btn', text: t('close'), onClick: closeModal }),
      ]),
      el('div', { class: 'detail-hero' }, [
        poster ? el('img', { class: 'detail-poster', src: poster, alt: titleMain }) : null,
        el('div', { class: 'list-stack', style: 'flex:1;min-width:200px;' }, [
          d.persianTitle && d.title && d.persianTitle !== d.title
            ? el('div', { class: 'muted', text: `${d.title} • ${d.persianTitle}` })
            : null,
          el('div', { class: 'muted', text: metaLine }),
          el('div', { class: 'chips-row' }, [
            el('button', {
              type: 'button',
              class: 'btn btn-accent',
              text: t('findSources'),
              onClick: () => openSourcesModal({
                mediaId: d.id,
                mediaType: d.mediaType,
                title: d.title,
                persianTitle: d.persianTitle,
                releaseYear: d.releaseYear || d.startYear,
              }),
            }),
            el('button', {
              type: 'button',
              class: 'btn btn-secondary',
              text: isFav ? t('removeFavorite') : t('addFavorite'),
              onClick: () => {
                if (isFav) {
                  state.favorites = state.favorites.filter((x) => x.id !== d.id);
                } else {
                  state.favorites = [{
                    id: d.id,
                    mediaType: d.mediaType,
                    title: d.title,
                    persianTitle: d.persianTitle,
                    releaseYear: d.releaseYear || d.startYear,
                    posterUrl: d.posterUrl,
                    imdbRating: d.imdbRating,
                    tmdbId: d.tmdbId,
                    imdbId: d.imdbId,
                    tvMazeId: d.tvMazeId,
                  }, ...(state.favorites || [])];
                }
                saveState();
                render();
                renderDetailsSheet(d, summary);
              },
            }),
          ]),
        ]),
      ]),
    ]);

    if (d.persianOverview) {
      body.appendChild(el('div', { class: 'item-row', dir: 'rtl' }, [
        el('strong', { text: t('persianSummary') }),
        el('p', { style: 'margin:0;', text: d.persianOverview }),
      ]));
    }
    if (d.overview) {
      body.appendChild(el('div', { class: 'item-row', dir: 'ltr' }, [
        el('strong', { text: t('englishSummary') }),
        el('p', { style: 'margin:0;', text: d.overview }),
      ]));
    }

    if (Array.isArray(d.cast) && d.cast.length > 0) {
      body.appendChild(el('strong', { text: t('castLabel') }));
      body.appendChild(
        el('div', { class: 'chips-row' }, d.cast.slice(0, 12).map((c) =>
          el('span', { class: 'badge', text: c.character ? `${c.name} (${c.character})` : c.name })
        ))
      );
    }

    if (Array.isArray(d.watchProviders) && d.watchProviders.length > 0) {
      body.appendChild(el('strong', { text: t('watchProviders') }));
      body.appendChild(
        el('div', { class: 'chips-row' }, d.watchProviders.slice(0, 10).map((p) => {
          const pUrl = safeUrl(p.url);
          return pUrl
            ? el('a', { class: 'chip-btn', href: pUrl, target: '_blank', rel: 'noopener noreferrer', text: p.name })
            : el('span', { class: 'badge', text: p.name });
        }))
      );
    }

    if (Array.isArray(d.seasons) && d.seasons.length > 0) {
      body.appendChild(el('strong', { text: t('seasonsLabel') }));
      const seasonsBox = el('div', { class: 'list-stack' });
      d.seasons.slice(0, 15).forEach((s) => {
        const seasonRow = el('div', { class: 'item-row' }, [
          el('div', { class: 'item-row-head' }, [
            el('strong', { text: `${s.title} (${s.episodeCount})` }),
            el('button', {
              type: 'button',
              class: 'chip-btn',
              text: t('findSources'),
              onClick: () => openSourcesModal({
                mediaId: d.id,
                mediaType: d.mediaType,
                title: d.title,
                persianTitle: d.persianTitle,
                releaseYear: d.releaseYear || d.startYear,
                seasonNumber: s.seasonNumber,
              }),
            }),
          ]),
        ]);
        seasonsBox.appendChild(seasonRow);
      });
      body.appendChild(seasonsBox);
    }

    showModal(body);
  }

  async function openSourcesModal(target, customQuery = '') {
    showModal(el('div', { class: 'list-stack' }, [
      el('div', { class: 'section-header' }, [
        el('h2', { class: 'section-title', text: `${t('sourcesTitle')}: ${target.persianTitle || target.title}` }),
        el('button', { type: 'button', class: 'chip-btn', text: t('close'), onClick: closeModal }),
      ]),
      el('p', { class: 'muted', text: t('loading') }),
    ]));

    try {
      const data = await apiPost('/api/sources', {
        target,
        sites: effectiveSites(),
        lang: state.searchLang,
        region: state.watchRegion,
        customQuery,
      });

      const box = el('div', { class: 'list-stack' }, [
        el('div', { class: 'section-header' }, [
          el('h2', { class: 'section-title', text: `${t('sourcesTitle')}: ${target.persianTitle || target.title}` }),
          el('button', { type: 'button', class: 'chip-btn', text: t('close'), onClick: closeModal }),
        ]),
        el('form', {
          class: 'search-row',
          onSubmit: (e) => {
            e.preventDefault();
            openSourcesModal(target, document.getElementById('custom-source-query').value.trim());
          },
        }, [
          el('input', {
            id: 'custom-source-query',
            type: 'text',
            class: 'text-input',
            placeholder: t('customQuery'),
            value: customQuery,
          }),
          el('button', { type: 'submit', class: 'btn btn-secondary', text: t('searchAgain') }),
        ]),
      ]);

      if (Array.isArray(data.searchEngineLaunchUrls) && data.searchEngineLaunchUrls.length > 0) {
        box.appendChild(el('strong', { text: t('searchEngines') }));
        box.appendChild(
          el('div', { class: 'chips-row' }, data.searchEngineLaunchUrls.map((item) => {
            const u = safeUrl(item.url);
            return u ? el('a', { class: 'chip-btn', href: u, target: '_blank', rel: 'noopener noreferrer', text: item.displayName }) : null;
          }))
        );
      }

      const siteResults = data.siteResults || [];
      box.appendChild(el('strong', { text: `${t('siteResults')} (${siteResults.length})` }));
      if (siteResults.length === 0) {
        box.appendChild(el('p', { class: 'muted', text: t('noSourcesYet') }));
      } else {
        siteResults.forEach((r) => box.appendChild(renderSourceItemRow(r)));
      }

      const webResults = data.webSearchResults || [];
      if (webResults.length > 0) {
        box.appendChild(el('strong', { text: `${t('webResults')} (${webResults.length})` }));
        webResults.forEach((r) => box.appendChild(renderSourceItemRow(r)));
      }

      showModal(box);
    } catch (e) {
      showModal(el('div', { class: 'list-stack' }, [
        el('div', { class: 'section-header' }, [
          el('h2', { class: 'section-title', text: t('sourcesTitle') }),
          el('button', { type: 'button', class: 'chip-btn', text: t('close'), onClick: closeModal }),
        ]),
        el('p', { class: 'muted', text: e.message }),
      ]));
    }
  }

  function renderSourceItemRow(r) {
    const u = safeUrl(r.url);
    const statusBadge = el('span', { class: 'badge', text: r.statusNote || r.status });
    return el('div', { class: 'item-row' }, [
      el('div', { class: 'item-row-head' }, [
        el('div', {}, [
          el('strong', { text: r.title }),
          el('div', { class: 'muted', style: 'font-size:0.8rem;', text: `${r.siteName} • ${r.domain}` }),
        ]),
        statusBadge,
      ]),
      r.snippet ? el('div', { class: 'muted', style: 'font-size:0.84rem;', text: r.snippet }) : null,
      u
        ? el('div', { class: 'chips-row' }, [
            el('button', {
              type: 'button',
              class: 'chip-btn active',
              text: t('sendToResolver'),
              onClick: () => {
                closeModal();
                runtime.resolverInput = u;
                runtime.smartInput = u;
                if (state.useSeparateTabs) state.activeTab = 'resolver';
                runResolve(u);
              },
            }),
            el('button', {
              type: 'button',
              class: 'chip-btn',
              text: t('verifyLink'),
              onClick: async () => {
                statusBadge.textContent = '...';
                try {
                  const res = await apiPost('/api/sources/verify', { url: u });
                  statusBadge.textContent = res.statusNote || res.status;
                } catch (err) {
                  statusBadge.textContent = err.message;
                }
              },
            }),
            el('a', { class: 'chip-btn', href: u, target: '_blank', rel: 'noopener noreferrer', text: t('openExternal') }),
          ])
        : null,
    ]);
  }

  function openVideoPreviewModal(title, proxyUrl, directUrl) {
    const box = el('div', { class: 'list-stack' }, [
      el('div', { class: 'section-header' }, [
        el('h3', { class: 'section-title', text: title }),
        el('button', { type: 'button', class: 'chip-btn', text: t('close'), onClick: closeModal }),
      ]),
      el('video', {
        class: 'video-preview',
        controls: 'controls',
        playsinline: 'playsinline',
        src: proxyUrl,
      }),
      el('div', { class: 'chips-row' }, [
        el('a', { class: 'btn btn-accent', href: proxyUrl, download: title, text: t('downloadFile') }),
        el('a', { class: 'btn btn-secondary', href: `vlc://${directUrl}`, text: t('openVlc') }),
      ]),
    ]);
    showModal(box);
  }

  async function bootstrap() {
    applyThemeAndDirection();
    renderNav();

    const isIosSafari = /iPhone|iPad|iPod/i.test(navigator.userAgent) && !window.navigator.standalone;
    const banner = document.getElementById('ios-install-banner');
    if (isIosSafari && !state.iosBannerDismissed) {
      document.getElementById('ios-install-text').textContent = t('iosBanner');
      banner.classList.remove('hidden');
    } else {
      banner.classList.add('hidden');
    }

    try {
      const cfg = await apiGet('/api/config');
      runtime.version = cfg.version || '2.0';
      runtime.tmdbConfigured = Boolean(cfg.tmdbConfigured);
      runtime.builtInSites = cfg.builtInSites || [];
      runtime.watchRegions = cfg.watchRegions || [];
    } catch (_) { /* ignore */ }

    await Promise.all([loadGenres(), loadTrending(false)]);
    render();
  }

  document.getElementById('quick-lang-btn').addEventListener('click', () => {
    state.lang = state.lang === 'fa' ? 'en' : 'fa';
    saveState();
    render();
  });
  document.getElementById('mobile-lang-btn').addEventListener('click', () => {
    state.lang = state.lang === 'fa' ? 'en' : 'fa';
    saveState();
    render();
  });
  const cycleTheme = () => {
    const order = ['system', 'dark', 'light'];
    state.theme = order[(order.indexOf(state.theme) + 1) % order.length];
    saveState();
    render();
  };
  document.getElementById('quick-theme-btn').addEventListener('click', cycleTheme);
  document.getElementById('mobile-theme-btn').addEventListener('click', cycleTheme);

  document.getElementById('ios-install-close').addEventListener('click', () => {
    state.iosBannerDismissed = true;
    saveState();
    document.getElementById('ios-install-banner').classList.add('hidden');
  });

  document.getElementById('modal-backdrop').addEventListener('click', (e) => {
    if (e.target.id === 'modal-backdrop') closeModal();
  });

  if ('serviceWorker' in navigator) {
    window.addEventListener('load', () => {
      navigator.serviceWorker.register('/sw.js').catch(() => {});
    });
  }

  bootstrap();
})();
