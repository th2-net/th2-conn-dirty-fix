/*
 * Copyright 2023 Exactpro (Exactpro Systems Limited)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.exactpro.th2.conn.dirty.fix

import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.*
import javax.crypto.Cipher


fun encryptPassword(password: String, encryptionKeyPemFilePath: Path): String {
    check(Files.exists(encryptionKeyPemFilePath)) {
        "Encryption key file path '$encryptionKeyPemFilePath' doesn't exist"
    }
    val key = String(Files.readAllBytes(encryptionKeyPemFilePath), Charset.defaultCharset())

    val privateKeyPEM = key
        .replace("-----BEGIN PUBLIC KEY-----", "")
        .replace(System.lineSeparator().toRegex(), "")
        .replace("-----END PUBLIC KEY-----", "")

    val encoded: ByteArray = Base64.getDecoder().decode(privateKeyPEM)

    val keyFactory = KeyFactory.getInstance("RSA")
    val keySpec = X509EncodedKeySpec(encoded)
    val publicKey = keyFactory.generatePublic(keySpec) as RSAPublicKey

    val cipher = Cipher.getInstance("RSA")
    cipher.init(Cipher.ENCRYPT_MODE, publicKey)

    val encryptedPasswordBytes = cipher.doFinal(password.toByteArray())
    return String(Base64.getEncoder().encode(encryptedPasswordBytes))
}