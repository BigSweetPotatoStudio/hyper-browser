(function () {
  const editableSelector = [
    "input:not([type=button]):not([type=checkbox]):not([type=color]):not([type=file]):not([type=hidden]):not([type=image]):not([type=radio]):not([type=range]):not([type=reset]):not([type=submit])",
    "textarea",
    "select",
    "[contenteditable='']",
    "[contenteditable='true']",
  ].join(",");

  let focusedEditable = null;
  let lastViewportHeight = viewportHeight();
  let expandedViewportHeight = lastViewportHeight;
  let observedViewportHeight = lastViewportHeight;
  let lastViewportChangeAt = 0;
  let settlingUntil = 0;
  let revealIntervalId = 0;
  let maxSessionTimerId = 0;
  let revealToken = 0;
  let revealArmed = false;

  const keyboardDeltaPx = 48;
  const stableDeltaPx = 2;
  const stableDelayMs = 100;
  const settleAfterStableMs = 500;
  const revealIntervalMs = 90;
  const maxRevealSessionMs = 2000;

  function viewportHeight() {
    return window.visualViewport ? window.visualViewport.height : window.innerHeight;
  }

  function isEditable(element) {
    return element instanceof Element && element.matches(editableSelector);
  }

  function activeEditable() {
    if (!document.hasFocus()) {
      return null;
    }
    const active = document.activeElement;
    if (isEditable(active)) {
      focusedEditable = active;
      return active;
    }
    return isEditable(focusedEditable) ? focusedEditable : null;
  }

  function isVisibleEnough(element) {
    const rect = element.getBoundingClientRect();
    const height = viewportHeight();
    const topPadding = 12;
    const bottomPadding = 18;
    return rect.top >= topPadding && rect.bottom <= height - bottomPadding;
  }

  function revealFocusedEditable() {
    const target = activeEditable();
    if (!revealArmed || !target) {
      return false;
    }
    if (!isVisibleEnough(target)) {
      target.scrollIntoView({
        block: "nearest",
        inline: "nearest",
        behavior: "auto",
      });
    }
    return true;
  }

  function clearRevealTimers() {
    if (revealIntervalId) {
      window.clearInterval(revealIntervalId);
      revealIntervalId = 0;
    }
    if (maxSessionTimerId) {
      window.clearTimeout(maxSessionTimerId);
      maxSessionTimerId = 0;
    }
  }

  function stopAutoReveal() {
    revealArmed = false;
    revealToken += 1;
    settlingUntil = 0;
    clearRevealTimers();
  }

  function runRevealStep(token) {
    if (token !== revealToken || !revealArmed) {
      return;
    }
    if (!revealFocusedEditable()) {
      stopAutoReveal();
      return;
    }

    const now = Date.now();
    const currentHeight = viewportHeight();
    if (Math.abs(currentHeight - observedViewportHeight) > stableDeltaPx) {
      observedViewportHeight = currentHeight;
      lastViewportChangeAt = now;
      settlingUntil = 0;
      return;
    }

    if (now - lastViewportChangeAt >= stableDelayMs) {
      if (!settlingUntil) {
        settlingUntil = now + settleAfterStableMs;
      } else if (now >= settlingUntil) {
        stopAutoReveal();
      }
    }
  }

  function startRevealSession() {
    if (!activeEditable()) {
      return;
    }

    const now = Date.now();
    const currentHeight = viewportHeight();
    observedViewportHeight = currentHeight;
    lastViewportChangeAt = now;
    settlingUntil = 0;

    if (revealArmed) {
      revealFocusedEditable();
      return;
    }

    revealArmed = true;
    const token = ++revealToken;
    revealFocusedEditable();
    revealIntervalId = window.setInterval(() => runRevealStep(token), revealIntervalMs);
    maxSessionTimerId = window.setTimeout(() => {
      if (token === revealToken) {
        stopAutoReveal();
      }
    }, maxRevealSessionMs);
  }

  function markUserInteraction() {
    if (revealArmed) {
      stopAutoReveal();
    }
  }

  document.addEventListener("focusin", (event) => {
    if (isEditable(event.target)) {
      focusedEditable = event.target;
      if (viewportHeight() < expandedViewportHeight - keyboardDeltaPx) {
        startRevealSession();
      }
    }
  }, true);

  document.addEventListener("focusout", (event) => {
    if (event.target === focusedEditable) {
      focusedEditable = null;
      stopAutoReveal();
    }
  }, true);

  function onViewportChanged() {
    const nextHeight = viewportHeight();
    const heightIsShrinking = nextHeight < lastViewportHeight - stableDeltaPx;
    const heightIsGrowing = nextHeight > lastViewportHeight + stableDeltaPx;

    if (nextHeight > expandedViewportHeight) {
      expandedViewportHeight = nextHeight;
    }

    if (heightIsGrowing) {
      stopAutoReveal();
    } else if (heightIsShrinking && nextHeight < expandedViewportHeight - keyboardDeltaPx && activeEditable()) {
      startRevealSession();
    }

    lastViewportHeight = nextHeight;
  }

  document.addEventListener("touchstart", markUserInteraction, { capture: true, passive: true });
  document.addEventListener("wheel", markUserInteraction, { capture: true, passive: true });
  document.addEventListener("keydown", (event) => {
    if (["ArrowUp", "ArrowDown", "PageUp", "PageDown", "Home", "End", " "].includes(event.key)) {
      markUserInteraction();
    }
  }, true);
  window.addEventListener("resize", onViewportChanged, true);
  if (window.visualViewport) {
    window.visualViewport.addEventListener("resize", onViewportChanged);
  }
})();
