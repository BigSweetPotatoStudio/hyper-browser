(function () {
  function navigate(path, params) {
    const url = new URL("hyper://command/" + path);
    Object.entries(params || {}).forEach(([key, value]) => {
      if (value !== undefined && value !== null) {
        url.searchParams.set(key, String(value));
      }
    });
    window.location.href = url.toString();
  }

  window.hyperBrowser = {
    open(input) {
      navigate("search/submit", { query: input });
    },
    showBookmarks() {
      window.location.href = "hyper://bookmarks";
    },
    showHistory() {
      window.location.href = "hyper://history";
    },
    showExtensions() {
      navigate("panel/extensions");
    },
    openBookmark(url) {
      navigate("bookmarks/open", { url });
    },
    removeBookmark(url) {
      navigate("bookmarks/remove", { url });
    },
    editBookmark(oldUrl, title, url) {
      navigate("bookmarks/edit", { oldUrl, title, url });
    },
    openHistory(url) {
      navigate("history/open", { url });
    },
    removeHistory(url) {
      navigate("history/remove", { url });
    },
    clearHistory() {
      navigate("history/clear");
    }
  };
})();
