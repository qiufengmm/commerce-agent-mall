package com.macro.mall.agent.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端 IP 解析，只用于限流维度分桶。
 *
 * <p>取值规则（fail-closed）：
 * <ol>
 *   <li>仅当请求对端 {@link HttpServletRequest#getRemoteAddr()} 是<strong>合法数值型 IP 字面量</strong>，
 *       且与配置的可信代理地址 {@code MALL_AGENT_TRUSTED_PROXY_IP}<strong>按地址字节相等</strong>时，
 *       才采用反向代理覆盖写入的 {@code X-Real-IP}（见
 *       {@code document/docker/nginx/conf.d/default.conf}，其值为 {@code $remote_addr}）；</li>
 *   <li>对端与可信地址不一致、未配置可信地址、或 {@code X-Real-IP} 非法/缺失时，一律忽略请求头，
 *       回退合法的 {@code remoteAddr}；</li>
 *   <li>{@code remoteAddr} 自身不是合法 IP 字面量时返回空字符串，交由 {@code RateLimiter}
 *       统一按 {@code unknown} 处理。</li>
 * </ol>
 *
 * <p><strong>默认不信任任何代理</strong>：{@code MALL_AGENT_TRUSTED_PROXY_IP} 默认空，未显式配置时
 * 永不采信 {@code X-Real-IP}，避免直连客户端伪造该头轮换 IP 绕过 IP 维度限流。只有把对端地址固定为
 * 反向代理容器地址后，本头才重新可信。
 *
 * <p><strong>刻意不读取 {@code X-Forwarded-For}</strong>：该头是<strong>追加</strong>语义，
 * 任意直连客户端都可以在链首伪造取值，用它分桶会让攻击者通过轮换伪造 IP 绕过 IP 维度限流。
 *
 * <p>所有输入都按<strong>严格数值字面量</strong>校验：IPv4 只接受四段规范十进制（禁止前导零、
 * 每段 0..255），IPv6 只接受十六进制分组、{@code ::} 压缩与末尾内嵌 IPv4。字符集外的 hostname、
 * 端口（{@code 1.2.3.4:80}）、IPv6 zone id（{@code fe80::1%eth0}）、逗号或分号拼接、任意空白
 * （含首尾与内嵌）、控制字符一律视为非法。校验过程<strong>绝不解析主机名，因此不会触发 DNS</strong>。
 *
 * <p>可信地址与对端地址的等价性按<strong>地址字节</strong>比较，因此同一 IPv6 地址的不同文本写法
 * （大小写、前导零、{@code ::} 压缩）也能正确识别，而不会退化为易绕过的文本子串判断。
 *
 * <p>本类不记录、不回显任何原始取值。
 */
public final class ClientIpResolver {

    /** 反向代理 {@code /agent-api/} 入口覆盖写入的真实客户端地址头。 */
    public static final String X_REAL_IP_HEADER = "X-Real-IP";

    private ClientIpResolver() {
    }

    /**
     * @param request        当前请求；为 {@code null} 时返回空字符串
     * @param trustedProxyIp 可信反向代理地址（可空或空串）；仅当对端与其字节相等时才采信 {@code X-Real-IP}
     * @return 单个合法 IP 字面量，或空字符串表示无可用取值
     */
    public static String resolve(HttpServletRequest request, String trustedProxyIp) {
        if (request == null) {
            return "";
        }
        String remoteAddr = request.getRemoteAddr();
        byte[] remote = numericIp(remoteAddr);
        if (remote == null) {
            // 对端不是合法字面量：无法确认是否来自可信代理，fail-closed 交 unknown 桶
            return "";
        }
        byte[] trusted = numericIp(trustedProxyIp);
        if (trusted != null && Arrays.equals(trusted, remote)) {
            String realIp = request.getHeader(X_REAL_IP_HEADER);
            // 可信来源也必须提供合法字面量；非法/缺失头一律回退对端地址
            if (numericIp(realIp) != null) {
                return realIp;
            }
        }
        return remoteAddr;
    }

    /** 严格解析单个数值型 IP 字面量；非法返回 {@code null}。绝不触发 DNS。 */
    private static byte[] numericIp(String value) {
        byte[] ipv4 = ipv4(value);
        return ipv4 != null ? ipv4 : ipv6(value);
    }

    /**
     * 解析规范 IPv4 字面量：四段十进制、每段 0..255、禁止前导零（唯一例外是单字符 {@code 0}）。
     * 字符集外的任意字符（含空白、控制字符、点号数量异常）都会被拒绝。
     */
    private static byte[] ipv4(String value) {
        if (value == null) {
            return null;
        }
        int length = value.length();
        if (length < 7 || length > 15) {
            return null;
        }
        byte[] address = new byte[4];
        int index = 0;
        for (int part = 0; part < 4; part++) {
            int start = index;
            int octet = 0;
            while (index < length) {
                char current = value.charAt(index);
                if (current < '0' || current > '9') {
                    break;
                }
                octet = octet * 10 + (current - '0');
                index++;
            }
            int digits = index - start;
            if (digits == 0 || digits > 3 || octet > 255) {
                return null;
            }
            if (digits > 1 && value.charAt(start) == '0') {
                return null;
            }
            address[part] = (byte) octet;
            if (part < 3) {
                if (index >= length || value.charAt(index) != '.') {
                    return null;
                }
                index++;
            }
        }
        return index == length ? address : null;
    }

    /**
     * 解析 IPv6 字面量：允许十六进制分组、单个 {@code ::} 压缩与末尾内嵌 IPv4。
     * 含 {@code %}（zone id）、非十六进制字符或分组数不为 8 时拒绝。
     */
    private static byte[] ipv6(String value) {
        if (value == null) {
            return null;
        }
        int length = value.length();
        if (length < 2 || length > 45 || value.indexOf(':') < 0) {
            return null;
        }
        for (int index = 0; index < length; index++) {
            char current = value.charAt(index);
            boolean allowed = (current >= '0' && current <= '9')
                    || (current >= 'a' && current <= 'f')
                    || (current >= 'A' && current <= 'F')
                    || current == ':'
                    || current == '.';
            if (!allowed) {
                return null;
            }
        }
        int gap = value.indexOf("::");
        if (gap >= 0 && gap != value.lastIndexOf("::")) {
            return null;
        }
        boolean compressed = gap >= 0;
        String head = compressed ? value.substring(0, gap) : value;
        String tail = compressed ? value.substring(gap + 2) : null;
        List<Integer> hextets = new ArrayList<>(8);
        // 内嵌 IPv4 只能是地址的最后 32 位：压缩时 head 之后还有内容，故 head 不允许内嵌 IPv4
        if (!parseHextets(head, hextets, !compressed)) {
            return null;
        }
        int headCount = hextets.size();
        int tailCount = 0;
        if (compressed) {
            List<Integer> tailHextets = new ArrayList<>(8);
            if (!parseHextets(tail, tailHextets, true)) {
                return null;
            }
            hextets.addAll(tailHextets);
            tailCount = tailHextets.size();
            // "::" 至少压缩一个全零分组
            if (headCount + tailCount >= 8) {
                return null;
            }
        } else if (headCount != 8) {
            return null;
        }
        byte[] address = new byte[16];
        int position = 0;
        for (int index = 0; index < headCount; index++) {
            int hextet = hextets.get(index);
            address[position++] = (byte) (hextet >> 8);
            address[position++] = (byte) hextet;
        }
        position += (8 - headCount - tailCount) * 2;
        for (int index = headCount; index < hextets.size(); index++) {
            int hextet = hextets.get(index);
            address[position++] = (byte) (hextet >> 8);
            address[position++] = (byte) hextet;
        }
        return address;
    }

    /**
     * 解析一段以 {@code :} 分隔的十六进制分组。空串视为「无分组」（供 {@code ::} 两侧使用），
     * 但组内出现空串（前导/尾随/连续冒号）会被拒绝。
     *
     * @param allowEmbeddedIpv4 是否允许本段末尾出现一个内嵌 IPv4（折算为两个分组）；仅当地址
     *                          未压缩或是 {@code ::} 之后的最后一段时才为 {@code true}
     */
    private static boolean parseHextets(String text, List<Integer> hextets, boolean allowEmbeddedIpv4) {
        if (text.isEmpty()) {
            return true;
        }
        String[] parts = text.split(":", -1);
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            if (part.isEmpty()) {
                return false;
            }
            if (part.indexOf('.') >= 0) {
                if (!allowEmbeddedIpv4 || index != parts.length - 1) {
                    return false;
                }
                byte[] embedded = ipv4(part);
                if (embedded == null) {
                    return false;
                }
                hextets.add(((embedded[0] & 0xFF) << 8) | (embedded[1] & 0xFF));
                hextets.add(((embedded[2] & 0xFF) << 8) | (embedded[3] & 0xFF));
            } else {
                if (part.length() > 4) {
                    return false;
                }
                int hextet = 0;
                for (int character = 0; character < part.length(); character++) {
                    int digit = Character.digit(part.charAt(character), 16);
                    if (digit < 0) {
                        return false;
                    }
                    hextet = (hextet << 4) | digit;
                }
                hextets.add(hextet);
            }
        }
        return true;
    }
}
