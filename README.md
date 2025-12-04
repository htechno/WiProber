# WiProber

**WiProber** is an open-source Android application for conducting Wi-Fi site surveys. It allows users to load a floor plan, perform Wi-Fi scans at specific points on the map, and export the collected data into the `.esx` format, compatible with Ekahau Pro™.

This project was born out of the need for a simple, mobile-first tool for network engineers and enthusiasts to perform quick on-site surveys without expensive, proprietary hardware.

![Screenshot of WiProber App](URL_К_СКРИНШОТУ_ВАШЕГО_ПРИЛОЖЕНИЯ)

## Features

*   **Interactive Floor Plan:** Load any image as a floor plan, with support for zoom and pan.
*   **Point-and-Scan:** Simply tap on the map to initiate a detailed Wi-Fi scan at that location.
*   **Advanced Data Collection:** Gathers comprehensive data for each network, including SSID, BSSID, RSSI, frequency, security standard (802.11ax/ac/n...), and raw Information Elements (IEs).
*   **Forced Disconnect Mode:** Automatically disconnects from the current Wi-Fi network before each scan to ensure a more thorough and unbiased result.
*   **Note-Taking:** Add text and photo annotations directly onto the map to document access point locations, obstacles, or other points of interest.
*   **Scale Calibration:** Set the physical scale of the map for accurate-to-reality measurements.
*   **Full `.esx` Export:** Generates a complete `.esx` project file, including all necessary JSON files, binary track data, and attached images, ready for import into Ekahau Pro™.

## Limitations & Known Issues

This is a non-commercial, open-source project with bazı limitations you should be aware of:

*   **Wi-Fi Scan Throttling:** Due to Android OS restrictions (Android 9+), the app is limited to **4 scans per 2-minute period**. If you reach this limit, the app will inform you to wait. This is a system-level limitation and cannot be bypassed.
*   **No "My Networks" Detection:** The app currently does not automatically identify or flag your own networks ("My Networks"). This must be done manually within Ekahau Pro after import.
*   **No AP Merging:** WiProber creates a new, separate access point (`accessPoints.json`) for every unique BSSID found. It does not attempt to group multiple radios (e.g., 2.4-GHz и 5-GHz radios) under a single physical access point device. This grouping should be performed manually in Ekahau.
*   **No Auto-Placing:** The app does not calculate or predict the physical location of access points on the map.
*   **Legacy Android Data:** On devices running Android 10 (API 29) or older, the app is unable to collect **raw Information Elements (IEs)** and detailed **Wi-Fi standard information (802.11n/ac/ax)** due to OS limitations. These fields will be empty in the exported data.

## Getting Started

### Prerequisites
*   Android Studio (latest version recommended)
*   An Android device with Android 9 (Pie, API 28) or higher.

### Building and Running
1.  Clone the repository: `git clone https://github.com/USERNAME/WiProber.git`
2.  Open the project in Android Studio.
3.  Let Gradle sync and download all dependencies.
4.  Build and run the application on your device.

## How to Use
1.  Launch the app and tap **"Select Map"** to load a floor plan image.
2.  (Optional) Tap the **Scale** icon to calibrate the map's scale.
3.  Tap on the map to perform a Wi-Fi scan at that location. A red dot will appear.
4.  (Optional) Tap the **Add Note** icon, then tap on the map to add a text or photo note. A blue dot will appear.
5.  Repeat scans and add notes as needed.
6.  Tap **"Save Report"** to generate and save the `.esx` project file.

## Post-Processing in Ekahau Pro

After importing the `.esx` file into Ekahau Pro, you will need to perform a few manual steps to finalize your project:

1.  **Set "My Networks":** Add Stars in the Network menu.
2.  **Group Radios:** To group multiple radios (BSSIDs) under a single physical AP, navigate to **Project -> Measurement Grouping Options** and configure the desired grouping logic.
3.  **Auto-Place Access Points:** To estimate the location of the access points on the map, use the **Auto-Placing** feature.

## Contributing

**WiProber** is a community-driven project, and your contributions are welcome and essential for its growth!

As I maintain this project in my spare time, my ability to resolve issues single-handedly is limited. Therefore, I have a strong preference for contributions in the form of **Pull Requests** over simple bug reports.

*   **Have an idea or a bug fix?** The best way to get it into the project is to implement it yourself and submit a PR.
*   **Want to request a feature?** You are welcome to open an issue to discuss it, but creating a PR is the surest way to see it happen.

I'm a wireless engineer, just like many of you, not a full-time developer. Let's build this tool together!

## License
This project is licensed under the **MIT License** - see the [LICENSE](https://github.com/htechno/WiProber/blob/main/LICENSE) file for details.

---
*Disclaimer: Ekahau Pro™ is a trademark of Ekahau. This project is not affiliated with, endorsed by, or sponsored by Ekahau.*

---
## Third-Party Libraries and Assets

This project uses some third-party libraries and assets. Here are their licenses:

*   **Material Design Icons:** The icons used in this app (e.g., `ic_add_note`, `ic_scale`, `ic_info`, etc.) are provided by Google and are licensed under the [Apache License Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
*   **PhotoView by Chris Banes:** Licensed under the [Apache License Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
*   **Gson by Google:** Licensed under the [Apache License Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
*   **Coil (Coil-kt):** Licensed under the [Apache License Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
*   **AndroidX Libraries (AppCompat, Core, Lifecycle, etc.):** Licensed under the [Apache License Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).

This project itself is licensed under the MIT license.
