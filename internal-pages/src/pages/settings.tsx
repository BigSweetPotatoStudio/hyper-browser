import React, { useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import "../hyper-browser";
import "../styles.css";
import type { BatteryOptimizationState, BrowserSettings, UpdateCheckResult, UpdateDownloadState } from "../hyper-browser";

function SettingsPage() {
  const [query, setQuery] = useState("");
  const [settings, setSettings] = useState<BrowserSettings | null>(null);
  const [customDraft, setCustomDraft] = useState("");
  const [dohDraft, setDohDraft] = useState("");
  const [dohDirty, setDohDirty] = useState(false);
  const [customError, setCustomError] = useState("");
  const [loadError, setLoadError] = useState("");
  const [updateResult, setUpdateResult] = useState<UpdateCheckResult | null>(null);
  const [updateDownload, setUpdateDownload] = useState<UpdateDownloadState | null>(null);
  const [batteryOptimization, setBatteryOptimization] = useState<BatteryOptimizationState | null>(null);
  const [updateChecking, setUpdateChecking] = useState(false);
  const [batteryMessage, setBatteryMessage] = useState("");
  const [privacyMessage, setPrivacyMessage] = useState("");
  const [privacyError, setPrivacyError] = useState("");
  const [updateMessage, setUpdateMessage] = useState("");
  const [backupMessage, setBackupMessage] = useState("");
  const [searchEngineExpanded, setSearchEngineExpanded] = useState(false);
  const [toolbarExpanded, setToolbarExpanded] = useState(false);
  const [httpDnsExpanded, setHttpDnsExpanded] = useState(false);
  const [privacyExpanded, setPrivacyExpanded] = useState(false);
  const [backupExpanded, setBackupExpanded] = useState(false);
  const [backgroundRuntimeExpanded, setBackgroundRuntimeExpanded] = useState(false);
  const [updateSettingsExpanded, setUpdateSettingsExpanded] = useState(false);
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
  const showBackgroundRuntime = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return true;
    return "后台运行 电池 省电 锁屏 下载 音乐 视频 播放 youtube vimeo background".includes(needle);
  }, [query]);
  const showHttpDnsSettings = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return true;
    return "http dns doh ech https only 安全".includes(needle);
  }, [query]);
  const showPrivacySettings = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return true;
    return "隐私 跟踪 指纹 cookie 标准 严格 无".includes(needle);
  }, [query]);
  const showBackupSettings = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return true;
    return "备份 导出 导入 恢复 加载 json 书签 webapp".includes(needle);
  }, [query]);
  const showUpdateSettings = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return true;
    return "更新 update 版本 github release apk".includes(needle);
  }, [query]);

  useEffect(() => {
    window.hyperBrowser.requestSettingsData()
      .then((value) => {
        setSettings(value);
        setCustomDraft(value.customSearchUrl);
        setDohDraft(value.dohProviderUrl);
        setDohDirty(false);
      })
      .catch(() => setLoadError("设置暂时不可用。"));
    window.hyperBrowser.requestBatteryOptimizationState()
      .then((value) => setBatteryOptimization(value))
      .catch(() => setBatteryMessage("电池设置状态暂时不可用。"));
  }, []);

  useEffect(() => {
    if (!updateDownload || !isActiveDownloadState(updateDownload.status)) return;
    const timer = window.setInterval(() => {
      window.hyperBrowser.requestUpdateDownloadState()
        .then((state) => {
          setUpdateDownload(state);
          if (state.status === "permissionRequired" || state.status === "ready" || state.status === "error") {
            setUpdateMessage(state.message || updateDownloadMessage(state));
          }
        })
        .catch((error) => setUpdateMessage(error instanceof Error ? error.message : "更新状态不可用。"));
    }, 500);
    return () => window.clearInterval(timer);
  }, [updateDownload?.status]);

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

  function updateBackgroundVideoEnhancement(enabled: boolean) {
    setBatteryMessage("");
    window.hyperBrowser.updateBackgroundVideoEnhancement(enabled)
      .then((value) => {
        setSettings(value);
        setBatteryMessage(enabled ? "后台视频播放增强已开启，已打开的视频页刷新后生效。" : "后台视频播放增强已关闭，已打开的视频页刷新后生效。");
      })
      .catch(() => setBatteryMessage("设置暂时不可用。"));
  }

  function updatePrivacySettings(patch: Partial<Pick<BrowserSettings, "dohEnabled" | "dohProviderUrl" | "httpsOnlyEnabled" | "privacyProtectionLevel">>) {
    if (!settings) return Promise.resolve<BrowserSettings | null>(null);
    const next = {
      dohEnabled: settings.dohEnabled,
      dohProviderUrl: settings.dohProviderUrl,
      httpsOnlyEnabled: settings.httpsOnlyEnabled,
      privacyProtectionLevel: settings.privacyProtectionLevel,
      ...patch,
    };
    const cleanUrl = next.dohProviderUrl.trim();
    if (!isHttpsUrl(cleanUrl)) {
      setPrivacyError("DoH 地址必须是 https:// URL。");
      return Promise.resolve<BrowserSettings | null>(null);
    }
    setPrivacyError("");
    setPrivacyMessage("");
    const dnsChanged = patch.dohEnabled !== undefined || patch.dohProviderUrl !== undefined;
    return window.hyperBrowser.updatePrivacySettings({ ...next, dohProviderUrl: cleanUrl })
      .then((value) => {
        setSettings(value);
        setPrivacyMessage(dnsChanged
          ? "DNS 设置已保存，并已尝试热重载；ECH 和部分 Gecko 配置重启 App 后最完整。"
          : "设置已保存；已打开页面刷新后生效。");
        return value;
      })
      .catch((error) => {
        setPrivacyError(error instanceof Error ? error.message : "隐私设置暂时不可用。");
        return null;
      });
  }

  function isHttpsUrl(value: string) {
    try {
      return new URL(value).protocol === "https:";
    } catch {
      return false;
    }
  }

  function commitDohProviderUrl() {
    if (!settings || !dohDirty) return;
    updatePrivacySettings({ dohProviderUrl: dohDraft })
      .then((value) => {
        if (!value) return;
        setDohDraft(value.dohProviderUrl);
        setDohDirty(false);
      });
  }

  function openBatteryOptimizationSettings() {
    setBatteryMessage("");
    window.hyperBrowser.openBatteryOptimizationSettings()
      .then((state) => {
        setBatteryOptimization(state);
        if (!state.opened) {
          setBatteryMessage("无法自动打开系统设置，请在系统设置中手动允许后台运行。");
        }
      })
      .catch((error) => setBatteryMessage(error instanceof Error ? error.message : "无法打开电池设置。"));
  }

  function exportBackup() {
    setBackupMessage("正在打开文件保存位置...");
    window.hyperBrowser.exportBackup()
      .then((result) => setBackupMessage(result.message || "请选择 JSON 备份保存位置。"))
      .catch((error) => setBackupMessage(error instanceof Error ? error.message : "备份导出失败。"));
  }

  function importBackup() {
    setBackupMessage("正在打开备份文件选择器...");
    window.hyperBrowser.importBackup()
      .then((result) => setBackupMessage(result.message || "请选择 Hyper Browser JSON 备份。"))
      .catch((error) => setBackupMessage(error instanceof Error ? error.message : "备份导入失败。"));
  }

  function batteryOptimizationLabel() {
    return batteryOptimization?.ignoringBatteryOptimizations ? "已允许" : "受系统省电影响";
  }

  function toolbarPositionLabel(toolbarPosition?: BrowserSettings["toolbarPosition"]) {
    return toolbarPosition === "bottom" ? "底部" : "顶部";
  }

  function privacyStatusLabel() {
    if (!settings) return "标准";
    if (settings.privacyProtectionLevel === "none") return "无";
    return settings.privacyProtectionLevel === "strict" ? "严格" : "标准";
  }

  function httpDnsStatusLabel() {
    if (!settings) return "未开启";
    const active = [
      settings.dohEnabled,
      settings.httpsOnlyEnabled,
    ].filter(Boolean).length;
    return active > 0 ? `${active}/2 已开启` : "未开启";
  }

  function checkUpdate(ignoreSkipped = false) {
    setUpdateChecking(true);
    setUpdateMessage("正在检查更新...");
    window.hyperBrowser.checkUpdate(ignoreSkipped)
      .then((result) => {
        setUpdateResult(result);
        setUpdateMessage(result.message || updateStatusLabel(result));
        return window.hyperBrowser.requestUpdateDownloadState()
          .then((state) => {
            if (result.update && state.versionCode === result.update.versionCode && state.status !== "idle") {
              setUpdateDownload(state);
              if (state.status === "permissionRequired" || state.status === "ready" || state.status === "error") {
                setUpdateMessage(state.message || updateDownloadMessage(state));
              }
            }
          })
          .catch(() => undefined);
      })
      .catch((error) => setUpdateMessage(error instanceof Error ? error.message : "检查更新失败。"))
      .finally(() => setUpdateChecking(false));
  }

  function installUpdate() {
    const update = updateResult?.update;
    if (!update) return;
    setUpdateMessage("已开始下载更新。");
    setUpdateDownload({
      status: "preparing",
      versionCode: update.versionCode,
      versionName: update.versionName,
      bytesDownloaded: 0,
      totalBytes: update.asset.sizeBytes || 0,
      message: "正在准备更新...",
    });
    window.hyperBrowser.installUpdate(update.versionCode)
      .then((state) => {
        setUpdateDownload(state);
        if (state.status === "permissionRequired" || state.status === "ready" || state.status === "error") {
          setUpdateMessage(state.message || updateDownloadMessage(state));
        }
      })
      .catch((error) => {
        setUpdateDownload(null);
        setUpdateMessage(error instanceof Error ? error.message : "更新失败。");
      });
  }

  function skipUpdate() {
    const update = updateResult?.update;
    if (!update) return;
    window.hyperBrowser.skipUpdate(update.versionCode)
      .then(() => {
        setUpdateResult({ ...updateResult, status: "skipped", skippedVersionCode: update.versionCode });
        setUpdateMessage(`已跳过 ${update.versionName}。`);
      })
      .catch((error) => setUpdateMessage(error instanceof Error ? error.message : "跳过失败。"));
  }

  function clearSkippedUpdate() {
    window.hyperBrowser.clearSkippedUpdate()
      .then(() => {
        setUpdateResult((result) => result ? { ...result, skippedVersionCode: 0, status: result.update ? "available" : result.status } : result);
        setUpdateMessage("已取消跳过版本。");
      })
      .catch((error) => setUpdateMessage(error instanceof Error ? error.message : "取消跳过失败。"));
  }

  function updateStatusLabel(result: UpdateCheckResult) {
    switch (result.status) {
      case "available": return "发现新版本。";
      case "skipped": return "此版本已被跳过。";
      case "upToDate": return "当前已是最新版本。";
      case "unsupported": return "当前设备不支持这个版本。";
      default: return "检查更新失败。";
    }
  }

  function formatBytes(value?: number) {
    if (!value || value <= 0) return "";
    if (value >= 1024 * 1024) return `${(value / 1024 / 1024).toFixed(1)} MB`;
    if (value >= 1024) return `${(value / 1024).toFixed(0)} KB`;
    return `${value} B`;
  }

  function isActiveDownloadState(status: UpdateDownloadState["status"]) {
    return status === "preparing" || status === "downloading" || status === "verifying";
  }

  function updateProgressPercent(state: UpdateDownloadState) {
    if (!state.totalBytes || state.totalBytes <= 0) return 0;
    return Math.min(100, Math.max(0, Math.round((state.bytesDownloaded / state.totalBytes) * 100)));
  }

  function updateActionLabel() {
    if (updateDownload && isActiveDownloadState(updateDownload.status)) {
      if (updateDownload.status === "downloading") return `下载中 ${updateProgressPercent(updateDownload)}%`;
      if (updateDownload.status === "verifying") return "校验中...";
      return "准备中...";
    }
    if (updateDownload?.status === "permissionRequired") return "授权后重试";
    if (updateDownload?.status === "ready") return "安装更新";
    if (updateDownload?.status === "error") return "重新下载";
    return updateResult?.status === "skipped" ? "仍然更新" : "立即更新";
  }

  function updateDownloadMessage(state: UpdateDownloadState) {
    switch (state.status) {
      case "preparing": return "正在准备更新...";
      case "permissionRequired": return "请先允许 Hyper Browser 安装未知应用。";
      case "downloading": return `正在下载更新... ${updateProgressPercent(state)}%`;
      case "verifying": return "正在校验安装包...";
      case "ready": return "下载完成，点击安装。";
      case "error": return state.message || "更新下载失败。";
      default: return "";
    }
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
        ) : !showToolbarPosition && !showHttpDnsSettings && !showPrivacySettings && !showBackupSettings && !showBackgroundRuntime && !showUpdateSettings ? (
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
        {showHttpDnsSettings && (
          <div className="settings-card settings-card-spaced">
            <button
              className="settings-row settings-row-button"
              type="button"
              aria-expanded={httpDnsExpanded}
              onClick={() => setHttpDnsExpanded((expanded) => !expanded)}
            >
              <span className="settings-row-title">HTTP / DNS</span>
              <span className="settings-row-value">{httpDnsStatusLabel()}</span>
            </button>
            {httpDnsExpanded && (
              <>
                <label className="settings-toggle-row">
                  <span className="settings-toggle-copy">
                    <span className="settings-row-title">DNS over HTTPS</span>
                    <span className="settings-toggle-description">使用加密 DNS 解析，减少 DNS 泄露；开启后自动启用 ECH 尝试。</span>
                  </span>
                  <input
                    className="settings-toggle"
                    type="checkbox"
                    checked={!!settings?.dohEnabled}
                    disabled={!settings}
                    onChange={(event) => updatePrivacySettings({ dohEnabled: event.currentTarget.checked })}
                  />
                </label>
                <div className="settings-field-row">
                  <span className="settings-field-title">
                    <span className="settings-row-title">DoH 地址</span>
                    {dohDirty && <span>未保存</span>}
                  </span>
                  <span className="settings-input-action-row">
                    <input
                      type="text"
                      inputMode="url"
                      value={dohDraft}
                      disabled={!settings}
                      onChange={(event) => {
                        setDohDraft(event.currentTarget.value);
                        setDohDirty(true);
                        setPrivacyError("");
                        setPrivacyMessage("");
                      }}
                      onKeyDown={(event) => {
                        if (event.key === "Enter") {
                          event.currentTarget.blur();
                          commitDohProviderUrl();
                        }
                      }}
                    />
                    <button
                      className="settings-field-action"
                      type="button"
                      disabled={!settings || !dohDirty}
                      onClick={commitDohProviderUrl}
                    >
                      保存
                    </button>
                  </span>
                </div>
                <label className="settings-toggle-row">
                  <span className="settings-toggle-copy">
                    <span className="settings-row-title">HTTPS-Only</span>
                    <span className="settings-toggle-description">开启后尝试升级 HTTPS，无法安全升级时阻止 HTTP 页面。</span>
                  </span>
                  <input
                    className="settings-toggle"
                    type="checkbox"
                    checked={!!settings?.httpsOnlyEnabled}
                    disabled={!settings}
                    onChange={(event) => updatePrivacySettings({ httpsOnlyEnabled: event.currentTarget.checked })}
                  />
                </label>
                <p className="settings-message">地址栏会始终按当前页面协议提示：HTTP 红色，HTTPS 绿色；HTTPS 且 DoH 开启时显示增强安全图标。ECH 会随 DoH 自动尝试，网站不支持时仍正常使用 HTTPS。</p>
                {privacyError && <p className="settings-inline-error">{privacyError}</p>}
                {privacyMessage && <p className="settings-message">{privacyMessage}</p>}
              </>
            )}
          </div>
        )}
        {showPrivacySettings && (
          <div className="settings-card settings-card-spaced">
            <button
              className="settings-row settings-row-button"
              type="button"
              aria-expanded={privacyExpanded}
              onClick={() => setPrivacyExpanded((expanded) => !expanded)}
            >
              <span className="settings-row-title">隐私</span>
              <span className="settings-row-value">{privacyStatusLabel()}</span>
            </button>
            {privacyExpanded && (
              <>
                <div className="settings-options">
                  <button
                    className={settings?.privacyProtectionLevel === "none" ? "settings-option selected" : "settings-option"}
                    type="button"
                    onClick={() => updatePrivacySettings({ privacyProtectionLevel: "none" })}
                  >
                    <span>无跟踪保护</span>
                    <span>关闭跟踪保护，页面兼容性最高。</span>
                  </button>
                  <button
                    className={settings?.privacyProtectionLevel === "standard" ? "settings-option selected" : "settings-option"}
                    type="button"
                    onClick={() => updatePrivacySettings({ privacyProtectionLevel: "standard" })}
                  >
                    <span>标准跟踪保护</span>
                    <span>页面兼容性更好，拦截常见跟踪器。</span>
                  </button>
                  <button
                    className={settings?.privacyProtectionLevel === "strict" ? "settings-option selected" : "settings-option"}
                    type="button"
                    onClick={() => updatePrivacySettings({ privacyProtectionLevel: "strict" })}
                  >
                    <span>严格跟踪保护</span>
                    <span>更强拦截与指纹保护，可能影响部分网站。</span>
                  </button>
                </div>
                <p className="settings-message">隐私保护使用 Firefox/GeckoView 内核能力。标准为默认等级，严格可能影响部分网站。</p>
                {privacyMessage && <p className="settings-message">{privacyMessage}</p>}
              </>
            )}
          </div>
        )}
        {showBackupSettings && (
          <div className="settings-card settings-card-spaced">
            <button
              className="settings-row settings-row-button"
              type="button"
              aria-expanded={backupExpanded}
              onClick={() => setBackupExpanded((expanded) => !expanded)}
            >
              <span className="settings-row-title">本地备份</span>
              <span className="settings-row-value">书签与 WebApp</span>
            </button>
            {backupExpanded && (
              <>
                <div className="settings-actions">
                  <button className="settings-action primary" type="button" onClick={exportBackup}>
                    导出 JSON
                  </button>
                  <button className="settings-action" type="button" onClick={importBackup}>
                    导入 JSON
                  </button>
                </div>
                <p className="settings-message">导入会合并当前书签和 WebApp，不会清空现有数据；桌面快捷方式需要重新固定。</p>
                {backupMessage && <p className="settings-message">{backupMessage}</p>}
              </>
            )}
          </div>
        )}
        {showBackgroundRuntime && (
          <div className="settings-card settings-card-spaced">
            <button
              className="settings-row settings-row-button"
              type="button"
              aria-expanded={backgroundRuntimeExpanded}
              onClick={() => setBackgroundRuntimeExpanded((expanded) => !expanded)}
            >
              <span className="settings-row-title">后台运行</span>
              <span className="settings-row-value">{batteryOptimizationLabel()}</span>
            </button>
            {backgroundRuntimeExpanded && (
              <>
                <label className="settings-toggle-row">
                  <span className="settings-toggle-copy">
                    <span className="settings-row-title">后台视频播放增强</span>
                    <span className="settings-toggle-description">对所有网页启用，减少切后台后网页主动暂停播放。</span>
                  </span>
                  <input
                    className="settings-toggle"
                    type="checkbox"
                    checked={!!settings?.backgroundVideoEnhancementEnabled}
                    disabled={!settings}
                    onChange={(event) => updateBackgroundVideoEnhancement(event.currentTarget.checked)}
                  />
                </label>
                <p className="settings-message">
                  如果锁屏后下载、音乐播放或视频播放被系统中断，可将 Hyper Browser 设为电池无限制或允许后台运行。
                </p>
                <div className="settings-actions">
                  <button className="settings-action" type="button" onClick={openBatteryOptimizationSettings}>
                    打开电池设置
                  </button>
                </div>
                {batteryMessage && <p className="settings-message">{batteryMessage}</p>}
              </>
            )}
          </div>
        )}
        {showUpdateSettings && (
          <div className="settings-card settings-card-spaced">
            <button
              className="settings-row settings-row-button"
              type="button"
              aria-expanded={updateSettingsExpanded}
              onClick={() => setUpdateSettingsExpanded((expanded) => !expanded)}
            >
              <span className="settings-row-title">应用更新</span>
              <span className="settings-row-value">
                {updateResult ? `${updateResult.currentVersionName} · ${updateStatusLabel(updateResult)}` : "GitHub Release"}
              </span>
            </button>
            {updateSettingsExpanded && (
              <>
                {updateResult?.update && (
                  <div className="settings-options">
                    <div className="settings-option">
                      <span>{updateResult.update.versionName}</span>
                      <span>
                        {updateResult.update.asset.abi}
                        {formatBytes(updateResult.update.asset.sizeBytes) ? ` · ${formatBytes(updateResult.update.asset.sizeBytes)}` : ""}
                      </span>
                    </div>
                    {updateResult.update.notes && (
                      <p className="settings-message">{updateResult.update.notes}</p>
                    )}
                  </div>
                )}
                <div className="settings-actions">
                  <button className="settings-action" type="button" disabled={updateChecking} onClick={() => checkUpdate(false)}>
                    {updateChecking ? "检查中..." : "检查更新"}
                  </button>
                  {updateResult?.status === "skipped" && (
                    <button className="settings-action" type="button" onClick={() => checkUpdate(true)}>仍然查看</button>
                  )}
                  {updateResult?.update && (
                    <button
                      className="settings-action primary"
                      type="button"
                      disabled={!!updateDownload && isActiveDownloadState(updateDownload.status)}
                      onClick={installUpdate}
                    >
                      {updateActionLabel()}
                    </button>
                  )}
                  {updateResult?.update && updateResult.status !== "skipped" && (
                    <button className="settings-action" type="button" onClick={skipUpdate}>跳过此版本</button>
                  )}
                  {(updateResult?.skippedVersionCode ?? 0) > 0 && (
                    <button className="settings-action" type="button" onClick={clearSkippedUpdate}>取消跳过</button>
                  )}
                </div>
                {updateDownload && updateDownload.status !== "idle" && (
                  <div className="settings-update-progress">
                    <div className="settings-update-progress-track">
                      <div
                        className="settings-update-progress-fill"
                        style={{ width: `${updateProgressPercent(updateDownload)}%` }}
                      />
                    </div>
                    <div className="settings-update-progress-text">
                      <span>{updateDownloadMessage(updateDownload) || (updateResult ? updateStatusLabel(updateResult) : "正在处理更新...")}</span>
                      <span>
                        {formatBytes(updateDownload.bytesDownloaded)}
                        {updateDownload.totalBytes > 0 ? ` / ${formatBytes(updateDownload.totalBytes)}` : ""}
                      </span>
                    </div>
                  </div>
                )}
                {updateMessage && (!updateDownload || !isActiveDownloadState(updateDownload.status)) && (
                  <p className="settings-message">{updateMessage}</p>
                )}
              </>
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
