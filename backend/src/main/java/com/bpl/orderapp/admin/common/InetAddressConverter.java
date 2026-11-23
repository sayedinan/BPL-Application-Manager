package com.bpl.orderapp.admin.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Stores an {@link InetAddress} as its textual address (e.g. "10.1.2.3") in a
 * TEXT column. Only IP literals are ever stored (the controller validates with
 * IpAddresses.parseIpv4), so reading back never triggers a DNS lookup.
 */
@Converter
public class InetAddressConverter implements AttributeConverter<InetAddress, String> {

    @Override
    public String convertToDatabaseColumn(InetAddress attribute) {
        // getHostAddress(), not toString(): toString() adds a leading "/".
        return attribute == null ? null : attribute.getHostAddress();
    }

    @Override
    public InetAddress convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        try {
            return InetAddress.getByName(dbData);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Stored server_ip is not a valid IP literal: " + dbData, e);
        }
    }
}
