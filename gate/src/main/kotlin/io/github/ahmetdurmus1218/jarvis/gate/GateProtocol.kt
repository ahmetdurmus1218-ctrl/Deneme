package io.github.ahmetdurmus1218.jarvis.gate

/**
 * Messenger protocol between Kale (this app) and Kapı (the gate APK).
 * Keep in sync with androidApp/src/main/kotlin/.../GateProtocol.kt.
 */
object GateProtocol {
    const val KALE_PACKAGE = "io.github.ahmetdurmus1218.jarvis"
    const val GATE_PACKAGE = "io.github.ahmetdurmus1218.jarvis.gate"
    const val GATE_SERVICE = "io.github.ahmetdurmus1218.jarvis.gate.GateService"

    const val MSG_PING = 1
    const val MSG_PONG = 2
    const val MSG_DOWNLOAD = 3
    const val MSG_PROGRESS = 4
    const val MSG_DONE = 5
    const val MSG_ERROR = 6
    const val MSG_CANCEL = 7
    /** One approved Wikipedia search (since 1.1): KEY_URL in, MSG_QUERY_DONE with KEY_TEXT out. */
    const val MSG_QUERY = 8
    const val MSG_QUERY_DONE = 9

    const val KEY_ITEM_ID = "itemId"
    const val KEY_URL = "url"
    const val KEY_SHA256 = "sha256"
    const val KEY_SIZE = "size"
    const val KEY_FILE_NAME = "fileName"
    /** Writable ParcelFileDescriptor of the destination file in Kale's private storage. */
    const val KEY_FD = "fd"
    const val KEY_OFFSET = "offset"
    const val KEY_BYTES = "bytes"
    const val KEY_TOTAL = "total"
    const val KEY_HOST = "host"
    const val KEY_MESSAGE = "message"
    const val KEY_VERSION = "version"
    const val KEY_TEXT = "text"
}
