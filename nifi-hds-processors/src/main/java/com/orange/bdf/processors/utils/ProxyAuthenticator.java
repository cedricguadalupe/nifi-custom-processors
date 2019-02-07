package com.orange.bdf.processors.utils;

import okhttp3.Authenticator;
import okhttp3.Credentials;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;

import javax.annotation.Nullable;
import java.io.IOException;

public class ProxyAuthenticator implements Authenticator {

    public ProxyAuthenticator() {
    }

    public ProxyAuthenticator(String proxyUsername, String proxyPassword) {
        this.proxyUsername = proxyUsername;
        this.proxyPassword = proxyPassword;
    }

    private String proxyUsername;
    private String proxyPassword;

    public void setProxyUsername(String proxyUsername) {
        this.proxyUsername = proxyUsername;
    }

    public void setProxyPassword(String proxyPassword) {
        this.proxyPassword = proxyPassword;
    }

    @Nullable
    @Override
    public Request authenticate(Route route, Response response) throws IOException {
        String credential = Credentials.basic(proxyUsername, proxyPassword);
        return response.request()
                .newBuilder()
                .header("Proxy-Authorization", credential)
                .build();
    }
}
