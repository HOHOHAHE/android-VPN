package com.example.vpntest.hev

data class HevTunnelConfig(
    val tunnel: TunnelConfig = TunnelConfig(),
    val socks5: Socks5Config = Socks5Config(),
    val tcp: TcpConfig = TcpConfig(),
    val misc: MiscConfig = MiscConfig()
) {
    
    data class TunnelConfig(
        val name: String = "android-vpn",
        val mtu: Int = 1500,
        val ipv4: Boolean = true,
        val ipv6: Boolean = false
    )
    
    data class Socks5Config(
        val address: String = "127.0.0.1",
        val port: Int = 1080,
        val username: String? = null,
        val password: String? = null
    )
    
    data class TcpConfig(
        val connect_timeout: Int = 5000,
        val read_timeout: Int = 60000,
        val write_timeout: Int = 60000
    )
    
    data class MiscConfig(
        val task_stack_size: Int = 20480,
        val log_file: String? = null,
        val log_level: String = "warn"
    )
    
    fun toYamlString(): String {
        return buildString {
            appendLine("tunnel:")
            appendLine("  name: ${tunnel.name}")
            appendLine("  mtu: ${tunnel.mtu}")
            appendLine("  ipv4: ${tunnel.ipv4}")
            appendLine("  ipv6: ${tunnel.ipv6}")
            appendLine()
            
            appendLine("socks5:")
            appendLine("  address: ${socks5.address}")
            appendLine("  port: ${socks5.port}")
            appendLine("  username: ${socks5.username ?: "~"}")
            appendLine("  password: ${socks5.password ?: "~"}")
            appendLine()
            
            appendLine("tcp:")
            appendLine("  connect-timeout: ${tcp.connect_timeout}")
            appendLine("  read-timeout: ${tcp.read_timeout}")
            appendLine("  write-timeout: ${tcp.write_timeout}")
            appendLine()
            
            appendLine("misc:")
            appendLine("  task-stack-size: ${misc.task_stack_size}")
            if (misc.log_file != null) {
                appendLine("  log-file: ${misc.log_file}")
            }
            appendLine("  log-level: ${misc.log_level}")
        }
    }
    
    companion object {
        fun createDefault(): HevTunnelConfig = HevTunnelConfig()
        
        fun createPerformanceOptimized(): HevTunnelConfig = HevTunnelConfig(
            tunnel = TunnelConfig(mtu = 1400),
            tcp = TcpConfig(
                connect_timeout = 3000,
                read_timeout = 30000,
                write_timeout = 30000
            ),
            misc = MiscConfig(
                task_stack_size = 16384,
                log_level = "error"
            )
        )
    }
}