package org.acme.apps;

import java.util.Map;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "org.acme.objectdetection.criteria")
public interface CriteriaFilter {
    Map<String, String> filters();
}
