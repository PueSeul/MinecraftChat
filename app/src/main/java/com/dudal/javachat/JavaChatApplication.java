package com.dudal.javachat;

import android.app.Application;

import org.xbill.DNS.config.AndroidResolverConfigProvider;
import org.xbill.DNS.ResolverConfig;

import java.util.List;

public final class JavaChatApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        AndroidResolverConfigProvider.setContext(this);
        ResolverConfig.setConfigProviders(List.of(new AndroidResolverConfigProvider()));
        ResolverConfig.refresh();
    }
}
