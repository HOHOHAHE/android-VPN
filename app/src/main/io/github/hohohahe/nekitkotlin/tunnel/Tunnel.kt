package io.github.hohohahe.nekitkotlin.tunnel

// Add RuleManager import
import android.util.Log
import io.github.hohohahe.nekitkotlin.rule.RuleManager
import io.github.hohohahe.nekitkotlin.socket.adapter.AdapterSocket
// Remove DirectAdapterSocket import if no longer directly used for creation
import io.github.hohohahe.nekitkotlin.socket.proxy.ProxySocket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import java.io.IOException
import java.nio.ByteBuffer

private const val TAG = "Tunnel"

class Tunnel(
    private val scope: CoroutineScope,
    private val proxySocket: ProxySocket,
    private val ruleManager: RuleManager, // Add RuleManager
    private var adapterSocket: AdapterSocket? = null // Keep for potential injection/testing, but typically created by RuleManager
) {
    private var job: Job? = null

    suspend fun openAndRelay() {
        job = scope.launch(CoroutineName("tunnel-${proxySocket.remoteAddress}")) {
            var currentAdapter: AdapterSocket? = null // Define here to be accessible in finally
            try {
                Log.i(TAG, "Tunnel opening for client: ${proxySocket.remoteAddress}")
                Log.d(TAG, "Tunnel: Waiting for ConnectSession from proxy socket...")
                val connectSession = proxySocket.getConnectSession().first()
                Log.i(TAG, "Tunnel received session: $connectSession")

                // Use RuleManager to get the AdapterFactory, then the AdapterSocket
                if (adapterSocket == null) { // Only create if not already injected (e.g. for tests)
                    Log.d(TAG, "Tunnel: Getting adapter factory from rule manager for session: $connectSession")
                    val adapterFactory = ruleManager.match(connectSession)
                    Log.d(TAG, "Tunnel: Using ${adapterFactory::class.simpleName} for session: $connectSession")
                    Log.d(TAG, "Tunnel: Creating adapter socket...")
                    adapterSocket = adapterFactory.getAdapter(connectSession)
                }
                currentAdapter = adapterSocket ?: throw IllegalStateException("AdapterSocket could not be created or injected")

                Log.d(TAG, "Tunnel: Opening adapter socket to ${connectSession.host}:${connectSession.port}...")
                currentAdapter.openSocket(connectSession)
                Log.i(TAG, "Adapter socket opened to ${connectSession.host}:${connectSession.port} via ${currentAdapter::class.simpleName}")

                Log.d(TAG, "Tunnel: Waiting for adapter socket to be ready...")
                currentAdapter.isReady.filter { it }.first()
                Log.i(TAG, "Adapter socket is ready. Responding success to proxy.")

                Log.d(TAG, "Tunnel: Sending success response to proxy...")
                proxySocket.respondToSuccess()

                Log.i(TAG, "Starting data relay between proxy (${proxySocket.remoteAddress}) and adapter (${currentAdapter.remoteAddress})")
                val proxyToAdapterJob = launchRelay("P->A", proxySocket, currentAdapter)
                val adapterToProxyJob = launchRelay("A->P", currentAdapter, proxySocket)

                Log.d(TAG, "Tunnel: Both relay jobs started, waiting for completion...")
                listOf(proxyToAdapterJob, adapterToProxyJob).joinAll()
                Log.d(TAG, "Tunnel: All relay jobs completed")

            } catch (e: Exception) {
                if (e is CancellationException) {
                    Log.i(TAG, "Tunnel for ${proxySocket.remoteAddress} cancelled.")
                    throw e
                }
                Log.e(TAG, "Error in tunnel for ${proxySocket.remoteAddress}: ${e.javaClass.simpleName} - ${e.message}", e)
                // Add specific error context
                when (e) {
                    is java.util.NoSuchElementException -> Log.e(TAG, "Tunnel: ConnectSession flow was empty for ${proxySocket.remoteAddress}")
                    is kotlinx.coroutines.TimeoutCancellationException -> Log.e(TAG, "Tunnel: Timeout waiting for operation for ${proxySocket.remoteAddress}")
                    is java.io.IOException -> Log.e(TAG, "Tunnel: IO error for ${proxySocket.remoteAddress}: ${e.message}")
                    is IllegalStateException -> Log.e(TAG, "Tunnel: State error for ${proxySocket.remoteAddress}: ${e.message}")
                    else -> Log.e(TAG, "Tunnel: Unexpected error type for ${proxySocket.remoteAddress}: ${e.javaClass.name}")
                }
                try {
                    Log.d(TAG, "Tunnel: Sending failure response to proxy...")
                    proxySocket.respondToFailure(e.message ?: "Tunnel setup failed")
                } catch (responseEx: Exception) {
                    Log.e(TAG, "Failed to send failure response to proxy.", responseEx)
                }
            } finally {
                Log.i(TAG, "Tunnel for ${proxySocket.remoteAddress} closing.")
                // Use currentAdapter for closing, as adapterSocket property might be reassigned or was null initially
                closeResources(proxySocket, currentAdapter)
            }
        }
    }

    // Extracted resource closing logic
    private fun closeResources(proxy: ProxySocket?, adapter: AdapterSocket?) {
         try {
            proxy?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing proxy socket during tunnel cleanup.", e)
        }
        try {
            adapter?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing adapter socket during tunnel cleanup.", e)
        }
    }


    // Enhanced relay methods with SSL handshake debugging
    private fun CoroutineScope.launchRelay(
        name: String,
        source: ProxySocket,
        destination: AdapterSocket
    ): Job = launch(CoroutineName("relay-$name-ProxyToAdapter")) {
        val buffer = ByteBuffer.allocate(4096)
        var totalBytesRelayed = 0
        var packetCount = 0
        try {
            Log.d(TAG, "Relay $name: Starting relay from ${source.remoteAddress} to ${destination.remoteAddress}")
            while (isActive) {
                buffer.clear()
                val bytesRead = source.read(buffer)
                if (bytesRead == -1) {
                    Log.d(TAG, "Relay $name: source EOF after $totalBytesRelayed bytes, $packetCount packets.")
                    break
                }
                if (bytesRead == 0) { delay(10); continue }
                
                packetCount++
                totalBytesRelayed += bytesRead
                Log.v(TAG, "Relay $name: read $bytesRead bytes (packet #$packetCount, total: $totalBytesRelayed).")
                
                // Log first few packets in detail for SSL handshake analysis
                if (packetCount <= 5 && bytesRead <= 512) {
                    buffer.flip()
                    val dataBytes = ByteArray(bytesRead)
                    buffer.duplicate().get(dataBytes)
                    val dataHex = dataBytes.joinToString(" ") { "%02x".format(it) }
                    Log.d(TAG, "Relay $name packet #$packetCount data: $dataHex")
                    buffer.rewind()
                }
                
                buffer.flip()
                var remainingToWrite = bytesRead
                var writeAttempts = 0
                while (buffer.hasRemaining() && isActive && remainingToWrite > 0) {
                    writeAttempts++
                    val bytesWritten = try {
                        destination.write(buffer)
                    } catch (e: Exception) {
                        Log.e(TAG, "Relay $name: Write failed on attempt $writeAttempts for packet #$packetCount: ${e.message}", e)
                        throw e
                    }
                    
                    remainingToWrite -= bytesWritten
                    Log.v(TAG, "Relay $name: wrote $bytesWritten bytes (attempt $writeAttempts, remaining: $remainingToWrite).")
                    
                    if (bytesWritten == 0 && buffer.hasRemaining()) {
                        Log.w(TAG, "Relay $name: Zero bytes written on attempt $writeAttempts, delaying...")
                        delay(50) // Small delay if write didn't consume all
                    }
                }
                
                if (remainingToWrite > 0) {
                    Log.e(TAG, "Relay $name: Failed to write all data for packet #$packetCount, $remainingToWrite bytes remaining")
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Relay $name: IO error after $totalBytesRelayed bytes, $packetCount packets: ${e.message}", e)
        }
        catch (e: CancellationException) {
            Log.d(TAG, "Relay $name: Cancelled after $totalBytesRelayed bytes, $packetCount packets.")
        }
        catch (e: Exception) {
            Log.e(TAG, "Relay $name: Unexpected error after $totalBytesRelayed bytes, $packetCount packets: ${e.message}", e)
        }
        finally {
            Log.d(TAG, "Relay $name: finished. Total: $totalBytesRelayed bytes, $packetCount packets.")
        }
    }

    private fun CoroutineScope.launchRelay(
        name: String,
        source: AdapterSocket,
        destination: ProxySocket
    ): Job = launch(CoroutineName("relay-$name-AdapterToProxy")) {
         val buffer = ByteBuffer.allocate(4096)
         var totalBytesRelayed = 0
         var packetCount = 0
        try {
            Log.d(TAG, "Relay $name: Starting relay from ${source.remoteAddress} to ${destination.remoteAddress}")
            while (isActive) {
                buffer.clear()
                val bytesRead = source.read(buffer)
                if (bytesRead == -1) {
                    Log.d(TAG, "Relay $name: source EOF after $totalBytesRelayed bytes, $packetCount packets.")
                    break
                }
                if (bytesRead == 0) { delay(10); continue }
                
                packetCount++
                totalBytesRelayed += bytesRead
                Log.v(TAG, "Relay $name: read $bytesRead bytes (packet #$packetCount, total: $totalBytesRelayed).")
                
                // Log first few packets in detail for SSL handshake analysis
                if (packetCount <= 5 && bytesRead <= 512) {
                    buffer.flip()
                    val dataBytes = ByteArray(bytesRead)
                    buffer.duplicate().get(dataBytes)
                    val dataHex = dataBytes.joinToString(" ") { "%02x".format(it) }
                    Log.d(TAG, "Relay $name packet #$packetCount data: $dataHex")
                    buffer.rewind()
                }
                
                buffer.flip()
                var remainingToWrite = bytesRead
                var writeAttempts = 0
                while (buffer.hasRemaining() && isActive && remainingToWrite > 0) {
                    writeAttempts++
                    val bytesWritten = try {
                        destination.write(buffer)
                    } catch (e: Exception) {
                        Log.e(TAG, "Relay $name: Write failed on attempt $writeAttempts for packet #$packetCount: ${e.message}", e)
                        throw e
                    }
                    
                    remainingToWrite -= bytesWritten
                    Log.v(TAG, "Relay $name: wrote $bytesWritten bytes (attempt $writeAttempts, remaining: $remainingToWrite).")
                    
                    if (bytesWritten == 0 && buffer.hasRemaining()) {
                        Log.w(TAG, "Relay $name: Zero bytes written on attempt $writeAttempts, delaying...")
                        delay(50) // Small delay
                    }
                }
                
                if (remainingToWrite > 0) {
                    Log.e(TAG, "Relay $name: Failed to write all data for packet #$packetCount, $remainingToWrite bytes remaining")
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Relay $name: IO error after $totalBytesRelayed bytes, $packetCount packets: ${e.message}", e)
        }
        catch (e: CancellationException) {
            Log.d(TAG, "Relay $name: Cancelled after $totalBytesRelayed bytes, $packetCount packets.")
        }
        catch (e: Exception) {
            Log.e(TAG, "Relay $name: Unexpected error after $totalBytesRelayed bytes, $packetCount packets: ${e.message}", e)
        }
        finally {
            Log.d(TAG, "Relay $name: finished. Total: $totalBytesRelayed bytes, $packetCount packets.")
        }
    }

    fun close() { // This method is for external calls to initiate closure
        Log.i(TAG, "Tunnel explicitly closing resources via close().")
        job?.cancel("Tunnel closing via external call")
        // The actual resource cleanup is now handled by the finally block in openAndRelay
        // and the closeResources helper.
        // To ensure cleanup even if openAndRelay hasn't been called or job is null:
        if (job == null || !job!!.isActive) { // If job is not active, close manually
            closeResources(proxySocket, adapterSocket)
        }
    }

    suspend fun join() {
        job?.join()
    }
}
