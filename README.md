# IVAC OTP Forwarder (Android App)

Dedicated Android Application to intercept IVAC OTP SMS and forward it directly to your Firebase Realtime Database.

## Features
- **Direct Firebase Sync**: Pre-configured with your Firebase Realtime Database URL (`https://ivac-otp-receiver-default-rtdb.asia-southeast1.firebasedatabase.app`).
- **Word-to-Digit Parser**: Automatically decodes IVAC word sequences (e.g. `Six-Seven-Eight-One-Four-One` ➔ `678141`).
- **24/7 Foreground Service**: Keeps listening for SMS even when screen is locked or app is closed.
- **Boot Receiver**: Automatically launches on device restart.
- **One-Tap Test**: "Test Send to Firebase" button to verify the integration without needing an actual SMS.
- **Activity Logs**: Real-time on-screen log monitor.

## How to Build the APK

### Option 1: Using Android Studio (Recommended)
1. Open **Android Studio**.
2. Select **Open** and choose the `ivac-sms-forwarder` folder.
3. Wait for Gradle sync to complete.
4. Go to menu: **Build ➔ Build Bundle(s) / APK(s) ➔ Build APK(s)**.
5. Once built, click **locate** to get `app-debug.apk` and transfer to your phone.

### Option 2: Using GitHub Actions (Cloud 1-Click Build, No Android Studio needed)
1. Create a new GitHub repository (can be Private).
2. Push this folder to your GitHub repo.
3. The `.github/workflows/build-apk.yml` workflow will automatically run and generate the `.apk` under the **Actions** tab as a downloadable artifact!
