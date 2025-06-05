package com.example.vpntest.proxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.util.Log
import com.example.vpntest.core.ProxyConnector
import com.example.vpntest.core.ProxyConnection
import com.example.vpntest.core.ProxyStats
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * SOCKS5 proxy server implementation
 */
class Socks5Proxy(
    private val context: Context,
    private val port: Int = 1080
) : ProxyConnector {
    
    companion object {
        private const val TAG = "Socks5Proxy"
    }
    
    override val proxyType: String = "SOCKS5"
    
    private var serverSocket: ServerSocket? = null
    private val proxyScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    
    // Statistics
    private val connectionCount = AtomicInteger(0)
    private val bytesTransferred = AtomicLong(0)
    private val errorCount = AtomicInteger(0)
    private val responseTimeTotal = AtomicLong(0)
    
    /**
     * Start the SOCKS5 server
     */
    fun start() {
        if (isRunning) {
            Log.w(TAG, "SOCKS5 server already running")
            return
        }
        
        proxyScope.launch {
            try {
                serverSocket = ServerSocket()
                serverSocket?.bind(InetSocketAddress("127.0.0.1", port))
                isRunning = true
                
                Log.i(TAG, "SOCKS5 server started on port $port")
                
                while (isRunning && serverSocket?.isClosed == false) {
                    try {
                        val client = serverSocket?.accept()
                        client?.let { 
                            launch { handleSocksClient(it) }
                        }
                    } catch (e: Exception) {
                        if (isRunning) {
                            Log.e(TAG, "Error accepting SOCKS client", e)
                            errorCount.incrementAndGet()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "SOCKS server error", e)
                errorCount.incrementAndGet()
            }
        }
    }
    
    /**
     * Stop the SOCKS5 server
     */
    fun stop() {
        isRunning = false
        proxyScope.cancel()
        
        try {
            serverSocket?.close()
            serverSocket = null
            Log.i(TAG, "SOCKS5 server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping SOCKS5 server", e)
        }
    }
    
    override suspend fun connect(targetHost: String, targetPort: Int): ProxyConnection? {
        // For SOCKS5, the actual connection is handled by the server
        // This method could be used for direct SOCKS5 client connections
        return null
    }
    
    override fun isHealthy(): Boolean = isRunning && serverSocket?.isClosed == false
    
    override fun getStats(): ProxyStats {
        val connCount = connectionCount.get()
        val avgResponseTime = if (connCount > 0) responseTimeTotal.get() / connCount else 0
        
        return ProxyStats(
            connectionCount = connCount,
            bytesTransferred = bytesTransferred.get(),
            errorCount = errorCount.get(),
            avgResponseTime = avgResponseTime
        )
    }
    
    private suspend fun handleSocksClient(client: Socket) = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        connectionCount.incrementAndGet()
        
        try {
            Log.d(TAG, "Handling SOCKS client: ${client.remoteSocketAddress}")
            
            val input = client.getInputStream()
            val output = client.getOutputStream()
            
            // SOCKS5 greeting phase
            if (!handleSocks5Greeting(input, output)) {
                return@withContext
            }
            
            // SOCKS5 connect request
            val targetInfo = handleSocks5ConnectRequest(input, output)
            if (targetInfo == null) {
                return@withContext
            }
            
            // Establish connection to target
            val targetSocket = connectToTarget(targetInfo.first, targetInfo.second, output)
            if (targetSocket == null) {
                return@withContext
            }
            
            // Record response time
            val responseTime = System.currentTimeMillis() - startTime
            responseTimeTotal.addAndGet(responseTime)
            
            // Start data relay
            relayData(client, targetSocket)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling SOCKS client", e)
            errorCount.incrementAndGet()
        } finally {
            try {
                client.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing client socket", e)
            }
        }
    }
    
    private suspend fun handleSocks5Greeting(input: InputStream, output: OutputStream): Boolean {
        try {
            val greeting = ByteArray(3) // Version + NMETHODS + METHOD[0]
            val greetingBytesRead = input.read(greeting)
            
            Log.d(TAG, "SOCKS5 greeting received: $greetingBytesRead bytes: ${greeting.joinToString { "%02x".format(it) }}")
            
            if (greetingBytesRead < 3) {
                Log.e(TAG, "SOCKS5 greeting too short: $greetingBytesRead bytes")
                return false
            }
            
            // Validate greeting: version=5, NMETHODS>=1
            if (greeting[0] != 0x05.toByte()) {
                Log.e(TAG, "SOCKS5 invalid version: ${greeting[0]}")
                return false
            }
            
            val nMethods = greeting[1].toInt() and 0xFF
            if (nMethods < 1) {
                Log.e(TAG, "SOCKS5 no methods specified: $nMethods")
                return false
            }
            
            // Read any additional method bytes if NMETHODS > 1
            if (nMethods > 1) {
                val additionalMethods = ByteArray(nMethods - 1)
                val additionalRead = input.read(additionalMethods)
                Log.d(TAG, "SOCKS5 additional methods: $additionalRead bytes: ${additionalMethods.joinToString { "%02x".format(it) }}")
            }
            
            Log.d(TAG, "SOCKS5 greeting valid: version=${greeting[0]}, nMethods=$nMethods, method[0]=${greeting[2]}")
            
            // Send no authentication required
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()
            Log.d(TAG, "SOCKS5 greeting response sent: 05 00")
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error in SOCKS5 greeting", e)
            return false
        }
    }
    
    private suspend fun handleSocks5ConnectRequest(input: InputStream, output: OutputStream): Pair<String, Int>? {
        try {
            // Read connect request header first
            val requestHeader = ByteArray(4)
            val headerRead = input.read(requestHeader)
            
            Log.d(TAG, "SOCKS5 connect request header: $headerRead bytes: ${requestHeader.joinToString { "%02x".format(it) }}")
            
            if (headerRead < 4 || requestHeader[0] != 0x05.toByte() || requestHeader[1] != 0x01.toByte()) {
                Log.w(TAG, "Invalid SOCKS5 request header")
                output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
                output.flush()
                return null
            }
            
            // Extract target address and port
            val (targetHost, targetPort) = when (requestHeader[3]) {
                0x01.toByte() -> { // IPv4
                    val ipBytes = ByteArray(4)
                    input.read(ipBytes)
                    val portBytes = ByteArray(2)
                    input.read(portBytes)
                    
                    val host = "${ipBytes[0].toUByte()}.${ipBytes[1].toUByte()}.${ipBytes[2].toUByte()}.${ipBytes[3].toUByte()}"
                    val port = ((portBytes[0].toUByte().toInt() shl 8) or portBytes[1].toUByte().toInt())
                    Pair(host, port)
                }
                0x03.toByte() -> { // Domain name
                    val lengthBytes = ByteArray(1)
                    input.read(lengthBytes)
                    val length = lengthBytes[0].toUByte().toInt()
                    
                    val domainBytes = ByteArray(length)
                    input.read(domainBytes)
                    val portBytes = ByteArray(2)
                    input.read(portBytes)
                    
                    val host = String(domainBytes)
                    val port = ((portBytes[0].toUByte().toInt() shl 8) or portBytes[1].toUByte().toInt())
                    Pair(host, port)
                }
                else -> {
                    Log.w(TAG, "Unsupported SOCKS5 address type: ${requestHeader[3]}")
                    output.write(byteArrayOf(0x05, 0x08, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
                    output.flush()
                    return null
                }
            }
            
            Log.d(TAG, "SOCKS5 connect request to $targetHost:$targetPort")
            return Pair(targetHost, targetPort)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in SOCKS5 connect request", e)
            return null
        }
    }
    
    private suspend fun connectToTarget(targetHost: String, targetPort: Int, output: OutputStream): Socket? {
        return try {
            val targetSocket = Socket()
            targetSocket.soTimeout = 15000 // 15 second timeout
            
            // Get underlying network to bypass VPN routing
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val underlyingNetwork = getUnderlyingNetwork(connectivityManager)
            
            // Bind socket to underlying network if available
            if (underlyingNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    underlyingNetwork.bindSocket(targetSocket)
                    Log.d(TAG, "SOCKS5 target socket bound to underlying network")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to bind SOCKS5 socket to underlying network, using default routing", e)
                }
            }
            
            Log.d(TAG, "Attempting connection to $targetHost:$targetPort...")
            
            // Connect to target with timeout
            targetSocket.connect(InetSocketAddress(targetHost, targetPort), 15000)
            targetSocket.soTimeout = 0 // Reset for data transfer
            
            // Send success response
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
            output.flush()
            
            Log.d(TAG, "SOCKS connection established to $targetHost:$targetPort")
            targetSocket
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to target $targetHost:$targetPort", e)
            errorCount.incrementAndGet()
            
            // Send failure response
            val errorCode = when (e) {
                is java.net.ConnectException -> 0x05.toByte() // Connection refused
                is java.net.NoRouteToHostException -> 0x03.toByte() // Network unreachable
                is java.net.SocketTimeoutException -> 0x04.toByte() // Host unreachable
                else -> 0x01.toByte() // General SOCKS server failure
            }
            
            output.write(byteArrayOf(0x05, errorCode, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
            output.flush()
            null
        }
    }
    
    private suspend fun relayData(client: Socket, targetSocket: Socket) {
        val clientRelayJob = proxyScope.launch { 
            try {
                relay(client.getInputStream(), targetSocket.getOutputStream(), "client->target") 
            } finally {
                try { targetSocket.shutdownOutput() } catch (e: Exception) { }
            }
        }
        
        val targetRelayJob = proxyScope.launch { 
            try {
                relay(targetSocket.getInputStream(), client.getOutputStream(), "target->client") 
            } finally {
                try { client.shutdownOutput() } catch (e: Exception) { }
            }
        }
        
        // Wait for either relay to complete, then close both
        try {
            clientRelayJob.join()
            targetRelayJob.join()
        } finally {
            clientRelayJob.cancel()
            targetRelayJob.cancel()
            try { targetSocket.close() } catch (e: Exception) { }
        }
    }
    
    private suspend fun relay(input: InputStream, output: OutputStream, direction: String = "") = withContext(Dispatchers.IO) {
        try {
            val buffer = ByteArray(4096)
            while (isRunning) {
                val bytesRead = input.read(buffer)
                if (bytesRead == -1) break
                output.write(buffer, 0, bytesRead)
                output.flush()
                
                bytesTransferred.addAndGet(bytesRead.toLong())
                
                if (direction.isNotEmpty()) {
                    Log.v(TAG, "SOCKS5 relay $direction: $bytesRead bytes")
                }
            }
        } catch (e: Exception) {
            if (isRunning && direction.isNotEmpty()) {
                Log.d(TAG, "SOCKS5 relay $direction closed: ${e.message}")
            }
        }
    }
    
    private fun getUnderlyingNetwork(connectivityManager: ConnectivityManager): Network? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val networks = connectivityManager.allNetworks
                
                for (network in networks) {
                    try {
                        val networkInfo = connectivityManager.getNetworkInfo(network)
                        val networkCapabilities = connectivityManager.getNetworkCapabilities(network)
                        
                        if (networkInfo != null && networkInfo.isConnected && 
                            networkInfo.type != ConnectivityManager.TYPE_VPN) {
                            
                            if (networkCapabilities != null && 
                                !networkCapabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) {
                                Log.d(TAG, "Found underlying network for SOCKS5: ${networkInfo.typeName}")
                                return network
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error checking network $network", e)
                    }
                }
                
                // Fallback approach
                for (network in networks) {
                    try {
                        val networkCapabilities = connectivityManager.getNetworkCapabilities(network)
                        if (networkCapabilities != null && 
                            networkCapabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            !networkCapabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) {
                            return network
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error checking network capabilities for $network", e)
                    }
                }
            }
            
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error finding underlying network for SOCKS5", e)
            null
        }
    }
}

/**
 * SOCKS5 proxy connection implementation
 */
class Socks5Connection(
    private val socket: Socket
) : ProxyConnection {
    
    override val isConnected: Boolean get() = socket.isConnected && !socket.isClosed
    
    override suspend fun sendData(data: ByteArray): Boolean {
        return try {
            socket.getOutputStream().write(data)
            socket.getOutputStream().flush()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    override suspend fun receiveData(): ByteArray? {
        return try {
            val buffer = ByteArray(4096)
            val bytesRead = socket.getInputStream().read(buffer)
            if (bytesRead > 0) buffer.copyOf(bytesRead) else null
        } catch (e: Exception) {
            null
        }
    }
    
    override fun close() {
        try {
            socket.close()
        } catch (e: Exception) {
            // Ignore
        }
    }
}
