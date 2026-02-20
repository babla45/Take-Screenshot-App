# TakeSS - Screenshot Capture App

**TakeSS** is an Android application that provides a convenient way to capture screenshots using a Quick Settings tile, with customizable storage options and instant preview functionality.

## Features

### 📸 Quick Screenshot Capture
- **Quick Settings Tile**: Add a tile to your notification shade for instant screenshot access
- **One-Tap Capture**: Take screenshots without opening the app
- **Background Service**: Keeps the screenshot functionality running in the background

### 👁️ Screenshot Preview
- **Instant Preview**: View captured screenshots immediately after capture
- **Customizable Duration**: Set auto-save delay (0.1 to 60 seconds)
- **Quick Actions**: Save or discard screenshots from the preview window

### 💾 Flexible Storage Options
- **Internal Storage**: Save screenshots to `Pictures/TakeSS` folder (default)
- **Custom Folder**: Choose any folder on internal or SD card storage
- **SAF Integration**: Uses Storage Access Framework for reliable SD card access

### ⚙️ Customization
- **Filename Prefix**: Customize the prefix for screenshot filenames
- **Preview Duration**: Adjust how long the preview shows before auto-saving
- **Storage Location**: Switch between internal storage and custom folders

## Screenshots

The app uses Android's MediaProjection API to capture screenshots without requiring root access.

## Requirements

- **Minimum SDK**: Android 7.0 (API 24)
- **Target SDK**: Android 14 (API 36)
- **Permissions**:
  - `FOREGROUND_SERVICE` - Run background screenshot service
  - `FOREGROUND_SERVICE_MEDIA_PROJECTION` - Media projection for screenshots
  - `POST_NOTIFICATIONS` - Show notification and preview (Android 13+)
  - `WRITE_EXTERNAL_STORAGE` - Save to storage (Android 9 and below)

## Installation

### Download APK
📥 **[Download the latest APK](https://drive.google.com/drive/folders/1vrd1-Zxgwuh7rZG4pq1XJ3_MtDhWT-tV)**

Download and install the APK directly on your Android device.

### From Source
1. Clone this repository:
   ```bash
   git clone https://github.com/yourusername/TakeSS.git
   ```

2. Open the project in Android Studio

3. Build and run the app on your device or emulator:
   ```
   Build → Make Project
   Run → Run 'app'
   ```

### Build APK Yourself
1. Build the APK in Android Studio:
   ```
   Build → Build Bundle(s) / APK(s) → Build APK(s)
   ```

2. Install the generated APK on your Android device
   
## Usage

### Initial Setup

1. **Launch the App**: Open TakeSS from your app drawer

2. **Enable the Service**:
   - Tap **"Enable Service"** button
   - Grant screen capture permission when prompted
   - The service will start running in the background

3. **Configure Storage** (Optional):
   - **Internal Storage** (Default): Screenshots saved to `Pictures/TakeSS`
   - **SD Card / Custom Folder**: Select radio button and choose a folder

4. **Customize Settings** (Optional):
   - Set filename prefix (default: "Screenshot")
   - Adjust preview duration (default: 3 seconds)

### Taking Screenshots

#### Method 1: Quick Settings Tile
1. Swipe down from the top of your screen to open Quick Settings
2. Find and tap the **"TakeSS"** tile (you may need to edit tiles to add it)
3. Screenshot captured! Preview appears automatically

#### Method 2: From Notification
1. When the service is running, you'll see a persistent notification
2. Tap the **"Take Screenshot"** action in the notification
3. Screenshot captured! Preview appears automatically

### Managing Screenshots

- **From Preview Window**:
  - **Auto-save**: Wait for the configured duration (default: 3 seconds)
  - **Manual Save**: Tap the save button
  - **Discard**: Tap the delete button or close the preview

- **Access Saved Screenshots**:
  - Open your gallery app
  - Navigate to `Pictures/TakeSS` (or your custom folder)
  - Screenshots are saved as PNG files with timestamp


## Key Components

### MainActivity
The main configuration screen where users can:
- Enable/disable the screenshot service
- Choose storage location (internal or custom folder)
- Customize filename prefix
- Set preview duration

### ScreenshotService
A foreground service that:
- Manages MediaProjection for screen capture
- Captures screenshots on demand
- Handles image processing and storage
- Provides persistent notification with quick actions

### ScreenshotTileService
Quick Settings tile that:
- Provides one-tap screenshot access from notification shade
- Integrates with Android's Quick Settings framework

### ScreenshotPreviewActivity
Preview window that:
- Shows captured screenshot immediately
- Allows save or discard actions
- Auto-saves after configured duration

## Technical Details

### MediaProjection API
The app uses Android's MediaProjection API to capture the screen:
- No root access required
- Works on all Android 7.0+ devices
- Requires one-time user permission

### Storage Access Framework (SAF)
For custom folder access:
- Uses SAF for persistent storage permissions
- Supports SD card and external storage
- Maintains access across app restarts

### Filename Format
Screenshots are saved with the following format:
```
{prefix}_{timestamp}.png
```
Example: `Screenshot_20260220_143052.png`


## Troubleshooting

### Screenshots not saving
- Ensure the service is enabled (check notification)
- Verify storage permissions are granted
- For custom folders, re-select the folder in settings

### Quick Settings tile not working
- Make sure the service is enabled first
- Check that screen capture permission was granted
- Restart the app if needed

### Preview not showing
- Grant notification permission (Android 13+)
- Check that preview duration is set above 0
- Verify the app is not in battery optimization



## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request



## Support

If you encounter any issues or have questions:
- Open an issue on GitHub
- Check the troubleshooting section above
- Review Android permissions in Settings → Apps → TakeSS

---

