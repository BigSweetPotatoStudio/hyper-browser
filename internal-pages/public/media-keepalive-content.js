(function () {
  const nativeApp = "hyperBrowser";
  const START_TYPE = "media.keepAlive.start";
  const STOP_TYPE = "media.keepAlive.stop";
  let active = false;
  let lastSignature = "";
  let lastSentAt = 0;

  function canSend() {
    return typeof browser !== "undefined" &&
      browser.runtime &&
      typeof browser.runtime.sendMessage === "function";
  }

  function hasLiveAudioTrack(media) {
    const stream = media && media.srcObject;
    if (!stream || typeof stream.getAudioTracks !== "function") {
      return false;
    }
    return stream.getAudioTracks().some((track) => track.readyState === "live" && track.enabled !== false);
  }

  function isPlayingCandidate(media) {
    if (!media || media.paused || media.ended || media.muted || media.volume === 0) {
      return false;
    }
    return hasLiveAudioTrack(media) ||
      media.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA ||
      !!media.currentSrc;
  }

  function activeMediaElements() {
    return Array.from(document.querySelectorAll("audio,video"))
      .filter(isPlayingCandidate);
  }

  function describeMedia(media) {
    const hasStreamAudio = hasLiveAudioTrack(media);
    return {
      title: document.title || location.hostname || "Playing media",
      url: location.href,
      mediaKind: hasStreamAudio ? "webrtc-audio" : media.tagName.toLowerCase(),
    };
  }

  function send(type, payload) {
    if (!canSend()) return;
    browser.runtime.sendMessage({
      nativeApp,
      type,
      payload: payload || {},
    }).catch(() => {});
  }

  function refresh(force) {
    const items = activeMediaElements();
    if (items.length === 0) {
      if (active) {
        active = false;
        lastSignature = "";
        send(STOP_TYPE, { url: location.href });
      }
      return;
    }

    const payload = describeMedia(items[0]);
    const signature = `${payload.mediaKind}|${payload.title}|${payload.url}`;
    const now = Date.now();
    if (!force && active && signature === lastSignature && now - lastSentAt < 3000) {
      return;
    }

    active = true;
    lastSignature = signature;
    lastSentAt = now;
    send(START_TYPE, payload);
  }

  function scheduleRefresh(force) {
    window.setTimeout(() => refresh(force), 0);
  }

  ["play", "playing", "pause", "ended", "emptied", "volumechange", "loadedmetadata"].forEach((eventName) => {
    document.addEventListener(eventName, () => scheduleRefresh(eventName === "play" || eventName === "playing"), true);
  });

  const observer = new MutationObserver(() => scheduleRefresh(false));
  observer.observe(document.documentElement || document, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ["src"],
  });

  window.addEventListener("pagehide", () => {
    if (active) {
      send(STOP_TYPE, { url: location.href });
    }
  });

  window.setInterval(() => refresh(false), 3000);
})();
