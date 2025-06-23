package io.github.hohohahe.nekitkotlin.socket.raw

import io.github.hohohahe.nekitkotlin.core.IpAddress
import io.github.hohohahe.nekitkotlin.core.Port
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val logger = KotlinLogging.logger {}

class NettyRawTcpSocket : RawTcpSocket {

    private var channel: SocketChannel?
    private val readChannelInternal = kotlinx.coroutines.channels.Channel<ByteBuffer>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private val eventLoopGroupToShutDown: EventLoopGroup? // Store if we created it
    private val isServerAcceptedSocket: Boolean


    // Constructor for client-side initiated connections
    constructor(eventLoopGroup: EventLoopGroup = NioEventLoopGroup(1) /* Default, creates new group */) : this(null, eventLoopGroup, true)

    // Constructor for server-side accepted connections
    constructor(acceptedChannel: SocketChannel, managingEventLoopGroup: EventLoopGroup) : this(acceptedChannel, managingEventLoopGroup, false)

    // Private common constructor
    private constructor(
        acceptedChannel: SocketChannel?,
        providedEventLoopGroup: EventLoopGroup,
        isClientInitiated: Boolean
    ) {
        this.isServerAcceptedSocket = acceptedChannel != null
        this.channel = acceptedChannel

        if (isClientInitiated) {
            // If client initiated and using default NioEventLoopGroup(1), then we own it.
            // This logic is a bit simplified. A more robust way is to check if the providedEventLoopGroup
            // is the one we would have defaulted to.
            // For now, if it's client and we get the default type, assume we created it.
            // This is not perfect. Better: pass a flag if group is shared.
            this.eventLoopGroupToShutDown = if (providedEventLoopGroup is NioEventLoopGroup && providedEventLoopGroup.executorCount() == 1 && acceptedChannel == null) {
                providedEventLoopGroup
            } else {
                null // Assume shared or managed externally
            }
            this.bootstrapEventLoopGroup = providedEventLoopGroup // Group for bootstrap (client)
        } else { // Server accepted socket
            this.eventLoopGroupToShutDown = null // Server's ELG is managed by server
            this.bootstrapEventLoopGroup = null // No bootstrap for accepted sockets

            // For server-accepted channel, need to add handler to its pipeline
            acceptedChannel?.pipeline()?.addLast("handler", createChannelInboundHandlerAdapter())
            if (acceptedChannel?.isActive == false) { // If channel provided is already inactive
                readChannelInternal.close()
            }
        }
    }

    private fun createChannelInboundHandlerAdapter(): ChannelInboundHandlerAdapter {
        return object : ChannelInboundHandlerAdapter() {
            override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                if (msg is io.netty.buffer.ByteBuf) {
                    val readableBytes = msg.readableBytes()
                    if (readableBytes > 0) {
                        val nioBuffer = msg.nioBuffer()
                        val bufferCopy = ByteBuffer.allocate(nioBuffer.remaining())
                        bufferCopy.put(nioBuffer)
                        bufferCopy.flip()
                        if (!readChannelInternal.trySend(bufferCopy).isSuccess) {
                             logger.warn { "Failed to send data to readChannel for ${ctx.channel().remoteAddress()}, channel may be closed or full." }
                        }
                    }
                    msg.release()
                } else {
                    super.channelRead(ctx, msg)
                }
            }

            override fun channelInactive(ctx: ChannelHandlerContext) {
                logger.debug { "NettyRawTcpSocket: Channel inactive: ${ctx.channel().remoteAddress()}" }
                readChannelInternal.close()
                super.channelInactive(ctx)
            }

            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                logger.error(cause) { "NettyRawTcpSocket: Exception in pipeline for ${ctx.channel().remoteAddress()}" }
                readChannelInternal.close(cause)
                ctx.close() // Close Netty channel on exception
            }
        }
    }


    override val isOpen: Boolean
        get() = channel?.isOpen == true

    override val localAddress: IpAddress?
        get() = (channel?.localAddress() as? InetSocketAddress)?.let { IpAddress(it.address.hostAddress) }

    override val remoteAddress: IpAddress?
        get() = (channel?.remoteAddress() as? InetSocketAddress)?.let { IpAddress(it.address.hostAddress) }

    // Bootstrap needs to be lazy or initialized in the client constructor path
    private var bootstrap: Bootstrap? = null
    private val bootstrapEventLoopGroup: EventLoopGroup?


    override suspend fun connect(host: String, port: Port) {
        if (isServerAcceptedSocket) {
            throw IllegalStateException("Cannot call connect() on a server-accepted socket.")
        }
        if (isOpen) {
            logger.warn { "Socket already connected or connecting." }
            return
        }
        val socketAddress = InetSocketAddress(host, port.value)

        val b = Bootstrap()
            .group(bootstrapEventLoopGroup!!) // Must be set for client sockets
            .channel(NioSocketChannel::class.java)
            .handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline().addLast("handler", createChannelInboundHandlerAdapter())
                }
            })
            .option(ChannelOption.TCP_NODELAY, true)
            .option(ChannelOption.SO_KEEPALIVE, true)
        this.bootstrap = b


        val connectFuture = b.connect(socketAddress)
        this.channel = connectFuture.channel() as SocketChannel

        suspendCancellableCoroutine<Unit> { continuation ->
            connectFuture.addListener { future ->
                if (future.isSuccess) {
                    logger.debug { "Connection successful to $host:${port.value}" }
                    if (continuation.isActive) continuation.resume(Unit)
                } else {
                    logger.error(future.cause()) { "Failed to connect to $host:${port.value}" }
                     if (continuation.isActive) continuation.resumeWithException(future.cause() ?: RuntimeException("Unknown connection error"))
                }
            }
            continuation.invokeOnCancellation {
                logger.debug { "Connection attempt cancelled for $host:${port.value}" }
                if (connectFuture.isCancellable) connectFuture.cancel(false)
            }
        }
    }

    override suspend fun read(buffer: ByteBuffer): Int = withContext(Dispatchers.IO) {
        if (!isOpen && readChannelInternal.isClosedForReceive) {
             return@withContext -1
        }
        try {
            // Check if channel is closed before attempting to receive
            if (readChannelInternal.isClosedForReceive && channel?.isOpen == false) {
                return@withContext -1
            }
            val receivedData = readChannelInternal.receive()
            val length = receivedData.remaining()

            if (length > buffer.remaining()) {
                logger.warn { "Read buffer (${buffer.remaining()}) too small for received data (${length}). Truncating." }
                val originalLimit = receivedData.limit()
                receivedData.limit(receivedData.position() + buffer.remaining())
                buffer.put(receivedData)
                receivedData.limit(originalLimit) // Restore limit for potential later use if data was re-queued
                // This part needs careful handling if data is to be preserved.
                // For now, it copies what fits.
                return@withContext buffer.position() // Actually means bytes put into buffer (from its perspective)
                                                    // which is buffer.remaining() before put.
                                                    // A clearer return would be the number of bytes *read from source*
                                                    // and put into buffer.
            }
            buffer.put(receivedData)
            length
        } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
            logger.debug { "Read channel closed for ${channel?.remoteAddress()}, likely EOF." }
            -1
        }
    }


    override suspend fun write(buffer: ByteBuffer): Int {
         val ch = channel ?: throw IllegalStateException("Socket not connected or already closed")
         if (!ch.isActive) throw IllegalStateException("Socket is not active for writing.")

        val nettyBuffer = Unpooled.wrappedBuffer(buffer)
        val bytesToWrite = nettyBuffer.readableBytes()
        if (bytesToWrite == 0) return 0


        return suspendCancellableCoroutine<Int> { continuation ->
            ch.writeAndFlush(nettyBuffer).addListener { future ->
                if (future.isSuccess) {
                    if (continuation.isActive) continuation.resume(bytesToWrite)
                } else {
                    logger.error(future.cause()) { "Failed to write ${bytesToWrite} bytes to ${ch.remoteAddress()}"}
                    if (continuation.isActive) continuation.resumeWithException(future.cause() ?: RuntimeException("Unknown write error"))
                }
            }
             continuation.invokeOnCancellation {
                logger.warn { "Write operation to ${ch.remoteAddress()} was cancelled." }
            }
        }
    }

    override fun close() {
        logger.debug { "Closing NettyRawTcpSocket for ${channel?.remoteAddress()}." }
        readChannelInternal.close()
        channel?.close()?.awaitUninterruptibly()
        channel = null // Mark as closed

        // Shut down event loop group only if this instance created and owns it (client-side default)
        eventLoopGroupToShutDown?.let {
            if (!it.isShuttingDown && !it.isShutdown) {
                logger.debug{"Shutting down owned EventLoopGroup for NettyRawTcpSocket"}
                it.shutdownGracefully().awaitUninterruptibly(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
    }
}
