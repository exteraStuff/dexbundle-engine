package io.github.exterastuff.dexbundle.engine.ui

import io.github.exterastuff.dexbundle.engine.impl.SignerInfo

/** Состояние подписи плагина. */
enum class PluginSignatureState {
    /** Плагин не подписан. */
    MISSING,

    /** Плагин подписан, но сертификат не доверенный. */
    UNTRUSTED,

    /** Плагин подписан доверенным сертификатом. */
    TRUSTED,

    /** Сертификат истёк, и TSA не подтверждает, что плагин подписали до истечения. */
    EXPIRED,

    /** Сертификат отозван. */
    REVOKED,

    /** Подпись отличается от подписи установленной версии плагина. */
    MISMATCH;

    val blocksInstall: Boolean
        get() = this == MISMATCH || this == REVOKED

    companion object {
        /**
         * @param signers подписи версии плагина, которую нужно установить
         * @param installedSigners подписи уже установленной версии плагина
         */
        fun of(
            signers: Map<String, SignerInfo>?,
            installedSigners: Map<String, SignerInfo>?,
        ): PluginSignatureState {
            val installedFingerprints = installedSigners?.keys.orEmpty()
            val fingerprints = signers?.keys.orEmpty()

            if (!fingerprints.containsAll(installedFingerprints)) return MISMATCH

            if (signers.isNullOrEmpty()) return MISSING

            if (signers.values.any(SignerInfo::isRevoked)) return REVOKED

            if (signers.values.any { !it.isValid }) return EXPIRED

            if (signers.values.any(SignerInfo::isTrusted)) return TRUSTED

            return UNTRUSTED
        }
    }
}
