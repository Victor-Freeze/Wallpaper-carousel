# Wallpaper carousel 🎠

A lightweight, resource-optimized, and visually stunning Android application built with **Kotlin**, **Jetpack Compose**, and **Material Design 3**. It automates your home screen and lock screen wallpaper rotation using a customizable list of images.


---

## 🌟 Core Features

- **Wallpaper Carousel Queue**: Add, preview, reorder, or clear images from your gallery to construct your rotation playlist.
- **Dynamic Change Triggers**:
  - **Timer-Based**: Automated rotation at customizable intervals.
  - **Screen Interactions**: Rotation triggered by specific daily routines or device events.
- **Custom Scaling Adapters**: Ensure every wallpaper matches your screen dimensions perfectly using several scale modes:
  - **Fill Screen**
  - **Fit Screen (Aspect Ratio Maintained)**
  - **Center Crop**
- **Robust Background Service**: The rotation logic operates seamlessly on a persistent background service, maintaining state even after device reboots.
- **Resource & Memory Safeguards**: Highly optimized bitmap processing pipeline including custom decoders with sample-size calculation, stream safety, and explicit multi-stage bitmap recycling in `finally` blocks to guarantee zero memory leaks or Out-of-Memory (OOM) errors.

---

## 🛠️ Architecture & Technologies

The app adheres to modern Android architecture principles:
- **UI Framework**: Modern Jetpack Compose using Material Design 3 guidelines featuring fluid responsive paddings, edge-to-edge screens (`enableEdgeToEdge`), and structured components.
- **State Management**: Model-View-ViewModel (MVVM) architecture with `ViewModel` and reactive `StateFlow` streams.
- **Data Persistence**: A robust Room database storing user configurations, wallpaper queues, and historical rotation logs.
- **Concurrency**: Asynchronous tasks handled via Kotlin Coroutines and background lifecycles.
- **Image Operations**: Custom downscaling stream decoders to load ultra-high-resolution images without taxing system RAM.

---

## 🦾 Created by AI Coding Agent

This entire codebase — including files, UI designs, layout logic, custom vector icons, and robust database models — has been built, customized, and thoroughly optimized by **Google AI Studio's AI Coding Agent** (powered by DeepMind and Gemini models). 

The application is fully functional, compiling successfully, and complies with edge-to-edge, accessibility (48dp touch targets), and Material 3 design directives.
