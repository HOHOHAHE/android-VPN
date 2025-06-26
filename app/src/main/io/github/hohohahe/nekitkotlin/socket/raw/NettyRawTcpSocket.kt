package io.github.hohohahe.nekitkotlin.socket.raw

import android.util.Log
import io.github.hohohahe.nekitkotlin.core.IpAddress
import io.github.hohohahe.nekitkotlin.core.Port
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "NettyRawTcpSocket"

class NettyRawTcpSocket : RawTcpSocket {

    private var channel: SocketChannel?
    private val readChannelInternal = kotlinx.coroutines.channels.Channel<ByteBuffer>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private val eventLoopGroupToShutDown: EventLoopGroup? // Store if we created it
    private val isServerAcceptedSocket: Boolean
    
    // CompletableDeferred to signal when first data arrives
    private val firstDataReceived = CompletableDeferred<Boolean>()


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
                        
                        // Send data to internal channel first
                        if (!readChannelInternal.trySend(bufferCopy).isSuccess) {
                             Log.w(TAG, "Failed to send data to readChannel for ${ctx.channel().remoteAddress()}, channel may be closed or full.")
                        }
                        
                        // Signal that first data has been received AFTER storing the data
                        if (!firstDataReceived.isCompleted) {
                            Log.d(TAG, "First data packet received from ${ctx.channel().remoteAddress()}")
                            firstDataReceived.complete(true)
                        }
                    }
                    msg.release()
                } else {
                    super.channelRead(ctx, msg)
                }
            }

            override fun channelInactive(ctx: ChannelHandlerContext) {
                Log.d(TAG, "NettyRawTcpSocket: Channel inactive: ${ctx.channel().remoteAddress()}")
                
                // Signal that connection closed before first data if not already completed
                if (!firstDataReceived.isCompleted) {
                    firstDataReceived.complete(false)
                }
                
                readChannelInternal.close()
                super.channelInactive(ctx)
            }

            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                Log.e(TAG, "NettyRawTcpSocket: Exception in pipeline for ${ctx.channel().remoteAddress()}", cause)
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
            Log.w(TAG, "Socket already connected or connecting.")
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
                    Log.d(TAG, "Connection successful to $host:${port.value}")
                    if (continuation.isActive) continuation.resume(Unit)
                } else {
                    Log.e(TAG, "Failed to connect to $host:${port.value}", future.cause())
                     if (continuation.isActive) continuation.resumeWithException(future.cause() ?: RuntimeException("Unknown connection error"))
                }
            }
            continuation.invokeOnCancellation {
                Log.d(TAG, "Connection attempt cancelled for $host:${port.value}")
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
                // Only read what fits and leave the rest for future reads
                val bytesToRead = buffer.remaining()
                val originalPosition = receivedData.position()
                val originalLimit = receivedData.limit()
                receivedData.limit(originalPosition + bytesToRead)
                buffer.put(receivedData)
                
                // Put the remaining data back to the channel for next read
                receivedData.position(originalPosition + bytesToRead)
                receivedData.limit(originalLimit)
                if (receivedData.hasRemaining()) {
                    val remainingData = ByteBuffer.allocate(receivedData.remaining())
                    remainingData.put(receivedData)
                    remainingData.flip()
                    if (!readChannelInternal.trySend(remainingData).isSuccess) {
                        Log.e(TAG, "Failed to put back remaining data to channel")
                    }
                }
                
                return@withContext bytesToRead
            }
            buffer.put(receivedData)
            length
        } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
            Log.d(TAG, "Read channel closed for ${channel?.remoteAddress()}, likely EOF.")
            -1
        }
    }


    override suspend fun write(buffer: ByteBuffer): Int {
         val ch = channel ?: throw IllegalStateException("Socket not connected or already closed")
         
         // Enhanced connection state checking before write
         if (!ch.isOpen) {
             Log.w(TAG, "Attempted to write to closed channel ${ch.remoteAddress()}")
             throw IllegalStateException("Socket channel is closed")
         }
         if (!ch.isActive) {
             Log.w(TAG, "Attempted to write to inactive channel ${ch.remoteAddress()}")
             throw IllegalStateException("Socket is not active for writing.")
         }
         if (!ch.isWritable) {
             Log.w(TAG, "Channel ${ch.remoteAddress()} is not writable, may be congested")
         }

        val nettyBuffer = Unpooled.wrappedBuffer(buffer)
        val bytesToWrite = nettyBuffer.readableBytes()
        if (bytesToWrite == 0) return 0

        // Add detailed write logging for SSL handshake debugging
        val remoteAddr = ch.remoteAddress()
        Log.v(TAG, "Writing $bytesToWrite bytes to $remoteAddr")
        if (bytesToWrite <= 1024) { // Log small packets (likely handshake data)
            val bufferCopy = buffer.duplicate()
            val bytes = ByteArray(bytesToWrite)
            bufferCopy.get(bytes)
            val dataHex = bytes.joinToString(" ") { "%02x".format(it) }
            Log.v(TAG, "Data to $remoteAddr: $dataHex")
        }

        return suspendCancellableCoroutine<Int> { continuation ->
            // Double-check channel state just before write
            if (!ch.isOpen || !ch.isActive) {
                Log.e(TAG, "Channel to $remoteAddr became inactive just before write")
                if (continuation.isActive) {
                    continuation.resumeWithException(IllegalStateException("Channel became inactive before write"))
                }
                return@suspendCancellableCoroutine
            }
            
            ch.writeAndFlush(nettyBuffer).addListener { future ->
                if (future.isSuccess) {
                    Log.v(TAG, "Successfully wrote $bytesToWrite bytes to $remoteAddr")
                    if (continuation.isActive) continuation.resume(bytesToWrite)
                } else {
                    val cause = future.cause()
                    Log.e(TAG, "Failed to write $bytesToWrite bytes to $remoteAddr: ${cause?.javaClass?.simpleName} - ${cause?.message}", cause)
                    
                    // Add specific error analysis
                    when (cause) {
                        is java.io.IOException -> {
                            if (cause.message?.contains("Broken pipe") == true) {
                                Log.e(TAG, "BROKEN PIPE: Remote side $remoteAddr closed connection during write")
                            } else if (cause.message?.contains("Connection reset") == true) {
                                Log.e(TAG, "CONNECTION RESET: Remote side $remoteAddr reset connection")
                            }
                        }
                    }
                    
                    if (continuation.isActive) continuation.resumeWithException(cause ?: RuntimeException("Unknown write error"))
                }
            }
             continuation.invokeOnCancellation {
                Log.w(TAG, "Write operation to $remoteAddr was cancelled.")
            }
        }
    }

    /**
     * Suspends until the first data packet is received or the connection is closed.
     * @param timeoutMs Maximum time to wait for first data in milliseconds
     * @return true if data was received, false if connection closed or timeout
     */
    suspend fun awaitFirstData(timeoutMs: Long = 5000): Boolean {
        return try {
            withTimeout(timeoutMs) {
                firstDataReceived.await()
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Timeout waiting for first data from ${channel?.remoteAddress()}")
            false
        }
    }

    override fun close() {
        Log.d(TAG, "Closing NettyRawTcpSocket for ${channel?.remoteAddress()}.")
        
        // Complete the first data deferred if not already completed
        if (!firstDataReceived.isCompleted) {
            firstDataReceived.complete(false)
        }
        
        readChannelInternal.close()
        channel?.close()?.awaitUninterruptibly()
        channel = null // Mark as closed

        // Shut down event loop group only if this instance created and owns it (client-side default)
        eventLoopGroupToShutDown?.let {
            if (!it.isShuttingDown && !it.isShutdown) {
                Log.d(TAG, "Shutting down owned EventLoopGroup for NettyRawTcpSocket")
                it.shutdownGracefully().awaitUninterruptibly(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
    }
}
