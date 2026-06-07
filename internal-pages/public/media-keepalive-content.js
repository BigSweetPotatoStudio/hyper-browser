(function () {
  const nativeApp = "hyperBrowser";
  const START_TYPE = "media.keepAlive.start";
  const PAUSE_TYPE = "media.keepAlive.pause";
  const STOP_TYPE = "media.keepAlive.stop";
  let playbackState = "stopped";
  let lastSignature = "";
  let lastSentAt = 0;

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

  function hasKnownMediaSource(media) {
    return hasLiveAudioTrack(media) ||
      media.readyState >= HTMLMediaElement.HAVE_METADATA ||
      !!media.currentSrc ||
      !!media.src;
  }

  function isPausedCandidate(media) {
    return !!media && media.paused && !media.ended && hasKnownMediaSource(media);
  }

  function mediaElements() {
    return Array.from(document.querySelectorAll("audio,video"));
  }

  function currentMediaState() {
    const items = mediaElements();
    const playing = items.find(isPlayingCandidate);
    if (playing) {
      return { state: "playing", type: START_TYPE, media: playing };
    }
    const paused = items.find(isPausedCandidate);
    if (paused && playbackState !== "stopped") {
      return { state: "paused", type: PAUSE_TYPE, media: paused };
    }
    return null;
  }

  function describeMedia(media) {
    const hasStreamAudio = hasLiveAudioTrack(media);
    return {
      title: document.title || location.hostname || "Playing media",
      url: location.href,
      mediaKind: hasStreamAudio ? "webrtc-audio" : media.tagName.toLowerCase(),
    };
  }

  function nativeMessage(type, payload) {
    return {
      type,
      payload: {
        ...(payload || {}),
        sourceUrl: payload && payload.sourceUrl ? payload.sourceUrl : location.href,
      },
    };
  }

  function backgroundMessage(type, payload) {
    return {
      nativeApp,
      type,
      payload: {
        ...(payload || {}),
        sourceUrl: payload && payload.sourceUrl ? payload.sourceUrl : location.href,
      },
    };
  }

  function sendViaBackground(type, payload) {
    if (!canSendViaBackground()) return Promise.resolve();
    return browser.runtime.sendMessage(backgroundMessage(type, payload));
  }

  function send(type, payload) {
    if (canSendNative()) {
      browser.runtime.sendNativeMessage(nativeApp, nativeMessage(type, payload))
        .catch(() => sendViaBackground(type, payload))
        .catch(() => {});
      return;
    }
    sendViaBackground(type, payload).catch(() => {});
  }

  function refresh(force) {
    const current = currentMediaState();
    if (!current) {
      if (playbackState !== "stopped") {
        playbackState = "stopped";
        lastSignature = "";
        send(STOP_TYPE, { url: location.href });
      }
      return;
    }

    const payload = describeMedia(current.media);
    const signature = `${current.state}|${payload.mediaKind}|${payload.title}|${payload.url}`;
    const now = Date.now();
    if (!force && signature === lastSignature && now - lastSentAt < 3000) {
      return;
    }

    playbackState = current.state;
    lastSignature = signature;
    lastSentAt = now;
    send(current.type, payload);
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

  window.setInterval(() => refresh(false), 3000);
})();
