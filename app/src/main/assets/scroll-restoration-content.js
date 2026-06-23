(function () {
  if (window.top !== window) return;

  const storagePrefix = "hyper.scroll.";
  const indexKey = `${storagePrefix}index`;
  const maxEntries = 64;
  const minRestoreY = 80;
  const maxEntryAgeMs = 1000 * 60 * 60 * 6;
  const restoreDelays = [0, 80, 250, 700, 1500, 3000];
  let userInteracted = false;
  let saveTimer = 0;

  function pageKey() {
    return `${storagePrefix}${location.href}`;
  }

  function readEntry(key) {
    try {
      const raw = sessionStorage.getItem(key);
      if (!raw) return null;
      const entry = JSON.parse(raw);
      if (!entry || typeof entry.y !== "number" || typeof entry.t !== "number") return null;
      if (Date.now() - entry.t > maxEntryAgeMs) {
        sessionStorage.removeItem(key);
        return null;
      }
      return entry;
    } catch {
      return null;
    }
  }

  function writeIndex(key) {
    try {
      const current = JSON.parse(sessionStorage.getItem(indexKey) || "[]");
      const next = [key].concat(current.filter((item) => item !== key)).slice(0, maxEntries);
      sessionStorage.setItem(indexKey, JSON.stringify(next));
      current.filter((item) => !next.includes(item)).forEach((item) => sessionStorage.removeItem(item));
    } catch {
      try {
        sessionStorage.setItem(indexKey, JSON.stringify([key]));
      } catch {
      }
    }
  }

  function saveScrollNow() {
    try {
      const y = window.scrollY || document.documentElement.scrollTop || document.body.scrollTop || 0;
      const x = window.scrollX || document.documentElement.scrollLeft || document.body.scrollLeft || 0;
      if (y < 1 && x < 1) return;
      const key = pageKey();
      sessionStorage.setItem(key, JSON.stringify({ x, y, t: Date.now() }));
      writeIndex(key);
    } catch {
    }
  }

  function saveScrollSoon() {
    if (saveTimer) return;
    saveTimer = window.setTimeout(() => {
      saveTimer = 0;
      saveScrollNow();
    }, 120);
  }

  function canRestoreTo(y) {
    const root = document.scrollingElement || document.documentElement || document.body;
    if (!root) return false;
    return root.scrollHeight >= y + Math.min(window.innerHeight || 0, 600);
  }

  function restoreScroll() {
    if (userInteracted) return;
    const entry = readEntry(pageKey());
    if (!entry || entry.y < minRestoreY) return;

    if ("scrollRestoration" in history) {
      try {
        history.scrollRestoration = "manual";
      } catch {
      }
    }

    restoreDelays.forEach((delay, index) => {
      window.setTimeout(() => {
        if (userInteracted) return;
        const currentY = window.scrollY || document.documentElement.scrollTop || document.body.scrollTop || 0;
        if (Math.abs(currentY - entry.y) <= 24) return;
        if (currentY > entry.y * 0.5) return;
        if (!canRestoreTo(entry.y) && index < restoreDelays.length - 1) return;
        window.scrollTo(entry.x || 0, entry.y);
      }, delay);
    });
  }

  function markUserInteracted() {
    userInteracted = true;
    saveScrollSoon();
  }

  function wrapHistoryMethod(name) {
    const original = history[name];
    if (typeof original !== "function") return;
    history[name] = function () {
      saveScrollNow();
      const result = original.apply(this, arguments);
      userInteracted = false;
      restoreScroll();
      return result;
    };
  }

  window.addEventListener("scroll", saveScrollSoon, { passive: true });
  window.addEventListener("pagehide", saveScrollNow, { capture: true });
  window.addEventListener("beforeunload", saveScrollNow, { capture: true });
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") saveScrollNow();
  }, true);

  ["touchstart", "wheel", "mousedown", "keydown"].forEach((type) => {
    window.addEventListener(type, markUserInteracted, { capture: true, passive: true });
  });

  wrapHistoryMethod("pushState");
  wrapHistoryMethod("replaceState");
  window.addEventListener("popstate", () => {
    userInteracted = false;
    restoreScroll();
  });
  window.addEventListener("pageshow", () => {
    userInteracted = false;
    restoreScroll();
  });
  document.addEventListener("DOMContentLoaded", restoreScroll, true);
  window.addEventListener("load", restoreScroll, true);
  restoreScroll();
})();
