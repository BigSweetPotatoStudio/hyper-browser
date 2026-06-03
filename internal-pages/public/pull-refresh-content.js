(function () {
  const nativeApp = "hyperBrowser";
  let lastSentAt = 0;
  let lastCanScrollUp = false;

  function isScrollable(element) {
    if (!element || element === document.documentElement || element === document.body) {
      return false;
    }
    const style = window.getComputedStyle(element);
    const overflowY = style.overflowY;
    return (overflowY === "auto" || overflowY === "scroll" || overflowY === "overlay") &&
      element.scrollHeight > element.clientHeight + 1;
  }

  function canScrollUpFromPoint(x, y) {
    let element = document.elementFromPoint(x, y);
    while (element) {
      if (isScrollable(element) && element.scrollTop > 1) {
        return true;
      }
      element = element.parentElement;
    }

    const root = document.scrollingElement || document.documentElement;
    return !!root && root.scrollTop > 1;
  }

  function sendTouchState(event, force) {
    const touch = event.touches && event.touches[0];
    if (!touch || typeof browser === "undefined" || !browser.runtime || typeof browser.runtime.sendMessage !== "function") {
      return;
    }

    const canScrollUp = canScrollUpFromPoint(touch.clientX, touch.clientY);
    const now = Date.now();
    if (!force && canScrollUp === lastCanScrollUp && now - lastSentAt < 120) {
      return;
    }
    lastSentAt = now;
    lastCanScrollUp = canScrollUp;

    browser.runtime.sendMessage({
      nativeApp,
      type: "pullRefresh.touch",
      payload: { canScrollUp }
    }).catch(() => {});
  }

  window.addEventListener("touchstart", (event) => sendTouchState(event, true), { capture: true, passive: true });
  window.addEventListener("touchmove", (event) => sendTouchState(event, false), { capture: true, passive: true });
  window.addEventListener("touchend", () => {
    if (typeof browser === "undefined" || !browser.runtime || typeof browser.runtime.sendMessage !== "function") {
      return;
    }
    browser.runtime.sendMessage({
      nativeApp,
      type: "pullRefresh.touch",
      payload: { canScrollUp: false }
    }).catch(() => {});
  }, { capture: true, passive: true });
})();
