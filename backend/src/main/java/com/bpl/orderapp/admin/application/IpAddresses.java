package com.bpl.orderapp.admin.application;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.net.UnknownHostException;

final class IpAddresses {

    private IpAddresses() {}

    /** Strict dotted-quad IPv4 parser. Never performs a DNS lookup. */
    static InetAddress parseIpv4(String value) {
        if (value == null) {
            throw invalid();
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            throw invalid();
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3 || !p.chars().allMatch(c -> c >= '0' && c <= '9')) {
                throw invalid();
            }
            int n = Integer.parseInt(p);
            if (n > 255) {
                throw invalid();
            }
            bytes[i] = (byte) n;
        }
        try {
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            throw invalid();
        }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "serverIp must be a valid IPv4 address");
    }
}