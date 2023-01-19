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

import org.bouncycastle.util.io.pem.PemReader
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.*
import javax.crypto.Cipher

enum class KeyFileType {
    PEM_PUBLIC_KEY {
        override fun encrypt(
            keyFilePath: Path,
            value: String,
            keyEncryptAlgorithm: String,
            encryptAlgorithm: String,
            operationMode: OperationMode
        ): String {
            check(Files.exists(keyFilePath)) {
                "Encryption key file path '$keyFilePath' doesn't exist"
            }

            val publicKeyContent: ByteArray = Files.newBufferedReader(keyFilePath).use {
                val pemObject = PemReader(it).readPemObject()
                pemObject.content
            }

            val publicKey: PublicKey = KeyFactory.getInstance(keyEncryptAlgorithm)
                .generatePublic(X509EncodedKeySpec(publicKeyContent))

            Cipher.getInstance(encryptAlgorithm).apply {
                init(Cipher.ENCRYPT_MODE, publicKey)
                return Base64.getEncoder().encodeToString(doFinal(value.toByteArray()))
            }
        }
    };

    abstract fun encrypt(
        keyFilePath: Path,
        value: String,
        keyEncryptAlgorithm: String,
        encryptAlgorithm: String,
        operationMode: OperationMode = OperationMode.ENCRYPT_MODE
    ): String

    companion object {
        private const val BEGIN_PUBLIC_KEY = "-----BEGIN PUBLIC KEY-----"
        private const val END_PUBLIC_KEY = "-----END PUBLIC KEY-----"

        enum class OperationMode(val value: Int) {
            ENCRYPT_MODE(Cipher.ENCRYPT_MODE),
        }
    }
}