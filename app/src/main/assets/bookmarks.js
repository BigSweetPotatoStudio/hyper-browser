let bookmarks = [];
let editingUrl = null;

function hasBootstrapData() {
  const hash = window.location.hash.startsWith("#") ? window.location.hash.slice(1) : window.location.hash;
  return new URLSearchParams(hash).has("data");
}

function readBookmarks() {
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

function render() {
  const content = document.getElementById("content");
  if (!bookmarks.length) {
    content.innerHTML = '<div class="empty">还没有书签。打开网页后可以从菜单添加。</div>';
    return;
  }
  const list = document.createElement("div");
  list.className = "list";
  bookmarks.forEach((bookmark) => {
    const item = document.createElement("div");
    item.className = "item";

    const open = document.createElement("a");
    open.className = "open";
    open.href = "#";
    open.addEventListener("click", (event) => {
      event.preventDefault();
      window.hyperBrowser.openBookmark(bookmark.url);
    });

    const title = document.createElement("div");
    title.className = "title";
    title.textContent = bookmark.title || bookmark.url;

    const url = document.createElement("div");
    url.className = "url";
    url.textContent = bookmark.url;

    const edit = document.createElement("button");
    edit.className = "edit";
    edit.type = "button";
    edit.setAttribute("aria-label", "Edit bookmark");
    edit.textContent = "✎";
    edit.addEventListener("click", () => {
      editingUrl = bookmark.url;
      render();
    });

    const remove = document.createElement("button");
    remove.className = "remove";
    remove.type = "button";
    remove.setAttribute("aria-label", "Remove bookmark");
    remove.textContent = "×";
    remove.addEventListener("click", () => {
      bookmarks = bookmarks.filter((item) => item.url !== bookmark.url);
      if (editingUrl === bookmark.url) editingUrl = null;
      render();
      window.hyperBrowser.removeBookmark(bookmark.url);
    });

    open.append(title, url);
    item.append(open, edit, remove);

    if (editingUrl === bookmark.url) {
      const editor = document.createElement("form");
      editor.className = "editor";

      const titleInput = document.createElement("input");
      titleInput.name = "title";
      titleInput.value = bookmark.title || "";
      titleInput.placeholder = "标题";

      const urlInput = document.createElement("input");
      urlInput.name = "url";
      urlInput.value = bookmark.url;
      urlInput.placeholder = "URL";
      urlInput.type = "url";

      const actions = document.createElement("div");
      actions.className = "editor-actions";

      const cancel = document.createElement("button");
      cancel.className = "cancel";
      cancel.type = "button";
      cancel.textContent = "取消";
      cancel.addEventListener("click", () => {
        editingUrl = null;
        render();
      });

      const save = document.createElement("button");
      save.className = "save";
      save.type = "submit";
      save.textContent = "保存";

      editor.addEventListener("submit", (event) => {
        event.preventDefault();
        const nextTitle = titleInput.value.trim();
        const nextUrl = urlInput.value.trim();
        if (!nextUrl) return;
        const oldUrl = bookmark.url;
        bookmarks = bookmarks.map((item) => (
          item.url === oldUrl ? { ...item, title: nextTitle || nextUrl, url: nextUrl } : item
        ));
        editingUrl = null;
        render();
        window.hyperBrowser.editBookmark(oldUrl, nextTitle, nextUrl);
      });

      actions.append(cancel, save);
      editor.append(titleInput, urlInput, actions);
      item.appendChild(editor);
    }

    list.appendChild(item);
  });
  content.replaceChildren(list);
}

if (hasBootstrapData()) {
  bookmarks = readBookmarks();
  render();
} else {
  document.getElementById("content").innerHTML = '<div class="empty">正在加载书签...</div>';
  window.hyperBrowser.requestBookmarksData()
    .then((items) => {
      bookmarks = items;
      render();
    })
    .catch(() => {
      document.getElementById("content").innerHTML = '<div class="empty">书签暂时不可用。</div>';
    });
}
