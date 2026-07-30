import "@fontsource-variable/azeret-mono";
import { createApp } from "vue";
import App from "./DirectApp.vue";
import "./styles.css";
import { installMobileKeyboardFocusBridge } from "./lib/mobileKeyboard";

installMobileKeyboardFocusBridge();
createApp(App).mount("#app");
