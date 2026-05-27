(function () {
  const nativeApp = "hyperBrowser";

  function canUseBridge() {
    return typeof browser !== "undefined" &&
      browser.runtime &&
      typeof browser.runtime.sendMessage === "function";
  }

  function send(type, payload) {
    if (!canUseBridge()) return Promise.reject(new Error("Hyper bridge unavailable."));
    return browser.runtime.sendMessage({ nativeApp, type, payload: payload || {} })
      .then((response) => {
        if (typeof response === "string") {
          response = JSON.parse(response);
        }
        if (!response || response.ok !== true) {
          throw new Error((response && response.error) || "Hyper bridge request failed.");
        }
        return response;
      });
  }

  function command(type, params) {
    send(type, params).catch((error) => {
      console.error(error);
    });
  }

  function requestData(type) {
    return send(type).then((response) => {
      if (!response || typeof response.itemsJson !== "string") return [];
      const items = JSON.parse(response.itemsJson);
      return Array.isArray(items) ? items : [];
    });
  }

  window.hyperBrowser = {
    open(input) {
      command("search.submit", { query: input });
    },
    showBookmarks() {
      window.location.href = "hyper://bookmarks";
    },
    showHistory() {
      window.location.href = "hyper://history";
    },
    showExtensions() {
      command("panel.extensions");
    },
    requestHomeData() {
      return requestData("data.home");
    },
    requestBookmarksData() {
      return requestData("data.bookmarks");
    },
    requestHistoryData() {
      return requestData("data.history");
    },
    openBookmark(url) {
      command("bookmarks.open", { url });
    },
    removeBookmark(url) {
      command("bookmarks.remove", { url });
    },
    editBookmark(oldUrl, title, url) {
      command("bookmarks.edit", { oldUrl, title, url });
    },
    openHistory(url) {
      command("history.open", { url });
    },
    removeHistory(url) {
      command("history.remove", { url });
    },
    clearHistory() {
      command("history.clear");
    }
  };
})();
