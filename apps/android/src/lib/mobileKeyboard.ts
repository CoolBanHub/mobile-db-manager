import { Capacitor, registerPlugin } from "@capacitor/core";

interface MobileKeyboardPlugin {
  show(): Promise<void>;
}

const MobileKeyboard = registerPlugin<MobileKeyboardPlugin>("MobileKeyboard");

function needsTextKeyboard(target: EventTarget | null): target is HTMLInputElement | HTMLTextAreaElement {
  if (target instanceof HTMLTextAreaElement) return true;
  if (!(target instanceof HTMLInputElement)) return false;
  return !["button", "checkbox", "color", "file", "radio", "range", "reset", "submit"].includes(target.type);
}

export function installMobileKeyboardFocusBridge() {
  if (!Capacitor.isNativePlatform()) return;
  document.addEventListener("focusin", (event) => {
    if (!needsTextKeyboard(event.target)) return;
    // WebView focus is already established when focusin fires. The native
    // request makes IME behavior deterministic when an emulator exposes the
    // host keyboard as a physical keyboard.
    window.setTimeout(() => {
      void MobileKeyboard.show().catch(() => undefined);
    }, 0);
  });
}
