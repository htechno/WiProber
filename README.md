# WiProber

**WiProber** is an open-source Android application for conducting Wi-Fi site surveys. It allows users to load a floor plan, perform Wi-Fi scans using different modes (Stop-and-Go or Continuous), and export the collected data into the `.esx` format, compatible with Ekahau Pro™.

This project was born out of the need for a simple, mobile-first tool for network engineers and enthusiasts to perform quick on-site surveys without expensive, proprietary hardware.

![Screenshot of WiProber App](https://github.com/htechno/WiProber/blob/main/docs/Screenshot_0_info.png)
![](https://github.com/htechno/WiProber/blob/main/docs/Screenshot_1_map.png)
![](https://github.com/htechno/WiProber/blob/main/docs/Screenshot_2_note.png)
![](https://github.com/htechno/WiProber/blob/main/docs/Screenshot_3_scale.png)
![](https://github.com/htechno/WiProber/blob/main/docs/Screenshot_4_scan.png)
![](https://github.com/htechno/WiProber/blob/main/docs/Screenshot_5_Signal_Strength.png)

## Features

*   **Interactive Floor Plan:** Load any image as a floor plan, with support for zoom and pan.
*   **Two Survey Modes:**
    *   **🔴 Stop-and-Go:** Tap a point, wait for a scan, move to the next point. High precision.
    *   **👣 Continuous (New in v2.0):** Tap "Start", walk along a path, and tap to mark turns. The app scans continuously in the background. Faster data collection.
*   **Advanced Data Collection:** Gathers comprehensive data for each network, including SSID, BSSID, RSSI, frequency, security standard (802.11ax/ac/n...), and raw Information Elements (IEs).
*   **Dynamic Adapter Info:** Automatically detects your device model (e.g., "Samsung S23") and injects this metadata into the project file.
*   **Note-Taking:** Add text and photo annotations directly onto the map to document access point locations, obstacles, or other points of interest.
*   **Scale Calibration:** Set the physical scale of the map for accurate-to-reality measurements.
*   **Full `.esx` Export:** Generates a complete `.esx` project file, including all necessary JSON files, binary track data, and attached images, ready for import into Ekahau Pro™.

## Limitations & System Requirements

This is a non-commercial, open-source project. Please be aware of the following system limitations:

*   **Wi-Fi Scan Throttling:**
    *   **The Issue:** Android limits third-party apps to **4 scans per 2 minutes**.
    *   **Stop-and-Go Mode:** WiProber enforces a countdown to prevent errors if you scan too fast.
    *   **Continuous Mode:** **REQUIRED:** You MUST disable "Wi-Fi scan throttling" in Developer Options. WiProber will check this setting and warn you if it's enabled.
*   **Location Services (GPS):** Android requires Location Services to be enabled to see Wi-Fi networks. The app will prompt you to turn it on.
*   **No "My Networks" Detection:** The app currently does not automatically identify or flag your own networks ("My Networks"). This must be done manually within Ekahau Pro after import.
*   **No AP Merging:** WiProber creates a new, separate access point (`accessPoints.json`) for every unique BSSID found. It does not attempt to group multiple radios (e.g., 2.4-GHz and 5-GHz radios) under a single physical access point device. This grouping should be performed manually in Ekahau.
*   **Legacy Android Data:** On devices running Android 10 (API 29) or older, the app is unable to collect raw Information Elements (IEs) due to OS limitations.

## Getting Started

### Prerequisites
*   Android Studio (latest version recommended)
*   An Android device with Android 9 (Pie, API 28) or higher.

### Building and Running
1.  Clone the repository: `git clone https://github.com/htechno/WiProber.git`
2.  Open the project in Android Studio.
3.  Let Gradle sync and download all dependencies.
4.  Build and run the application on your device.

## How to Use

### Stop-and-Go Mode (Default)
1.  Tap **"Select Map"** to load a floor plan.
2.  Tap on the map to scan. A **Red Dot** will appear.
3.  Wait for the scan to finish. Repeat.

### Continuous Mode
1.  **Disable Throttling:** Ensure "Wi-Fi scan throttling" is OFF in Developer Options.
2.  Tap the **Mode Switch** button (bottom-left, changes from "Point" to "Path" icon).
3.  Tap on the map to **Start** the track.
4.  Start walking. The app scans in the background.
5.  Tap again to mark a **Turn** (waypoint). A Magenta line follows your path.
6.  Tap **"STOP TRACKING"** to finish the path. The line turns Green.

### Export
Tap **"Save Report"** to generate and save the `.esx` project file.

## Post-Processing in Ekahau Pro

After importing the `.esx` file into Ekahau Pro, you will need to perform a few manual steps to finalize your project:

1.  **Set "My Networks":** Add Stars in the Network menu.
2.  **Group Radios:** To group multiple radios (BSSIDs) under a single physical AP, navigate to **Project -> Measurement Grouping Options** and configure the desired grouping logic.
3.  **Auto-Place Access Points:** To estimate the location of the access points on the map, use the **Auto-Placing** feature.

## Contributing

**WiProber** is a community-driven project, and your contributions are welcome!

*   **Have an idea or a bug fix?** The best way to get it into the project is to implement it yourself and submit a PR.
*   **Want to request a feature?** You are welcome to open an issue to discuss it.

I'm a wireless engineer, just like many of you, not a full-time developer. Let's build this tool together!

## License
This project is licensed under the **MIT License** - see the [LICENSE](https://github.com/htechno/WiProber/blob/main/LICENSE) file for details.

---
*Disclaimer: Ekahau Pro™ is a trademark of Ekahau. This project is not affiliated with, endorsed by, or sponsored by Ekahau.*

---
## Third-Party Libraries and Assets

This project uses some third-party libraries and assets. Here are their licenses:

*   **Material Design Icons:** The icons used in this app are provided by Google ([Apache License Version 2.0](https://www.apache.org/licenses/LICENSE-2.0)).
*   **PhotoView by Chris Banes:** Licensed under the [Apache License Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
*   **Gson by Google:** Licensed under the [Apache License Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
*   **Coil (Coil-kt):** Licensed under the [Apache License Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
*   **AndroidX Libraries:** Licensed under the [Apache License Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).

This project itself is licensed under the MIT license.