import type { CapacitorConfig } from "@capacitor/cli";

const config: CapacitorConfig = {
  appId: "com.houtsider.dbx",
  appName: "DBX Mobile",
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
