package com.mintv.tv.data;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * 取本机的局域网地址。
 *
 * <p>写死 IP 不行 —— 每个家庭网段都不一样（192.168.x.x / 10.x.x.x / 172.16~31.x.x）。
 *
 * <p>三种策略按可靠性排序，前一个拿到就用：
 * <ol>
 *   <li><b>UDP socket 探测</b>：connect 一个外网地址（不会真的发包），
 *       再读 socket 的本地地址 —— 这是内核选出的"实际上网用的那张网卡"，
 *       最准，而且不需要任何权限。</li>
 *   <li><b>遍历网卡</b>：挑第一个非回环的私有 IPv4。某些 ROM 上
 *       NetworkInterface 枚举会返回空或不完整，所以只能当兜底。</li>
 *   <li><b>WifiManager</b>：只有前两个都失败才用，需要 ACCESS_WIFI_STATE，
 *       而且只能在连 Wi-Fi 时有值。</li>
 * </ol>
 *
 * <p>全拿不到就退回 127.0.0.1：至少地址真实存在，
 * 手机上访问会失败，但比显示一个编造的地址诚实。
 */
public final class LocalIp {

    private LocalIp() { }

    public static String get(Context c) {
        // 顺序很重要：盒子可能同时挂着 VPN，
        // UDP 探测拿到的是 VPN 的地址（如 10.10.14.1），
        // 那个地址手机根本连不上。所以先找真实网卡（wlan / eth），
        // 找不到才退到"默认出口地址"。
        String ip = byLinkProperties(c);   // API 21+ 最可靠：系统告诉你当前网络的地址
        if (ip == null) ip = byWifiInterface();
        if (ip == null) ip = byWifi(c);
        if (ip == null) ip = byUdpProbe();
        if (ip == null) ip = byInterfaces();
        return ip == null ? "127.0.0.1" : ip;
    }

    /**
     * 从 ConnectivityManager 的 LinkProperties 读当前网络地址。
     *
     * <p>这是系统自己维护的"当前在用网络"信息，比枚举网卡准得多：
     * 盒子挂着 VPN 时，网卡枚举会把 tun 的地址排在前面，
     * 而 LinkProperties 给的是真实 Wi-Fi/以太网的地址。
     */
    private static String byLinkProperties(Context c) {
        if (c == null || android.os.Build.VERSION.SDK_INT < 21) return null;
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            android.net.Network[] nets = cm.getAllNetworks();
            if (nets == null) return null;
            for (android.net.Network n : nets) {
                android.net.LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null) continue;
                String iface = lp.getInterfaceName() == null ? "" : lp.getInterfaceName();
                if (iface.startsWith("tun") || iface.startsWith("ppp")) continue;
                for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                    InetAddress a = la.getAddress();
                    if (!(a instanceof Inet4Address)) continue;
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    String ip = a.getHostAddress();
                    if (ip != null && isPrivate(ip)) return ip;
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** 优先取 wlan / eth 这类真实局域网的网卡地址。 */
    private static String byWifiInterface() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface nif = ifs.nextElement();
                if (nif == null || nif.isLoopback() || !nif.isUp()) continue;
                String name = nif.getName() == null ? "" : nif.getName().toLowerCase(java.util.Locale.US);
                if (!(name.startsWith("wlan") || name.startsWith("eth")
                        || name.startsWith("ap") || name.startsWith("wifi"))) continue;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs != null && addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!(a instanceof Inet4Address)) continue;
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    String ip = a.getHostAddress();
                    if (ip != null && isPrivate(ip)) return ip;
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** 内核视角的本机出口地址。最可靠，且零权限。 */
    private static String byUdpProbe() {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            // connect 只做路由选择，不发包、不建连接
            s.connect(InetAddress.getByName("8.8.8.8"), 53);
            String ip = s.getLocalAddress().getHostAddress();
            if (ip != null && !ip.startsWith("127.") && !ip.equals("0.0.0.0")) {
                return ip;
            }
        } catch (Throwable ignored) {
        } finally {
            if (s != null) s.close();
        }
        return null;
    }

    private static String byInterfaces() {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface nif = ifs.nextElement();
                if (nif == null || nif.isLoopback() || !nif.isUp()) continue;
                String name = nif.getName() == null ? "" : nif.getName();
                // 跳过虚拟网卡：VPN / 热点 / 隧道，它们的地址手机访问不到
                if (name.startsWith("tun") || name.startsWith("ppp")
                        || name.startsWith("rmnet") || name.startsWith("clat")) continue;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs != null && addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!(a instanceof Inet4Address)) continue;
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    String ip = a.getHostAddress();
                    if (ip == null) continue;
                    if (isPrivate(ip)) return ip;   // 私有网段优先，命中即返回
                    if (fallback == null) fallback = ip;
                }
            }
        } catch (Throwable ignored) { }
        return fallback;
    }

    @SuppressWarnings("deprecation")
    private static String byWifi(Context c) {
        if (c == null) return null;
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            int raw = wm.getConnectionInfo().getIpAddress();
            if (raw == 0) return null;
            return (raw & 0xFF) + "." + ((raw >> 8) & 0xFF) + "."
                    + ((raw >> 16) & 0xFF) + "." + ((raw >> 24) & 0xFF);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isPrivate(String ip) {
        return ip.startsWith("192.168.") || ip.startsWith("10.") || is172(ip);
    }

    private static boolean is172(String ip) {
        if (!ip.startsWith("172.")) return false;
        int dot = ip.indexOf('.', 4);
        if (dot < 0) return false;
        try {
            int b = Integer.parseInt(ip.substring(4, dot));
            return b >= 16 && b <= 31;
        } catch (Throwable t) {
            return false;
        }
    }
}
