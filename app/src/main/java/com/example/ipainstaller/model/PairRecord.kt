package com.example.ipainstaller.model

data class PairRecord(
    val hostId: String,
    val systemBuid: String,
    val hostCertificate: ByteArray,
    val hostPrivateKey: ByteArray,
    val deviceCertificate: ByteArray,
    val rootCertificate: ByteArray,
    val rootPrivateKey: ByteArray,
    val escrowBag: ByteArray? = null,
    val wifiMacAddress: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairRecord) return false
        return hostId == other.hostId && systemBuid == other.systemBuid
    }

    override fun hashCode(): Int = hostId.hashCode() * 31 + systemBuid.hashCode()
}
