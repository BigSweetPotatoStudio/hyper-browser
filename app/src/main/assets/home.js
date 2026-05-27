const form = document.getElementById("search-form");
const input = document.getElementById("search-input");
const extensionsLink = document.getElementById("extensions-link");
const recent = document.getElementById("recent");

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

function renderRecent(historyItems) {
  const items = historyItems.slice(0, 10);
  if (!items.length) {
    recent.innerHTML = '<div class="empty">最近访问记录在历史页中管理。</div>';
    return;
  }

  const list = document.createElement("div");
  list.className = "recent-list";
  items.forEach((entry) => {
    const item = document.createElement("a");
    item.className = "recent-item";
    item.href = "#";
    item.addEventListener("click", (event) => {
      event.preventDefault();
      window.hyperBrowser.openHistory(entry.url);
    });

    const title = document.createElement("div");
    title.className = "recent-title";
    title.textContent = entry.title || entry.url;

    const url = document.createElement("div");
    url.className = "recent-url";
    url.textContent = entry.url;

    item.append(title, url);
    list.appendChild(item);
  });
  recent.replaceChildren(list);
}

function openInput() {
  const value = input.value.trim();
  if (value) {
    window.hyperBrowser.open(value);
  }
}

form.addEventListener("submit", function (event) {
  event.preventDefault();
  openInput();
});

extensionsLink.addEventListener("click", function (event) {
  event.preventDefault();
  window.hyperBrowser.showExtensions();
});

input.addEventListener("keydown", function (event) {
  if (event.key === "Enter") {
    event.preventDefault();
    openInput();
  }
});

if (hasBootstrapData()) {
  renderRecent(readHistory());
} else {
  recent.innerHTML = '<div class="empty">正在加载最近访问记录...</div>';
  window.hyperBrowser.requestHomeData()
    .then(renderRecent)
    .catch(() => {
      recent.innerHTML = '<div class="empty">最近访问记录暂时不可用。</div>';
    });
}
