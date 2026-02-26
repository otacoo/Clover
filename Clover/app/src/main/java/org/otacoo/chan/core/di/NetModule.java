package org.otacoo.chan.core.di;

import android.content.Context;

import org.codejargon.feather.Provides;
import org.otacoo.chan.core.cache.FileCache;
import org.otacoo.chan.core.net.ChanInterceptor;
import org.otacoo.chan.core.settings.ChanSettings;
import org.otacoo.chan.utils.Logger;

import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import javax.inject.Singleton;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.dnsoverhttps.DnsOverHttps;

public class NetModule {
    private static final long FILE_CACHE_DISK_SIZE = 50 * 1024 * 1024;
    private static final String FILE_CACHE_NAME = "filecache";
    private static final int TIMEOUT = 30000;
    private static final String TAG = "NetModule";

    @Provides
    @Singleton
    public OkHttpClient provideOkHttpClient(UserAgentProvider userAgentProvider) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .readTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .writeTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .addInterceptor(new ChanInterceptor(userAgentProvider));

        if (ChanSettings.dnsOverHttps.get()) {
            try {
                // We need a temporary client to build the DnsOverHttps
                OkHttpClient dnsClient = new OkHttpClient.Builder().build();
                DnsOverHttps dns = new DnsOverHttps.Builder()
                        .client(dnsClient)
                        .url(HttpUrl.parse("https://cloudflare-dns.com/dns-query"))
                        .bootstrapDnsHosts(Arrays.asList(
                                InetAddress.getByName("162.159.36.1"),
                                InetAddress.getByName("162.159.46.1"),
                                InetAddress.getByName("1.1.1.1"),
                                InetAddress.getByName("1.0.0.1"),
                                InetAddress.getByName("162.159.132.53"),
                                InetAddress.getByName("2606:4700:4700::1111"),
                                InetAddress.getByName("2606:4700:4700::1001"),
                                InetAddress.getByName("2606:4700:4700::0064"),
                                InetAddress.getByName("2606:4700:4700::6400")
                        ))
                        .build();
                builder.dns(dns);
            } catch (UnknownHostException e) {
                Logger.e(TAG, "Error Dns over https", e);
            }
        }

        return builder.build();
    }

    @Provides
    @Singleton
    public FileCache provideFileCache(Context applicationContext, UserAgentProvider userAgentProvider, OkHttpClient okHttpClient) {
        return new FileCache(new File(getCacheDir(applicationContext), FILE_CACHE_NAME), FILE_CACHE_DISK_SIZE, userAgentProvider.getUserAgent(), okHttpClient);
    }

    private File getCacheDir(Context applicationContext) {
        // See also res/xml/filepaths.xml for the fileprovider.
        if (applicationContext.getExternalCacheDir() != null) {
            return applicationContext.getExternalCacheDir();
        } else {
            return applicationContext.getCacheDir();
        }
    }
}
