package com.example.relay.deliveryengine.destination;

import java.net.InetAddress;
import java.util.List;

import com.example.relay.deliveryengine.http.DeliveryDeadline;

public interface HostAddressLookup {

    List<InetAddress> lookup(String absoluteHostname, DeliveryDeadline deadline) throws DnsResolutionException;
}
