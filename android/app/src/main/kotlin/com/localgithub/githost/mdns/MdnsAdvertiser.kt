package com.localgithub.githost.mdns

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

private const val TAG = "MdnsAdvertiser"
private const val SERVICE_TYPE = "_localgithub._tcp"

/**
 * Advertises the git-host SSH service via mDNS using Android's NsdManager.
 * Clients can discover the host as `<devicename>.local` on the LAN without
 * needing to know the IP address.
 */
class MdnsAdvertiser(context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    // Debug builds run next to the release app, so they advertise under another name.
    private val deviceName = android.os.Build.MODEL.replace(" ", "-") +
        if (com.localgithub.githost.BuildConfig.DEBUG) "-dev" else ""

    private var registrationListener: NsdManager.RegistrationListener? = null

    /** Registers the service on [port]. Registration is asynchronous; failures are only logged. */
    fun advertise(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = SERVICE_TYPE
            setPort(port)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "mDNS registration failed: $errorCode")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "mDNS unregistration failed: $errorCode")
            }

            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "mDNS registered: ${info.serviceName}.$SERVICE_TYPE port=${info.port}")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "mDNS unregistered: ${info.serviceName}")
            }
        }

        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        registrationListener = listener
    }

    /** Withdraws the advertisement, if one was registered. */
    fun stop() {
        registrationListener?.let {
            runCatching { nsdManager.unregisterService(it) }
            registrationListener = null
        }
    }
}
