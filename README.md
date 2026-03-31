# Monochrome Cordova Android Wrapper

A simple Android wrapper for [monochrome.tf](https://monochrome.tf) built with Apache Cordova.

## Features
- Native Android WebView for best performance
- Fullscreen with no status bar
- Auth and login stays inside the app
- Custom icon and splash screen

## Requirements
- Node.js
- Apache Cordova (`npm install -g cordova`)
- Android Studio + SDK
- Java JDK (bundled with Android Studio)

## Setup
1. Clone the repo
2. Install dependencies:
```bash
   npm install
```
3. Add the Android platform:
```bash
   cordova platform add android
```
4. Install plugins:
```bash
   cordova plugin add cordova-plugin-splashscreen
   cordova plugin add cordova-plugin-statusbar
   cordova plugin add cordova-plugin-inappbrowser
```

## Build
```bash
cordova build android
```
APK will be at `platforms/android/app/build/outputs/apk/debug/app-debug.apk`

## Disclaimer
This is an unofficial wrapper and is not affiliated with or endorsed by monochrome.tf.
