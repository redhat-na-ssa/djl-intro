package org.acme.apps;

import javax.ws.rs.core.Response;

import io.smallrye.mutiny.Uni;

public interface IApp {

    public void startResource();
    public Uni<Response> logGPUDebug();
    public Uni<Response> getGpuCount();
    public Uni<Response> getGpuMemory();
    public Uni<Response> predict();
    public Uni<Response> listModelZooAppSignatures();
  
}
