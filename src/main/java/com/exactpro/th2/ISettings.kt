package com.exactpro.th2

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.schema.dictionary.DictionaryType
import io.netty.buffer.ByteBuf
import java.io.InputStream

interface ISettings {
    val ssl: Boolean
    val host: String
    val port: Int
    val handler: IProtocolHandlerSettings
    val mangler: IMessageManglerSettings
}

interface IClient {
    val isConnected: Boolean
    fun connect()
    fun send(message: ByteBuf)
    fun disconnect()
}

interface IProtocolHandlerSettings

interface IProtocolHandlerContext {
    val client: IClient
    val settings: IProtocolHandlerSettings
    operator fun get(dictionary: DictionaryType): InputStream
    fun send(event: Event)
}

interface IProtocolHandlerFactory {
    val protocol: String
    val settings: Class<out IProtocolHandlerSettings>
    fun create(context: IProtocolHandlerContext): IProtocolHandler
}

interface IProtocolHandler : AutoCloseable {
    fun onConnect() = Unit
    fun onData(buffer: ByteBuf): ByteArray?
    fun onMessage(message: ByteBuf): Map<String, String> = mapOf()
    fun onDisconnect() = Unit
    fun prepareMessage(message: ByteBuf): Map<String, String> = mapOf()
    override fun close() = Unit
}

interface IMessageManglerSettings

interface IMessageManglerContext {
    val client: IClient
    val settings: IMessageManglerSettings
    operator fun get(dictionary: DictionaryType): InputStream
    fun send(event: Event)
}

interface IMessageManglerFactory {
    val name: String
    val settings: Class<out IMessageManglerSettings>
    fun create(context: IMessageManglerContext): IMessageMangler
}

interface IMessageMangler {
    fun beforeMessage() = Unit
    fun mangleMessage(message: ByteBuf, metadata: Map<String, String>): Event?
    fun afterMessage() = Unit
}
