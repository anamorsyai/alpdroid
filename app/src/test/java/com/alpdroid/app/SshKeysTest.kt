package com.alpdroid.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshKeysTest {
    private val body = "AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl"

    @Test fun acceptsEd25519WithComment() = assertTrue(SshKeys.isValidPublicKeyLine("ssh-ed25519 $body me@laptop"))

    @Test fun acceptsKeyWithoutComment() = assertTrue(SshKeys.isValidPublicKeyLine("ssh-ed25519 $body"))

    @Test fun acceptsRsaAndEcdsa() {
        assertTrue(SshKeys.isValidPublicKeyLine("ssh-rsa $body"))
        assertTrue(SshKeys.isValidPublicKeyLine("ecdsa-sha2-nistp256 $body user"))
    }

    @Test fun rejectsUnknownType() = assertFalse(SshKeys.isValidPublicKeyLine("ssh-foo $body"))

    @Test fun rejectsMissingBody() = assertFalse(SshKeys.isValidPublicKeyLine("ssh-ed25519"))

    @Test fun rejectsMultipleLines() = assertFalse(SshKeys.isValidPublicKeyLine("ssh-ed25519 $body a\nssh-ed25519 $body b"))

    @Test fun rejectsCommandInjectionAttempt() =
        assertFalse(SshKeys.isValidPublicKeyLine("command=\"rm -rf /\" ssh-ed25519 $body"))

    @Test fun rejectsOversizedLine() = assertFalse(SshKeys.isValidPublicKeyLine("ssh-ed25519 " + "A".repeat(9000)))

    @Test fun rejectsEmpty() = assertFalse(SshKeys.isValidPublicKeyLine(""))
}
