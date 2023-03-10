package org.acme;

import java.time.Duration;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.ws.rs.core.Response;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import io.quarkus.test.junit.QuarkusTest;

import ai.djl.repository.zoo.ZooModel;
import io.smallrye.mutiny.Uni;

import org.acme.apps.BaseResource;
import org.acme.apps.IApp;

import io.quarkus.runtime.StartupEvent;

@QuarkusTest
public class DJLMainTest {

    @Inject
    DJLBaseApp djlBaseApp;

    @Test
    public void testGetGpuCount() {
        Response rObj = djlBaseApp.getGpuCount().await().atMost(Duration.ofSeconds(2));
        int gpuCount = ((int)rObj.getEntity());
        Assertions.assertTrue( gpuCount > -1);
    }

    @Test
    public void testGpuMemory() {
        Response rObj = djlBaseApp.getGpuMemory().await().atMost(Duration.ofSeconds(2));
        long gpuM = (long)rObj.getEntity();
        Assertions.assertTrue(gpuM == 0L);
    }

}

@ApplicationScoped
class DJLBaseApp extends DJLMain{

    @Inject
    Instance<IApp> djlApp;

    void startup(@Observes StartupEvent event)  {
        super.setDjlApp(djlApp);
        log.info("startup() djlApp = "+djlApp.get());
    }
}

@ApplicationScoped
class AppResource extends BaseResource implements IApp {

    @PostConstruct
    public void startResource() {
        super.start();
    }

    @Override
    public Uni<Response> predict() {
        throw new UnsupportedOperationException("Unimplemented method 'predict'");
    }

    @Override
    public ZooModel<?, ?> getAppModel() {
        throw new UnsupportedOperationException("Unimplemented method 'getAppModel'");
    }
    
}
