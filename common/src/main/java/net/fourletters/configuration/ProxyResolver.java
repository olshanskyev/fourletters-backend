package net.fourletters.configuration;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;

import org.springframework.http.client.JdkClientHttpRequestFactory;

public class ProxyResolver {
    public record ProxyEntry(String host, int port) {}

    /**
     * Builds a JdkClientHttpRequestFactory that routes requests through the
     * system proxy (when configured)
     */
    public static JdkClientHttpRequestFactory proxyAwareRequestFactory() {
        HttpClient.Builder httpClientBuilder = HttpClient.newBuilder();

        ProxyEntry proxyEntry = getSystemProxy();
        if (proxyEntry != null) {
            httpClientBuilder.proxy(ProxySelector.of(
                    new InetSocketAddress(proxyEntry.host(), proxyEntry.port())));
        }

        return new JdkClientHttpRequestFactory(httpClientBuilder.build());
    }

    public static ProxyEntry getSystemProxy() {
        String proxyHost = System.getProperty("http.proxyHost");
        if (proxyHost == null || proxyHost.trim().isEmpty()) {
            proxyHost = System.getProperty("https.proxyHost");
        }

        if (proxyHost != null && !proxyHost.trim().isEmpty()) {
            String proxyPortStr = System.getProperty("http.proxyPort");
            if (proxyPortStr == null || proxyPortStr.trim().isEmpty()) {
                proxyPortStr = System.getProperty("https.proxyPort");
            }

            int proxyPort = 8080;
            if (proxyPortStr != null && !proxyPortStr.trim().isEmpty()) {
                try {
                    proxyPort = Integer.parseInt(proxyPortStr.trim());
                } catch (NumberFormatException e) {
                    System.err.println("Invalid proxy port format '" + proxyPortStr + "'. Using default 8080.");
                }
            }

            return new ProxyEntry(proxyHost, proxyPort);
        }
        return null;
    }
}
