/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dubbo.registry.consul;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.support.FailbackRegistry;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.QueryParams;
import com.ecwid.consul.v1.Response;
import com.ecwid.consul.v1.agent.model.NewService;
import com.ecwid.consul.v1.catalog.CatalogServicesRequest;
import com.ecwid.consul.v1.health.HealthServicesRequest;
import com.ecwid.consul.v1.health.model.HealthService;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import static java.util.concurrent.Executors.newCachedThreadPool;
import static org.apache.dubbo.common.Constants.ANY_VALUE;

/**
 * registry center implementation for consul
 */
public class ConsulRegistry extends FailbackRegistry {
    private static final Logger logger = LoggerFactory.getLogger(ConsulRegistry.class);

    private static final String SERVICE_TAG = "dubbo";
    private static final String URL_META_KEY = "url";
    private static final String WATCH_TIMEOUT = "consul-watch-timeout";
    private static final String CHECK_INTERVAL = "consul-check-interval";
    private static final String CHECK_TIMEOUT = "consul-check-timeout";
    private static final String DEREGISTER_AFTER = "consul-deregister-critical-service-after";

    private static final int DEFAULT_PORT = 8500;
    // default watch timeout in millisecond
    private static final int DEFAULT_WATCH_TIMEOUT = 60 * 1000;
    // default tcp check interval
    private static final String DEFAULT_CHECK_INTERVAL = "10s";
    // default tcp check timeout
    private static final String DEFAULT_CHECK_TIMEOUT = "1s";
    // default deregister critical server after
    private static final String DEFAULT_DEREGISTER_TIME = "20s";

    private ConsulClient client;

    private ExecutorService notifierExecutor = newCachedThreadPool(
            new NamedThreadFactory("dubbo-consul-notifier", true));
    private ConcurrentMap<URL, ConsulNotifier> notifiers = new ConcurrentHashMap<>();

    public ConsulRegistry(URL url) {
        super(url);
        String host = url.getHost();
        int port = url.getPort() != 0 ? url.getPort() : DEFAULT_PORT;
        client = new ConsulClient(host, port);
    }

    @Override
    public void register(URL url) {
        if (isConsumerSide(url)) {
            return;
        }

        super.register(url);
    }

    @Override
    public void doRegister(URL url) {
        client.agentServiceRegister(buildService(url));
    }

    @Override
    public void unregister(URL url) {
        if (isConsumerSide(url)) {
            return;
        }

        super.unregister(url);
    }

    @Override
    public void doUnregister(URL url) {
        client.agentServiceDeregister(buildId(url));
    }

    @Override
    public void subscribe(URL url, NotifyListener listener) {
        if (isProviderSide(url)) {
            return;
        }

        super.subscribe(url, listener);
    }

    @Override
    public void doSubscribe(URL url, NotifyListener listener) {
        Long index;
        List<URL> urls;
        if (ANY_VALUE.equals(url.getServiceInterface())) {
            Response<Map<String, List<String>>> response = getAllServices(-1, buildWatchTimeout(url));
            index = response.getConsulIndex();
            List<HealthService> services = getHealthServices(response.getValue());
            urls = convert(services);
        } else {
            String service = url.getServiceKey();
            Response<List<HealthService>> response = getHealthServices(service, -1, buildWatchTimeout(url));
            index = response.getConsulIndex();
            urls = convert(response.getValue());
        }

        notify(url, listener, urls);
        ConsulNotifier notifier = notifiers.computeIfAbsent(url, k -> new ConsulNotifier(url, index));
        notifierExecutor.submit(notifier);
    }

    @Override
    public void unsubscribe(URL url, NotifyListener listener) {
        if (isProviderSide(url)) {
            return;
        }

        super.unsubscribe(url, listener);
    }

    @Override
    public void doUnsubscribe(URL url, NotifyListener listener) {
        ConsulNotifier notifier = notifiers.remove(url);
        notifier.stop();
    }

    @Override
    public boolean isAvailable() {
        return client.getAgentSelf() != null;
    }

    @Override
    public void destroy() {
        super.destroy();
        notifierExecutor.shutdown();
    }

    private Response<List<HealthService>> getHealthServices(String service, long index, int watchTimeout) {
        HealthServicesRequest request = HealthServicesRequest.newBuilder()
                .setTag(SERVICE_TAG)
                .setQueryParams(new QueryParams(watchTimeout, index))
                .setPassing(true)
                .build();
        return client.getHealthServices(service, request);
    }

    private Response<Map<String, List<String>>> getAllServices(long index, int watchTimeout) {
        CatalogServicesRequest request = CatalogServicesRequest.newBuilder()
                .setQueryParams(new QueryParams(watchTimeout, index))
                .build();
        return client.getCatalogServices(request);
    }

    private List<HealthService> getHealthServices(Map<String, List<String>> services) {
        return services.keySet().stream()
                .filter(s -> services.get(s).contains(SERVICE_TAG))
                .map(s -> getHealthServices(s, -1, -1).getValue())
                .flatMap(Collection::stream)
                .collect(Collectors.toList());
    }


    private boolean isConsumerSide(URL url) {
        return url.getProtocol().equals(Constants.CONSUMER_PROTOCOL);
    }

    private boolean isProviderSide(URL url) {
        return url.getProtocol().equals(Constants.PROVIDER_PROTOCOL);
    }

    private List<URL> convert(List<HealthService> services) {
        return services.stream()
                .map(s -> s.getService().getMeta().get(URL_META_KEY))
                .map(URL::valueOf)
                .collect(Collectors.toList());
    }

    private NewService buildService(URL url) {
        NewService service = new NewService();
        service.setAddress(url.getHost());
        service.setPort(url.getPort());
        service.setId(buildId(url));
        service.setName(url.getServiceInterface());
        service.setCheck(buildCheck(url));
        service.setTags(buildTags(url));
        service.setMeta(Collections.singletonMap(URL_META_KEY, url.toFullString()));
        return service;
    }

    private List<String> buildTags(URL url) {
        Map<String, String> params = url.getParameters();
        List<String> tags = params.keySet().stream()
                .map(k -> k + "=" + params.get(k))
                .collect(Collectors.toList());
        tags.add(SERVICE_TAG);
        return tags;
    }

    private String buildId(URL url) {
        // let's simply use url's hashcode to generate unique service id for now
        return Integer.toHexString(url.hashCode());
    }

    private NewService.Check buildCheck(URL url) {
        NewService.Check check = new NewService.Check();
        check.setTcp(url.getAddress());
        check.setInterval(url.getParameter(CHECK_INTERVAL, DEFAULT_CHECK_INTERVAL));
        check.setTimeout(url.getParameter(CHECK_TIMEOUT, DEFAULT_CHECK_TIMEOUT));
        check.setDeregisterCriticalServiceAfter(url.getParameter(DEREGISTER_AFTER, DEFAULT_DEREGISTER_TIME));
        return check;
    }

    private int buildWatchTimeout(URL url) {
        return url.getParameter(WATCH_TIMEOUT, DEFAULT_WATCH_TIMEOUT) / 1000;
    }

    private class ConsulNotifier implements Runnable {
        private URL url;
        private long consulIndex;
        private boolean running;

        ConsulNotifier(URL url, long consulIndex) {
            this.url = url;
            this.consulIndex = consulIndex;
            this.running = true;
        }

        @Override
        public void run() {
            while (this.running) {
                if (ANY_VALUE.equals(url.getServiceInterface())) {
                    processServices();
                } else {
                    processService();
                }
            }
        }

        private void processService() {
            String service = url.getServiceKey();
            Response<List<HealthService>> response = getHealthServices(service, consulIndex, buildWatchTimeout(url));
            Long currentIndex = response.getConsulIndex();
            if (currentIndex != null && currentIndex > consulIndex) {
                consulIndex = currentIndex;
                List<HealthService> services = response.getValue();
                List<URL> urls = convert(services);
                for (NotifyListener listener : getSubscribed().get(url)) {
                    doNotify(url, listener, urls);
                }
            }
        }

        private void processServices() {
            Response<Map<String, List<String>>> response = getAllServices(consulIndex, buildWatchTimeout(url));
            Long currentIndex = response.getConsulIndex();
            if (currentIndex != null && currentIndex > consulIndex) {
                consulIndex = currentIndex;
                List<HealthService> services = getHealthServices(response.getValue());
                List<URL> urls = convert(services);
                for (NotifyListener listener : getSubscribed().get(url)) {
                    doNotify(url, listener, urls);
                }
            }
        }

        void stop() {
            this.running = false;
        }
    }
}
