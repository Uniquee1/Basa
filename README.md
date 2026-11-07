# Basa

Basa is a lightweight Android app that reads text from screenshots and lets you ask an AI about it. Screenshot text is recognized on your device. Basa does not keep a chat or image history.

## Install

1. Copy [Basa.apk](Basa.apk) to your Android phone.
2. Open the APK from your Files app and tap **Install**. If Android asks, allow your Files app to install unknown apps, then try again.
3. Open **Basa**. The app requires Android 7.0 or newer.

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
