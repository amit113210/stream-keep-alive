import re

with open("app/src/main/java/com/keepalive/yesplus/MainActivity.kt", "r") as f:
    content = f.read()

# Replace property declarations
content = re.sub(r"private lateinit var hotspotSettingsButton: Button\n.*?private lateinit var showQrCodeButton: Button",
"""private lateinit var toggleHotspotButton: Button
    private lateinit var showQrCodeButton: Button""", content, flags=re.DOTALL)

# Add title bindings
content = content.replace("versionText = findViewById(R.id.versionText)",
"versionText = findViewById(R.id.versionText)\n        toggleHotspotButton = findViewById(R.id.toggleHotspotButton)")

# Remove hotspotSettingsButton usages
content = content.replace("hotspotSettingsButton = findViewById(R.id.hotspotSettingsButton)\n", "")
content = content.replace("hotspotSettingsButton.setOnClickListener { openHotspotSettings() }\n", "")

# Update runSpeedTest logic
content = re.sub(r"private fun onRunSpeedTestClicked\(\) \{.*?\n    \}",
"""private fun onRunSpeedTestClicked() {
        val now = System.currentTimeMillis()
        val prefs = getSharedPreferences(SPEED_PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(SPEED_LAST_RESULT, getString(R.string.speed_test_placeholder_result))
            .putLong(SPEED_LAST_AT, now)
            .apply()

        updateServiceStatus()
        startActivity(Intent(this, SpeedTestActivity::class.java))
    }""", content, flags=re.DOTALL)


# Update toggle button logic in onCreate
content = content.replace("runSpeedTestButton.setOnClickListener { onRunSpeedTestClicked() }",
"""runSpeedTestButton.setOnClickListener { onRunSpeedTestClicked() }
        
        toggleHotspotButton.setOnClickListener {
            if (HotspotManager.isLocalOnlyHotspotActive()) {
                HotspotManager.stopLocalOnlyHotspot()
                Toast.makeText(this, getString(R.string.button_stop_hotspot), Toast.LENGTH_SHORT).show()
            } else {
                HotspotManager.startLocalOnlyHotspot(this)
                Toast.makeText(this, getString(R.string.button_start_hotspot), Toast.LENGTH_SHORT).show()
            }
            uiHandler.postDelayed({ updateServiceStatus() }, 1000)
        }
        
        // Listen to HotspotManager callbacks
        HotspotManager.setCallback(object : HotspotManager.HotspotCallback {
            override fun onStarted() {
                uiHandler.post { updateServiceStatus() }
            }
            override fun onStopped() {
                uiHandler.post { updateServiceStatus() }
            }
            override fun onFailed(reason: Int) {
                uiHandler.post { 
                    updateServiceStatus()
                    Toast.makeText(this@MainActivity, "Hotspot failed to start (Code: $reason)", Toast.LENGTH_LONG).show()
                }
            }
        })""")

# Update updateServiceStatus to show the right text for toggle button
content = re.sub(r"if \(hotspot\.state == getString\(R\.string\.hotspot_state_active\).*?\} else \{.*?\}",
"""if (HotspotManager.isLocalOnlyHotspotActive()) {
            val hSsid = HotspotManager.getSsid()
            val hPass = HotspotManager.getPassword()
            toggleHotspotButton.text = getString(R.string.button_stop_hotspot)
            
            if (hSsid != "-") {
                showQrCodeButton.visibility = View.VISIBLE
                showQrCodeButton.setOnClickListener {
                    showQrCodeDialog(hSsid, hPass)
                }
            } else {
                showQrCodeButton.visibility = View.GONE
            }
        } else {
            toggleHotspotButton.text = getString(R.string.button_start_hotspot)
            
            // Fallback for system hotspot (not managed by our app)
            if (hotspot.state == getString(R.string.hotspot_state_active) && hotspot.ssid != "-" && hotspot.ssid.isNotBlank()) {
                showQrCodeButton.visibility = View.VISIBLE
                showQrCodeButton.setOnClickListener {
                    showQrCodeDialog(hotspot.ssid, hotspot.password)
                }
            } else {
                showQrCodeButton.visibility = View.GONE
                showQrCodeButton.setOnClickListener(null)
            }
        }""", content, flags=re.DOTALL)


# Update readHotspotStatus to combine with HotspotManager
content = re.sub(r"private fun readHotspotStatus\(\): HotspotStatus \{.*?\} catch \(_: Exception\) \{.*?\}",
"""private fun readHotspotStatus(): HotspotStatus {
        if (HotspotManager.isLocalOnlyHotspotActive()) {
            return HotspotStatus(
                state = getString(R.string.hotspot_state_active),
                ssid = HotspotManager.getSsid(),
                note = getString(R.string.hotspot_app_active),
                password = HotspotManager.getPassword()
            )
        }
    
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val apMethod = wm?.javaClass?.getDeclaredMethod("isWifiApEnabled")
            apMethod?.isAccessible = true
            val enabled = apMethod?.invoke(wm) as? Boolean ?: false
            
            var apSsid = "-"
            var apPass = ""
            if (enabled) {
                try {
                    val apConfigMethod = wm?.javaClass?.getDeclaredMethod("getWifiApConfiguration")
                    apConfigMethod?.isAccessible = true
                    val config = apConfigMethod?.invoke(wm) as? WifiConfiguration
                    if (config != null) {
                        apSsid = config.SSID ?: "-"
                        apPass = config.preSharedKey ?: ""
                    }
                } catch (_: Exception) {}
                
                if (apSsid == "-" && Build.VERSION.SDK_INT >= 30) {
                     try {
                         val softApMethod = wm?.javaClass?.getDeclaredMethod("getSoftApConfiguration")
                         softApMethod?.isAccessible = true
                         val softApConfig = softApMethod?.invoke(wm)
                         apSsid = softApConfig?.javaClass?.getMethod("getSsid")?.invoke(softApConfig) as? String ?: "-"
                         apPass = softApConfig?.javaClass?.getMethod("getPassphrase")?.invoke(softApConfig) as? String ?: ""
                     } catch (_: Exception) {}
                }
                
                HotspotStatus(state = getString(R.string.hotspot_state_active), ssid = apSsid, note = getString(R.string.hotspot_note_active), password = apPass)
            } else {
                HotspotStatus(state = getString(R.string.hotspot_state_inactive), ssid = "-", note = getString(R.string.hotspot_note_inactive))
            }
        } catch (_: Exception) {
            HotspotStatus(state = getString(R.string.hotspot_state_unavailable), ssid = "-", note = getString(R.string.hotspot_note_unavailable))
        }
    }""", content, flags=re.DOTALL)

with open("app/src/main/java/com/keepalive/yesplus/MainActivity.kt", "w") as f:
    f.write(content)
