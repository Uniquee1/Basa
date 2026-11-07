# Basa

Basa is a lightweight Android app that reads text from screenshots and lets you ask an AI about it. Screenshot text is recognized on your device. Basa does not keep a chat or image history.

## Install

1. Copy [Basa.apk](Basa.apk) to your Android phone.
2. Open the APK from your Files app and tap **Install**. If Android asks, allow your Files app to install unknown apps, then try again.
3. Open **Basa**. The app requires Android 7.0 or newer.

The APK is a build artifact and may not be included in the GitHub repository. Build it from the source using either method below.

### Build with Android Studio

1. Install Android Studio and the Android SDK for API 35.
2. Clone or download this repository, then open its project folder in Android Studio.
3. Let Gradle sync, then select **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
4. Android Studio creates the debug APK at `app/build/outputs/apk/debug/app-debug.apk`.

### Build from a terminal

With JDK 17 or newer, Android SDK API 35, and Gradle 8.11.1 installed, run this from the project folder:

```powershell
gradle assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`. Rename it to `Basa.apk` if desired.

## Use Basa

1. Share an image or screenshot to **Basa**, or open Basa and tap **Import screenshot**.
2. Basa scans the image and displays the recognized text. You can edit it or tap **Copy all**.
3. Tap **Ask AI about this** to start a chat with the screenshot text as context.

## Set up AI chat

AI chat requires your own API key from Groq or OpenRouter. OCR works without an API key.

1. Open **Settings** in Basa and choose **Groq** or **OpenRouter**.
2. Enter your API key and tap **Test connection & load models**.
3. Choose a model from the list, or enter a model ID, then tap **Save configuration**.
4. Return to the screenshot and tap **Ask AI about this**. Your API key is stored encrypted on the device.
