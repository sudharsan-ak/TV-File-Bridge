package com.tvfilebridge.app.remote

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

private const val TAG = "SonyIrccWaker"

// Standard Sony Bravia IRCC-IP code for the Power button. Sony's remote-code
// table is fixed across Bravia generations - this same base64 blob works for
// power on every IRCC-IP capable model, unlike per-app commands which vary.
private const val IRCC_POWER_CODE = "AAAAAQAAAAEAAAAVAw=="

private const val SOAP_BODY_TEMPLATE = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:X_SendIRCC xmlns:u="urn:schemas-sony-com:service:IRCC:1">
<IRCCCode>%s</IRCCCode>
</u:X_SendIRCC>
</s:Body>
</s:Envelope>"""

private const val REGISTER_BODY = """{"method":"actRegister","params":[{"clientid":"tvfilebridge:1","nickname":"TV File Bridge"},[{"clientid":"tvfilebridge:1","value":"yes","nickname":"TV File Bridge","function":"WOL"}]],"id":1,"version":"1.0"}"""

sealed class RegistrationResult {
    data object PinRequired : RegistrationResult()
    data object Success : RegistrationResult()
}

/**
 * Wakes a Sony Bravia TV via its IRCC-IP endpoint (/sony/IRCC), the protocol
 * behind the "Remote start: On (Powered on by apps)" setting. Unlike plain
 * Wake-on-LAN - which this TV's hardware/router combo doesn't honor (tested
 * directly, packet delivery confirmed, TV never woke) - this hits a SOAP
 * service Sony keeps listening even while the TV looks fully off.
 *
 * IRCC-IP requires one-time client registration first, even under
 * Authentication: Normal - the TV shows an on-screen PIN, and only after
 * submitting it back does the TV issue a session cookie that authorizes
 * future requests (including waking from standby, when the TV can't show a
 * PIN prompt at all). That registration has to happen while the TV is awake.
 */
class SonyIrccWaker(private val authStore: SonyAuthStore) {

    /** Step 1: call with no PIN. Returns PinRequired if the TV needs pairing (check its screen), or Success if already registered. */
    suspend fun startRegistration(host: String): Result<RegistrationResult> = register(host, pin = null)

    /** Step 2: call with the PIN shown on the TV screen to complete pairing and store the session cookie. */
    suspend fun submitPin(host: String, pin: String): Result<RegistrationResult> = register(host, pin = pin)

    private suspend fun register(host: String, pin: String?): Result<RegistrationResult> {
        var newCookie: String? = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val url = URL("http://$host/sony/accessControl")
                val body = REGISTER_BODY.toByteArray(StandardCharsets.UTF_8)

                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 5000
                    readTimeout = 5000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    if (pin != null) {
                        // Sony expects HTTP Basic auth with an empty username and
                        // the on-screen PIN as the password for this one call.
                        val credentials = Base64.encodeToString(":$pin".toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
                        setRequestProperty("Authorization", "Basic $credentials")
                    }
                }

                connection.use {
                    OutputStreamWriter(it.outputStream, StandardCharsets.UTF_8).use { writer -> writer.write(String(body, StandardCharsets.UTF_8)) }
                    val responseCode = it.responseCode
                    Log.i(TAG, "register($host, hasPin=${pin != null}) -> HTTP $responseCode")
                    when (responseCode) {
                        200 -> {
                            newCookie = it.headerFields["Set-Cookie"]?.firstOrNull()?.substringBefore(";")
                            RegistrationResult.Success
                        }
                        401, 403 -> RegistrationResult.PinRequired
                        else -> throw IllegalStateException("TV responded with HTTP $responseCode")
                    }
                }
            }.onFailure { Log.e(TAG, "register($host) failed: ${it.message}", it) }
        }
        newCookie?.let { authStore.setCookie(host, it) }
        return result
    }

    suspend fun wake(host: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cookie = authStore.cookie(host).first()
            val url = URL("http://$host/sony/IRCC")
            val body = SOAP_BODY_TEMPLATE.format(IRCC_POWER_CODE).toByteArray(StandardCharsets.UTF_8)

            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "text/xml; charset=UTF-8")
                setRequestProperty("SOAPACTION", "\"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC\"")
                if (cookie != null) setRequestProperty("Cookie", cookie)
            }

            connection.use {
                OutputStreamWriter(it.outputStream, StandardCharsets.UTF_8).use { writer -> writer.write(String(body, StandardCharsets.UTF_8)) }
                val responseCode = it.responseCode
                Log.i(TAG, "IRCC power request to $host -> HTTP $responseCode")
                if (responseCode == 403) {
                    throw IllegalStateException("Not registered - pair with the TV first (HTTP 403)")
                }
                if (responseCode !in 200..299) {
                    throw IllegalStateException("TV responded with HTTP $responseCode")
                }
            }
        }.onFailure { Log.e(TAG, "wake($host) failed: ${it.message}", it) }
    }
}

private fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T {
    try {
        return block(this)
    } finally {
        disconnect()
    }
}
