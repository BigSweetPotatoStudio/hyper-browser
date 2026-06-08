(function () {
  const nativeApp = "hyperBrowser";
  const SETTING_TYPE = "settings.backgroundVideoEnhancement.enabled";
  const IS_YOUTUBE = window.location.hostname.search(/(?:^|.+\.)youtube\.com/) > -1 ||
    window.location.hostname.search(/(?:^|.+\.)youtube-nocookie\.com/) > -1;
  const IS_MOBILE_YOUTUBE = window.location.hostname === "m.youtube.com";
  const IS_DESKTOP_YOUTUBE = IS_YOUTUBE && !IS_MOBILE_YOUTUBE;
  const IS_VIMEO = window.location.hostname.search(/(?:^|.+\.)vimeo\.com/) > -1;
  const IS_ANDROID = window.navigator.userAgent.indexOf("Android") > -1;

  if ((!IS_YOUTUBE && !IS_VIMEO) || window.__hyperBackgroundVideoEnhancementLoaded) {
    return;
  }
  window.__hyperBackgroundVideoEnhancementLoaded = true;

  function canSendNative() {
    return typeof browser !== "undefined" &&
      browser.runtime &&
      typeof browser.runtime.sendNativeMessage === "function";
  }

  function canSendViaBackground() {
    return typeof browser !== "undefined" &&
      browser.runtime &&
      typeof browser.runtime.sendMessage === "function";
  }

  function parseResponse(response) {
    return typeof response === "string" ? JSON.parse(response) : response;
  }

  function requestEnabled() {
    const payload = { sourceUrl: location.href };
    if (canSendNative()) {
      return browser.runtime.sendNativeMessage(nativeApp, { type: SETTING_TYPE, payload })
        .then(parseResponse)
        .then((response) => !!(response && response.ok && response.data && response.data.enabled));
    }
    if (canSendViaBackground()) {
      return browser.runtime.sendMessage({ nativeApp, type: SETTING_TYPE, payload })
        .then(parseResponse)
        .then((response) => !!(response && response.ok && response.data && response.data.enabled));
    }
    return Promise.resolve(false);
  }

  requestEnabled()
    .then((enabled) => {
      if (enabled) enableBackgroundVideoEnhancement();
    })
    .catch(() => {});

  function enableBackgroundVideoEnhancement() {
    if (window.__hyperBackgroundVideoEnhancementEnabled) return;
    window.__hyperBackgroundVideoEnhancementEnabled = true;

    if (IS_ANDROID || !IS_DESKTOP_YOUTUBE) {
      try {
        Object.defineProperties(document.wrappedJSObject || document, {
          hidden: { value: false },
          visibilityState: { value: "visible" },
        });
      } catch (_) {}
    }

    window.addEventListener("visibilitychange", stopEvent, true);

    if (IS_VIMEO) {
      window.addEventListener("fullscreenchange", stopEvent, true);
    }

    if (IS_YOUTUBE) {
      loop(sendActivityKey, 60 * 1000, 10 * 1000);
    }
  }

  function stopEvent(event) {
    event.stopImmediatePropagation();
  }

  function sendActivityKey() {
    const keyCode = 18;
    sendKeyEvent("keydown", keyCode);
    sendKeyEvent("keyup", keyCode);
  }

  function sendKeyEvent(eventName, keyCode) {
    document.dispatchEvent(new KeyboardEvent(eventName, {
      bubbles: true,
      cancelable: true,
      key: "Alt",
      code: "AltLeft",
      keyCode,
      which: keyCode,
    }));
  }

  function loop(callback, delay, jitter) {
    const currentJitter = getRandomInt(-jitter / 2, jitter / 2);
    const nextDelay = Math.max(delay + currentJitter, 0);
    window.setTimeout(() => {
      callback();
      loop(callback, delay, jitter);
    }, nextDelay);
  }

  function getRandomInt(minimum, maximum) {
    const min = Math.ceil(minimum);
    const max = Math.floor(maximum);
    return Math.floor(Math.random() * (max - min)) + min;
  }
})();
