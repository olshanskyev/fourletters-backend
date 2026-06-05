package net.fourletters.configuration;

public class ProxyResolver {
    public record ProxyEntry(String host, int port) {}

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
