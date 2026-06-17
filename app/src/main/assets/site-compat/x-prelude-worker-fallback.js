(function () {
  const PATCH_ID = "x-prelude-worker-fallback";
  const PRELUDE_SCRIPT_URLS = [
    "https://abs.twimg.com/responsive-web/client-web/prelude*.js"
  ];

  // X creates its core worker from abs.twimg.com while the page origin is x.com.
  // Gecko rejects that cross-origin worker path, so disable only that worker creation
  // and let X use the fallback path already present in the prelude script.
  function disableCrossOriginCoreWorker(source) {
    return source
      .replace(
        /this\.worker=new Worker\(new URL\(n\.p\+n\.u\(\d+\),n\.b\),\{type:void 0\}\)/g,
        "this.worker=void 0"
      )
      .replace(
        /this\.worker=new Worker\(new URL\(n\.p\+n\.u\(\d+\),n\.b\)\)/g,
        "this.worker=void 0"
      );
  }

  function register(browserApi) {
    const webRequest = browserApi && browserApi.webRequest;
    if (!webRequest || !webRequest.onBeforeRequest || !webRequest.filterResponseData) {
      console.warn("[HyperSiteCompat] " + PATCH_ID + " unavailable: filterResponseData is missing.");
      return;
    }

    webRequest.onBeforeRequest.addListener(
      (details) => {
        const filter = webRequest.filterResponseData(details.requestId);
        const decoder = new TextDecoder("utf-8");
        const encoder = new TextEncoder();
        let body = "";

        filter.ondata = (event) => {
          body += decoder.decode(event.data, { stream: true });
        };

        filter.onstop = () => {
          body += decoder.decode();
          let patched = body;
          try {
            patched = disableCrossOriginCoreWorker(body);
          } catch (error) {
            console.error("[HyperSiteCompat] " + PATCH_ID + " failed while patching.", error);
          }
          filter.write(encoder.encode(patched));
          filter.close();
        };

        filter.onerror = () => {
          try {
            filter.disconnect();
          } catch (error) {
            // Gecko has already failed this response filter.
          }
        };
      },
      { urls: PRELUDE_SCRIPT_URLS, types: ["script"] },
      ["blocking"]
    );
  }

  globalThis.hyperBrowserSiteCompatXPreludeWorkerFallback = {
    id: PATCH_ID,
    register
  };
})();
