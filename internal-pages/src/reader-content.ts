import { Readability } from "@mozilla/readability";

const NATIVE_APP = "hyperBrowser";
const SESSION_COMMAND_TARGET = "hyper.internal.sessionCommand";
const PREFERENCES_KEY = "hyperReaderPreferences";
const HOST_ID = "hyper-browser-reader-root";
const MIN_ARTICLE_CHARACTERS = 200;
const MIN_FONT_SIZE = 16;
const MAX_FONT_SIZE = 30;

type ReaderTheme = "light" | "sepia" | "dark";

type ReaderPreferences = {
  theme: ReaderTheme;
  fontSize: number;
};

type ReaderArticle = {
  title: string;
  byline: string | null;
  siteName: string | null;
  publishedTime: string | null;
  content: string;
  textContent: string;
};

type ReaderNavigationRole = "previous" | "index" | "next";

type ReaderNavigationControl = {
  role: ReaderNavigationRole;
  text: string;
  href: string | null;
  rel: string | null;
  target: string | null;
  activate: (() => void) | null;
};

type ReaderNavigationCandidate = ReaderNavigationControl & {
  element: Element;
  score: number;
  container: Element | null;
};

type ReaderCommand = {
  target?: unknown;
  requestId?: unknown;
  type?: unknown;
};

type ReaderOverlay = {
  host: HTMLElement;
  close: (notify: boolean) => void;
};

let overlay: ReaderOverlay | null = null;
let previousBodyDisplay = "";
let previousHtmlOverflow = "";

const labels = navigator.language.toLowerCase().startsWith("zh")
  ? {
      exit: "退出阅读模式",
      smaller: "减小字号",
      larger: "增大字号",
      theme: "切换阅读主题",
      light: "浅色",
      sepia: "护眼",
      dark: "深色",
      source: "原文链接",
      previous: "上一章",
      index: "目录",
      next: "下一章",
    }
  : {
      exit: "Exit reader mode",
      smaller: "Decrease text size",
      larger: "Increase text size",
      theme: "Change reader theme",
      light: "Light",
      sepia: "Sepia",
      dark: "Dark",
      source: "Original article",
      previous: "Previous",
      index: "Contents",
      next: "Next",
    };

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === "object" && !Array.isArray(value);
}

function clamp(value: number, minimum: number, maximum: number): number {
  return Math.min(maximum, Math.max(minimum, value));
}

function normalizedPreferences(value: unknown): ReaderPreferences {
  const item = isPlainObject(value) ? value : {};
  const theme = item.theme === "sepia" || item.theme === "dark" ? item.theme : "light";
  const rawFontSize = typeof item.fontSize === "number" ? item.fontSize : 20;
  return {
    theme,
    fontSize: clamp(Math.round(rawFontSize), MIN_FONT_SIZE, MAX_FONT_SIZE),
  };
}

async function loadPreferences(): Promise<ReaderPreferences> {
  const stored: Record<string, unknown> = await browser?.storage?.local
    ?.get(PREFERENCES_KEY)
    .catch(() => ({})) ?? {};
  return normalizedPreferences(stored?.[PREFERENCES_KEY]);
}

function savePreferences(preferences: ReaderPreferences): void {
  void browser?.storage?.local?.set({ [PREFERENCES_KEY]: preferences }).catch(() => undefined);
}

function sanitizeArticle(content: string): DocumentFragment {
  const template = document.createElement("template");
  template.innerHTML = content;
  template.content
    .querySelectorAll(
      "script, style, form, input, button, textarea, select, option, object, embed, iframe, frame, canvas, img, picture, source, svg",
    )
    .forEach((element) => element.remove());

  template.content.querySelectorAll<HTMLElement>("*").forEach((element) => {
    for (const attribute of Array.from(element.attributes)) {
      const name = attribute.name.toLowerCase();
      if (name.startsWith("on") || name === "style" || name === "srcdoc") {
        element.removeAttribute(attribute.name);
      }
    }
  });
  return template.content;
}

function extractArticle(): ReaderArticle | null {
  const documentClone = document.cloneNode(true) as Document;
  const parsed = new Readability(documentClone, {
    keepClasses: false,
    charThreshold: MIN_ARTICLE_CHARACTERS,
  }).parse();
  const textContent = parsed?.textContent?.trim() || "";
  const content = parsed?.content || "";
  if (!parsed || !content || textContent.length < MIN_ARTICLE_CHARACTERS) return null;
  return {
    title: parsed.title || document.title,
    byline: parsed.byline || null,
    siteName: parsed.siteName || null,
    publishedTime: parsed.publishedTime || null,
    content,
    textContent,
  };
}

function normalizedNavigationText(value: string | null | undefined): string {
  return (value || "")
    .replace(/[\u200b-\u200d\ufeff]/g, "")
    .replace(/\s+/g, " ")
    .trim()
    .toLowerCase();
}

function exactNavigationRole(value: string): ReaderNavigationRole | null {
  const compact = normalizedNavigationText(value).replace(/\s+/g, "");
  if (
    /^(上一(章|节|節|页|頁|篇|话|話)|上页|上頁|前一(章|节|節|页|頁|篇|话|話)|前页|前頁|previous(chapter|page|post)?|prev|older(chapter|page|post)?|前へ|前の(章|ページ|話)|이전(장|페이지)?|←|‹|«)$/.test(compact)
  ) {
    return "previous";
  }
  if (
    /^(下一(章|节|節|页|頁|篇|话|話)|下页|下頁|后一(章|节|節|页|頁|篇|话|話)|后页|後頁|next(chapter|page|post)?|newer(chapter|page|post)?|次へ|次の(章|ページ|話)|다음(장|페이지)?|→|›|»)$/.test(compact)
  ) {
    return "next";
  }
  if (
    /^(目录|目錄|章节目录|章節目錄|返回目录|返回目錄|书页\/目录|書頁\/目錄|首页\/目录|首頁\/目錄|contents?|tableofcontents|chapterlist|catalog(ue)?|toc|目次|一覧|목차)$/.test(compact)
  ) {
    return "index";
  }
  return null;
}

function hintedNavigationRole(value: string): ReaderNavigationRole | null {
  const signal = normalizedNavigationText(
    value.replace(/([a-z0-9])([A-Z])/g, "$1 $2"),
  ).replace(/[_-]+/g, " ");
  if (/(^|\W)(prev|previous|older|backward)(\W|$)|上一|上頁|前一|前頁|前へ|前の|이전/.test(signal)) {
    return "previous";
  }
  if (
    /(^|\W)(next|newer|forward|continue|read on)(\W|$)|下一|下頁|后一|後一|继续阅读|繼續閱讀|后续章节|後續章節|次へ|次の|다음/.test(signal)
  ) {
    return "next";
  }
  if (/(^|\W)(toc|contents?|catalog(ue)?|chapter list)(\W|$)|目录|目錄|目次|一覧|목차/.test(signal)) {
    return "index";
  }
  return null;
}

function elementClassName(element: Element | null): string {
  if (!element) return "";
  const className = element.getAttribute("class");
  return className || "";
}

function navigationContainer(element: Element): Element | null {
  let current: Element | null = element;
  for (let depth = 0; current && depth < 4; depth += 1, current = current.parentElement) {
    const hint = [
      current.tagName,
      current.id,
      elementClassName(current),
      current.getAttribute("role"),
      current.getAttribute("aria-label"),
    ].join(" ").toLowerCase();
    if (
      current.tagName === "NAV" ||
      /read[_-]?nav|chapter[_-]?nav|post[_-]?nav|page[_-]?nav|nav[_-]?links|pagination|paginator|pager|next[_-]?prev|page[_-]?links/.test(hint)
    ) {
      return current;
    }
  }
  return element.parentElement;
}

function navigationContextScore(element: Element): number {
  const container = navigationContainer(element);
  const hint = [
    element.tagName,
    element.id,
    elementClassName(element),
    container?.tagName,
    container?.id,
    elementClassName(container),
    container?.getAttribute("role"),
    container?.getAttribute("aria-label"),
  ].join(" ").toLowerCase();
  if (/carousel|slider|swiper|gallery|lightbox|slideshow|product/.test(hint)) return -120;
  if (
    container?.tagName === "NAV" ||
    /read[_-]?nav|chapter[_-]?nav|post[_-]?nav|page[_-]?nav|nav[_-]?links|pagination|paginator|pager|next[_-]?prev|page[_-]?links/.test(hint)
  ) {
    return 70;
  }
  return 0;
}

function candidateFromElement(element: HTMLElement): ReaderNavigationCandidate | null {
  if (
    element.hasAttribute("disabled") ||
    element.getAttribute("aria-disabled") === "true" ||
    /(^|\s)disabled(\s|$)/i.test(elementClassName(element))
  ) {
    return null;
  }

  const relTokens = normalizedNavigationText(element.getAttribute("rel")).split(/\s+/);
  const relRole: ReaderNavigationRole | null = relTokens.includes("prev")
    ? "previous"
    : relTokens.includes("next")
      ? "next"
      : relTokens.some((value) => value === "index" || value === "contents" || value === "up")
        ? "index"
        : null;
  const visibleText = element instanceof HTMLInputElement
    ? element.value
    : element.textContent || "";
  const accessibleText = [
    element.getAttribute("aria-label"),
    element.getAttribute("title"),
    element.getAttribute("alt"),
    element.getAttribute("data-label"),
    element.querySelector("[aria-label]")?.getAttribute("aria-label"),
    element.querySelector("title")?.textContent,
  ].filter(Boolean).join(" ");
  const structuralText = [
    element.id,
    elementClassName(element),
    element.getAttribute("data-testid"),
    element.getAttribute("data-action"),
    element.querySelector("i, svg, use")?.getAttribute("class"),
    element.querySelector("use")?.getAttribute("href"),
  ].filter(Boolean).join(" ");

  const textRole = exactNavigationRole(visibleText);
  const visibleHintRole = hintedNavigationRole(visibleText);
  const accessibleRole = exactNavigationRole(accessibleText) || hintedNavigationRole(accessibleText);
  const structuralRole = hintedNavigationRole(structuralText);
  const role = relRole || textRole || accessibleRole || structuralRole || visibleHintRole;
  if (!role) return null;

  let score = navigationContextScore(element);
  if (relRole === role) score += 220;
  const compactVisibleText = normalizedNavigationText(visibleText).replace(/\s+/g, "");
  if (textRole === role) score += /^(←|‹|«|→|›|»)$/.test(compactVisibleText) ? 35 : 130;
  if (!textRole && visibleHintRole === role) score += 45;
  if (accessibleRole === role) score += 110;
  if (structuralRole === role) score += 85;
  if (element instanceof HTMLAnchorElement && element.hasAttribute("href")) score += 15;
  score += Math.max(0, Math.min(25, Math.round(element.getBoundingClientRect().top / 600)));
  if (score < 90) return null;

  const href = element instanceof HTMLAnchorElement
    ? element.getAttribute("href")?.trim() || null
    : element.getAttribute("data-href")?.trim() ||
      element.getAttribute("data-url")?.trim() ||
      null;
  const onclick = element.getAttribute("onclick");
  const canActivateOriginal = element instanceof HTMLAnchorElement
    ? !!onclick || !href || href === "#" || href.toLowerCase().startsWith("javascript:void")
    : !href;
  if (!href && !canActivateOriginal) return null;

  return {
    element,
    container: navigationContainer(element),
    role,
    score,
    text: (visibleText || accessibleText).replace(/\s+/g, " ").trim() || labels[role],
    href,
    rel: element.getAttribute("rel"),
    target: element.getAttribute("target"),
    activate: canActivateOriginal ? () => element.click() : null,
  };
}

function candidateFromHeadLink(link: HTMLLinkElement): ReaderNavigationCandidate | null {
  const rel = normalizedNavigationText(link.rel).split(/\s+/);
  const role: ReaderNavigationRole | null = rel.includes("prev")
    ? "previous"
    : rel.includes("next")
      ? "next"
      : rel.some((value) => value === "index" || value === "contents" || value === "up")
        ? "index"
        : null;
  const href = link.getAttribute("href")?.trim() || null;
  if (!role || !href) return null;
  return {
    element: link,
    container: link.parentElement,
    role,
    score: 260,
    text: labels[role],
    href,
    rel: link.getAttribute("rel"),
    target: link.getAttribute("target"),
    activate: null,
  };
}

function extractChapterNavigation(): ReaderNavigationControl[] {
  const candidates = [
    ...Array.from(document.querySelectorAll<HTMLElement>(
      "a[href], button, [role='link'], input[type='button'], input[type='submit'], [data-href], [data-url]",
    )).map(candidateFromElement),
    ...Array.from(document.head?.querySelectorAll<HTMLLinkElement>("link[rel][href]") || [])
      .map(candidateFromHeadLink),
  ].filter((candidate): candidate is ReaderNavigationCandidate => candidate !== null);

  candidates.forEach((candidate) => {
    const hasCompanion = candidates.some((other) =>
      other !== candidate &&
      other.role !== candidate.role &&
      other.container === candidate.container
    );
    if (hasCompanion) candidate.score += 45;
  });

  const selected = new Map<ReaderNavigationRole, ReaderNavigationCandidate>();
  candidates.forEach((candidate) => {
    const current = selected.get(candidate.role);
    if (!current || candidate.score >= current.score) selected.set(candidate.role, candidate);
  });

  return (["previous", "index", "next"] as const).flatMap((role) => {
    const candidate = selected.get(role);
    if (!candidate) return [];
    return [{
      role,
      text: candidate.text,
      href: candidate.href,
      rel: candidate.rel,
      target: candidate.target,
      activate: candidate.activate,
    }];
  });
}

function themeLabel(theme: ReaderTheme): string {
  if (theme === "sepia") return labels.sepia;
  if (theme === "dark") return labels.dark;
  return labels.light;
}

function nextTheme(theme: ReaderTheme): ReaderTheme {
  if (theme === "light") return "sepia";
  if (theme === "sepia") return "dark";
  return "light";
}

async function notifyState(active: boolean): Promise<void> {
  await browser?.runtime?.sendNativeMessage?.(NATIVE_APP, {
    type: "reader.state",
    payload: { active },
  }).catch(() => undefined);
}

async function notifyReady(): Promise<void> {
  await browser?.runtime?.sendNativeMessage?.(NATIVE_APP, {
    type: "reader.ready",
    payload: {},
  }).catch(() => undefined);
}

function closeOverlay(notify: boolean): void {
  const current = overlay;
  if (!current) return;
  overlay = null;
  current.host.remove();
  if (document.body) document.body.style.display = previousBodyDisplay;
  document.documentElement.style.overflow = previousHtmlOverflow;
  if (notify) void notifyState(false);
}

async function openOverlay(): Promise<boolean> {
  if (overlay) return true;
  const chapterNavigation = extractChapterNavigation();
  const article = extractArticle();
  if (!article || !document.body) return false;

  const preferences = await loadPreferences();
  previousBodyDisplay = document.body.style.display;
  previousHtmlOverflow = document.documentElement.style.overflow;

  const host = document.createElement("div");
  host.id = HOST_ID;
  host.setAttribute("role", "dialog");
  host.setAttribute("aria-label", article.title);
  const shadow = host.attachShadow({ mode: "closed" });
  const style = document.createElement("style");
  style.textContent = READER_CSS;
  const shell = document.createElement("div");
  shell.className = "reader-shell";
  shell.dataset.theme = preferences.theme;
  shell.style.setProperty("--reader-font-size", `${preferences.fontSize}px`);

  const toolbar = document.createElement("header");
  toolbar.className = "reader-toolbar";
  const exitButton = document.createElement("button");
  exitButton.type = "button";
  exitButton.className = "reader-exit";
  exitButton.textContent = "×";
  exitButton.setAttribute("aria-label", labels.exit);
  exitButton.title = labels.exit;

  const controls = document.createElement("div");
  controls.className = "reader-controls";
  const smallerButton = document.createElement("button");
  smallerButton.type = "button";
  smallerButton.textContent = "A−";
  smallerButton.setAttribute("aria-label", labels.smaller);
  const largerButton = document.createElement("button");
  largerButton.type = "button";
  largerButton.textContent = "A+";
  largerButton.setAttribute("aria-label", labels.larger);
  const themeButton = document.createElement("button");
  themeButton.type = "button";
  themeButton.className = "reader-theme";
  themeButton.textContent = themeLabel(preferences.theme);
  themeButton.setAttribute("aria-label", labels.theme);
  controls.append(smallerButton, largerButton, themeButton);
  toolbar.append(exitButton, controls);

  const main = document.createElement("main");
  main.className = "reader-main";
  const articleElement = document.createElement("article");
  const title = document.createElement("h1");
  title.textContent = article.title;
  articleElement.append(title);

  const metadata = [article.byline, article.siteName, article.publishedTime]
    .map((value) => value?.trim())
    .filter((value): value is string => !!value);
  if (metadata.length > 0) {
    const meta = document.createElement("p");
    meta.className = "reader-meta";
    meta.textContent = metadata.join(" · ");
    articleElement.append(meta);
  }

  const source = document.createElement("a");
  source.className = "reader-source";
  source.href = location.href;
  source.textContent = labels.source;
  articleElement.append(source);

  const body = document.createElement("div");
  body.className = "reader-content";
  body.append(sanitizeArticle(article.content));
  articleElement.append(body);
  if (chapterNavigation.length > 0) {
    const navigation = document.createElement("nav");
    navigation.className = "reader-navigation";
    navigation.setAttribute("aria-label", labels.index);
    chapterNavigation.forEach((item) => {
      const control = item.href
        ? document.createElement("a")
        : document.createElement("button");
      control.className = `reader-navigation-${item.role}`;
      control.textContent = item.text;
      if (control instanceof HTMLAnchorElement && item.href) {
        control.setAttribute("href", item.href);
        if (item.rel) control.setAttribute("rel", item.rel);
        if (item.target) control.setAttribute("target", item.target);
      } else if (control instanceof HTMLButtonElement) {
        control.type = "button";
      }
      if (item.activate) {
        control.addEventListener("click", (event) => {
          event.preventDefault();
          item.activate?.();
        });
      }
      navigation.append(control);
    });
    articleElement.append(navigation);
  }
  main.append(articleElement);
  shell.append(toolbar, main);
  shadow.append(style, shell);

  function applyPreferences(next: ReaderPreferences): void {
    preferences.theme = next.theme;
    preferences.fontSize = clamp(next.fontSize, MIN_FONT_SIZE, MAX_FONT_SIZE);
    shell.dataset.theme = preferences.theme;
    shell.style.setProperty("--reader-font-size", `${preferences.fontSize}px`);
    themeButton.textContent = themeLabel(preferences.theme);
    savePreferences(preferences);
  }

  exitButton.addEventListener("click", () => closeOverlay(true));
  smallerButton.addEventListener("click", () => applyPreferences({
    ...preferences,
    fontSize: preferences.fontSize - 2,
  }));
  largerButton.addEventListener("click", () => applyPreferences({
    ...preferences,
    fontSize: preferences.fontSize + 2,
  }));
  themeButton.addEventListener("click", () => applyPreferences({
    ...preferences,
    theme: nextTheme(preferences.theme),
  }));
  shadow.addEventListener("click", (event) => {
    const anchor = event.composedPath().find((item) => item instanceof HTMLAnchorElement);
    if (!(anchor instanceof HTMLAnchorElement)) return;
    const href = anchor.getAttribute("href") || "";
    if (!href.startsWith("#")) return;
    const target = shadow.getElementById(decodeURIComponent(href.slice(1)));
    if (!target) return;
    event.preventDefault();
    target.scrollIntoView({ behavior: "smooth", block: "start" });
  });

  overlay = { host, close: closeOverlay };
  document.body.style.display = "none";
  document.documentElement.style.overflow = "hidden";
  document.documentElement.append(host);
  await notifyState(true);
  return true;
}

async function handleCommand(type: string): Promise<{ active: boolean }> {
  if (type === "reader.exit") {
    closeOverlay(true);
    return { active: false };
  }
  if (type === "reader.open") {
    const opened = await openOverlay();
    if (!opened) throw new Error("reader.notAvailable");
    return { active: true };
  }
  if (type !== "reader.toggle") throw new Error("reader.unknownCommand");
  if (overlay) {
    closeOverlay(true);
    return { active: false };
  }
  const opened = await openOverlay();
  if (!opened) throw new Error("reader.notAvailable");
  return { active: true };
}

function connectNativePort(): void {
  const port = browser?.runtime?.connectNative?.(NATIVE_APP);
  if (!port) return;
  port.onMessage?.addListener((rawMessage) => {
    const message = rawMessage as ReaderCommand;
    if (message.target !== SESSION_COMMAND_TARGET ||
        typeof message.requestId !== "string" ||
        typeof message.type !== "string"
    ) {
      return;
    }
    void handleCommand(message.type)
      .then((data) => port.postMessage({
        target: SESSION_COMMAND_TARGET,
        requestId: message.requestId,
        ok: true,
        data,
      }))
      .catch((error) => port.postMessage({
        target: SESSION_COMMAND_TARGET,
        requestId: message.requestId,
        ok: false,
        error: error instanceof Error ? error.message : String(error),
      }));
  });
  port.onDisconnect?.addListener(() => {
    closeOverlay(false);
  });
  void notifyReady();
}

connectNativePort();

const READER_CSS = `
  :host {
    position: fixed;
    inset: 0;
    z-index: 2147483647;
    display: block;
    color-scheme: light dark;
  }
  * { box-sizing: border-box; }
  .reader-shell {
    --reader-bg: #f8f9fa;
    --reader-surface: rgba(248, 249, 250, 0.94);
    --reader-text: #202124;
    --reader-muted: #5f6368;
    --reader-link: #0b57d0;
    --reader-border: #dadce0;
    position: absolute;
    inset: 0;
    overflow: auto;
    overscroll-behavior: contain;
    background: var(--reader-bg);
    color: var(--reader-text);
    font-family: ui-serif, Georgia, "Noto Serif", serif;
    font-size: var(--reader-font-size);
    line-height: 1.78;
    -webkit-text-size-adjust: 100%;
  }
  .reader-shell[data-theme="sepia"] {
    --reader-bg: #f4ecd8;
    --reader-surface: rgba(244, 236, 216, 0.95);
    --reader-text: #3d3528;
    --reader-muted: #716653;
    --reader-link: #755b18;
    --reader-border: #d8c9a8;
  }
  .reader-shell[data-theme="dark"] {
    --reader-bg: #1f2023;
    --reader-surface: rgba(31, 32, 35, 0.95);
    --reader-text: #e8eaed;
    --reader-muted: #bdc1c6;
    --reader-link: #8ab4f8;
    --reader-border: #4b4d52;
  }
  .reader-toolbar {
    position: sticky;
    top: 0;
    z-index: 2;
    display: flex;
    justify-content: space-between;
    align-items: center;
    min-height: 58px;
    padding: max(8px, env(safe-area-inset-top)) max(14px, env(safe-area-inset-right)) 8px max(14px, env(safe-area-inset-left));
    border-bottom: 1px solid var(--reader-border);
    background: var(--reader-surface);
    backdrop-filter: blur(16px);
  }
  button {
    min-width: 44px;
    min-height: 44px;
    border: 0;
    border-radius: 22px;
    background: transparent;
    color: var(--reader-text);
    font: 600 16px/1 system-ui, sans-serif;
  }
  button:active { background: color-mix(in srgb, var(--reader-text) 12%, transparent); }
  .reader-exit { font-size: 32px; font-weight: 300; }
  .reader-controls { display: flex; gap: 4px; }
  .reader-theme { min-width: 60px; padding: 0 12px; }
  .reader-main {
    width: min(100%, 780px);
    margin: 0 auto;
    padding: 38px max(22px, env(safe-area-inset-right)) max(72px, env(safe-area-inset-bottom)) max(22px, env(safe-area-inset-left));
  }
  h1 {
    margin: 0 0 14px;
    color: var(--reader-text);
    font-size: clamp(2rem, 8vw, 3.4rem);
    line-height: 1.16;
    letter-spacing: -0.025em;
  }
  .reader-meta {
    margin: 0 0 10px;
    color: var(--reader-muted);
    font: 500 0.78em/1.5 system-ui, sans-serif;
  }
  .reader-source {
    display: inline-block;
    margin-bottom: 28px;
    font: 500 0.78em/1.5 system-ui, sans-serif;
  }
  .reader-content :where(p, ul, ol, blockquote, pre, figure, table) { margin: 1.25em 0; }
  .reader-content :where(h2, h3, h4) {
    margin: 1.8em 0 0.65em;
    line-height: 1.28;
  }
  .reader-content a, .reader-source {
    color: var(--reader-link);
    text-decoration: underline;
    text-decoration-thickness: 0.08em;
    text-underline-offset: 0.18em;
    overflow-wrap: anywhere;
  }
  .reader-navigation {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 10px;
    margin-top: 48px;
    padding-top: 24px;
    border-top: 1px solid var(--reader-border);
    font: 600 0.82em/1.3 system-ui, sans-serif;
  }
  .reader-navigation a, .reader-navigation button {
    display: flex;
    align-items: center;
    justify-content: center;
    min-height: 48px;
    padding: 10px 8px;
    border: 1px solid var(--reader-border);
    border-radius: 14px;
    color: var(--reader-link);
    background: transparent;
    font: inherit;
    text-align: center;
    text-decoration: none;
  }
  .reader-navigation a:active, .reader-navigation button:active {
    background: color-mix(in srgb, var(--reader-text) 10%, transparent);
  }
  .reader-content video {
    display: block;
    max-width: 100%;
    height: auto;
    margin: 1.5em auto;
    border-radius: 10px;
  }
  .reader-content blockquote {
    margin-left: 0;
    padding-left: 1em;
    border-left: 4px solid var(--reader-border);
    color: var(--reader-muted);
  }
  .reader-content pre {
    overflow-x: auto;
    padding: 16px;
    border: 1px solid var(--reader-border);
    border-radius: 10px;
    font-size: 0.8em;
    line-height: 1.55;
  }
  .reader-content table {
    display: block;
    max-width: 100%;
    overflow-x: auto;
    border-collapse: collapse;
  }
  .reader-content th, .reader-content td {
    padding: 8px 10px;
    border: 1px solid var(--reader-border);
  }
  .reader-content hr { border: 0; border-top: 1px solid var(--reader-border); }
  @media (max-width: 420px) {
    .reader-main { padding-top: 28px; }
    .reader-theme { min-width: 50px; padding: 0 8px; }
  }
`;
