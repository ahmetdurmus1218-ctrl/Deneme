package io.github.ahmetdurmus1218.jarvis.core.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafetyTest {
    @Test
    fun emergencyNumbersAlwaysReachTheDialer() {
        assertEquals(CallSafety.Kind.EMERGENCY, CallSafety.kind("112", fromContacts = false))
        assertEquals(CallSafety.Kind.EMERGENCY, CallSafety.kind("155", fromContacts = true))
    }

    @Test
    fun paidAndShortServiceNumbersAreRefused() {
        assertEquals(CallSafety.Kind.PAID, CallSafety.kind("09001234567", fromContacts = false))
        assertEquals(CallSafety.Kind.PAID, CallSafety.kind("+909001234567", fromContacts = false))
        assertEquals(CallSafety.Kind.PAID, CallSafety.kind("4440", fromContacts = true))
        assertEquals(CallSafety.Kind.PAID, CallSafety.kind("12345", fromContacts = false))
    }

    @Test
    fun contactsAreCalledAndUnknownNumbersOnlyDialled() {
        assertEquals(CallSafety.Kind.CONTACT, CallSafety.kind("05321234567", fromContacts = true))
        assertEquals(CallSafety.Kind.UNKNOWN_NUMBER, CallSafety.kind("05321234567", fromContacts = false))
        assertEquals(CallSafety.Kind.UNKNOWN_NUMBER, CallSafety.kind("+905321234567", fromContacts = false))
        assertEquals(CallSafety.Kind.CONTACT, CallSafety.kind("4441234", fromContacts = true))
    }

    @Test
    fun onlyHarmlessActionsRunWhileLocked() {
        assertTrue(LockPolicy.allowedWhileLocked(DeviceAction.Media(MediaKey.PAUSE)))
        assertTrue(LockPolicy.allowedWhileLocked(DeviceAction.Torch(true)))
        assertFalse(LockPolicy.allowedWhileLocked(DeviceAction.Call("Annem")))
        assertFalse(LockPolicy.allowedWhileLocked(DeviceAction.Message("Annem", "geliyorum")))
        assertFalse(LockPolicy.allowedWhileLocked(DeviceAction.Navigate("ev")))
    }

    @Test
    fun handsDriveOnlyChosenAppsAndNeverBanksOrMessengers() {
        for (p in HandApps.DEFAULT) assertFalse(HandApps.neverAllowed(p), p)
        assertTrue(HandApps.allowed("com.google.android.youtube", HandApps.DEFAULT))
        assertFalse(HandApps.allowed("com.example.game", HandApps.DEFAULT))
        assertFalse(HandApps.allowed(null, HandApps.DEFAULT))
        for ((p, label) in listOf(
            "com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp Business", "com.ziraat.ziraatmobil" to "Ziraat Mobil",
            "com.garanti.cepsubesi" to "Garanti BBVA", "com.akbank.android.apps.akbank_direkt" to "Akbank", "com.pozitron.iscep" to "İşCep",
            "com.ykb.android" to "Yapı Kredi Mobil", "com.mobillium.papara" to "Papara", "com.google.android.apps.walletnfcrel" to "Google Wallet",
            "com.android.settings" to "Ayarlar", "com.google.android.apps.messaging" to "Mesajlar", "com.google.android.gm" to "Gmail",
            "com.binance.dev" to "Binance", "com.x8bit.bitwarden" to "Bitwarden", "com.google.android.dialer" to "Telefon",
        )) {
            assertTrue(HandApps.neverAllowed(p, label), "$p $label")
            // Even if somehow chosen, by the package name alone.
            assertFalse(HandApps.allowed(p, setOf(p)), p)
        }
    }
}
