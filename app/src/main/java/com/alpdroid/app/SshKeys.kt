package com.alpdroid.app

/** Validation for the one-line OpenSSH public keys pasted into "Add SSH public key". */
object SshKeys {
    private const val MAX_LENGTH = 8192

    private val PUBLIC_KEY_LINE = Regex(
        "^(ssh-(ed25519|rsa|dss)|ecdsa-sha2-nistp(256|384|521)|sk-ssh-ed25519@openssh\\.com|sk-ecdsa-sha2-nistp256@openssh\\.com)" +
            " [A-Za-z0-9+/=]+( [^\\r\\n]*)?$",
    )

    /** True for exactly one well-formed public-key line (type, base64 body, optional comment). */
    fun isValidPublicKeyLine(line: String): Boolean = line.length <= MAX_LENGTH && PUBLIC_KEY_LINE.matches(line)
}
