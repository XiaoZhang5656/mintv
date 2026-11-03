package com.mintv.tv.data;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * 取本机的局域网地址。
 *
 * <p>写死 IP 是不行的 —— 每个家庭网段都不一样（192.168.x.x / 10.x.x.x / 172.16~31.x.x）。
 * 这里遍历网卡，挑第一个「非回环、IPv4、私有网段」的地址。
 *
 * <p>拿不到就退回 127.0.0.1：至少地址是真实存在的，
 * 手机上访问会失败，但比显示一个编造的地址诚实。
 */
public final class LocalIp {

    private LocalIp() { }

    public static String get() {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface nif = ifs.nextElement();
                if (nif.isLoopback() || !nif.isUp()) continue;
                String name = nif.getName() == null ? "" : nif.getName();
                // 跳过虚拟网卡：VPN / 热点 / 隧道，它们的地址手机访问不到
                if (name.startsWith("tun") || name.startsWith("ppp")
                        || name.startsWith("rmnet") || name.startsWith("clat")) continue;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!(a instanceof Inet4Address)) continue;
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    String ip = a.getHostAddress();
                    if (ip == null) continue;
                    if (ip.startsWith("192.168.") || ip.startsWith("10.")
                            || isPrivate172(ip)) {
                        // 私有网段优先，直接返回
                        return ip;
                    }
                    if (fallback == null) fallback = ip;
                }
            }
        } catch (Throwable ignored) { }
        return fallback == null ? "127.0.0.1" : fallback;
    }

    private static boolean isPrivate172(String ip) {
        if (!ip.startsWith("172.")) return false;
        int dot = ip.indexOf('.', 4);
        if (dot < 0) return false;
        try {
            int second = Integer.parseInt(ip.substring(4, dot));
            return second >= 16 && second <= 31;
        } catch (Throwable t) {
            return false;
        }
    }
}
