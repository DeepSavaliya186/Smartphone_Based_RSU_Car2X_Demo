package com.example.car2xvehicle

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Location
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import android.provider.Settings

class MainActivity : AppCompatActivity() {

    // UI
    private lateinit var tvStatus: TextView
    private lateinit var tvLastData: TextView
    private lateinit var tvWarning: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    // Location
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var highAccRequest: LocationRequest
    private lateinit var balancedRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    private var lastLocation: Location? = null

    // Extra state for filtering
    private var lastGoodLocation: Location? = null
    private val MIN_ACCURACY = 25f         // meters: ignore worse than this
    private val MAX_JUMP_SPEED = 12.0      // m/s ~ 43 km/h to filter crazy jumps
    private val MAX_SPEED_DELTA = 40.0     // km/h sudden spike cap
    private val MAX_LOCATION_AGE_MS = 3000 // 3s: ignore old cached fixes

    // CAM beacon loop
    private val handler = Handler(Looper.getMainLooper())
    private var broadcasting = false

    // IDs & network
    private val vehicleId: String by lazy {
        val id = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        "CAR" + id.takeLast(4).uppercase()
    }
    private val camPort = 30001        // RSU listens here
    private val denmPort = 30002       // Vehicle listens here

    // Coroutine scope for networking
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var switchMode: SwitchCompat

    // Permissions
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val fine = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
            val coarse = perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (fine || coarse) {
                startLocationUpdates()
            } else {
                tvStatus.text = "Status: Location permission denied"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvLastData = findViewById(R.id.tvLastData)
        tvWarning = findViewById(R.id.tvWarning)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Updated high-accuracy request with shorter intervals (faster location updates)
        highAccRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            500L           // 500ms interval
        )
            .setMinUpdateIntervalMillis(300L)  // Minimum update interval of 300ms
            .setMinUpdateDistanceMeters(0f)
            .setMaxUpdateDelayMillis(0L)
            .build()

        // Balanced request for better indoor positioning (with 2s interval)
        balancedRequest = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            2000L          // 2s interval
        )
            .setMinUpdateIntervalMillis(1500L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val raw = result.lastLocation ?: return

                // Ignore stale cached locations (>3 seconds old)
                if (System.currentTimeMillis() - raw.time > MAX_LOCATION_AGE_MS) {
                    tvStatus.text = "Ignoring old GPS (${raw.accuracy} m)"
                    return
                }

                val filtered = filterLocation(raw)
                if (filtered != null) {
                    lastLocation = filtered

                    val speedKmh = filtered.speed * 3.6
                    tvStatus.text =
                        "GPS OK (acc=${filtered.accuracy} m), v=%.1f km/h".format(speedKmh)
                } else {
                    tvStatus.text = "Ignoring bad GPS (acc=${raw.accuracy} m)"
                }
            }
        }

        btnStart.setOnClickListener {
            if (!broadcasting) checkPermissionsAndStart()
        }

        btnStop.setOnClickListener {
            stopBroadcasting()
        }

        // Start DENM listener immediately (it will wait for packets)
        startDenmListener()
    }

    // -------- LOCATION FILTERING (MAIN FIX) --------

    private fun filterLocation(newLoc: Location): Location? {
        // 0) Basic sanity + accuracy
        if (newLoc.accuracy <= 0f || newLoc.accuracy > MIN_ACCURACY) {
            return null
        }

        val last = lastGoodLocation ?: run {
            // First good reading
            lastGoodLocation = newLoc
            return newLoc
        }

        // 1) Time difference
        val dtSec = (newLoc.time - last.time) / 1000.0
        if (dtSec <= 0.0) {
            return null
        }

        // 2) Distance between last and new point
        val results = FloatArray(1)
        Location.distanceBetween(
            last.latitude, last.longitude,
            newLoc.latitude, newLoc.longitude,
            results
        )
        val distanceMeters = results[0].toDouble()

        // Speed based on distance/time (not on raw sensor)
        val approxSpeedMps = distanceMeters / dtSec

        // If this implied speed is crazy high, it's a jump → ignore
        if (approxSpeedMps > MAX_JUMP_SPEED) {
            return null
        }

        // 3) Filter out speed spikes from sensor
        val newSpeedKmh = newLoc.speed * 3.6
        val lastSpeedKmh = last.speed * 3.6
        if (kotlin.math.abs(newSpeedKmh - lastSpeedKmh) > MAX_SPEED_DELTA) {
            return null
        }

        // Passed all checks → accept
        lastGoodLocation = newLoc
        return newLoc
    }

    // -------- PERMISSIONS & GPS START --------
    private fun checkPermissionsAndStart() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)

        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            startLocationUpdates()
        }
    }

    private fun startLocationUpdates() {
        tvStatus.text = "Status: Requesting GPS fix..."

        // One-shot fresh fix to avoid old cached location
        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            null
        ).addOnSuccessListener { loc ->
            val filtered = loc?.let { filterLocation(it) }
            if (filtered != null) {
                lastLocation = filtered
            }
        }

        fusedLocationClient.requestLocationUpdates(
            highAccRequest,
            locationCallback,
            Looper.getMainLooper()
        )
        fusedLocationClient.requestLocationUpdates(
            balancedRequest,
            locationCallback,
            Looper.getMainLooper()
        )

        startBroadcasting()
    }

    // -------- CAM BROADCAST LOOP --------
    private fun startBroadcasting() {
        broadcasting = true
        handler.post(beaconRunnable)
    }

    private fun stopBroadcasting() {
        broadcasting = false
        handler.removeCallbacks(beaconRunnable)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        tvStatus.text = "Status: Stopped"
    }

    private val beaconRunnable = object : Runnable {
        override fun run() {
            if (!broadcasting) return

            val loc = lastLocation
            if (loc != null) {
                sendCamBeacon(loc)
            } else {
                tvStatus.text = "Status: Waiting for stable GPS..."
            }

            handler.postDelayed(this, 500L) // send every 0.5s
        }
    }

    private fun sendCamBeacon(location: Location) {
        scope.launch {
            try {
                // Convert speed to km/h
                val speedKmh = location.speed.toDouble() * 3.6

                val localIp = getLocalIp() ?: "0.0.0.0"
                val broadcastIp = getBroadcastIp() ?: "255.255.255.255"

                val json = JSONObject().apply {
                    put("vehicle_id", vehicleId)
                    put("lat", location.latitude)
                    put("lon", location.longitude)
                    put("speed_kmh", speedKmh)
                    put("timestamp", System.currentTimeMillis())
                    put("ip", localIp)
                    put("denm_port", denmPort)
                }

                val data = json.toString().toByteArray()
                val address = InetAddress.getByName(broadcastIp)

                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    val packet = DatagramPacket(data, data.size, address, camPort)
                    socket.send(packet)
                }

                withContext(Dispatchers.Main) {
                    tvStatus.text = "Status: Broadcasting CAM beacons..."
                    tvLastData.text =
                        "Last CAM:\n$vehicleId | Lat=${location.latitude}, Lon=${location.longitude}, Speed=%.1f km/h"
                            .format(speedKmh)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Error sending CAM: ${e.message}"
                }
            }
        }
    }

    // -------- DENM RECEIVER (RSU → Vehicle) --------
    private fun startDenmListener() {
        scope.launch {
            try {
                val socket = DatagramSocket(denmPort)
                val buffer = ByteArray(4096)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isActive) {
                    socket.receive(packet)
                    val msg = String(packet.data, 0, packet.length)

                    try {
                        val json = JSONObject(msg)
                        when (json.optString("type")) {

                            "DENM" -> {
                                val event = json.optString("event")
                                val cause = json.optString("cause")
                                val severity = json.optString("severity")
                                val dist = json.optDouble("distance_m", -1.0)
                                val speed = json.optDouble("speed_kmh", -1.0)

                                val display = buildString {
                                    append("⚠ DENM Warning ⚠\n")
                                    append("Event: $event\n")
                                    append("Cause: $cause\n")
                                    append("Severity: $severity\n")
                                    if (dist >= 0) append("Distance: %.2f m\n".format(dist))
                                    if (speed >= 0) append("Speed: %.1f km/h".format(speed))
                                }

                                withContext(Dispatchers.Main) {
                                    tvWarning.text = display
                                }
                            }

                            "V2V" -> {
                                val vehicles = json.optJSONArray("vehicles")
                                val myLoc = lastLocation

                                if (vehicles != null) {
                                    val nearbyText = buildString {
                                        append("🚗 V2V Nearby Vehicles\n")
                                        append("Total: ${vehicles.length()}\n")

                                        // show only 3 nearest (optional)
                                        if (myLoc != null) {
                                            val list = mutableListOf<Pair<Double, String>>()
                                            for (i in 0 until vehicles.length()) {
                                                val v = vehicles.getJSONObject(i)
                                                val vid = v.optString("vehicle_id")
                                                val lat = v.optDouble("lat")
                                                val lon = v.optDouble("lon")
                                                val spd = v.optDouble("speed_kmh")

                                                val res = FloatArray(1)
                                                Location.distanceBetween(
                                                    myLoc.latitude, myLoc.longitude,
                                                    lat, lon, res
                                                )
                                                val d = res[0].toDouble()

                                                list.add(d to "$vid | %.1f m | %.1f km/h".format(d, spd))
                                            }

                                            list.sortedBy { it.first }
                                                .take(3)
                                                .forEach { append(it.second).append("\n") }
                                        } else {
                                            append("(Waiting for GPS to compute nearest...)")
                                        }
                                    }

                                    withContext(Dispatchers.Main) {
                                        // If DENM already showing, append below, else replace:
                                        tvWarning.text = nearbyText
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // ignore bad packets
                    }
                }

                socket.close()
            } catch (_: Exception) {
                // ignore
            }
        }
    }


    // -------- IP & BROADCAST HELPERS --------
    private fun getLocalIp(): String? {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val ip = wifiManager.connectionInfo.ipAddress
        if (ip == 0) return null
        return String.format(
            "%d.%d.%d.%d",
            ip and 0xff,
            ip shr 8 and 0xff,
            ip shr 16 and 0xff,
            ip shr 24 and 0xff
        )
    }

    private fun getBroadcastIp(): String? {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val ip = wifiManager.connectionInfo.ipAddress
        if (ip == 0) return null

        // X.Y.Z.255
        return String.format(
            "%d.%d.%d.%d",
            ip and 0xff,
            ip shr 8 and 0xff,
            ip shr 16 and 0xff,
            255
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBroadcasting()
        scope.cancel()
    }
}