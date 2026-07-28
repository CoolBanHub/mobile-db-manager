import type { CapacitorConfig } from "@capacitor/cli";

const config: CapacitorConfig = {
  appId: "com.houtsider.dbx",
  appName: "DBX Mobile",
  webDir: "dist",
  android: {
    allowMixedContent: false,
    backgroundColor: "#0b0d0c",
  },
  plugins: {
    CapacitorHttp: {
      enabled: true,
    },
    SplashScreen: {
      launchAutoHide: true,
      launchShowDuration: 900,
      backgroundColor: "#0b0d0c",
      showSpinner: false,
    },
    StatusBar: {
      style: "LIGHT",
      backgroundColor: "#0b0d0c",
    },
  },
};

export default config;
