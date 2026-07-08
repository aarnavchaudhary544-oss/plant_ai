# Plant AI 🌱

An intelligent Android application that identifies plants and acts as a virtual "Plant Doctor" entirely on your device. Plant AI uses on-device Generative AI via Google's Gemma Vision model to analyze photos of your plants, diagnose diseases, and provide detailed care advice—no internet connection required for inference!

## Features

- **On-Device AI Inference**: Uses MediaPipe Tasks GenAI and a 2.0GB quantized Gemma 4 E2B Vision model (`.task`) to process images directly on your device.
- **Robust Background Downloading**: Downloads the large 2.0GB AI model using a dedicated **Foreground Service**. The download securely continues in the background even if you minimize or close the app, displaying a persistent progress notification.
- **Dynamic UI/UX**: When consulting the Plant Doctor, the UI dynamically transitions your selected photo into a circular crop in the upper right, displaying the plant name alongside it. This provides a clean, unobstructed view of the AI's diagnosis text.
- **Camera Integration**: Built-in CameraX integration for taking photos of plants instantly.
- **Gallery Support**: Upload existing photos of plants from your device's gallery.
- **Automated CI/CD**: Fully automated GitHub Actions workflow builds and publishes the compiled APK directly to the GitHub Releases section using Git LFS on every push to the `main` branch.
- **Privacy First**: Because inference happens locally on the device, your photos and plant data never leave your phone.

## How it Works

1. **Take a Photo**: Use the camera view to snap a picture of a plant or its leaves.
2. **Download Model**: The first time you attempt to consult the Plant Doctor, the app will download the required Gemma Vision model (~2.0 GB) to your device's external app storage via a Foreground Service.
3. **Consult the Doctor**: Once downloaded, tap "Consult Plant Doctor". The app passes the image to the MediaPipe `LlmInferenceSession` along with a prompt to analyze the growing conditions, watering needs, and potential diseases.
4. **Streaming Response**: The AI streams its analysis chunk-by-chunk directly into the UI for a fast, ChatGPT-like experience.

## Requirements

- **Android SDK**: Min SDK 24, Target SDK 36
- **Device Specs**: A high-end Android device is recommended. The app requests `largeHeap="true"` to load the 2GB model into memory without triggering an `OutOfMemoryError`.

## Build Instructions

1. Clone the repository.
2. Open the project in Android Studio.
3. Sync Gradle.
4. Build and run on a physical Android device (emulators may struggle with on-device LLM inference due to memory and graphics constraints).

## Technical Details

- **Language**: Kotlin
- **Core Library**: `com.google.mediapipe:tasks-genai:0.10.27`
- **UI**: XML Layouts & ViewBinding (Jetpack Compose boilerplate removed for a leaner APK)
- **Concurrency**: Kotlin Coroutines & Flows
- **CI/CD**: GitHub Actions & Git LFS
