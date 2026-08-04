import type { CapacitorConfig } from "@capacitor/cli";

const config: CapacitorConfig = {
  appId: "com.coolbanhub.mobiledbmanager",
  appName: "Mobile DB Manager",
  webDir: "dist",
  android: {
    allowMixedContent: false,
    backgroundColor: "#f5f7fb",
  },
  plugins: {
    SplashScreen: {
      launchAutoHide: true,
      launchShowDuration: 900,
      backgroundColor: "#f5f7fb",
      showSpinner: false,
    },
    StatusBar: {
      overlaysWebView: false,
      style: "DARK",
      backgroundColor: "#ffffff",
    },
  },
};

export default config;
