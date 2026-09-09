package io.github.exterastuff.dexbundle.engine.ui

import io.github.exterastuff.dexbundle.engine.impl.SignerInfo

/** Plugin signature state. */
enum class PluginSignatureState {
    /** Plugin isn't signed. */
    MISSING,

    /** Plugin has signature, but certificate isn't trusted. */
    UNTRUSTED,

    /** Plugin signed with trusted certificate. */
    TRUSTED,

    /**
     * Certificate was expired and TSA can't guarantee
     * what plugin was signed before expiration.
     * */
    EXPIRED,

    /** Signature differences from signature of installed plugin version. */
    MISMATCH;

    val blocksInstall: Boolean get() = this == MISMATCH

    companion object {
        /**
         * @param signers signatures of plugin version that needs to be installed
         * @param installedSigners signatures of already installed plugin version
         */
        fun of(
            signers: Map<String, SignerInfo>?,
            installedSigners: Map<String, SignerInfo>?,
        ): PluginSignatureState {
            val installedFingerprints = installedSigners?.keys.orEmpty()
            val fingerprints = signers?.keys.orEmpty()

            if (!fingerprints.containsAll(installedFingerprints))
                return MISMATCH

            if (signers.isNullOrEmpty())
                return MISSING

            if (signers.values.any { !it.isValid })
                return EXPIRED

            if (signers.values.any(SignerInfo::isTrusted))
                return TRUSTED

            return UNTRUSTED
        }
    }
}
