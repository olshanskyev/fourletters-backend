package net.fourletters.configuration.vk;


import com.vk.api.sdk.httpclient.HttpTransportClient;
import org.apache.http.HttpHost;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;

public class VkHttpTransportClientWithProxy extends HttpTransportClient {

    public VkHttpTransportClientWithProxy(String host, int port) {
        super();

        HttpHost proxyHost = new HttpHost(host, port, "http");
        HttpTransportClient.httpClient = HttpClients.custom()
                .setRoutePlanner(new DefaultProxyRoutePlanner(proxyHost))
                .setDefaultRequestConfig(org.apache.http.client.config.RequestConfig.custom()
                        .setCookieSpec(org.apache.http.client.config.CookieSpecs.IGNORE_COOKIES)
                        .build())
                .build();
    }
}