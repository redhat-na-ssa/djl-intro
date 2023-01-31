package org.acme.apps;

import javax.ws.rs.core.Response;

import io.smallrye.mutiny.Uni;

public interface IApp {

    public Uni<Response> logGPUDebug();
    public Uni<Response> getGpuCount();
    public Uni<Response> getGpuMemory();
  
}
