package eu.hlavki.identity.services.google.impl;

import eu.hlavki.identity.services.google.config.Configuration;
import eu.hlavki.identity.services.google.model.PushChannel;
import java.time.Duration;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import eu.hlavki.identity.services.google.model.StartPushChannel;
import eu.hlavki.identity.services.google.model.StopPushChannel;
import javax.ws.rs.ClientErrorException;
import javax.ws.rs.core.Response;
import static javax.ws.rs.core.Response.Status.*;
import org.apache.cxf.jaxrs.client.WebClient;
import org.apache.cxf.rs.security.oauth2.common.ClientAccessToken;
import org.apache.karaf.scheduler.Scheduler;
import eu.hlavki.identity.services.google.PushNotificationService;
import java.io.File;
import java.util.Optional;
import static java.util.Optional.empty;
import org.apache.karaf.scheduler.ScheduleOptions;
import org.apache.karaf.scheduler.SchedulerError;

public class PushNotificationServiceImpl implements PushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationServiceImpl.class);

    private static final String PUSH_SCHEDULER_JOB = "PushNotificationJob";

    private Optional<PushChannel> channel = empty();

    private final Configuration config;
    private final WebClient directoryApiClient;
    private final Scheduler scheduler;
    private final TokenCache tokenCache;


    public PushNotificationServiceImpl(Configuration config, WebClient directoryApiClient,
            TokenCache tokenCache, Scheduler scheduler) {
        this.config = config;
        this.tokenCache = tokenCache;
        this.directoryApiClient = directoryApiClient;
        this.scheduler = scheduler;
        this.channel = read();
        startPushNotifications();
    }


    private Optional<PushChannel> read() {
        Optional<PushChannel> result = empty();
        File channelFile = config.getPushChannelFile();
        if (channelFile.exists()) {
            try {
                JAXBContext jaxbContext = JAXBContext.newInstance(PushChannel.class);
                Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();

                //We had written this file in marshalling example
                PushChannel channel = (PushChannel) jaxbUnmarshaller.unmarshal(channelFile);
                result = Optional.of(channel);
            } catch (JAXBException e) {
                log.error("Cannot read watchings!", e);
            }
        }
        return result;
    }


    private void store(PushChannel channel) {
        try {
            JAXBContext jaxbContext = JAXBContext.newInstance(PushChannel.class);
            Marshaller jaxbMarshaller = jaxbContext.createMarshaller();

            jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            //Marshal the employees list in file
            jaxbMarshaller.marshal(channel, config.getPushChannelFile());
        } catch (JAXBException e) {
            log.error("Cannot store watchings", e);
        }
    }


    @Override
    public void enablePushNotifications(String hostname) {
        if (!config.isPushEnabled()) {
            try {
                config.setPushServiceHostname(hostname);
                startPushChannel(hostname);
                if (!scheduler.getJobs().containsKey(PUSH_SCHEDULER_JOB)) {
                    ScheduleOptions opts = scheduler.EXPR("0 0 * * * ?").name(PUSH_SCHEDULER_JOB);
                    scheduler.schedule(new RefreshPushNotificationJob(this), opts);
                }
                config.setPushEnabled(true);
                log.info("Push notifications successfully enabled");
            } catch (SchedulerError e) {
                log.error("Cannot enable push notifications", e);
            }
        } else {
            log.info("Push notifications already enabled");
        }
    }


    @Override
    public void disablePushNotifications() {
        channel.ifPresent(this::stopPushChannel);
        channel = empty();
        scheduler.unschedule(PUSH_SCHEDULER_JOB);
        config.setPushEnabled(false);
        log.info("Push notifications disabled");
    }


    @Override
    public void stopPushChannel(String id, String resourceId) {
        stopPushChannel(new StopPushChannel(id, resourceId));
    }


    private void startPushNotifications() {
        try {
            if (config.isPushEnabled()) {
                refreshPushNotifications();
                if (!scheduler.getJobs().containsKey(PUSH_SCHEDULER_JOB)) {
                    ScheduleOptions opts = scheduler.EXPR("0 0 * * * ?").name(PUSH_SCHEDULER_JOB);
                    scheduler.schedule(new RefreshPushNotificationJob(this), opts);
                }
            }
        } catch (SchedulerError e) {
            log.error("Cannot enable push notifications", e);
        }
    }


    private void startPushChannel(String hostname) {
        WebClient webClient = WebClient.fromClient(directoryApiClient, true)
                .path("/admin/reports/v1/activity/users/all/applications/admin/watch");
        ClientAccessToken accessToken = tokenCache.getToken();
        webClient.authorization(accessToken);
        String url = "https://" + hostname + "/cxf/push/notify";
        StartPushChannel watchRequest = new StartPushChannel(url, Duration.ofHours(6));
        try {
            PushChannel ch = webClient.post(watchRequest, PushChannel.class);
            channel = Optional.of(ch);
            store(ch);
        } catch (ClientErrorException e) {
            String body = e.getResponse().readEntity(String.class);
            log.error("Cannot register push notification channel for {}.\nResponse: {}", config.getGSuiteDomain(), body);
        }
    }


    private void stopPushChannel(PushChannel channel) {
        stopPushChannel(new StopPushChannel(channel));
    }


    private void stopPushChannel(StopPushChannel stopChannel) {
        WebClient webClient = WebClient.fromClient(directoryApiClient, true).path("/admin/reports_v1/channels/stop");
        ClientAccessToken accessToken = tokenCache.getToken();
        webClient.authorization(accessToken);
        Response resp = webClient.post(stopChannel);
        Response.StatusType status = resp.getStatusInfo();
        if (status.toEnum() == OK || status.toEnum() == NO_CONTENT || status.toEnum() == NOT_FOUND) {
            log.info("Push notifications successfully stopeed");
            this.channel = empty();
            config.getPushChannelFile().delete();
        } else {
            log.error("Cannot stop watching domain! Status: {}, Reason: {}", status.getStatusCode(), status.getReasonPhrase());
        }
    }


    @Override
    public void refreshPushNotifications() {
        channel.ifPresent(this::refreshPushNotifications);
    }


    private void refreshPushNotifications(PushChannel channel) {
        if (channel.expiresIn(Duration.ofHours(1))) {
            startPushChannel(config.getPushServiceHostname());
            stopPushChannel(channel);
            log.info("Push notification channel sucessfully refreshed");
        }
    }


    @Override
    public boolean isEnabled() {
        return config.isPushEnabled();
    }
}
