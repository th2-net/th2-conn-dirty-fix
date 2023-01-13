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

import java.io.ObjectInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.PublicKey
import java.util.*
import javax.crypto.Cipher

fun encryptPassword(password: String, encryptionKeyFilePath: Path): String {
    check(Files.exists(encryptionKeyFilePath)) {
        "Encryption key file path '$encryptionKeyFilePath' doesn't exist"
    }
    ObjectInputStream(Files.newInputStream(encryptionKeyFilePath)).use { inputStream ->
        val publicKey: PublicKey = inputStream.readObject() as PublicKey
        val cipher = Cipher.getInstance("RSA")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encryptedPasswordBytes = cipher.doFinal(password.toByteArray())
        return String(Base64.getEncoder().encode(encryptedPasswordBytes))
    }

}