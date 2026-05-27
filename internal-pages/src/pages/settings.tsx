import React, { useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import "../hyper-browser";
import "../styles.css";
import type { BrowserSettings } from "../hyper-browser";

function SettingsPage() {
  const [query, setQuery] = useState("");
  const [settings, setSettings] = useState<BrowserSettings | null>(null);
  const [customDraft, setCustomDraft] = useState("");
  const [customError, setCustomError] = useState("");
  const [loadError, setLoadError] = useState("");
  const [searchEngineExpanded, setSearchEngineExpanded] = useState(false);
  const [toolbarExpanded, setToolbarExpanded] = useState(false);
  const customInputRef = useRef<HTMLInputElement | null>(null);
  const showSearchEngine = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return true;
    return "搜索引擎 google bing 自定义".includes(needle);
  }, [query]);
  const showToolbarPosition = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return true;
    return "地址栏 工具栏 位置 顶部 底部".includes(needle);
  }, [query]);

  useEffect(() => {
    window.hyperBrowser.requestSettingsData()
      .then((value) => {
        setSettings(value);
        setCustomDraft(value.customSearchUrl);
      })
      .catch(() => setLoadError("设置暂时不可用。"));
  }, []);

  function updateSearchEngine(searchEngineId: BrowserSettings["searchEngineId"], nextCustomUrl: string) {
    window.hyperBrowser.updateSearchEngine(searchEngineId, nextCustomUrl)
      .then((value) => {
        setSettings(value);
        setCustomDraft(value.customSearchUrl);
      });
  }

  function selectPreset(searchEngineId: "google" | "bing") {
    setCustomError("");
    updateSearchEngine(searchEngineId, settings?.customSearchUrl || "");
  }

  function focusCustomInput() {
    setCustomError("");
    window.setTimeout(() => customInputRef.current?.focus(), 0);
  }

  function commitCustomSearchUrl() {
    const nextCustomUrl = customDraft.trim();
    if (!nextCustomUrl) {
      setCustomError("");
      return;
    }
    if (!nextCustomUrl.includes("%s")) {
      setCustomError("自定义搜索 URL 必须包含 %s。");
      return;
    }
    setCustomError("");
    window.hyperBrowser.updateSearchEngine("custom", nextCustomUrl)
      .then((value) => {
        setSettings(value);
        setCustomDraft(value.customSearchUrl);
      })
      .catch((error) => setCustomError(error instanceof Error ? error.message : "更新失败。"));
  }

  function updateToolbarPosition(toolbarPosition: BrowserSettings["toolbarPosition"]) {
    window.hyperBrowser.updateToolbarPosition(toolbarPosition)
      .then((value) => setSettings(value))
      .catch(() => setLoadError("设置暂时不可用。"));
  }

  function toolbarPositionLabel(toolbarPosition?: BrowserSettings["toolbarPosition"]) {
    return toolbarPosition === "bottom" ? "底部" : "顶部";
  }

  return (
    <main className="settings-page">
      <header className="settings-header">
        <button className="settings-back" type="button" aria-label="Back" onClick={() => window.history.back()}>‹</button>
        <h1>设置</h1>
        <button className="settings-help" type="button" aria-label="Help">?</button>
      </header>

      <label className="settings-search">
        <span className="settings-search-icon" aria-hidden="true">⌕</span>
        <input
          type="search"
          placeholder="搜索设置"
          value={query}
          onChange={(event) => setQuery(event.currentTarget.value)}
        />
      </label>

      <section className="settings-section">
        <h2>基本</h2>
        {showSearchEngine ? (
          <div className="settings-card search-engine-card">
            <button
              className="settings-row settings-row-button"
              type="button"
              aria-expanded={searchEngineExpanded}
              onClick={() => setSearchEngineExpanded((expanded) => !expanded)}
            >
              <span className="settings-row-title">搜索引擎</span>
              <span className="settings-row-value">{settings?.searchEngineName || "Google"}</span>
            </button>
            {searchEngineExpanded && (
              <div className="settings-options">
                <button
                  className={settings?.searchEngineId === "google" ? "settings-option selected" : "settings-option"}
                  type="button"
                  onClick={() => selectPreset("google")}
                >
                  <span>Google</span>
                  <span>google.com</span>
                </button>
                <button
                  className={settings?.searchEngineId === "bing" ? "settings-option selected" : "settings-option"}
                  type="button"
                  onClick={() => selectPreset("bing")}
                >
                  <span>Bing</span>
                  <span>bing.com</span>
                </button>
                <div className="custom-search-form">
                  <button
                    className={settings?.searchEngineId === "custom" ? "settings-option selected" : "settings-option"}
                    type="button"
                    onClick={focusCustomInput}
                  >
                    <span>自定义</span>
                    <span>使用 %s 作为关键词</span>
                  </button>
                  <input
                    ref={customInputRef}
                    type="text"
                    inputMode="url"
                    placeholder="https://example.com/search?q=%s"
                    value={customDraft}
                    onChange={(event) => {
                      setCustomDraft(event.currentTarget.value);
                      setCustomError("");
                    }}
                    onBlur={commitCustomSearchUrl}
                  />
                  {customError && <p className="settings-inline-error">{customError}</p>}
                </div>
              </div>
            )}
          </div>
        ) : !showToolbarPosition ? (
          <div className="settings-empty">没有匹配的设置。</div>
        ) : null}
        {showToolbarPosition && (
          <div className="settings-card settings-card-spaced">
            <button
              className="settings-row settings-row-button"
              type="button"
              aria-expanded={toolbarExpanded}
              onClick={() => setToolbarExpanded((expanded) => !expanded)}
            >
              <span className="settings-row-title">地址栏</span>
              <span className="settings-row-value">{toolbarPositionLabel(settings?.toolbarPosition)}</span>
            </button>
            {toolbarExpanded && (
              <div className="settings-options">
                <button
                  className={settings?.toolbarPosition !== "bottom" ? "settings-option selected" : "settings-option"}
                  type="button"
                  onClick={() => updateToolbarPosition("top")}
                >
                  <span>顶部</span>
                  <span>工具栏显示在页面上方</span>
                </button>
                <button
                  className={settings?.toolbarPosition === "bottom" ? "settings-option selected" : "settings-option"}
                  type="button"
                  onClick={() => updateToolbarPosition("bottom")}
                >
                  <span>底部</span>
                  <span>工具栏显示在页面下方</span>
                </button>
              </div>
            )}
          </div>
        )}
        {loadError && <p className="settings-message">{loadError}</p>}
      </section>
    </main>
  );
}

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <SettingsPage />
  </React.StrictMode>
);
