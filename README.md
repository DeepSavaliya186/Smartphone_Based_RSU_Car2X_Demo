# 🚗 Car2X Communication System

A Car-to-Everything (Car2X) communication system implemented using Android applications.

This project demonstrates real-time wireless communication between a Vehicle and a Road Side Unit (RSU) using UDP-based socket programming.

---

## 📌 Project Modules

### 📡 Car2XRSU (Road Side Unit App)
- Receives vehicle data via UDP
- Displays vehicle location on map (OSMDroid)
- Calculates distance between RSU and vehicle
- Generates warning alerts
- Real-time monitoring interface

### 🚘 Car2XVehicle (Vehicle App)
- Sends GPS location data
- Transmits vehicle speed
- Communicates with RSU over network
- Runs on Android device/emulator

---

## 🛠 Technologies Used

- Kotlin
- Android SDK
- UDP Socket Programming
- OSMDroid (Map Integration)
- Google Location Services
- Gradle (Kotlin DSL)

---

## 📂 Project Structure
Car2XRSU/
│
├── Car2XRSU/ # Road Side Unit Application
└── Car2XVehicle/ # Vehicle Application


---

## 🚀 How to Run

1. Clone the repository:

git clone git@github.com
:DeepSavaliya186/Car2XRSU.git

2. Open the project in **Android Studio**

3. Build & Run:
   - Install **Car2XRSU** on one device/emulator
   - Install **Car2XVehicle** on another device/emulator

4. Ensure both devices are connected to the same network.

---

## 🎯 Key Features

- Real-time vehicle tracking
- Wireless communication using UDP
- Map-based visualization
- Distance-based warning system
- Modular Android architecture

---

## 🎓 Academic Context

This project was developed as part of:

**M.Sc. Automotive Software Engineering**  
Technische Hochschule Deggendorf  
Germany

---

## 👨‍💻 Author

**Deep Savaliya**  
GitHub: [DeepSavaliya186](https://github.com/DeepSavaliya186)

---

## 📄 License

This project is developed for academic and research purposes.

