package io.github.hohohahe.nekitkotlin.tunnel

// Add RuleManager import
import io.github.hohohahe.nekitkotlin.rule.RuleManager
import io.github.hohohahe.nekitkotlin.socket.adapter.AdapterSocket
// Remove DirectAdapterSocket import if no longer directly used for creation
import io.github.hohohahe.nekitkotlin.socket.proxy.ProxySocket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import mu.KotlinLogging
import java.io.IOException
import java.nio.ByteBuffer

private val logger = KotlinLogging.logger {}

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
                logger.info { "Tunnel opening for client: ${proxySocket.remoteAddress}" }
                val connectSession = proxySocket.getConnectSession().first()
                logger.info { "Tunnel received session: $connectSession" }

                // Use RuleManager to get the AdapterFactory, then the AdapterSocket
                if (adapterSocket == null) { // Only create if not already injected (e.g. for tests)
                    val adapterFactory = ruleManager.match(connectSession)
                    logger.debug { "Tunnel: Using ${adapterFactory::class.simpleName} for session: $connectSession" }
                    adapterSocket = adapterFactory.getAdapter(connectSession)
                }
                currentAdapter = adapterSocket ?: throw IllegalStateException("AdapterSocket could not be created or injected")


                currentAdapter.openSocket(connectSession)
                logger.info { "Adapter socket opened to ${connectSession.host}:${connectSession.port} via ${currentAdapter::class.simpleName}" }

                currentAdapter.isReady.filter { it }.first()
                logger.info { "Adapter socket is ready. Responding success to proxy." }

                proxySocket.respondToSuccess()

                logger.info { "Starting data relay between proxy (${proxySocket.remoteAddress}) and adapter (${currentAdapter.remoteAddress})" }
                val proxyToAdapterJob = launchRelay("P->A", proxySocket, currentAdapter)
                val adapterToProxyJob = launchRelay("A->P", currentAdapter, proxySocket)

                listOf(proxyToAdapterJob, adapterToProxyJob).joinAll()

            } catch (e: Exception) {
                if (e is CancellationException) {
                    logger.info { "Tunnel for ${proxySocket.remoteAddress} cancelled." }
                    throw e
                }
                logger.error(e) { "Error in tunnel for ${proxySocket.remoteAddress}: ${e.message}" }
                try {
                    proxySocket.respondToFailure(e.message ?: "Tunnel setup failed")
                } catch (responseEx: Exception) {
                    logger.error(responseEx) { "Failed to send failure response to proxy." }
                }
            } finally {
                logger.info { "Tunnel for ${proxySocket.remoteAddress} closing." }
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
            logger.error(e) { "Error closing proxy socket during tunnel cleanup." }
        }
        try {
            adapter?.close()
        } catch (e: Exception) {
            logger.error(e) { "Error closing adapter socket during tunnel cleanup." }
        }
    }


    // Relay methods remain the same
    private fun CoroutineScope.launchRelay(
        name: String,
        source: ProxySocket,
        destination: AdapterSocket
    ): Job = launch(CoroutineName("relay-$name-ProxyToAdapter")) {
        val buffer = ByteBuffer.allocate(4096)
        try {
            while (isActive) {
                buffer.clear()
                val bytesRead = source.read(buffer)
                if (bytesRead == -1) { logger.debug { "Relay $name: source EOF." }; break }
                if (bytesRead == 0) { delay(10); continue }
                logger.trace { "Relay $name: read $bytesRead bytes." }
                buffer.flip()
                while (buffer.hasRemaining() && isActive) {
                    val bytesWritten = destination.write(buffer)
                    logger.trace { "Relay $name: wrote $bytesWritten bytes." }
                     if (bytesWritten == 0 && buffer.hasRemaining()) { delay(50); } // Small delay if write didn't consume all
                }
            }
        } catch (e: IOException) { logger.warn(e) { "Relay $name: IO error: ${e.message}" } }
        catch (e: CancellationException) { logger.debug { "Relay $name: Cancelled." } }
        catch (e: Exception) { logger.error(e) { "Relay $name: Unexpected error: ${e.message}" } }
        finally { logger.debug { "Relay $name: finished." } }
    }

    private fun CoroutineScope.launchRelay(
        name: String,
        source: AdapterSocket,
        destination: ProxySocket
    ): Job = launch(CoroutineName("relay-$name-AdapterToProxy")) {
         val buffer = ByteBuffer.allocate(4096)
        try {
            while (isActive) {
                buffer.clear()
                val bytesRead = source.read(buffer)
                if (bytesRead == -1) { logger.debug { "Relay $name: source EOF." }; break }
                if (bytesRead == 0) { delay(10); continue }
                logger.trace { "Relay $name: read $bytesRead bytes." }
                buffer.flip()
                while (buffer.hasRemaining() && isActive) {
                    val bytesWritten = destination.write(buffer)
                    logger.trace { "Relay $name: wrote $bytesWritten bytes." }
                    if (bytesWritten == 0 && buffer.hasRemaining()) { delay(50); } // Small delay
                }
            }
        } catch (e: IOException) { logger.warn(e) { "Relay $name: IO error: ${e.message}" } }
        catch (e: CancellationException) { logger.debug { "Relay $name: Cancelled." } }
        catch (e: Exception) { logger.error(e) { "Relay $name: Unexpected error: ${e.message}" } }
        finally { logger.debug { "Relay $name: finished." } }
    }

    fun close() { // This method is for external calls to initiate closure
        logger.info { "Tunnel explicitly closing resources via close()." }
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
