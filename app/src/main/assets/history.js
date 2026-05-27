let historyItems = [];

function hasBootstrapData() {
  const hash = window.location.hash.startsWith("#") ? window.location.hash.slice(1) : window.location.hash;
  return new URLSearchParams(hash).has("data");
}

function readHistory() {
  const hash = window.location.hash.startsWith("#") ? window.location.hash.slice(1) : window.location.hash;
  const params = new URLSearchParams(hash);
  const raw = params.get("data");
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch (error) {
    return [];
  }
}

function formatVisitTime(timestamp) {
  if (!timestamp) return "";
  try {
    return new Date(timestamp).toLocaleString();
  } catch (error) {
    return "";
  }
}

function render() {
  const content = document.getElementById("content");
  const clear = document.getElementById("clear");
  clear.hidden = !historyItems.length;
  if (!historyItems.length) {
    content.innerHTML = '<div class="empty">还没有历史记录。访问过的网页会显示在这里。</div>';
    return;
  }

  const list = document.createElement("div");
  list.className = "list";
  historyItems.forEach((entry) => {
    const item = document.createElement("div");
    item.className = "item";

    const open = document.createElement("a");
    open.className = "open";
    open.href = "#";
    open.addEventListener("click", (event) => {
      event.preventDefault();
      window.hyperBrowser.openHistory(entry.url);
    });

    const title = document.createElement("div");
    title.className = "title";
    title.textContent = entry.title || entry.url;

    const url = document.createElement("div");
    url.className = "url";
    url.textContent = entry.url;

    const meta = document.createElement("div");
    meta.className = "meta";
    meta.textContent = formatVisitTime(entry.visitedAt);

    const remove = document.createElement("button");
    remove.className = "remove";
    remove.type = "button";
    remove.setAttribute("aria-label", "Remove history entry");
    remove.textContent = "×";
    remove.addEventListener("click", () => {
      historyItems = historyItems.filter((item) => item.url !== entry.url);
      render();
      window.hyperBrowser.removeHistory(entry.url);
    });

    open.append(title, url, meta);
    item.append(open, remove);
    list.appendChild(item);
  });
  content.replaceChildren(list);
}

document.getElementById("clear").addEventListener("click", () => {
  historyItems = [];
  render();
  window.hyperBrowser.clearHistory();
});

if (hasBootstrapData()) {
  historyItems = readHistory();
  render();
} else {
  document.getElementById("clear").hidden = true;
  document.getElementById("content").innerHTML = '<div class="empty">正在加载历史记录...</div>';
  window.hyperBrowser.requestHistoryData()
    .then((items) => {
      historyItems = items;
      render();
    })
    .catch(() => {
      document.getElementById("content").innerHTML = '<div class="empty">历史记录暂时不可用。</div>';
    });
}
