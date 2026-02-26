package org.otacoo.chan.core.di;

import android.content.Context;

import org.codejargon.feather.Provides;

import javax.inject.Singleton;

public class AppModule {
    private Context applicationContext;
    private UserAgentProvider userAgentProvider;

    public AppModule(Context applicationContext, UserAgentProvider userAgentProvider) {
        this.applicationContext = applicationContext;
        this.userAgentProvider = userAgentProvider;
    }

    @Provides
    @Singleton
    public Context provideApplicationContext() {
        return applicationContext;
    }

    @Provides
    @Singleton
    public UserAgentProvider provideUserAgentProvider() {
        return userAgentProvider;
    }
}
