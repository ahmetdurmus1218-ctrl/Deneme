package io.github.ahmetdurmus1218.jarvis.gate

import okhttp3.CertificatePinner

/**
 * Certificate pins (SHA-256 of SubjectPublicKeyInfo) for every allowed host, captured from the live
 * chains by tools/probe.py on 2026-11-02 and cross-checked against the system CA store.
 * We pin the CA keys (roots + current intermediates), not leaf certificates, so routine leaf
 * renewals keep working. If a host changes CA, downloads fail closed until Kapı is updated.
 */
object Pins {
    private const val AMAZON_ROOT_CA_1 = "sha256/++MBgDH5WGvL9Bcn5Be30cRcL0f5O+NyoXuWtQdX1aI="
    private const val AMAZON_ROOT_CA_2 = "sha256/f0KW/FtqTjs108NpYj42SrGvOB2PpxIVM8nWxjPqJGE="
    private const val AMAZON_ROOT_CA_3 = "sha256/NqvDJlas/GRcYbcWE8S/IceH9cq77kg0jVhZeAPXq8k="
    private const val AMAZON_ROOT_CA_4 = "sha256/9+ze1cZgR9KO1kZrVDxA4HQ6voHRCSVNz4RdTCx4U8U="
    private const val AMAZON_RSA_2048_M01 = "sha256/DxH4tt40L+eduF6szpY6TONlxhZhBd+pJ9wbHlQ2fuw="

    private const val ISRG_ROOT_X1 = "sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M="
    private const val ISRG_ROOT_X2 = "sha256/diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI="
    private const val ISRG_ROOT_YR = "sha256/fk6IOKit1ild5647BH06ujSIq5XbCgqlbYl6ANhhi88="
    private const val LE_YR1 = "sha256/LoMHBotttiDko50Gi13uXW71eIy7LAttI+rYT8wXF4w="
    private const val LE_YR2 = "sha256/nWN7PSep5XDQdge5zK24CnCRXHr3KvzhKEGxsdqCX9E="

    private const val SECTIGO_ROOT_E46 = "sha256/sLVjNUaFYfW7n6EtgBeEpjOlcnBdNPMrZDRF36iwBdE="
    private const val SECTIGO_ROOT_R46 = "sha256/Douxi77vs4G+Ib/BogbTFymEYq0QSFXwSgVCaZcI09Q="
    private const val SECTIGO_DV_E36 = "sha256/ZSagvDzjltLkewXEBuDxIzpW/dpVw1Juvvmd0hhkzdY="
    private const val USERTRUST_ECC = "sha256/ICGRfpgmOUXIWcQ/HXPLQTkFPEFPoDyjvH7ohhQpjzs="
    private const val USERTRUST_RSA = "sha256/x4QzPSC810K5/cMjb05Qm4k3Bw5zBn4lTdO/nEW/Td4="

    // Google Trust Services roots (storage.googleapis.com), from the JDK trust store on 2026-11-03.
    private const val GTS_ROOT_R1 = "sha256/hxqRlPTu1bMS/0DITB1SSu0vd4u/8l8TjPgfaAp63Gc="
    private const val GTS_ROOT_R3 = "sha256/QXnt2YHvdHR3tJYmQIr0Paosp6t/nggsEGD4QJZ3Q0g="
    private const val GTS_ROOT_R4 = "sha256/mEflZT5enoR1FuXLgYYGqnVEoZvmf9c2bVBpiOjYQ0c="

    private val GOOGLE = arrayOf(GTS_ROOT_R1, GTS_ROOT_R3, GTS_ROOT_R4)
    private val AMAZON = arrayOf(AMAZON_ROOT_CA_1, AMAZON_ROOT_CA_2, AMAZON_ROOT_CA_3, AMAZON_ROOT_CA_4, AMAZON_RSA_2048_M01)
    private val LETS_ENCRYPT = arrayOf(ISRG_ROOT_X1, ISRG_ROOT_X2, ISRG_ROOT_YR, LE_YR1, LE_YR2)
    private val SECTIGO = arrayOf(SECTIGO_ROOT_E46, SECTIGO_ROOT_R46, SECTIGO_DV_E36, USERTRUST_ECC, USERTRUST_RSA)

    val pinner: CertificatePinner = CertificatePinner.Builder()
        .add("huggingface.co", *AMAZON)
        .add("**.huggingface.co", *AMAZON)
        .add("hf.co", *AMAZON)
        .add("**.hf.co", *AMAZON)
        .add("download.kiwix.org", *LETS_ENCRYPT)
        .add("**.download.kiwix.org", *LETS_ENCRYPT)
        .add("github.com", *SECTIGO)
        .add("objects.githubusercontent.com", *LETS_ENCRYPT)
        .add("release-assets.githubusercontent.com", *LETS_ENCRYPT)
        .add("storage.googleapis.com", *GOOGLE)
        .build()
}
