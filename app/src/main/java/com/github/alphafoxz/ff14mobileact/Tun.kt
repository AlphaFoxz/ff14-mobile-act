package com.github.alphafoxz.ff14mobileact

import android.net.VpnService
import android.util.Log
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SocketChannel

/* ────────────────────────────── 1. 会话封装 ────────────────────────────── */
private const val IPPROTO_TCP = 6
private const val IPPROTO_UDP = 17

private data class ConnKey(
    val srcIp: Int, val srcPort: Int,
    val dstIp: Int, val dstPort: Int,
    val proto: Int
)

private class Tunnel(
    val proto: Int,
    val socket: SocketChannel?,          // TCP 用
    val dgram: DatagramChannel?,         // UDP 用
) {
    val inBuffer: ByteBuffer = ByteBuffer.allocate(65535)
    val outBuffer: ByteBuffer = ByteBuffer.allocate(65535)
}

/* ────────────────────────────── 2. pumpTun 主循环 ────────────────────────────── */
private fun pumpTun(vpnService: VpnService, fd: FileDescriptor) {
    val input = FileInputStream(fd)
    val output = FileOutputStream(fd)

    val sessions = HashMap<ConnKey, Tunnel>()
    val tunBuf = ByteBuffer.allocate(65535)

    while (!Thread.interrupted()) {

        /* ---------- 2-1. 从 TUN 读包 ---------- */
        val len = input.read(tunBuf.array())
        if (len <= 0) continue
        tunBuf.limit(len)

        /* 解析 IP 头（只演示最简 20 字节 IPv4，无选项） */
        val ipVerIhl = tunBuf.get(0).toInt() and 0xFF
        if (ipVerIhl shr 4 != 4) {
            tunBuf.clear(); continue
        } // 非 IPv4
        val proto = tunBuf.get(9).toInt() and 0xFF
        val srcIp = tunBuf.getInt(12)
        val dstIp = tunBuf.getInt(16)
        val srcPort: Int
        val dstPort: Int
        val ipHeaderLen = (ipVerIhl and 0x0F) * 4

        when (proto) {
            IPPROTO_TCP -> {
                srcPort = (tunBuf.getShort(ipHeaderLen).toInt() and 0xFFFF)
                dstPort = (tunBuf.getShort(ipHeaderLen + 2).toInt() and 0xFFFF)
            }

            IPPROTO_UDP -> {
                srcPort = (tunBuf.getShort(ipHeaderLen).toInt() and 0xFFFF)
                dstPort = (tunBuf.getShort(ipHeaderLen + 2).toInt() and 0xFFFF)
            }

            else -> {
                tunBuf.clear(); continue
            }  // 其它协议忽略
        }

        val key = ConnKey(srcIp, srcPort, dstIp, dstPort, proto)
        var tunnel = sessions[key]

        /* ---------- 2-2. 若新会话则创建 Socket ---------- */
        if (tunnel == null) {
            tunnel = when (proto) {
                IPPROTO_TCP -> {
                    val sc = SocketChannel.open()
                    sc.configureBlocking(false)
                    vpnService.protect(sc.socket())        // VERY IMPORTANT
                    sc.connect(InetSocketAddress(intToIp(dstIp), dstPort))
                    Tunnel(proto, sc, null)
                }

                IPPROTO_UDP -> {
                    val dc = DatagramChannel.open()
                    dc.configureBlocking(false)
                    vpnService.protect(
                        (dc as java.nio.channels.spi.AbstractSelectableChannel).javaClass
                            .getDeclaredField("fd").apply { isAccessible = true }
                            .get(dc) as java.net.DatagramSocket /* dummy */)
                    dc.connect(InetSocketAddress(intToIp(dstIp), dstPort))
                    Tunnel(proto, null, dc)
                }

                else -> continue
            }
            sessions[key] = tunnel
        }

        /* ---------- 2-3. 把 VPN → 真实网络 ---------- */
        tunBuf.limit(len)
        tunBuf.position(0)
        when (proto) {

            IPPROTO_TCP -> {
                println("TCP\nsrc=${srcIp}:$srcPort → dst=${dstIp}:$dstPort len=${len - ipHeaderLen}")
                tunnel.socket?.write(tunBuf)
            }

            IPPROTO_UDP -> {
//                Log.d(
//                    "Sniff-TCP",
//                    "src=${srcIp}:$srcPort → dst=${dstIp}:$dstPort len=${len - ipHeaderLen}"
//                )
                println("UDP\nsrc=${srcIp}:$srcPort → dst=${dstIp}:$dstPort len=${len - ipHeaderLen}")
                tunnel.dgram?.write(tunBuf)
            }
        }
        tunBuf.clear()

        /* ---------- 2-4. 轮询所有 Socket 回包 ---------- */
        val it = sessions.entries.iterator()
        while (it.hasNext()) {
            val (k, t) = it.next()
            val buf = t.inBuffer
            buf.clear()

            val read = when (t.proto) {
                IPPROTO_TCP -> t.socket?.read(buf) ?: -1
                IPPROTO_UDP -> t.dgram?.read(buf) ?: -1
                else -> -1
            }
            if (read != null && read > 0) {
                output.write(buf.array(), 0, read)   // 网络 → VPN
            }

            /* 简单超时/关闭检测（省略），必要时 it.remove() */
        }
    }
}

/* ────────────────────────────── 3. 若要仅“记录” ────────────────────────────── */
/* 上面在写入 Socket / 写回 TUN 前，插入日志即可 */
//Log.d("Sniff-TCP", "src=${ip(srcIp)}:$srcPort → dst=${ip(dstIp)}:$dstPort len=${len-ipHeaderLen}")

/* ────────────────────────────── 4. 工具函数 ────────────────────────────── */
private fun intToIp(ip: Int): String =
    ((ip shr 24) and 0xFF).toString() + "." +
            ((ip shr 16) and 0xFF) + "." +
            ((ip shr 8) and 0xFF) + "." +
            (ip and 0xFF)