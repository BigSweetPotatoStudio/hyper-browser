(function () {
  const patches = [
    globalThis.hyperBrowserSiteCompatXPreludeWorkerFallback
  ].filter(Boolean);

  for (const patch of patches) {
    try {
      patch.register(browser);
    } catch (error) {
      console.error("[HyperSiteCompat] failed to register " + patch.id, error);
    }
  }
})();
