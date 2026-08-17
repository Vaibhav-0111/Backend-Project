package com.example.webhook.endpoint;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;

@Component
public class UrlValidator {

    @Value("${webhook.allow-internal-ips:false}")
    private boolean allowInternalIps;

    public void validateUrl(String urlString) {
        URL url;
        try {
            url = new URL(urlString);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid URL format");
        }

        if (!"http".equalsIgnoreCase(url.getProtocol()) && !"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IllegalArgumentException("URL must use HTTP or HTTPS");
        }

        if (!allowInternalIps) {
            try {
                InetAddress[] addresses = InetAddress.getAllByName(url.getHost());
                for (InetAddress address : addresses) {
                    if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()) {
                        throw new IllegalArgumentException("Internal IP addresses are not allowed: " + address.getHostAddress());
                    }
                }
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Could not resolve host: " + url.getHost());
            }
        }
    }
}
